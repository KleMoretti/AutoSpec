package com.autospec.workflow.runtime;

import com.autospec.dto.KnowledgeSourceResponse;
import com.autospec.entity.WorkflowNodeRun;
import com.autospec.entity.ModelInvocation;
import com.autospec.mapper.ModelInvocationMapper;
import com.autospec.mapper.WorkflowNodeRunMapper;
import com.autospec.service.KnowledgeIndexService;
import com.autospec.util.ContentHash;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class WorkflowNodeInputAssembler {
    private final WorkflowNodeRunMapper nodeRunMapper;
    private final ObjectMapper objectMapper;
    private final ModelInvocationMapper modelInvocationMapper;
    private final KnowledgeIndexService knowledgeIndexService;

    public WorkflowNodeInputAssembler(
            WorkflowNodeRunMapper nodeRunMapper,
            ObjectMapper objectMapper,
            ModelInvocationMapper modelInvocationMapper
    ) {
        this(nodeRunMapper, objectMapper, modelInvocationMapper, null);
    }

    @Autowired
    public WorkflowNodeInputAssembler(
            WorkflowNodeRunMapper nodeRunMapper,
            ObjectMapper objectMapper,
            ModelInvocationMapper modelInvocationMapper,
            KnowledgeIndexService knowledgeIndexService
    ) {
        this.nodeRunMapper = nodeRunMapper;
        this.objectMapper = objectMapper;
        this.modelInvocationMapper = modelInvocationMapper;
        this.knowledgeIndexService = knowledgeIndexService;
    }

    public void assemble(CompiledWorkflow graph, WorkflowNodeRun target) {
        ObjectNode input = objectInput(target.getInputJson());
        Map<String, WorkflowNodeRun> latest = latestRuns(target.getWorkflowRunId());
        Set<String> ancestorIds = ancestors(graph, target.getNodeId());
        for (String ancestor : ancestorIds) {
            WorkflowNodeRun source = latest.get(ancestor);
            if (source == null || !"SUCCEEDED".equals(source.getStatus()) || source.getOutputJson() == null) {
                continue;
            }
            input.set(inputField(graph.nodes().get(ancestor)), json(source.getOutputJson()));
        }
        if ("evaluator".equals(target.getNodeId())) {
            input.set("records", trustedRecords(latest, ancestorIds));
            input.set("model_invocations", trustedModelInvocations(target.getWorkflowRunId()));
            input.putArray("generated_files");
        }
        retrieveNodeSources(input, target);
        String assembled = input.toString();
        int updated = nodeRunMapper.update(null, new LambdaUpdateWrapper<WorkflowNodeRun>()
                .eq(WorkflowNodeRun::getId, target.getId())
                .eq(WorkflowNodeRun::getStatus, WorkflowNodeStatus.PENDING.name())
                .set(WorkflowNodeRun::getInputJson, assembled));
        if (updated == 1) {
            target.setInputJson(assembled);
        }
    }

    /**
     * Resolve project knowledge after upstream artifacts have been assembled.
     * The resulting sources and snapshot are frozen into the node input so a
     * retry/replay can explain exactly which corpus epoch and hits were used.
     */
    private void retrieveNodeSources(ObjectNode input, WorkflowNodeRun target) {
        if (knowledgeIndexService == null) {
            return;
        }
        JsonNode policy = input.path("retrieval_policy");
        if (!policy.isObject() || !policy.path("enabled").asBoolean(false)) {
            return;
        }
        Long projectId = longMetadata(input, "_autospec_project_id");
        Long actorUserId = longMetadata(input, "_autospec_actor_user_id");
        if (projectId == null || actorUserId == null) {
            input.set("retrieved_sources", objectMapper.createArrayNode());
            return;
        }

        int topN = Math.max(1, Math.min(policy.path("top_n").asInt(20), 50));
        int topK = Math.max(1, Math.min(policy.path("top_k").asInt(5), topN));
        int maxPerArtifact = Math.max(1, policy.path("max_per_artifact")
                .asInt(2));
        String query = retrievalQuery(input, target.getNodeId());
        List<String> corpora = textValues(policy.path("allowed_corpora"));
        List<KnowledgeSourceResponse> candidates = new ArrayList<>();
        if (corpora.isEmpty()) {
            candidates.addAll(knowledgeIndexService.retrieveForProject(
                    query, topN, projectId, actorUserId, null
            ));
        } else {
            for (String corpus : corpora) {
                candidates.addAll(knowledgeIndexService.retrieveForProject(
                        query, topN, projectId, actorUserId, corpus
                ));
            }
        }
        List<KnowledgeSourceResponse> selected = selectSources(
                candidates, projectId, topK, maxPerArtifact
        );
        input.set("retrieved_sources", sourceArray(selected));
        input.put("retrieval_project_id", projectId);
        input.put("retrieval_node_id", target.getNodeId());
        long corpusEpoch = knowledgeIndexService.currentCorpusEpoch(projectId);
        input.put("corpus_epoch", corpusEpoch);
        String cacheKey = knowledgeIndexService.retrievalCacheKey(
                query, topN, projectId, actorUserId
        );
        ObjectNode snapshot = input.putObject("retrieval_snapshot");
        snapshot.put("version", "node-retrieval-snapshot-v1");
        snapshot.put("node_id", target.getNodeId());
        snapshot.put("query_hash", ContentHash.sha256(query));
        snapshot.put("policy_hash", ContentHash.sha256(policy.toString()));
        snapshot.put("project_id", projectId);
        snapshot.put("corpus_epoch", corpusEpoch);
        snapshot.put("hit_count", selected.size());
        snapshot.put("cache_key", cacheKey == null ? "UNAVAILABLE" : cacheKey);
        snapshot.set("hit_ids", hitIds(selected));
        snapshot.put("retriever_version", policy.path("retriever_version")
                .asText(KnowledgeIndexService.RETRIEVAL_STRATEGY));
        snapshot.put("reranker_version", policy.path("reranker_version")
                .asText(KnowledgeIndexService.RERANKER_VERSION));
        String actorScopeHash = knowledgeIndexService.actorScopeHash(projectId, actorUserId);
        if (actorScopeHash != null) {
            snapshot.put("actor_scope_hash", actorScopeHash);
        }
    }

    private List<KnowledgeSourceResponse> selectSources(
            List<KnowledgeSourceResponse> candidates,
            Long projectId,
            int topK,
            int maxPerArtifact
    ) {
        Map<String, KnowledgeSourceResponse> unique = new LinkedHashMap<>();
        for (KnowledgeSourceResponse source : candidates) {
            if (!projectId.equals(source.projectId())) {
                continue;
            }
            String key = source.chunkId() == null
                    ? source.artifactId() + ":" + source.artifactVersion() + ":"
                    + source.citationLocation()
                    : "chunk:" + source.chunkId();
            unique.putIfAbsent(key, source);
        }
        Map<Long, Integer> perArtifact = new LinkedHashMap<>();
        return unique.values().stream()
                .sorted(Comparator
                        .comparingDouble((KnowledgeSourceResponse source) ->
                                source.relevanceScore() == null ? 0.0 : source.relevanceScore())
                        .reversed()
                        .thenComparing(source -> source.artifactId() == null
                                ? Long.MAX_VALUE : source.artifactId())
                        .thenComparing(source -> source.chunkId() == null
                                ? Long.MAX_VALUE : source.chunkId()))
                .filter(source -> {
                    Long artifactId = source.artifactId();
                    int count = perArtifact.getOrDefault(artifactId, 0);
                    if (count >= maxPerArtifact) {
                        return false;
                    }
                    perArtifact.put(artifactId, count + 1);
                    return true;
                })
                .limit(topK)
                .toList();
    }

    private ArrayNode sourceArray(List<KnowledgeSourceResponse> sources) {
        ArrayNode values = objectMapper.createArrayNode();
        for (KnowledgeSourceResponse source : sources) {
            ObjectNode value = values.addObject();
            String citationId = "artifact:" + source.artifactId()
                    + ":v" + source.artifactVersion();
            if (source.chunkIndex() != null) {
                citationId += ":chunk:" + source.chunkIndex();
            }
            value.put("citation_id", citationId);
            value.put("project_id", source.projectId());
            value.put("artifact_id", source.artifactId());
            value.put("artifact_type", source.artifactType());
            value.put("corpus_type", source.corpusType());
            value.put("title", source.title());
            value.put("artifact_version", source.artifactVersion());
            value.put("chunk_id", source.chunkId());
            value.put("chunk_index", source.chunkIndex());
            value.put("citation_location", source.citationLocation());
            value.put("content", source.content());
            value.put("retrieval_strategy", source.retrievalStrategy());
            value.put("chunker_version", source.chunkerVersion());
            value.put("embedding_model", source.embeddingModel());
            value.put("artifact_content_hash", source.artifactContentHash());
            value.put("chunk_content_hash", source.chunkContentHash());
            value.put("relevance_score", source.relevanceScore());
        }
        return values;
    }

    private ArrayNode hitIds(List<KnowledgeSourceResponse> sources) {
        ArrayNode values = objectMapper.createArrayNode();
        for (KnowledgeSourceResponse source : sources) {
            ObjectNode value = values.addObject();
            value.put("artifact_id", source.artifactId());
            value.put("artifact_version", source.artifactVersion());
            value.put("chunk_id", source.chunkId());
            value.put("chunk_content_hash", source.chunkContentHash());
            value.put("corpus_type", source.corpusType());
        }
        return values;
    }

    private String retrievalQuery(ObjectNode input, String nodeId) {
        ObjectNode query = input.deepCopy();
        query.remove("_autospec_project_id");
        query.remove("_autospec_actor_user_id");
        query.remove("retrieval_policy");
        query.remove("retrieved_sources");
        query.remove("retrieval_snapshot");
        query.put("node_id", nodeId);
        return query.toString();
    }

    private Long longMetadata(ObjectNode input, String field) {
        JsonNode value = input.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        try {
            return Long.valueOf(value.asText());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private List<String> textValues(JsonNode value) {
        if (!value.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        value.forEach(item -> {
            if (item.isTextual() && !item.asText().isBlank()) {
                result.add(item.asText());
            }
        });
        return List.copyOf(result);
    }

    private Map<String, WorkflowNodeRun> latestRuns(long runId) {
        Map<String, WorkflowNodeRun> latest = new LinkedHashMap<>();
        for (WorkflowNodeRun candidate : nodeRunMapper.selectList(
                new LambdaQueryWrapper<WorkflowNodeRun>()
                        .eq(WorkflowNodeRun::getWorkflowRunId, runId)
                        .orderByDesc(WorkflowNodeRun::getRevision)
                        .orderByDesc(WorkflowNodeRun::getAttempt))) {
            latest.putIfAbsent(candidate.getNodeId(), candidate);
        }
        return latest;
    }

    private ArrayNode trustedRecords(
            Map<String, WorkflowNodeRun> latest,
            Set<String> ancestorIds
    ) {
        ArrayNode records = objectMapper.createArrayNode();
        for (String nodeId : ancestorIds) {
            WorkflowNodeRun run = latest.get(nodeId);
            if (run == null) {
                continue;
            }
            ObjectNode record = records.addObject();
            record.put("node_name", run.getNodeId());
            record.put("execution_id", run.getExecutionId());
            record.put("revision", run.getRevision());
            record.put("attempt", run.getAttempt());
            record.put("status", run.getStatus());
            if (run.getDurationMs() != null) {
                record.put("duration_ms", run.getDurationMs());
            }
            if (run.getErrorCode() != null) {
                record.put("error_code", run.getErrorCode());
            }
        }
        return records;
    }

    private ArrayNode trustedModelInvocations(long workflowRunId) {
        ArrayNode values = objectMapper.createArrayNode();
        for (ModelInvocation invocation : modelInvocationMapper.selectList(
                new LambdaQueryWrapper<ModelInvocation>()
                        .eq(ModelInvocation::getWorkflowRunId, workflowRunId)
                        .orderByAsc(ModelInvocation::getId))) {
            ObjectNode value = values.addObject();
            value.put("invocation_id", invocation.getId());
            value.put("call_id", invocation.getCallId());
            value.put("call_type", invocation.getCallType());
            value.put("execution_id", invocation.getExecutionId());
            value.put("call_sequence", invocation.getCallSequence());
            value.put("attempt", invocation.getAttempt());
            value.put("node_run_id", invocation.getWorkflowNodeRunId());
            value.put("provider", invocation.getProviderKey());
            value.put("model", invocation.getModelName());
            value.put("status", invocation.getStatus());
            value.put("route_key", invocation.getRouteKey());
            value.put("route_reason", invocation.getRouteReason());
            value.put("fallback_used", Boolean.TRUE.equals(invocation.getFallbackUsed()));
            value.put("model_call_count", invocation.getCallCount());
            value.put("input_tokens", invocation.getInputTokens());
            value.put("output_tokens", invocation.getOutputTokens());
            value.put("cache_tokens", invocation.getCacheTokens());
            value.put("estimated_cost", invocation.getEstimatedCost());
            value.put("prompt_version", invocation.getPromptVersion());
            value.put("prompt_checksum", invocation.getPromptChecksum());
            value.put("schema_version", invocation.getSchemaVersion());
            value.put("contract_hash", invocation.getContractHash());
            value.put("normalized_params_hash", invocation.getNormalizedParamsHash());
            value.put("result_hash", invocation.getResultHash());
            value.put("reserved_input_tokens", invocation.getReservedInputTokens());
            value.put("reserved_output_tokens", invocation.getReservedOutputTokens());
            value.put("reserved_cost", invocation.getReservedCost());
            value.put("settlement_delta_tokens", invocation.getSettlementDeltaTokens());
            value.put("settlement_delta_cost", invocation.getSettlementDeltaCost());
            value.put("error_code", invocation.getErrorCode());
            value.put("error_message", invocation.getErrorMessage());
            value.put("duration_ms", invocation.getDurationMs());
            value.put("deadline_epoch_ms", invocation.getDeadlineEpochMs());
            value.put("idempotency_key", invocation.getIdempotencyKey());
            value.put("tool_name", invocation.getToolName());
            value.put("tool_version", invocation.getToolVersion());
            value.put("permission_policy", invocation.getPermissionPolicy());
            value.put("reference_sources_json", invocation.getReferenceSourcesJson());
        }
        return values;
    }

    private Set<String> ancestors(CompiledWorkflow graph, String nodeId) {
        Set<String> result = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(graph.predecessors().getOrDefault(nodeId, java.util.List.of()));
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            if (result.add(current)) {
                queue.addAll(graph.predecessors().getOrDefault(current, java.util.List.of()));
            }
        }
        return result;
    }

    private String inputField(com.autospec.workflow.spec.WorkflowNodeDocument node) {
        if (node.artifactType() == null || node.artifactType().isBlank()) {
            return node.nodeId();
        }
        return switch (node.artifactType()) {
            case "PRD" -> "prd";
            case "ARCHITECTURE_DESIGN" -> "architecture_design";
            case "BACKEND_DESIGN" -> "backend_design";
            case "FRONTEND_SKELETON" -> "frontend_skeleton";
            case "REVIEW_REPORT" -> "review_report";
            case "EVALUATION_REPORT" -> "evaluation_report";
            default -> node.nodeId();
        };
    }

    private ObjectNode objectInput(String value) {
        JsonNode parsed = json(value == null || value.isBlank() ? "{}" : value);
        if (!parsed.isObject()) {
            throw new IllegalArgumentException("workflow node input must be a JSON object");
        }
        return (ObjectNode) parsed.deepCopy();
    }

    private JsonNode json(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("invalid workflow node input JSON", exception);
        }
    }
}
