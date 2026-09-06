package com.autospec.service;

import com.autospec.dto.KnowledgeSourceResponse;
import com.autospec.dto.ToolGatewayRequest;
import com.autospec.dto.ToolGatewayResponse;
import com.autospec.entity.Artifact;
import com.autospec.entity.WorkflowNodeRun;
import com.autospec.entity.WorkflowRun;
import com.autospec.entity.WorkflowToolCallFact;
import com.autospec.mapper.WorkflowNodeRunMapper;
import com.autospec.mapper.WorkflowRunMapper;
import com.autospec.mapper.WorkflowToolCallFactMapper;
import com.autospec.util.CanonicalJson;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Control-plane boundary for Worker tool calls. Only fixed read-only tools are
 * dispatched; the Worker never receives a database or shell capability.
 */
@Service
public class ToolGatewayService {
    public static final String SERVICE_HEADER = "X-AutoSpec-Service-Token";
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Set<String> CONTROLLED_TOOLS = Set.of(
            "knowledge.search",
            "artifact.get",
            "contract.lookup",
            "trace.query",
            "bundle.verify"
    );
    private static final Set<String> DETERMINISTIC_TOOLS = Set.of(
            "contract.lookup",
            "bundle.verify"
    );

    private final WorkflowRunMapper runMapper;
    private final WorkflowNodeRunMapper nodeRunMapper;
    private final WorkflowToolCallFactMapper factMapper;
    private final ArtifactService artifactService;
    private final KnowledgeIndexService knowledgeIndexService;
    private final WorkflowTraceService workflowTraceService;
    private final AuditEventService auditEventService;
    private final ObjectMapper objectMapper;
    private final String serviceToken;

    public ToolGatewayService(
            WorkflowRunMapper runMapper,
            WorkflowNodeRunMapper nodeRunMapper,
            WorkflowToolCallFactMapper factMapper,
            ArtifactService artifactService,
            KnowledgeIndexService knowledgeIndexService,
            WorkflowTraceService workflowTraceService,
            AuditEventService auditEventService,
            ObjectMapper objectMapper,
            @Value("${autospec.agent-engine.service-token:}") String serviceToken
    ) {
        this.runMapper = runMapper;
        this.nodeRunMapper = nodeRunMapper;
        this.factMapper = factMapper;
        this.artifactService = artifactService;
        this.knowledgeIndexService = knowledgeIndexService;
        this.workflowTraceService = workflowTraceService;
        this.auditEventService = auditEventService;
        this.objectMapper = objectMapper;
        this.serviceToken = serviceToken == null ? "" : serviceToken;
    }

    public ToolGatewayService(
            WorkflowRunMapper runMapper,
            WorkflowNodeRunMapper nodeRunMapper,
            WorkflowToolCallFactMapper factMapper,
            ArtifactService artifactService,
            KnowledgeIndexService knowledgeIndexService,
            WorkflowTraceService workflowTraceService,
            AuditEventService auditEventService,
            ObjectMapper objectMapper
    ) {
        this(
                runMapper,
                nodeRunMapper,
                factMapper,
                artifactService,
                knowledgeIndexService,
                workflowTraceService,
                auditEventService,
                objectMapper,
                ""
        );
    }

