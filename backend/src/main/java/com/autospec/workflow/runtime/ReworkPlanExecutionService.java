package com.autospec.workflow.runtime;

import com.autospec.entity.Artifact;
import com.autospec.entity.WorkflowNodeRun;
import com.autospec.entity.WorkflowRun;
import com.autospec.mapper.ArtifactMapper;
import com.autospec.mapper.WorkflowNodeRunMapper;
import com.autospec.mapper.WorkflowRunMapper;
import com.autospec.util.ContentHash;
import com.autospec.workflow.transport.WorkflowRunReconciliationTrigger;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ReworkPlanExecutionService {
    private final WorkflowRunMapper workflowRunMapper;
    private final WorkflowNodeRunMapper nodeRunMapper;
    private final ArtifactMapper artifactMapper;
    private final WorkflowSnapshotParser snapshotParser;
    private final DagCompiler dagCompiler;
    private final ReworkPlanner reworkPlanner;
    private final WorkflowSchedulingGateway schedulingGateway;
    private final WorkflowRunReconciliationTrigger reconciliationTrigger;
    private final ObjectMapper objectMapper;

    public ReworkPlanExecutionService(
            WorkflowRunMapper workflowRunMapper,
            WorkflowNodeRunMapper nodeRunMapper,
            ArtifactMapper artifactMapper,
            WorkflowSnapshotParser snapshotParser,
            DagCompiler dagCompiler,
            ReworkPlanner reworkPlanner,
            WorkflowSchedulingGateway schedulingGateway,
            WorkflowRunReconciliationTrigger reconciliationTrigger,
            ObjectMapper objectMapper
    ) {
        this.workflowRunMapper = workflowRunMapper;
        this.nodeRunMapper = nodeRunMapper;
        this.artifactMapper = artifactMapper;
        this.snapshotParser = snapshotParser;
        this.dagCompiler = dagCompiler;
        this.reworkPlanner = reworkPlanner;
        this.schedulingGateway = schedulingGateway;
        this.reconciliationTrigger = reconciliationTrigger;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ReworkPlanner.ReworkPlan execute(
            long workflowRunId,
            String reviewerNodeId,
            List<String> requestedTargets
    ) {
        return execute(workflowRunId, reviewerNodeId, requestedTargets, null);
    }

    @Transactional
    public ReworkPlanner.ReworkPlan execute(
            long workflowRunId,
            String reviewerNodeId,
            List<String> requestedTargets,
            JsonNode triggerPayload
    ) {
        WorkflowRun workflowRun = requireWorkflowRun(workflowRunId);
        CompiledWorkflow graph = dagCompiler.compile(
                snapshotParser.parse(workflowRun.getWorkflowSnapshotJson())
        );
        validateReworkConditions(graph, reviewerNodeId, requestedTargets, triggerPayload);
        Map<String, WorkflowNodeRun> latestRuns = latestRuns(
                schedulingGateway.listNodeRuns(workflowRunId)
        );
        int reviewRound = valueOrZero(workflowRun.getReviewRound());
        int maxReviewRounds = valueOrZero(workflowRun.getMaxReviewRounds());
        ReworkPlanner.ReworkPlan plan = reworkPlanner.plan(
                graph,
                reviewerNodeId,
                requestedTargets,
                latestRuns,
                reviewRound,
                maxReviewRounds
        );

        if (plan.action() == ReworkPlanner.Action.MANUAL_INTERVENTION) {
            moveToManualIntervention(workflowRun);
            return plan;
        }

        validateTargetsAreCompleted(plan);
        advanceReviewRound(workflowRun, plan.nextReviewRound());
        LocalDateTime now = LocalDateTime.now();
        WorkflowNodeRun reviewerRun = latestRuns.get(reviewerNodeId);
        for (String nodeId : plan.staleNodeIds()) {
            WorkflowNodeRun previousRevision = latestRuns.get(nodeId);
            markStale(previousRevision, now);
            JsonNode directive = plan.targetRevisions().containsKey(nodeId)
                    ? reworkDirective(
                            nodeId,
                            previousRevision,
                            reviewerRun,
                            triggerPayload,
                            plan.nextReviewRound(),
                            plan.preservedNodeIds(),
                            plan.staleNodeIds()
                    )
                    : null;
            insertPendingRevision(previousRevision, now, directive);
        }

        reconciliationTrigger.reconcile(workflowRunId);
        return plan;
    }

    private void validateReworkConditions(
            CompiledWorkflow graph,
            String reviewerNodeId,
            List<String> requestedTargets,
            JsonNode triggerPayload
    ) {
        if (triggerPayload == null) {
            return;
        }
        RestrictedConditionEvaluator evaluator = new RestrictedConditionEvaluator();
        for (String target : requestedTargets) {
            boolean allowed = graph.reworkEdges().getOrDefault(reviewerNodeId, List.of()).stream()
                    .filter(edge -> edge.toNode().equals(target))
                    .anyMatch(edge -> edge.condition() == null
                            || evaluator.evaluate(triggerPayload, edge.condition()));
            if (!allowed) {
                throw new IllegalArgumentException(
                        "rework edge condition did not match target: " + target
                );
            }
        }
    }

    private void validateTargetsAreCompleted(ReworkPlanner.ReworkPlan plan) {
        for (String target : plan.targetRevisions().keySet()) {
            if (!plan.staleNodeIds().contains(target)) {
                throw new IllegalStateException(
                        "rework target must have a completed current revision: " + target
                );
            }
        }
    }

    private WorkflowRun requireWorkflowRun(long workflowRunId) {
        WorkflowRun workflowRun = workflowRunMapper.selectById(workflowRunId);
        if (workflowRun == null) {
            throw new IllegalArgumentException("workflow run not found: " + workflowRunId);
        }
        if (workflowRun.getWorkflowSnapshotJson() == null
                || workflowRun.getWorkflowSnapshotJson().isBlank()) {
            throw new IllegalArgumentException(
                    "workflow run has no frozen workflow snapshot: " + workflowRunId
            );
        }
        return workflowRun;
    }

    private Map<String, WorkflowNodeRun> latestRuns(List<WorkflowNodeRun> runs) {
        Map<String, WorkflowNodeRun> latest = new LinkedHashMap<>();
        for (WorkflowNodeRun candidate : runs) {
            WorkflowNodeRun current = latest.get(candidate.getNodeId());
            if (current == null || isNewer(candidate, current)) {
                latest.put(candidate.getNodeId(), candidate);
            }
        }
        return latest;
    }

    private boolean isNewer(WorkflowNodeRun candidate, WorkflowNodeRun current) {
        int revisionComparison = Integer.compare(candidate.getRevision(), current.getRevision());
        return revisionComparison > 0
                || revisionComparison == 0 && candidate.getAttempt() > current.getAttempt();
    }

    private void advanceReviewRound(WorkflowRun workflowRun, int nextReviewRound) {
        int lockVersion = valueOrZero(workflowRun.getLockVersion());
        LocalDateTime now = LocalDateTime.now();
        int updated = workflowRunMapper.update(null, new LambdaUpdateWrapper<WorkflowRun>()
                .eq(WorkflowRun::getId, workflowRun.getId())
                .eq(WorkflowRun::getReviewRound, valueOrZero(workflowRun.getReviewRound()))
                .eq(WorkflowRun::getLockVersion, lockVersion)
                .set(WorkflowRun::getReviewRound, nextReviewRound)
                .set(WorkflowRun::getLockVersion, lockVersion + 1)
                .set(WorkflowRun::getUpdatedAt, now));
        if (updated == 0) {
            throw concurrentChange("workflow run", workflowRun.getId());
        }
    }

    private void moveToManualIntervention(WorkflowRun workflowRun) {
        int lockVersion = valueOrZero(workflowRun.getLockVersion());
        LocalDateTime now = LocalDateTime.now();
        int updated = workflowRunMapper.update(null, new LambdaUpdateWrapper<WorkflowRun>()
                .eq(WorkflowRun::getId, workflowRun.getId())
                .eq(WorkflowRun::getLockVersion, lockVersion)
                .set(WorkflowRun::getStatus, "MANUAL_INTERVENTION")
                .set(WorkflowRun::getResponseStatus, "MANUAL_INTERVENTION")
                .set(WorkflowRun::getLockVersion, lockVersion + 1)
                .set(WorkflowRun::getUpdatedAt, now));
        if (updated == 0) {
            throw concurrentChange("workflow run", workflowRun.getId());
        }
    }

    private void markStale(WorkflowNodeRun nodeRun, LocalDateTime now) {
        if (nodeRun == null) {
            throw new IllegalStateException("missing node run selected by rework plan");
        }
        int lockVersion = valueOrZero(nodeRun.getLockVersion());
        int updated = nodeRunMapper.update(null, new LambdaUpdateWrapper<WorkflowNodeRun>()
                .eq(WorkflowNodeRun::getId, nodeRun.getId())
                .eq(WorkflowNodeRun::getStatus, nodeRun.getStatus())
                .eq(WorkflowNodeRun::getLockVersion, lockVersion)
                .set(WorkflowNodeRun::getStatus, WorkflowNodeStatus.STALE.name())
                .set(WorkflowNodeRun::getLockVersion, lockVersion + 1)
                .set(WorkflowNodeRun::getUpdatedAt, now));
        if (updated == 0) {
            throw concurrentChange("workflow node run", nodeRun.getId());
        }
    }

    private void insertPendingRevision(
            WorkflowNodeRun previous,
            LocalDateTime now,
            JsonNode reworkDirective
    ) {
        WorkflowNodeRun revision = new WorkflowNodeRun();
        revision.setWorkflowRunId(previous.getWorkflowRunId());
        revision.setNodeId(previous.getNodeId());
        revision.setRevision(previous.getRevision() + 1);
        revision.setAttempt(1);
        revision.setExecutionId("pending:" + UUID.randomUUID());
        revision.setStatus(WorkflowNodeStatus.PENDING.name());
        revision.setHandlerKey(previous.getHandlerKey());
        revision.setHandlerVersion(previous.getHandlerVersion());
        revision.setTimeoutMs(previous.getTimeoutMs());
        revision.setInputJson(withReworkDirective(previous.getInputJson(), reworkDirective));
        revision.setLockVersion(0);
        revision.setCreatedAt(now);
        revision.setUpdatedAt(now);
        nodeRunMapper.insert(revision);
    }

    private JsonNode reworkDirective(
            String targetNodeId,
            WorkflowNodeRun previousRevision,
            WorkflowNodeRun reviewerRun,
            JsonNode triggerPayload,
            int reviewRound,
            List<String> preservedNodeIds,
            List<String> staleNodeIds
    ) {
        if (triggerPayload == null) {
            return null;
        }
        Set<String> issueIds = new LinkedHashSet<>();
        Set<String> requiredChanges = new LinkedHashSet<>();
        triggerPayload.path("routes").forEach(route -> {
            if (!targetNodeId.equals(route.path("target_node").asText())) {
                return;
            }
            route.path("issue_ids").forEach(value -> {
                if (!value.asText().isBlank()) {
                    issueIds.add(value.asText());
                }
            });
            route.path("required_changes").forEach(value -> {
                if (!value.asText().isBlank()) {
                    requiredChanges.add(value.asText());
                }
            });
        });
        if (issueIds.isEmpty() || requiredChanges.isEmpty()) {
            throw new IllegalArgumentException(
                    "Reviewer rework route must include issue_ids and required_changes for "
                            + targetNodeId
            );
        }

        ObjectNode directive = objectMapper.createObjectNode();
        directive.put("review_round", reviewRound);
        directive.put("target_node", targetNodeId);
        directive.put("reviewer_output_hash", ContentHash.sha256(triggerPayload.toString()));
        directive.put("reviewer_node_id", reviewerRun == null
                ? "reviewer"
                : reviewerRun.getNodeId());
        if (reviewerRun != null) {
            directive.put("reviewer_node_run_id", reviewerRun.getId());
            directive.put("reviewer_revision", reviewerRun.getRevision());
            directive.put("reviewer_execution_id", reviewerRun.getExecutionId());
        }
        ArrayNode issueIdValues = directive.putArray("issue_ids");
        issueIds.forEach(issueIdValues::add);
        ArrayNode requiredChangeValues = directive.putArray("required_changes");
        requiredChanges.forEach(requiredChangeValues::add);

        ArrayNode issues = directive.putArray("issues");
        Set<String> evidencePaths = new LinkedHashSet<>();
        triggerPayload.path("issues").forEach(issue -> {
            if (issueIds.contains(issue.path("issue_id").asText())) {
                issues.add(issue.deepCopy());
                String artifactPath = issue.path("artifact_path").asText("").trim();
                if (!artifactPath.isEmpty()) {
                    evidencePaths.add(artifactPath);
                }
            }
        });
        ArrayNode evidencePathValues = directive.putArray("evidence_paths");
        evidencePaths.forEach(evidencePathValues::add);
        ObjectNode preservationPolicy = directive.putObject("preservation_policy");
        preservationPolicy.put("preserve_unaffected_stable_ids", true);
        preservationPolicy.put("preserve_unaffected_approved_decisions", true);
        ArrayNode preservedNodes = preservationPolicy.putArray("preserved_node_ids");
        preservedNodeIds.forEach(preservedNodes::add);
        ObjectNode allowedChangeScope = directive.putObject("allowed_change_scope");
        allowedChangeScope.put("mode", "ISSUE_SCOPED");
        ArrayNode scopedIssueIds = allowedChangeScope.putArray("issue_ids");
        issueIds.forEach(scopedIssueIds::add);
        ArrayNode scopedPaths = allowedChangeScope.putArray("artifact_paths");
        evidencePaths.forEach(scopedPaths::add);
        ArrayNode invalidatedDownstream = directive.putArray("invalidated_downstream_node_ids");
        staleNodeIds.stream()
                .filter(nodeId -> !targetNodeId.equals(nodeId))
                .forEach(invalidatedDownstream::add);
        directive.set("previous_artifact", artifactReference(previousRevision));
        Artifact reviewerArtifact = artifactForNodeRun(reviewerRun);
        if (reviewerArtifact != null) {
            directive.set("reviewer_artifact", artifactReference(reviewerArtifact));
        }
        directive.put("directive_hash", ContentHash.sha256(directive.toString()));
        return directive;
    }

    private ObjectNode artifactReference(WorkflowNodeRun nodeRun) {
        ObjectNode reference = objectMapper.createObjectNode();
        reference.put("node_run_id", nodeRun.getId());
        reference.put("revision", nodeRun.getRevision());
        Artifact artifact = artifactForNodeRun(nodeRun);
        if (artifact == null) {
            reference.putNull("artifact_id");
            reference.putNull("artifact_version");
            reference.put(
                    "content_hash",
                    ContentHash.sha256(nodeRun.getOutputJson() == null ? "" : nodeRun.getOutputJson())
            );
            return reference;
        }
        reference.put("artifact_id", artifact.getId());
        reference.put("artifact_version", artifact.getVersion());
        reference.put(
                "content_hash",
                artifact.getContentHash() == null || artifact.getContentHash().isBlank()
                        ? ContentHash.sha256(artifact.getContent() == null ? "" : artifact.getContent())
                        : artifact.getContentHash()
        );
        return reference;
    }

    private ObjectNode artifactReference(Artifact artifact) {
        ObjectNode reference = objectMapper.createObjectNode();
        reference.put("artifact_id", artifact.getId());
        reference.put("artifact_version", artifact.getVersion());
        reference.put(
                "content_hash",
                artifact.getContentHash() == null || artifact.getContentHash().isBlank()
                        ? ContentHash.sha256(artifact.getContent() == null ? "" : artifact.getContent())
                        : artifact.getContentHash()
        );
        return reference;
    }

    private Artifact artifactForNodeRun(WorkflowNodeRun nodeRun) {
        if (nodeRun == null || nodeRun.getId() == null) {
            return null;
        }
        return artifactMapper.selectOne(new LambdaQueryWrapper<Artifact>()
                .eq(Artifact::getWorkflowNodeRunId, nodeRun.getId())
                .orderByDesc(Artifact::getId)
                .last("limit 1"));
    }

    private String withReworkDirective(String inputJson, JsonNode directive) {
        if (directive == null) {
            return inputJson;
        }
        try {
            JsonNode parsed = objectMapper.readTree(
                    inputJson == null || inputJson.isBlank() ? "{}" : inputJson
            );
            if (!parsed.isObject()) {
                throw new IllegalArgumentException("workflow node input must be a JSON object");
            }
            ObjectNode input = (ObjectNode) parsed.deepCopy();
            input.set("rework_directive", directive.deepCopy());
            return objectMapper.writeValueAsString(input);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("invalid workflow node input JSON", exception);
        }
    }

    private IllegalStateException concurrentChange(String aggregate, long id) {
        return new IllegalStateException(
                "concurrent change while applying rework plan to " + aggregate + ": " + id
        );
    }

    private int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }
}
