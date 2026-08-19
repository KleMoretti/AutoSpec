package com.autospec.workflow.runtime;

import com.autospec.entity.Artifact;
import com.autospec.entity.ModelInvocation;
import com.autospec.entity.ReviewIssue;
import com.autospec.entity.WorkflowNodeRun;
import com.autospec.entity.WorkflowRun;
import com.autospec.mapper.ArtifactMapper;
import com.autospec.mapper.ModelInvocationMapper;
import com.autospec.mapper.ReviewIssueMapper;
import com.autospec.mapper.WorkflowNodeRunMapper;
import com.autospec.mapper.WorkflowRunMapper;
import com.autospec.service.ArtifactTraceGraphService;
import com.autospec.workflow.spec.WorkflowNodeDocument;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class MybatisWorkflowArtifactProjector implements WorkflowArtifactProjector {
    private final ArtifactMapper artifactMapper;
    private final WorkflowRunMapper runMapper;
    private final WorkflowNodeRunMapper nodeRunMapper;
    private final ModelInvocationMapper modelInvocationMapper;
    private final ReviewIssueMapper reviewIssueMapper;
    private final WorkflowSnapshotParser snapshotParser;
    private final DagCompiler dagCompiler;
    private final ObjectMapper objectMapper;
    private final ArtifactTraceGraphService traceGraphService;

    public MybatisWorkflowArtifactProjector(
            ArtifactMapper artifactMapper,
            WorkflowRunMapper runMapper,
            WorkflowNodeRunMapper nodeRunMapper,
            ModelInvocationMapper modelInvocationMapper,
            ReviewIssueMapper reviewIssueMapper,
            WorkflowSnapshotParser snapshotParser,
            DagCompiler dagCompiler,
            ObjectMapper objectMapper,
            ArtifactTraceGraphService traceGraphService
    ) {
        this.artifactMapper = artifactMapper;
        this.runMapper = runMapper;
        this.nodeRunMapper = nodeRunMapper;
        this.modelInvocationMapper = modelInvocationMapper;
        this.reviewIssueMapper = reviewIssueMapper;
        this.snapshotParser = snapshotParser;
        this.dagCompiler = dagCompiler;
        this.objectMapper = objectMapper;
        this.traceGraphService = traceGraphService;
    }

    @Override
    public Artifact project(WorkflowNodeRun nodeRun, String outputJson, String status) {
        Artifact existing = artifactMapper.selectOne(new LambdaQueryWrapper<Artifact>()
                .eq(Artifact::getWorkflowNodeRunId, nodeRun.getId())
                .orderByDesc(Artifact::getId)
                .last("limit 1"));
        if (existing != null) {
            return existing;
        }
        WorkflowRun run = runMapper.selectById(nodeRun.getWorkflowRunId());
        if (run == null) {
            throw new IllegalStateException("workflow run not found: " + nodeRun.getWorkflowRunId());
        }
        CompiledWorkflow graph = dagCompiler.compile(
                snapshotParser.parse(run.getWorkflowSnapshotJson())
        );
        WorkflowNodeDocument spec = graph.nodes().get(nodeRun.getNodeId());
        if (spec == null || spec.artifactType() == null || spec.artifactType().isBlank()) {
            return null;
        }
        Artifact latest = artifactMapper.selectOne(new LambdaQueryWrapper<Artifact>()
                .eq(Artifact::getProjectId, run.getProjectId())
                .eq(Artifact::getType, spec.artifactType())
                .orderByDesc(Artifact::getVersion)
                .orderByDesc(Artifact::getId)
                .last("limit 1"));
        LocalDateTime now = LocalDateTime.now();
        Artifact artifact = new Artifact();
        artifact.setProjectId(run.getProjectId());
        artifact.setType(spec.artifactType());
        artifact.setTitle(title(spec.artifactType()));
        artifact.setContent(outputJson == null ? "{}" : outputJson);
        artifact.setFormat("JSON");
        artifact.setVersion(latest == null ? 1 : latest.getVersion() + 1);
        artifact.setStatus(status);
        artifact.setSourceAgent(nodeRun.getHandlerKey());
        artifact.setParentArtifactId(latest == null ? null : latest.getId());
        artifact.setWorkflowNodeRunId(nodeRun.getId());
        artifact.setContentHash(contentHash(artifact.getContent()));
        artifact.setSchemaVersion(schemaVersion(spec, nodeRun));
        artifact.setPromptKey(spec.promptKey() == null ? nodeRun.getHandlerKey() : spec.promptKey());
        artifact.setPromptVersion(
                spec.promptVersion() == null ? nodeRun.getHandlerVersion() : spec.promptVersion()
        );
        ModelInvocation invocation = latestInvocation(nodeRun.getId());
        if (invocation != null) {
            artifact.setModelProvider(invocation.getProviderKey());
            artifact.setModelName(invocation.getModelName());
        }
        artifact.setSourceCitationsJson(sourceCitations(nodeRun.getInputJson()));
        artifact.setProvenanceJson(provenance(run, nodeRun, graph, invocation));
        artifact.setCreatedAt(now);
        artifact.setUpdatedAt(now);
        artifactMapper.insert(artifact);
        traceGraphService.project(artifact, run.getId());
        persistReviewIssues(artifact, outputJson, now);
        return artifact;
    }

    private ModelInvocation latestInvocation(long nodeRunId) {
        return modelInvocationMapper.selectOne(new LambdaQueryWrapper<ModelInvocation>()
                .eq(ModelInvocation::getWorkflowNodeRunId, nodeRunId)
                .orderByDesc(ModelInvocation::getId)
                .last("limit 1"));
    }

    private String sourceCitations(String inputJson) {
        JsonNode input = json(inputJson == null || inputJson.isBlank() ? "{}" : inputJson);
        JsonNode sources = input.path("retrieved_sources");
        return sources.isArray() ? sources.toString() : "[]";
    }

    private String provenance(
            WorkflowRun run,
            WorkflowNodeRun nodeRun,
            CompiledWorkflow graph,
            ModelInvocation invocation
    ) {
        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("workflow_run_id", run.getId());
        provenance.put("workflow_version_id", run.getWorkflowVersionId());
        provenance.put("node_run_id", nodeRun.getId());
        provenance.put("node_id", nodeRun.getNodeId());
        provenance.put("execution_id", nodeRun.getExecutionId());
        provenance.put("handler_key", nodeRun.getHandlerKey());
        provenance.put("handler_version", nodeRun.getHandlerVersion());
        provenance.put("contract_hash", nodeRun.getContractHash());
        provenance.put("fencing_token", nodeRun.getFencingToken());
        WorkflowNodeDocument nodeSpec = graph.nodes().get(nodeRun.getNodeId());
        if (nodeSpec != null && nodeSpec.hasExecutableContract()) {
            provenance.put("input_schema", nodeSpec.inputSchema());
            provenance.put("input_schema_hash", nodeSpec.inputSchemaHash());
            provenance.put("output_schema", nodeSpec.outputSchema());
            provenance.put("output_schema_hash", nodeSpec.outputSchemaHash());
            provenance.put("prompt_key", nodeSpec.promptKey());
            provenance.put("prompt_version", nodeSpec.promptVersion());
            provenance.put("prompt_checksum", nodeSpec.promptChecksum());
            provenance.put("model_policy", nodeSpec.modelPolicy());
            provenance.put("retry_policy", nodeSpec.retryPolicy());
        }
        JsonNode nodeInput = json(
                nodeRun.getInputJson() == null || nodeRun.getInputJson().isBlank()
                        ? "{}"
                        : nodeRun.getInputJson()
        );
        if (nodeInput.path("context_manifest").isObject()) {
            provenance.put("context_manifest", nodeInput.path("context_manifest"));
        }
        if (invocation != null) {
            provenance.put("provider", invocation.getProviderKey());
            provenance.put("model", invocation.getModelName());
            provenance.put("route_key", invocation.getRouteKey());
            provenance.put("route_reason", invocation.getRouteReason());
            provenance.put("fallback_used", invocation.getFallbackUsed());
            if (invocation.getContextManifestJson() != null
                    && !invocation.getContextManifestJson().isBlank()) {
                provenance.put(
                        "context_manifest",
                        json(invocation.getContextManifestJson())
                );
            }
        }
        provenance.put("upstream_artifacts", upstreamArtifacts(run, nodeRun, graph));
        try {
            return objectMapper.writeValueAsString(provenance);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize artifact provenance", exception);
        }
    }

    private List<Map<String, Object>> upstreamArtifacts(
            WorkflowRun run,
            WorkflowNodeRun target,
            CompiledWorkflow graph
    ) {
        Set<String> ancestorNodeIds = ancestors(graph, target.getNodeId());
        if (ancestorNodeIds.isEmpty()) {
            return List.of();
        }
        List<WorkflowNodeRun> nodeRuns = nodeRunMapper.selectList(
                new LambdaQueryWrapper<WorkflowNodeRun>()
                        .eq(WorkflowNodeRun::getWorkflowRunId, run.getId())
                        .in(WorkflowNodeRun::getNodeId, ancestorNodeIds)
                        .eq(WorkflowNodeRun::getStatus, "SUCCEEDED")
                        .orderByDesc(WorkflowNodeRun::getRevision)
                        .orderByDesc(WorkflowNodeRun::getAttempt)
        );
        Map<String, WorkflowNodeRun> latestByNode = new LinkedHashMap<>();
        nodeRuns.forEach(value -> latestByNode.putIfAbsent(value.getNodeId(), value));
        if (latestByNode.isEmpty()) {
            return List.of();
        }
        List<Artifact> artifacts = artifactMapper.selectList(new LambdaQueryWrapper<Artifact>()
                .in(
                        Artifact::getWorkflowNodeRunId,
                        latestByNode.values().stream().map(WorkflowNodeRun::getId).toList()
                )
                .orderByAsc(Artifact::getId));
        List<Map<String, Object>> values = new ArrayList<>();
        for (Artifact artifact : artifacts) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("artifact_id", artifact.getId());
            value.put("artifact_type", artifact.getType());
            value.put("version", artifact.getVersion());
            value.put("content_hash", artifact.getContentHash());
            values.add(value);
        }
        return List.copyOf(values);
    }

    private Set<String> ancestors(CompiledWorkflow graph, String nodeId) {
        Set<String> result = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>(
                graph.predecessors().getOrDefault(nodeId, List.of())
        );
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            if (result.add(current)) {
                queue.addAll(graph.predecessors().getOrDefault(current, List.of()));
            }
        }
        return result;
    }

    private void persistReviewIssues(Artifact artifact, String outputJson, LocalDateTime now) {
        if (!Set.of("REVIEW_REPORT", "EVALUATION_REPORT").contains(artifact.getType())) {
            return;
        }
        JsonNode issues = json(outputJson == null ? "{}" : outputJson).path("issues");
        if (!issues.isArray()) {
            return;
        }
        String keyPrefix = artifact.getType() + ":";
        Set<String> seen = new HashSet<>();
        for (JsonNode issueNode : issues) {
            String issueKey = issueKey(keyPrefix, issueNode);
            seen.add(issueKey);
            ReviewIssue issue = reviewIssueMapper.selectOne(new LambdaQueryWrapper<ReviewIssue>()
                    .eq(ReviewIssue::getProjectId, artifact.getProjectId())
                    .eq(ReviewIssue::getIssueKey, issueKey)
                    .orderByDesc(ReviewIssue::getId)
                    .last("limit 1"));
            if (issue == null) {
                issue = new ReviewIssue();
                issue.setProjectId(artifact.getProjectId());
                issue.setIssueKey(issueKey);
                issue.setCreatedAt(now);
            }
            issue.setSeverity(issueNode.path("severity").asText("LOW").toUpperCase());
            issue.setIssueType(issueNode.path("issue_type").asText("SEMANTIC_REVIEW"));
            issue.setArtifactPath(textOrNull(issueNode, "artifact_path"));
            issue.setArtifactType(targetArtifactType(issue.getArtifactPath()));
            issue.setRequirementId(textOrNull(issueNode, "requirement_id"));
            issue.setDescription(issueNode.path("description").asText(""));
            issue.setSuggestion(issueNode.path("suggestion").asText(""));
            issue.setEvidence(issueNode.path("evidence").toString());
            if (!"IGNORED".equals(issue.getStatus())) {
                issue.setStatus("OPEN");
                issue.setResolution(null);
                issue.setResolvedInArtifactId(null);
            }
            issue.setUpdatedAt(now);
            if (issue.getId() == null) {
                reviewIssueMapper.insert(issue);
            } else {
                reviewIssueMapper.updateById(issue);
            }
        }

        List<ReviewIssue> previous = reviewIssueMapper.selectList(
                new LambdaQueryWrapper<ReviewIssue>()
                        .eq(ReviewIssue::getProjectId, artifact.getProjectId())
                        .likeRight(ReviewIssue::getIssueKey, keyPrefix)
                        .in(ReviewIssue::getStatus, "OPEN", "IN_PROGRESS")
        );
        for (ReviewIssue issue : previous) {
            if (seen.contains(issue.getIssueKey())) {
                continue;
            }
            reviewIssueMapper.update(null, new LambdaUpdateWrapper<ReviewIssue>()
                    .eq(ReviewIssue::getId, issue.getId())
                    .in(ReviewIssue::getStatus, "OPEN", "IN_PROGRESS")
                    .set(ReviewIssue::getStatus, "RESOLVED")
                    .set(ReviewIssue::getResolution, "No longer present in artifact #" + artifact.getId())
                    .set(ReviewIssue::getResolvedInArtifactId, artifact.getId())
                    .set(ReviewIssue::getUpdatedAt, now));
        }
    }

    private String issueKey(String prefix, JsonNode issue) {
        String explicit = textOrNull(issue, "issue_id");
        if (explicit != null) {
            return prefix + explicit;
        }
        return prefix + contentHash(
                issue.path("issue_type").asText() + "|"
                        + issue.path("requirement_id").asText() + "|"
                        + issue.path("artifact_path").asText() + "|"
                        + issue.path("description").asText()
        ).substring(0, 24);
    }

    private String targetArtifactType(String artifactPath) {
        if (artifactPath == null || artifactPath.isBlank()) {
            return null;
        }
        int separator = artifactPath.indexOf('.');
        return (separator < 0 ? artifactPath : artifactPath.substring(0, separator)).toUpperCase();
    }

    private String textOrNull(JsonNode node, String field) {
        String value = node.path(field).asText("").trim();
        return value.isEmpty() ? null : value;
    }

    private JsonNode json(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid workflow artifact JSON", exception);
        }
    }

    private String contentHash(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String schemaVersion(WorkflowNodeDocument spec, WorkflowNodeRun nodeRun) {
        if (spec.outputSchemaHash() != null && spec.outputSchemaHash().length() >= 32) {
            return spec.outputSchemaHash().substring(0, 32);
        }
        return nodeRun.getHandlerVersion();
    }

    private String title(String artifactType) {
        String normalized = artifactType.toLowerCase().replace('_', ' ');
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }
}
