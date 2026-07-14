package com.autospec.service.impl;

import com.autospec.entity.Artifact;
import com.autospec.entity.WorkflowApproval;
import com.autospec.entity.WorkflowNodeRun;
import com.autospec.entity.WorkflowRun;
import com.autospec.entity.WorkflowTransition;
import com.autospec.mapper.ArtifactMapper;
import com.autospec.mapper.WorkflowApprovalMapper;
import com.autospec.mapper.WorkflowNodeRunMapper;
import com.autospec.mapper.WorkflowRunMapper;
import com.autospec.mapper.WorkflowTransitionMapper;
import com.autospec.service.WorkflowApprovalService;
import com.autospec.workflow.runtime.CompiledWorkflow;
import com.autospec.workflow.runtime.DagCompiler;
import com.autospec.workflow.runtime.WorkflowNodeStatus;
import com.autospec.workflow.runtime.WorkflowSnapshotParser;
import com.autospec.workflow.transport.WorkflowRunReconciliationTrigger;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class WorkflowApprovalServiceImpl implements WorkflowApprovalService {
    private static final Set<String> SUPPORTED_ACTIONS = Set.of(
            "APPROVE",
            "REJECT",
            "EDIT_AND_APPROVE",
            "ROLLBACK_TO_NODE",
            "CANCEL_WORKFLOW"
    );

    private final WorkflowApprovalMapper approvalMapper;
    private final WorkflowRunMapper runMapper;
    private final WorkflowNodeRunMapper nodeRunMapper;
    private final ArtifactMapper artifactMapper;
    private final WorkflowTransitionMapper transitionMapper;
    private final WorkflowSnapshotParser snapshotParser;
    private final DagCompiler dagCompiler;
    private final WorkflowRunReconciliationTrigger reconciliationTrigger;

    public WorkflowApprovalServiceImpl(
            WorkflowApprovalMapper approvalMapper,
            WorkflowRunMapper runMapper,
            WorkflowNodeRunMapper nodeRunMapper,
            ArtifactMapper artifactMapper,
            WorkflowTransitionMapper transitionMapper,
            WorkflowSnapshotParser snapshotParser,
            DagCompiler dagCompiler,
            @Lazy WorkflowRunReconciliationTrigger reconciliationTrigger
    ) {
        this.approvalMapper = approvalMapper;
        this.runMapper = runMapper;
        this.nodeRunMapper = nodeRunMapper;
        this.artifactMapper = artifactMapper;
        this.transitionMapper = transitionMapper;
        this.snapshotParser = snapshotParser;
        this.dagCompiler = dagCompiler;
        this.reconciliationTrigger = reconciliationTrigger;
    }

    @Override
    public WorkflowApproval getById(long approvalId) {
        WorkflowApproval approval = approvalMapper.selectById(approvalId);
        if (approval == null) {
            throw notFound("Workflow approval not found");
        }
        return approval;
    }

    @Override
    public List<WorkflowApproval> listByProjectId(long projectId) {
        List<Long> runIds = runMapper.selectList(new LambdaQueryWrapper<WorkflowRun>()
                        .eq(WorkflowRun::getProjectId, projectId)
                        .select(WorkflowRun::getId))
                .stream()
                .map(WorkflowRun::getId)
                .toList();
        if (runIds.isEmpty()) {
            return List.of();
        }
        return approvalMapper.selectList(new LambdaQueryWrapper<WorkflowApproval>()
                .in(WorkflowApproval::getWorkflowRunId, runIds)
                .orderByDesc(WorkflowApproval::getId));
    }

    @Override
    @Transactional
    public boolean pauseBeforeIfRequired(
            CompiledWorkflow graph,
            WorkflowNodeRun nodeRun
    ) {
        if (!"BEFORE_NODE".equals(graph.nodes().get(nodeRun.getNodeId()).approval().mode())) {
            return false;
        }
        WorkflowApproval existing = approvalMapper.selectOne(
                new LambdaQueryWrapper<WorkflowApproval>()
                        .eq(WorkflowApproval::getNodeRunId, nodeRun.getId())
                        .eq(WorkflowApproval::getMode, "BEFORE_NODE")
                        .orderByDesc(WorkflowApproval::getId)
                        .last("limit 1")
        );
        if (existing != null) {
            return "PENDING".equals(existing.getStatus());
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = nodeRunMapper.update(null, new LambdaUpdateWrapper<WorkflowNodeRun>()
                .eq(WorkflowNodeRun::getId, nodeRun.getId())
                .eq(WorkflowNodeRun::getStatus, WorkflowNodeStatus.PENDING.name())
                .eq(WorkflowNodeRun::getLockVersion, valueOrZero(nodeRun.getLockVersion()))
                .set(WorkflowNodeRun::getStatus, WorkflowNodeStatus.WAITING_APPROVAL.name())
                .set(WorkflowNodeRun::getLockVersion, valueOrZero(nodeRun.getLockVersion()) + 1)
                .set(WorkflowNodeRun::getUpdatedAt, now));
        if (updated == 0) {
            WorkflowNodeRun current = nodeRunMapper.selectById(nodeRun.getId());
            return current != null && WorkflowNodeStatus.WAITING_APPROVAL.name()
                    .equals(current.getStatus());
        }
        createPendingApproval(nodeRun, "BEFORE_NODE", null, now);
        transition(nodeRun, "PENDING", "WAITING_APPROVAL", "APPROVAL_REQUESTED", now);
        return true;
    }

    @Override
    @Transactional
    public Integer pauseAfterIfRequired(
            WorkflowNodeRun nodeRun,
            String executionId,
            String outputJson,
            LocalDateTime completedAt
    ) {
        WorkflowRun run = requireRun(nodeRun.getWorkflowRunId());
        CompiledWorkflow graph = dagCompiler.compile(
                snapshotParser.parse(run.getWorkflowSnapshotJson())
        );
        if (!"AFTER_NODE".equals(graph.nodes().get(nodeRun.getNodeId()).approval().mode())) {
            return null;
        }
        int updated = nodeRunMapper.update(null, new LambdaUpdateWrapper<WorkflowNodeRun>()
                .eq(WorkflowNodeRun::getId, nodeRun.getId())
                .eq(WorkflowNodeRun::getExecutionId, executionId)
                .in(WorkflowNodeRun::getStatus,
                        WorkflowNodeStatus.QUEUED.name(), WorkflowNodeStatus.RUNNING.name())
                .set(WorkflowNodeRun::getStatus, WorkflowNodeStatus.WAITING_APPROVAL.name())
                .set(WorkflowNodeRun::getOutputJson, outputJson)
                .set(WorkflowNodeRun::getFinishedAt, completedAt)
                .set(WorkflowNodeRun::getLockVersion, valueOrZero(nodeRun.getLockVersion()) + 1)
                .set(WorkflowNodeRun::getUpdatedAt, completedAt));
        if (updated == 0) {
            return 0;
        }
        Artifact candidate = artifactMapper.selectOne(new LambdaQueryWrapper<Artifact>()
                .eq(Artifact::getWorkflowNodeRunId, nodeRun.getId())
                .orderByDesc(Artifact::getId)
                .last("limit 1"));
        createPendingApproval(
                nodeRun,
                "AFTER_NODE",
                candidate == null ? null : candidate.getId(),
                completedAt
        );
        transition(
                nodeRun,
                nodeRun.getStatus(),
                "WAITING_APPROVAL",
                "APPROVAL_REQUESTED",
                completedAt
        );
        return 1;
    }

    @Override
    @Transactional
    public WorkflowApproval decide(long approvalId, ApprovalDecision command) {
        WorkflowApproval approval = getById(approvalId);
        validateIdempotency(command.idempotencyKey());
        if (!"PENDING".equals(approval.getStatus())) {
            if (command.idempotencyKey().equals(approval.getIdempotencyKey())) {
                return approval;
            }
            throw conflict("Workflow approval already decided");
        }

        WorkflowRun run = requireRun(approval.getWorkflowRunId());
        WorkflowNodeRun nodeRun = requireNodeRun(approval.getNodeRunId());
        CompiledWorkflow graph = dagCompiler.compile(
                snapshotParser.parse(run.getWorkflowSnapshotJson())
        );
        String action = normalizeAction(command.action());
        validateAllowedAction(graph, nodeRun.getNodeId(), action);

        LocalDateTime now = LocalDateTime.now();
        reserveDecision(approval, command, action, now);
        switch (action) {
            case "APPROVE" -> approveNode(approval, nodeRun, null, now);
            case "EDIT_AND_APPROVE" -> {
                Artifact revised = reviseCandidate(run, approval, nodeRun, command.editedContent());
                approval.setRevisedArtifactId(revised.getId());
                approvalMapper.updateById(approval);
                approveNode(approval, nodeRun, revised.getContent(), now);
            }
            case "REJECT" -> reject(run, nodeRun, command.reason(), now);
            case "ROLLBACK_TO_NODE" -> rollback(
                    run, graph, nodeRun, command.rollbackNodeId(), now
            );
            case "CANCEL_WORKFLOW" -> cancel(run, nodeRun, now);
            default -> throw new IllegalStateException("Unsupported approval action: " + action);
        }
        return approvalMapper.selectById(approvalId);
    }

    private void reserveDecision(
            WorkflowApproval approval,
            ApprovalDecision command,
            String action,
            LocalDateTime now
    ) {
        int updated = approvalMapper.update(null, new LambdaUpdateWrapper<WorkflowApproval>()
                .eq(WorkflowApproval::getId, approval.getId())
                .eq(WorkflowApproval::getStatus, "PENDING")
                .set(WorkflowApproval::getStatus, "DECIDED")
                .set(WorkflowApproval::getDecision, action)
                .set(WorkflowApproval::getDecidedByUserId, command.userId())
                .set(WorkflowApproval::getDecisionReason, command.reason())
                .set(WorkflowApproval::getIdempotencyKey, command.idempotencyKey())
                .set(WorkflowApproval::getDecidedAt, now)
                .set(WorkflowApproval::getUpdatedAt, now));
        if (updated == 0) {
            throw conflict("Workflow approval was decided concurrently");
        }
        approval.setStatus("DECIDED");
        approval.setDecision(action);
        approval.setDecidedByUserId(command.userId());
        approval.setDecisionReason(command.reason());
        approval.setIdempotencyKey(command.idempotencyKey());
        approval.setDecidedAt(now);
        approval.setUpdatedAt(now);
    }

    private void createPendingApproval(
            WorkflowNodeRun nodeRun,
            String mode,
            Long candidateArtifactId,
            LocalDateTime now
    ) {
        WorkflowApproval approval = new WorkflowApproval();
        approval.setWorkflowRunId(nodeRun.getWorkflowRunId());
        approval.setNodeRunId(nodeRun.getId());
        approval.setMode(mode);
        approval.setStatus("PENDING");
        approval.setCandidateArtifactId(candidateArtifactId);
        approval.setIdempotencyKey("pending:" + nodeRun.getId() + ":" + mode);
        approval.setCreatedAt(now);
        approval.setUpdatedAt(now);
        approvalMapper.insert(approval);
    }

    private void approveNode(
            WorkflowApproval approval,
            WorkflowNodeRun nodeRun,
            String editedOutput,
            LocalDateTime now
    ) {
        String targetStatus = "BEFORE_NODE".equals(approval.getMode())
                ? WorkflowNodeStatus.PENDING.name()
                : WorkflowNodeStatus.SUCCEEDED.name();
        LambdaUpdateWrapper<WorkflowNodeRun> update = new LambdaUpdateWrapper<WorkflowNodeRun>()
                .eq(WorkflowNodeRun::getId, nodeRun.getId())
                .eq(WorkflowNodeRun::getStatus, WorkflowNodeStatus.WAITING_APPROVAL.name())
                .set(WorkflowNodeRun::getStatus, targetStatus)
                .set(WorkflowNodeRun::getLockVersion, valueOrZero(nodeRun.getLockVersion()) + 1)
                .set(WorkflowNodeRun::getUpdatedAt, now);
        if (editedOutput != null) {
            update.set(WorkflowNodeRun::getOutputJson, editedOutput);
        }
        if (nodeRunMapper.update(null, update) == 0) {
            throw conflict("Approval node is no longer waiting");
        }
        transition(nodeRun, "WAITING_APPROVAL", targetStatus, "APPROVAL_ACCEPTED", now);
        reconciliationTrigger.reconcile(nodeRun.getWorkflowRunId());
    }

    private Artifact reviseCandidate(
            WorkflowRun run,
            WorkflowApproval approval,
            WorkflowNodeRun nodeRun,
            String editedContent
    ) {
        if (editedContent == null || editedContent.isBlank()) {
            throw badRequest("editedContent is required for EDIT_AND_APPROVE");
        }
        if (approval.getCandidateArtifactId() == null) {
            throw conflict("Approval has no candidate artifact to edit");
        }
        Artifact candidate = artifactMapper.selectById(approval.getCandidateArtifactId());
        if (candidate == null || !run.getProjectId().equals(candidate.getProjectId())) {
            throw conflict("Approval candidate artifact is unavailable");
        }
        int nextVersion = artifactMapper.selectList(new LambdaQueryWrapper<Artifact>()
                        .eq(Artifact::getProjectId, candidate.getProjectId())
                        .eq(Artifact::getType, candidate.getType()))
                .stream()
                .map(Artifact::getVersion)
                .filter(java.util.Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0) + 1;
        Artifact revised = new Artifact();
        revised.setProjectId(candidate.getProjectId());
        revised.setType(candidate.getType());
        revised.setTitle(candidate.getTitle());
        revised.setContent(editedContent);
        revised.setFormat(candidate.getFormat());
        revised.setVersion(nextVersion);
        revised.setStatus("APPROVED");
        revised.setSourceAgent("HUMAN_EDITOR");
        revised.setParentArtifactId(candidate.getId());
        revised.setWorkflowNodeRunId(nodeRun.getId());
        revised.setApprovedAt(LocalDateTime.now());
        artifactMapper.insert(revised);
        return revised;
    }

    private void reject(
            WorkflowRun run,
            WorkflowNodeRun nodeRun,
            String reason,
            LocalDateTime now
    ) {
        updateWaitingNode(nodeRun, WorkflowNodeStatus.FAILED.name(), now);
        runMapper.update(null, new LambdaUpdateWrapper<WorkflowRun>()
                .eq(WorkflowRun::getId, run.getId())
                .set(WorkflowRun::getStatus, "FAILED")
                .set(WorkflowRun::getErrorMessage,
                        reason == null || reason.isBlank() ? "Approval rejected" : reason)
                .set(WorkflowRun::getCompletedAt, now)
                .set(WorkflowRun::getUpdatedAt, now));
        transition(nodeRun, "WAITING_APPROVAL", "FAILED", "APPROVAL_REJECTED", now);
    }

    private void rollback(
            WorkflowRun run,
            CompiledWorkflow graph,
            WorkflowNodeRun approvalNode,
            String targetNodeId,
            LocalDateTime now
    ) {
        if (targetNodeId == null || targetNodeId.isBlank()) {
            throw badRequest("rollbackNodeId is required for ROLLBACK_TO_NODE");
        }
        if (!graph.nodes().containsKey(targetNodeId)) {
            throw badRequest("Unknown rollback node: " + targetNodeId);
        }
        Set<String> affected = downstreamClosure(graph, targetNodeId);
        if (!affected.contains(approvalNode.getNodeId())) {
            throw badRequest("Rollback node must be an ancestor of the approval node");
        }
        Map<String, WorkflowNodeRun> latest = latestRuns(run.getId());
        for (String nodeId : affected) {
            WorkflowNodeRun previous = latest.get(nodeId);
            if (previous == null || !canInvalidateForRollback(previous.getStatus())) {
                continue;
            }
            markStale(previous, now);
            insertRevision(previous, now);
        }
        transition(
                approvalNode,
                "WAITING_APPROVAL",
                "STALE",
                "APPROVAL_ROLLBACK",
                now
        );
        reconciliationTrigger.reconcile(run.getId());
    }

    private void cancel(WorkflowRun run, WorkflowNodeRun nodeRun, LocalDateTime now) {
        updateWaitingNode(nodeRun, WorkflowNodeStatus.CANCELLED.name(), now);
        runMapper.update(null, new LambdaUpdateWrapper<WorkflowRun>()
                .eq(WorkflowRun::getId, run.getId())
                .set(WorkflowRun::getStatus, "CANCELLED")
                .set(WorkflowRun::getErrorMessage, "Cancelled by approval decision")
                .set(WorkflowRun::getCompletedAt, now)
                .set(WorkflowRun::getUpdatedAt, now));
        transition(nodeRun, "WAITING_APPROVAL", "CANCELLED", "APPROVAL_CANCELLED", now);
    }

    private void updateWaitingNode(
            WorkflowNodeRun nodeRun,
            String targetStatus,
            LocalDateTime now
    ) {
        int updated = nodeRunMapper.update(null, new LambdaUpdateWrapper<WorkflowNodeRun>()
                .eq(WorkflowNodeRun::getId, nodeRun.getId())
                .eq(WorkflowNodeRun::getStatus, WorkflowNodeStatus.WAITING_APPROVAL.name())
                .set(WorkflowNodeRun::getStatus, targetStatus)
                .set(WorkflowNodeRun::getLockVersion, valueOrZero(nodeRun.getLockVersion()) + 1)
                .set(WorkflowNodeRun::getUpdatedAt, now));
        if (updated == 0) {
            throw conflict("Approval node is no longer waiting");
        }
    }

    private void validateAllowedAction(
            CompiledWorkflow graph,
            String nodeId,
            String action
    ) {
        List<String> allowed = graph.nodes().get(nodeId).approval().allowedActions();
        if (!allowed.contains(action)) {
            throw badRequest("Approval action is not allowed for node " + nodeId + ": " + action);
        }
    }

    private String normalizeAction(String action) {
        String normalized = action == null ? "" : action.trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_ACTIONS.contains(normalized)) {
            throw badRequest("Unsupported approval action: " + action);
        }
        return normalized;
    }

    private void validateIdempotency(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw badRequest("idempotencyKey is required");
        }
    }

    private WorkflowRun requireRun(long runId) {
        WorkflowRun run = runMapper.selectById(runId);
        if (run == null) {
            throw notFound("Workflow run not found");
        }
        return run;
    }

    private WorkflowNodeRun requireNodeRun(long nodeRunId) {
        WorkflowNodeRun nodeRun = nodeRunMapper.selectById(nodeRunId);
        if (nodeRun == null) {
            throw notFound("Workflow node run not found");
        }
        return nodeRun;
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

    private Set<String> downstreamClosure(CompiledWorkflow graph, String target) {
        Set<String> affected = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(target);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            if (affected.add(current)) {
                queue.addAll(graph.successors().getOrDefault(current, List.of()));
            }
        }
        return affected;
    }

    private boolean canInvalidateForRollback(String status) {
        return WorkflowNodeStatus.SUCCEEDED.name().equals(status)
                || WorkflowNodeStatus.SKIPPED.name().equals(status)
                || WorkflowNodeStatus.WAITING_APPROVAL.name().equals(status);
    }

    private void markStale(WorkflowNodeRun previous, LocalDateTime now) {
        int updated = nodeRunMapper.update(null, new LambdaUpdateWrapper<WorkflowNodeRun>()
                .eq(WorkflowNodeRun::getId, previous.getId())
                .eq(WorkflowNodeRun::getStatus, previous.getStatus())
                .set(WorkflowNodeRun::getStatus, WorkflowNodeStatus.STALE.name())
                .set(WorkflowNodeRun::getLockVersion, valueOrZero(previous.getLockVersion()) + 1)
                .set(WorkflowNodeRun::getUpdatedAt, now));
        if (updated == 0) {
            throw conflict("Workflow changed while applying approval rollback");
        }
    }

    private void insertRevision(WorkflowNodeRun previous, LocalDateTime now) {
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
        revision.setInputJson(previous.getInputJson());
        revision.setLockVersion(0);
        revision.setCreatedAt(now);
        revision.setUpdatedAt(now);
        nodeRunMapper.insert(revision);
    }

    private void transition(
            WorkflowNodeRun nodeRun,
            String fromStatus,
            String toStatus,
            String eventType,
            LocalDateTime now
    ) {
        WorkflowTransition transition = new WorkflowTransition();
        transition.setWorkflowRunId(nodeRun.getWorkflowRunId());
        transition.setNodeRunId(nodeRun.getId());
        transition.setFromStatus(fromStatus);
        transition.setToStatus(toStatus);
        transition.setEventType(eventType);
        transition.setEventId("approval:" + UUID.randomUUID());
        transition.setCreatedAt(now);
        transitionMapper.insert(transition);
    }

    private int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }
}