    public void requireServiceToken(String candidate) {
        if (serviceToken.isBlank() || candidate == null || candidate.isBlank()
                || !MessageDigest.isEqual(
                serviceToken.getBytes(StandardCharsets.UTF_8),
                candidate.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid internal service token");
        }
    }

    @Transactional
    public ToolGatewayResponse execute(ToolGatewayRequest request) {
        validateEnvelope(request);
        WorkflowToolCallFact existing = factMapper.selectOne(new LambdaQueryWrapper<WorkflowToolCallFact>()
                .eq(WorkflowToolCallFact::getIdempotencyKey, request.idempotencyKey())
                .last("limit 1"));
        if (existing != null) {
            if (!sameRequest(existing, request)) {
                return failure(request, "IDEMPOTENCY_CONFLICT", "idempotency key was used for another request", 0);
            }
            return response(existing, true);
        }

        WorkflowRun run = runMapper.selectById(request.workflowRunId());
        WorkflowNodeRun node = nodeRunMapper.selectById(request.nodeRunId());
        GatewayFailure identityFailure = validateExecutionScope(request, run, node);
        if (identityFailure != null) {
            return persistFailure(request, run, identityFailure.code, identityFailure.getMessage());
        }

        ObjectNode policy = frozenToolPolicy(node);
        String expectedPolicyHash = CanonicalJson.sha256(policy.toString());
        if (!expectedPolicyHash.equals(request.policyHash())) {
            return persistFailure(request, run, "POLICY_HASH_MISMATCH", "tool policy does not match the execution bundle");
        }
        if (!policy.path("enabled").asBoolean(false)) {
            return persistFailure(request, run, "TOOL_NOT_ALLOWED", "tool policy is disabled");
        }
        if (!allowedTool(policy, request.name(), request.version())) {
            return persistFailure(request, run, "TOOL_NOT_ALLOWED", "tool is not in the frozen allowlist");
        }
        if (DETERMINISTIC_TOOLS.contains(request.name())
                && !contains(policy.path("allowed_side_effects"), "DETERMINISTIC")) {
            return persistFailure(request, run, "TOOL_SIDE_EFFECT_DENIED", "deterministic tool side effect is not allowed");
        }
        if (!DETERMINISTIC_TOOLS.contains(request.name())
                && !contains(policy.path("allowed_side_effects"), "READ_ONLY")) {
            return persistFailure(request, run, "TOOL_SIDE_EFFECT_DENIED", "read-only tool side effect is not allowed");
        }
        if (request.maxResultBytes() > policy.path("max_result_bytes").asInt(32_000)) {
            return persistFailure(request, run, "TOOL_RESULT_LIMIT_INVALID", "requested result limit exceeds the frozen policy");
        }
        int maxCalls = policy.path("max_calls").asInt(0);
        long usedCalls = factMapper.selectCount(new LambdaQueryWrapper<WorkflowToolCallFact>()
                .eq(WorkflowToolCallFact::getExecutionId, request.executionId()));
        if (maxCalls < 1 || usedCalls >= maxCalls) {
            return persistFailure(request, run, "TOOL_BUDGET_EXCEEDED", "frozen tool call budget was exhausted");
        }

        long started = System.currentTimeMillis();
        try {
            JsonNode result = dispatch(request, run);
            int resultLimit = Math.min(request.maxResultBytes(), policy.path("max_result_bytes").asInt(32_000));
            if (objectMapper.writeValueAsBytes(result).length > resultLimit) {
                return persistFailure(request, run, "TOOL_RESULT_TOO_LARGE", "tool result exceeds the frozen size limit");
            }
            ToolGatewayResponse response = new ToolGatewayResponse(
                    request.requestId(),
                    request.idempotencyKey(),
                    "SUCCEEDED",
                    result,
                    CanonicalJson.sha256(result.toString()),
                    null,
                    null,
                    false,
                    request.executionId(),
                    1,
                    Math.max(0, (int) (System.currentTimeMillis() - started)),
                    Map.of("tool_name", request.name(), "tool_version", request.version())
            );
            return persist(request, run, response);
        } catch (GatewayFailure failure) {
            return persistFailure(request, run, failure.code, failure.getMessage());
        } catch (Exception exception) {
            return persistFailure(request, run, "TOOL_EXECUTION_FAILED", "controlled tool execution failed");
        }
    }

    private GatewayFailure validateExecutionScope(
            ToolGatewayRequest request,
            WorkflowRun run,
            WorkflowNodeRun node
    ) {
        if (run == null || node == null) {
            return new GatewayFailure("EXECUTION_NOT_FOUND", "workflow execution context was not found");
        }
        if (!request.workflowRunId().equals(node.getWorkflowRunId())
                || !request.projectId().equals(run.getProjectId())
                || !request.actorUserId().equals(run.getInitiatedByUserId())
                || !request.executionId().equals(node.getExecutionId())
                || !request.nodeId().equals(node.getNodeId())) {
            return new GatewayFailure("TOOL_SCOPE_DENIED", "tool request is outside its workflow scope");
        }
        if (request.executionBundleHash() != null
                && !request.executionBundleHash().equals(run.getExecutionBundleHash())) {
            return new GatewayFailure("BUNDLE_HASH_MISMATCH", "tool request bundle does not match the workflow run");
        }
        long currentFence = node.getFencingToken() == null ? 0 : node.getFencingToken();
        if (currentFence > 0 && currentFence != request.fencingToken()) {
            return new GatewayFailure("STALE_FENCING_TOKEN", "tool request fencing token is stale");
        }
        if (!("QUEUED".equals(node.getStatus()) || "RUNNING".equals(node.getStatus()))) {
            return new GatewayFailure("EXECUTION_NOT_ACTIVE", "tool request belongs to an inactive node run");
        }
        return null;
    }

    private JsonNode dispatch(ToolGatewayRequest request, WorkflowRun run) throws JsonProcessingException {
        JsonNode arguments = request.arguments();
        return switch (request.name()) {
            case "knowledge.search" -> knowledgeSearch(request, run, arguments);
            case "artifact.get" -> artifactGet(request, run, arguments);
            case "contract.lookup" -> contractLookup(request, run, arguments);
            case "trace.query" -> objectMapper.valueToTree(workflowTraceService.trace(run.getId()));
            case "bundle.verify" -> bundleVerify(request, run);
            default -> throw new GatewayFailure("TOOL_NOT_ALLOWED", "tool is not in the controlled catalog");
        };
    }

    private JsonNode knowledgeSearch(
            ToolGatewayRequest request,
            WorkflowRun run,
            JsonNode arguments
    ) {
        String query = textArgument(arguments, "query");
        int limit = boundedInt(arguments, "limit", 5, 1, 20);
        String corpus = optionalText(arguments, "corpus_type");
        List<KnowledgeSourceResponse> sources = knowledgeIndexService.retrieveForProject(
                query,
                limit,
                run.getProjectId(),
                request.actorUserId(),
                corpus
        );
        return objectMapper.valueToTree(sources);
    }

    private JsonNode artifactGet(
            ToolGatewayRequest request,
            WorkflowRun run,
            JsonNode arguments
    ) {
        long artifactId = requiredLong(arguments, "artifact_id");
        Artifact artifact = artifactService.getById(artifactId);
        if (artifact == null || !run.getProjectId().equals(artifact.getProjectId())) {
            throw new GatewayFailure("TOOL_SCOPE_DENIED", "artifact is outside the workflow project");
        }
        Integer requestedVersion = optionalInt(arguments, "version");
        if (requestedVersion != null && !requestedVersion.equals(artifact.getVersion())) {
            throw new GatewayFailure("ARTIFACT_VERSION_NOT_FOUND", "requested artifact version is not the current source");
        }
        return objectMapper.valueToTree(artifact);
    }

    private JsonNode contractLookup(
            ToolGatewayRequest request,
            WorkflowRun run,
            JsonNode arguments
    ) throws JsonProcessingException {
        if (run.getExecutionBundleJson() == null || run.getExecutionBundleJson().isBlank()) {
            throw new GatewayFailure("BUNDLE_NOT_FOUND", "workflow execution bundle is missing");
        }
        String nodeId = optionalText(arguments, "node_id");
        nodeId = nodeId == null ? request.nodeId() : nodeId;
        JsonNode nodes = objectMapper.readTree(run.getExecutionBundleJson()).path("nodes");
        if (!nodes.isArray()) {
            throw new GatewayFailure("BUNDLE_INVALID", "workflow execution bundle has no node list");
        }
        for (JsonNode node : nodes) {
            if (nodeId.equals(node.path("node_id").asText())) {
                ObjectNode result = (ObjectNode) node.deepCopy();
                JsonNode prompt = result.get("prompt");
                if (prompt != null && prompt.isObject()) {
                    ((ObjectNode) prompt).remove("content");
                }
                return result;
            }
        }
        throw new GatewayFailure("CONTRACT_NOT_FOUND", "requested node contract was not found");
    }

    private JsonNode bundleVerify(ToolGatewayRequest request, WorkflowRun run) {
        String actualHash = run.getExecutionBundleJson() == null
                ? ""
                : CanonicalJson.sha256(run.getExecutionBundleJson());
        boolean valid = !actualHash.isBlank()
                && actualHash.equals(run.getExecutionBundleHash())
                && (request.executionBundleHash() == null || actualHash.equals(request.executionBundleHash()));
        ObjectNode result = objectMapper.createObjectNode();
        result.put("valid", valid);
        result.put("workflow_run_id", run.getId());
        result.put("bundle_hash", actualHash);
        if (!valid) {
            result.put("failure_reason", "BUNDLE_HASH_MISMATCH");
        }
        return result;
    }

    private ObjectNode frozenToolPolicy(WorkflowNodeRun node) {
        try {
            JsonNode input = objectMapper.readTree(node.getInputJson());
            return normalizeToolPolicy(input == null ? null : input.get("tool_policy"));
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Workflow node input is invalid", exception);
        }
    }

    private ObjectNode normalizeToolPolicy(JsonNode raw) {
        ObjectNode result = objectMapper.createObjectNode();
        JsonNode source = raw != null && raw.isObject() ? raw : objectMapper.createObjectNode();
        result.put("version", source.path("version").asText("tools-v1"));
        result.put("enabled", source.path("enabled").asBoolean(false));
        result.set("allowed_tools", normalizedAllowedTools(source.get("allowed_tools")));
        result.put("max_calls", source.path("max_calls").asInt(0));
        result.put("per_call_timeout_ms", source.path("per_call_timeout_ms").asInt(5_000));
        result.put("total_timeout_ms", source.path("total_timeout_ms").asInt(30_000));
        result.put("max_result_bytes", source.path("max_result_bytes").asInt(32_000));
        result.set("allowed_side_effects", normalizedStrings(
                source.get("allowed_side_effects"), List.of("READ_ONLY", "DETERMINISTIC")
        ));
        result.put("permission_policy", source.path("permission_policy").asText("workflow"));
        result.set("retry_policy", normalizedRetryPolicy(source.get("retry_policy")));
        return result;
    }

    private ArrayNode normalizedAllowedTools(JsonNode raw) {
        ArrayNode result = objectMapper.createArrayNode();
        if (raw != null && raw.isArray()) {
            raw.forEach(item -> {
                if (item.isObject()) {
                    ObjectNode tool = objectMapper.createObjectNode();
                    tool.put("name", item.path("name").asText(""));
                    tool.put("version", item.path("version").asText(""));
                    result.add(tool);
                }
            });
        }
        return result;
    }

    private ArrayNode normalizedStrings(JsonNode raw, List<String> defaults) {
        ArrayNode result = objectMapper.createArrayNode();
        if (raw != null && raw.isArray()) {
            raw.forEach(item -> result.add(item.asText()));
        } else {
            defaults.forEach(result::add);
        }
        return result;
    }

    private ObjectNode normalizedRetryPolicy(JsonNode raw) {
        JsonNode source = raw != null && raw.isObject() ? raw : objectMapper.createObjectNode();
        ObjectNode result = objectMapper.createObjectNode();
        result.put("max_attempts", source.path("max_attempts").asInt(1));
        result.put("retry_on_validation_error", source.path("retry_on_validation_error").asBoolean(false));
        result.put("initial_delay_ms", source.path("initial_delay_ms").asInt(1_000));
        result.put("max_delay_ms", source.path("max_delay_ms").asInt(10_000));
        result.put("multiplier", source.path("multiplier").asDouble(2.0));
        result.set("retryable_errors", normalizedStrings(source.get("retryable_errors"), List.of()));
        return result;
    }

    private boolean allowedTool(JsonNode policy, String name, String version) {
        JsonNode allowed = policy.get("allowed_tools");
        if (allowed == null || !allowed.isArray() || !CONTROLLED_TOOLS.contains(name) || !"v1".equals(version)) {
            return false;
        }
        for (JsonNode item : allowed) {
            if (name.equals(item.path("name").asText()) && version.equals(item.path("version").asText())) {
                return true;
            }
        }
        return false;
    }

    private boolean contains(JsonNode values, String expected) {
        if (values == null || !values.isArray()) {
            return false;
        }
        for (JsonNode value : values) {
            if (expected.equals(value.asText())) {
                return true;
            }
        }
        return false;
    }

    private void validateEnvelope(ToolGatewayRequest request) {
        if (request == null || blank(request.requestId()) || blank(request.executionId())
                || request.workflowRunId() == null || request.workflowRunId() < 1
                || request.nodeRunId() == null || request.nodeRunId() < 1
                || blank(request.nodeId()) || request.actorUserId() == null || request.actorUserId() < 1
                || request.projectId() == null || request.projectId() < 1
                || request.fencingToken() == null || request.fencingToken() < 1
                || request.deadlineEpochMs() == null || request.deadlineEpochMs() < 1
                || request.deadlineEpochMs() <= System.currentTimeMillis()
                || blank(request.policyHash()) || !SHA256.matcher(request.policyHash()).matches()
                || blank(request.idempotencyKey()) || blank(request.name()) || blank(request.version())
                || request.arguments() == null || !request.arguments().isObject()
                || blank(request.normalizedParamsHash()) || !SHA256.matcher(request.normalizedParamsHash()).matches()
                || request.maxResultBytes() == null || request.maxResultBytes() < 256
                || request.maxResultBytes() > 1_000_000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid tool gateway request envelope");
        }
        if (!request.normalizedParamsHash().equals(CanonicalJson.sha256(request.arguments().toString()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tool request parameter hash mismatch");
        }
    }

    private ToolGatewayResponse persistFailure(
            ToolGatewayRequest request,
            WorkflowRun run,
            String code,
            String message
    ) {
        return persist(request, run, failure(request, code, message, 0));
    }

    private ToolGatewayResponse persist(
            ToolGatewayRequest request,
            WorkflowRun run,
            ToolGatewayResponse response
    ) {
        WorkflowToolCallFact fact = new WorkflowToolCallFact();
        fact.setRequestId(request.requestId());
        fact.setIdempotencyKey(request.idempotencyKey());
        fact.setExecutionId(request.executionId());
        fact.setWorkflowRunId(request.workflowRunId());
        fact.setNodeRunId(request.nodeRunId());
        fact.setNodeId(request.nodeId());
        fact.setActorUserId(request.actorUserId());
        fact.setProjectId(request.projectId());
        fact.setFencingToken(request.fencingToken());
        fact.setDeadlineEpochMs(request.deadlineEpochMs());
        fact.setExecutionBundleHash(request.executionBundleHash());
        fact.setPolicyHash(request.policyHash());
        fact.setName(request.name());
        fact.setVersion(request.version());
        fact.setNormalizedParamsHash(request.normalizedParamsHash());
        fact.setStatus(response.status());
        fact.setResultJson(response.result() == null ? null : response.result().toString());
        fact.setResultHash(response.resultHash());
        fact.setErrorCode(response.errorCode());
        fact.setErrorMessage(response.errorMessage());
        fact.setCached(response.cached());
        fact.setSourceExecutionId(response.sourceExecutionId());
        fact.setAttempts(response.attempts());
        fact.setDurationMs(response.durationMs());
        fact.setCorrelationId(request.correlationId());
        fact.setTraceparent(request.traceparent());
        fact.setTracestate(request.tracestate());
        fact.setCreatedAt(LocalDateTime.now());
        try {
            factMapper.insert(fact);
        } catch (DuplicateKeyException duplicate) {
            WorkflowToolCallFact existing = factMapper.selectOne(new LambdaQueryWrapper<WorkflowToolCallFact>()
                    .eq(WorkflowToolCallFact::getIdempotencyKey, request.idempotencyKey())
                    .last("limit 1"));
            if (existing != null && sameRequest(existing, request)) {
                return response(existing, true);
            }
            return failure(request, "IDEMPOTENCY_CONFLICT", "idempotency key was used for another request", 0);
        }
        if (run != null && run.getProjectId() != null) {
            auditEventService.record(
                    run.getProjectId(),
                    request.actorUserId(),
                    run.getCorrelationId(),
                    "TOOL_GATEWAY_CALL",
                    "WORKFLOW_TOOL_CALL",
                    fact.getId(),
                    "Controlled tool call " + request.name() + ":" + request.version(),
                    "{\"status\":\"" + response.status() + "\",\"error_code\":"
                            + jsonString(response.errorCode()) + "}"
            );
        }
        return response;
    }

    private boolean sameRequest(WorkflowToolCallFact fact, ToolGatewayRequest request) {
        return request.name().equals(fact.getName())
                && request.version().equals(fact.getVersion())
                && request.executionId().equals(fact.getExecutionId())
                && request.policyHash().equals(fact.getPolicyHash())
                && request.normalizedParamsHash().equals(fact.getNormalizedParamsHash());
    }

    private ToolGatewayResponse response(WorkflowToolCallFact fact, boolean cached) {
        JsonNode result = null;
        if (fact.getResultJson() != null) {
            try {
                result = objectMapper.readTree(fact.getResultJson());
            } catch (JsonProcessingException ignored) {
                // The fact is still surfaced as a failed protocol result below.
                return failure(
                        new ToolGatewayRequest(
                                fact.getRequestId(), fact.getExecutionId(), fact.getWorkflowRunId(),
                                fact.getNodeRunId(), fact.getNodeId(), fact.getActorUserId(), fact.getProjectId(),
                                fact.getFencingToken(), fact.getDeadlineEpochMs(), fact.getExecutionBundleHash(),
                                fact.getPolicyHash(), fact.getIdempotencyKey(), fact.getName(), fact.getVersion(),
                                objectMapper.createObjectNode(), fact.getNormalizedParamsHash(), 256,
                                fact.getCorrelationId(), fact.getTraceparent(), fact.getTracestate()
                        ),
                        "TOOL_FACT_CORRUPT",
                        "stored tool result is invalid",
                        fact.getDurationMs() == null ? 0 : fact.getDurationMs()
                );
            }
        }
        return new ToolGatewayResponse(
                fact.getRequestId(),
                fact.getIdempotencyKey(),
                fact.getStatus(),
                result,
                fact.getResultHash(),
                fact.getErrorCode(),
                fact.getErrorMessage(),
                cached,
                fact.getSourceExecutionId(),
                fact.getAttempts() == null ? 1 : fact.getAttempts(),
                fact.getDurationMs() == null ? 0 : fact.getDurationMs(),
                Map.of("tool_name", fact.getName(), "tool_version", fact.getVersion())
        );
    }

    private ToolGatewayResponse failure(
            ToolGatewayRequest request,
            String code,
            String message,
            int durationMs
    ) {
        return new ToolGatewayResponse(
                request.requestId(),
                request.idempotencyKey(),
                "FAILED",
                null,
                null,
                code,
                message == null ? "tool gateway request failed" : message.substring(0, Math.min(1000, message.length())),
                false,
                request.executionId(),
                1,
                Math.max(0, durationMs),
                Map.of("tool_name", request.name(), "tool_version", request.version())
        );
    }

    private String textArgument(JsonNode arguments, String name) {
        String value = optionalText(arguments, name);
        if (value == null) {
            throw new GatewayFailure("TOOL_INPUT_INVALID", name + " is required");
        }
        return value;
    }

    private String optionalText(JsonNode arguments, String name) {
        JsonNode value = arguments == null ? null : arguments.get(name);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            return null;
        }
        return value.asText().trim();
    }

    private int boundedInt(JsonNode arguments, String name, int fallback, int min, int max) {
        JsonNode value = arguments == null ? null : arguments.get(name);
        int resolved = value == null || !value.isIntegralNumber() ? fallback : value.asInt();
        if (resolved < min || resolved > max) {
            throw new GatewayFailure("TOOL_INPUT_INVALID", name + " is outside the allowed range");
        }
        return resolved;
    }

    private Integer optionalInt(JsonNode arguments, String name) {
        JsonNode value = arguments == null ? null : arguments.get(name);
        return value != null && value.isIntegralNumber() ? value.asInt() : null;
    }

    private long requiredLong(JsonNode arguments, String name) {
        JsonNode value = arguments == null ? null : arguments.get(name);
        if (value == null || !value.canConvertToLong() || value.asLong() < 1) {
            throw new GatewayFailure("TOOL_INPUT_INVALID", name + " must be a positive integer");
        }
        return value.asLong();
    }

    private String jsonString(String value) {
        if (value == null) {
            return "null";
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ignored) {
            return "\"unknown\"";
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static final class GatewayFailure extends RuntimeException {
        private final String code;

        private GatewayFailure(String code, String message) {
            super(message);
            this.code = code;
        }
    }
}
