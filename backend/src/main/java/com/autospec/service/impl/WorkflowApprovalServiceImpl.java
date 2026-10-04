package com.autospec.service.impl;

import com.autospec.entity.Artifact;
import com.autospec.entity.WorkflowApproval;
import com.autospec.entity.WorkflowClarification;
import com.autospec.entity.WorkflowNodeRun;
import com.autospec.entity.WorkflowRun;
import com.autospec.entity.WorkflowTransition;
import com.autospec.exception.OptimisticLockConflictException;
import com.autospec.mapper.ArtifactMapper;
import com.autospec.mapper.WorkflowApprovalMapper;
import com.autospec.mapper.WorkflowClarificationMapper;
import com.autospec.mapper.WorkflowNodeRunMapper;
import com.autospec.mapper.WorkflowRunMapper;
import com.autospec.mapper.WorkflowTransitionMapper;
import com.autospec.service.WorkflowApprovalService;
import com.autospec.service.ArtifactApprovalOutboxService;
import com.autospec.service.RequirementBaselineService;
import com.autospec.workflow.runtime.CompiledWorkflow;
import com.autospec.workflow.runtime.DagCompiler;
import com.autospec.workflow.runtime.WorkflowNodeStatus;
import com.autospec.workflow.runtime.WorkflowArtifactProjector;
import com.autospec.workflow.runtime.WorkflowSnapshotParser;
import com.autospec.workflow.transport.WorkflowRunReconciliationTrigger;
import com.autospec.util.ContentHash;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.context.annotation.Lazy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
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
    private final WorkflowClarificationMapper clarificationMapper;
    private final WorkflowRunMapper runMapper;
    private final WorkflowNodeRunMapper nodeRunMapper;
    private final ArtifactMapper artifactMapper;
    private final WorkflowTransitionMapper transitionMapper;
    private final WorkflowSnapshotParser snapshotParser;
    private final DagCompiler dagCompiler;
    private final WorkflowRunReconciliationTrigger reconciliationTrigger;
    private final WorkflowArtifactProjector artifactProjector;
    private final ArtifactApprovalOutboxService approvalOutboxService;
    private final ObjectMapper objectMapper;
    private final RequirementBaselineService requirementBaselineService;

    @Autowired
    public WorkflowApprovalServiceImpl(
            WorkflowApprovalMapper approvalMapper,
            WorkflowRunMapper runMapper,
            WorkflowNodeRunMapper nodeRunMapper,
            ArtifactMapper artifactMapper,
            WorkflowTransitionMapper transitionMapper,
            WorkflowSnapshotParser snapshotParser,
            DagCompiler dagCompiler,
            WorkflowArtifactProjector artifactProjector,
            ArtifactApprovalOutboxService approvalOutboxService,
            @Lazy WorkflowRunReconciliationTrigger reconciliationTrigger,
            WorkflowClarificationMapper clarificationMapper,
            ObjectMapper objectMapper,
            RequirementBaselineService requirementBaselineService
    ) {
        this.approvalMapper = approvalMapper;
        this.runMapper = runMapper;
        this.nodeRunMapper = nodeRunMapper;
        this.artifactMapper = artifactMapper;
        this.transitionMapper = transitionMapper;
        this.snapshotParser = snapshotParser;
        this.dagCompiler = dagCompiler;
        this.artifactProjector = artifactProjector;
        this.approvalOutboxService = approvalOutboxService;
        this.reconciliationTrigger = reconciliationTrigger;
        this.clarificationMapper = clarificationMapper;
        this.objectMapper = objectMapper;
        this.requirementBaselineService = requirementBaselineService;
    }

    public WorkflowApprovalServiceImpl(
            WorkflowApprovalMapper approvalMapper,
            WorkflowRunMapper runMapper,
            WorkflowNodeRunMapper nodeRunMapper,
            ArtifactMapper artifactMapper,
            WorkflowTransitionMapper transitionMapper,
            WorkflowSnapshotParser snapshotParser,
            DagCompiler dagCompiler,
            WorkflowArtifactProjector artifactProjector,
            ArtifactApprovalOutboxService approvalOutboxService,
            @Lazy WorkflowRunReconciliationTrigger reconciliationTrigger
    ) {
        this(
                approvalMapper,
                runMapper,
                nodeRunMapper,
                artifactMapper,
                transitionMapper,
                snapshotParser,
                dagCompiler,
                artifactProjector,
                approvalOutboxService,
                reconciliationTrigger,
                null,
                new ObjectMapper(),
                null
        );
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
    public List<WorkflowClarification> listClarifications(long workflowRunId) {
        requireRun(workflowRunId);
        return clarificationMapper.selectList(new LambdaQueryWrapper<WorkflowClarification>()
                .eq(WorkflowClarification::getWorkflowRunId, workflowRunId)
                .orderByDesc(WorkflowClarification::getId));
    }

    @Override
    public WorkflowClarification getClarification(long workflowRunId, long clarificationId) {
        WorkflowClarification clarification = clarificationMapper.selectById(clarificationId);
        if (clarification == null || !Long.valueOf(workflowRunId).equals(clarification.getWorkflowRunId())) {
            throw notFound("Workflow clarification not found");
        }
        return clarification;
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
        Artifact candidate = artifactProjector.project(
                nodeRun,
                outputJson,
                "PENDING_REVIEW"
        );
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
    public Integer pauseForInputRequired(
            WorkflowNodeRun nodeRun,
            String executionId,
            String outputJson,
            LocalDateTime completedAt
    ) {
        if (nodeRun == null
                || !"product_manager".equals(nodeRun.getNodeId())
                || outputJson == null
                || outputJson.isBlank()) {
            return null;
        }
        JsonNode envelope = parseJson(outputJson, "clarification output");
        if (!"CLARIFICATION_REQUIRED".equals(envelope.path("kind").asText())
                || !envelope.path("clarification_request").isObject()) {
            return null;
        }
        JsonNode request = envelope.path("clarification_request");
        String requestId = requiredJsonText(request, "request_id");
        int round = request.path("round").asInt(0);
        if (round < 1 || round > 10) {
            throw badRequest("clarification round must be between 1 and 10");
        }
        LocalDateTime now = completedAt == null ? LocalDateTime.now() : completedAt;
        int updated = nodeRunMapper.update(null, new LambdaUpdateWrapper<WorkflowNodeRun>()
                .eq(WorkflowNodeRun::getId, nodeRun.getId())
                .eq(WorkflowNodeRun::getExecutionId, executionId)
                .in(WorkflowNodeRun::getStatus, "QUEUED", "RUNNING")
                .set(WorkflowNodeRun::getStatus, WorkflowNodeStatus.WAITING_APPROVAL.name())
                .set(WorkflowNodeRun::getOutputJson, outputJson)
                .set(WorkflowNodeRun::getFinishedAt, now)
                .set(WorkflowNodeRun::getLockVersion, valueOrZero(nodeRun.getLockVersion()) + 1)
                .set(WorkflowNodeRun::getUpdatedAt, now));
        if (updated == 0) {
            return 0;
        }

        WorkflowApproval approval = createPendingApproval(
                nodeRun,
                "CLARIFICATION",
                null,
                now,
                "pending:" + nodeRun.getId() + ":CLARIFICATION:" + requestId
        );
        WorkflowClarification clarification = new WorkflowClarification();
        clarification.setWorkflowRunId(nodeRun.getWorkflowRunId());
        clarification.setNodeRunId(nodeRun.getId());
        clarification.setRevision(nodeRun.getRevision());
        clarification.setRound(round);
        clarification.setRequestId(requestId);
        clarification.setRequestJson(request.toString());
        clarification.setStatus("PENDING");
        clarification.setApprovalId(approval.getId());
        clarification.setLockVersion(request.path("lock_version").asInt(0));
        clarification.setCreatedAt(now);
        clarification.setUpdatedAt(now);
        clarificationMapper.insert(clarification);
        transition(
                nodeRun,
                nodeRun.getStatus(),
                WorkflowNodeStatus.WAITING_APPROVAL.name(),
                "NODE_INPUT_REQUIRED",
                now
        );
        return 1;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WorkflowClarification respondToClarification(
            long workflowRunId,
            long clarificationId,
            int expectedLockVersion,
            ClarificationResponseDecision response
    ) {
        if (response == null || response.idempotencyKey() == null
                || response.idempotencyKey().isBlank()) {
            throw badRequest("idempotencyKey is required");
        }
        WorkflowClarification clarification = getClarification(workflowRunId, clarificationId);
        if (!"PENDING".equals(clarification.getStatus())) {
            if (response.idempotencyKey().equals(clarification.getIdempotencyKey())) {
                return clarification;
            }
            throw conflict("Workflow clarification is no longer pending");
        }
        if (expectedLockVersion != valueOrZero(clarification.getLockVersion())) {
            throw new OptimisticLockConflictException(
                    "workflowClarification",
                    clarification.getId(),
                    expectedLockVersion,
                    valueOrZero(clarification.getLockVersion()),
                    clarification.getId(),
                    valueOrZero(clarification.getLockVersion())
            );
        }
        WorkflowRun run = requireRun(workflowRunId);
        if (!"RUNNING".equals(run.getStatus())) {
            throw conflict("Workflow clarification cannot resume a terminal workflow run");
        }
        WorkflowApproval approval = approvalMapper.selectById(clarification.getApprovalId());
        if (approval == null || !"CLARIFICATION".equals(approval.getMode())
                || !"PENDING".equals(approval.getStatus())) {
            throw conflict("Workflow clarification approval is no longer pending");
        }
        WorkflowNodeRun waiting = requireNodeRun(clarification.getNodeRunId());
        ObjectNode responseJson = clarificationResponseJson(
                clarification,
                response,
                response.userId()
        );
        LocalDateTime now = LocalDateTime.now();
        int updated = clarificationMapper.update(null, new LambdaUpdateWrapper<WorkflowClarification>()
                .eq(WorkflowClarification::getId, clarification.getId())
                .eq(WorkflowClarification::getStatus, "PENDING")
                .eq(WorkflowClarification::getLockVersion, expectedLockVersion)
                .set(WorkflowClarification::getStatus, "ANSWERED")
                .set(WorkflowClarification::getResponseJson, responseJson.toString())
                .set(WorkflowClarification::getIdempotencyKey, response.idempotencyKey())
                .set(WorkflowClarification::getAnsweredAt, now)
                .set(WorkflowClarification::getLockVersion, expectedLockVersion + 1)
                .set(WorkflowClarification::getUpdatedAt, now));
        if (updated == 0) {
            WorkflowClarification current = getClarification(workflowRunId, clarificationId);
            if (response.idempotencyKey().equals(current.getIdempotencyKey())) {
                return current;
            }
            throw new OptimisticLockConflictException(
                    "workflowClarification",
                    current.getId(),
                    expectedLockVersion,
                    valueOrZero(current.getLockVersion()),
                    current.getId(),
                    valueOrZero(current.getLockVersion())
            );
        }

        updateClarificationApproval(approval, response.idempotencyKey(), response.userId(), now);
        int stale = nodeRunMapper.update(null, new LambdaUpdateWrapper<WorkflowNodeRun>()
                .eq(WorkflowNodeRun::getId, waiting.getId())
                .eq(WorkflowNodeRun::getStatus, WorkflowNodeStatus.WAITING_APPROVAL.name())
                .set(WorkflowNodeRun::getStatus, WorkflowNodeStatus.STALE.name())
                .set(WorkflowNodeRun::getFinishedAt, now)
                .set(WorkflowNodeRun::getLockVersion, valueOrZero(waiting.getLockVersion()) + 1)
                .set(WorkflowNodeRun::getUpdatedAt, now));
        if (stale == 0) {
            throw conflict("Workflow clarification node is no longer waiting");
        }
        insertClarificationRevision(
                waiting,
                clarification.getRequestJson(),
                responseJson,
                now
        );
        transition(
                waiting,
                WorkflowNodeStatus.WAITING_APPROVAL.name(),
                WorkflowNodeStatus.STALE.name(),
                "CLARIFICATION_RESPONDED",
                now
        );
        reconciliationTrigger.reconcile(workflowRunId);
        return clarificationMapper.selectById(clarificationId);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WorkflowApproval decide(
            long approvalId,
            int expectedLockVersion,
            ApprovalDecision command
    ) {
        WorkflowApproval approval = getById(approvalId);
        validateIdempotency(command.idempotencyKey());
        if (!"PENDING".equals(approval.getStatus())) {
            if (command.idempotencyKey().equals(approval.getIdempotencyKey())) {
                return approval;
            }
            if (expectedLockVersion != valueOrZero(approval.getLockVersion())) {
                throw optimisticConflict(approval, expectedLockVersion);
            }
            throw conflict("Workflow approval already decided");
        }
        if (expectedLockVersion != valueOrZero(approval.getLockVersion())) {
            throw optimisticConflict(approval, expectedLockVersion);
        }

        WorkflowRun run = requireRun(approval.getWorkflowRunId());
        WorkflowNodeRun nodeRun = requireNodeRun(approval.getNodeRunId());
        if (!"RUNNING".equals(run.getStatus())) {
            throw conflict("Workflow approval cannot race a cancelled or terminal workflow run");
        }
        CompiledWorkflow graph = dagCompiler.compile(
                snapshotParser.parse(run.getWorkflowSnapshotJson())
        );
        String action = normalizeAction(command.action());
        validateAllowedAction(graph, nodeRun.getNodeId(), action);

        LocalDateTime now = LocalDateTime.now();
        if (!reserveDecision(approval, expectedLockVersion, command, action, now)) {
            return getById(approvalId);
        }
        switch (action) {
            case "APPROVE" -> approveNode(approval, nodeRun, null, now, command.userId());
            case "EDIT_AND_APPROVE" -> {
                Artifact revised = reviseCandidate(run, approval, nodeRun, command.editedContent());
                approval.setRevisedArtifactId(revised.getId());
                approvalMapper.updateById(approval);
                approveNode(approval, nodeRun, revised.getContent(), now, command.userId());
                approvalOutboxService.enqueue(revised);
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

    private boolean reserveDecision(
            WorkflowApproval approval,
            int expectedLockVersion,
            ApprovalDecision command,
            String action,
            LocalDateTime now
    ) {
        int updated = approvalMapper.update(null, new LambdaUpdateWrapper<WorkflowApproval>()
                .eq(WorkflowApproval::getId, approval.getId())
                .eq(WorkflowApproval::getStatus, "PENDING")
                .eq(WorkflowApproval::getLockVersion, expectedLockVersion)
                .set(WorkflowApproval::getStatus, "DECIDED")
                .set(WorkflowApproval::getDecision, action)
                .set(WorkflowApproval::getDecidedByUserId, command.userId())
                .set(WorkflowApproval::getDecisionReason, command.reason())
                .set(WorkflowApproval::getIdempotencyKey, command.idempotencyKey())
                .set(WorkflowApproval::getLockVersion, expectedLockVersion + 1)
                .set(WorkflowApproval::getDecidedAt, now)
                .set(WorkflowApproval::getUpdatedAt, now));
        if (updated == 0) {
            WorkflowApproval current = getById(approval.getId());
            if (command.idempotencyKey().equals(current.getIdempotencyKey())) {
                return false;
            }
            throw optimisticConflict(current, expectedLockVersion);
        }
        approval.setStatus("DECIDED");
        approval.setDecision(action);
        approval.setDecidedByUserId(command.userId());
        approval.setDecisionReason(command.reason());
        approval.setIdempotencyKey(command.idempotencyKey());
        approval.setLockVersion(expectedLockVersion + 1);
        approval.setDecidedAt(now);
        approval.setUpdatedAt(now);
        return true;
    }

    private WorkflowApproval createPendingApproval(
            WorkflowNodeRun nodeRun,
            String mode,
            Long candidateArtifactId,
            LocalDateTime now
    ) {
        return createPendingApproval(
                nodeRun,
                mode,
                candidateArtifactId,
                now,
                "pending:" + nodeRun.getId() + ":" + mode
        );
    }

    private WorkflowApproval createPendingApproval(
            WorkflowNodeRun nodeRun,
            String mode,
            Long candidateArtifactId,
            LocalDateTime now,
            String idempotencyKey
    ) {
        WorkflowApproval approval = new WorkflowApproval();
        approval.setWorkflowRunId(nodeRun.getWorkflowRunId());
        approval.setNodeRunId(nodeRun.getId());
        approval.setMode(mode);
        approval.setStatus("PENDING");
        approval.setCandidateArtifactId(candidateArtifactId);
        approval.setIdempotencyKey(idempotencyKey);
        approval.setLockVersion(0);
        approval.setCreatedAt(now);
        approval.setUpdatedAt(now);
        approvalMapper.insert(approval);
        return approval;
    }

    private ObjectNode clarificationResponseJson(
            WorkflowClarification clarification,
            ClarificationResponseDecision response,
            long userId
    ) {
        ObjectNode value = objectMapper.createObjectNode();
        value.put("request_id", clarification.getRequestId());
        value.put("expected_lock_version", valueOrZero(clarification.getLockVersion()));
        value.put("idempotency_key", response.idempotencyKey());
        value.set("answers", jsonArray(response.answersJson()));
        value.set("accepted_assumption_ids", jsonArray(response.acceptedAssumptionIdsJson()));
        value.set("conflict_resolutions", jsonArray(response.conflictResolutionsJson()));
        // This is an audit field, not a client-controlled request field.
        value.put("actor_user_id", userId);
        return value;
    }

    private ArrayNode jsonArray(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createArrayNode();
        }
        try {
            JsonNode parsed = objectMapper.readTree(json);
            if (!parsed.isArray()) {
                throw badRequest("clarification response fields must be arrays");
            }
            return (ArrayNode) parsed;
        } catch (JsonProcessingException exception) {
            throw badRequest("clarification response fields must be valid JSON arrays");
        }
    }

    private void updateClarificationApproval(
            WorkflowApproval approval,
            String idempotencyKey,
            long userId,
            LocalDateTime now
    ) {
        int updated = approvalMapper.update(null, new LambdaUpdateWrapper<WorkflowApproval>()
                .eq(WorkflowApproval::getId, approval.getId())
                .eq(WorkflowApproval::getStatus, "PENDING")
                .eq(WorkflowApproval::getLockVersion, valueOrZero(approval.getLockVersion()))
                .set(WorkflowApproval::getStatus, "DECIDED")
                .set(WorkflowApproval::getDecision, "RESPOND")
                .set(WorkflowApproval::getDecidedByUserId, userId)
                .set(WorkflowApproval::getDecisionReason, "User answered Product Manager clarification")
                .set(WorkflowApproval::getIdempotencyKey, idempotencyKey)
                .set(WorkflowApproval::getLockVersion, valueOrZero(approval.getLockVersion()) + 1)
                .set(WorkflowApproval::getDecidedAt, now)
                .set(WorkflowApproval::getUpdatedAt, now));
        if (updated == 0) {
            throw conflict("Workflow clarification approval was concurrently decided");
        }
    }

    private void insertClarificationRevision(
            WorkflowNodeRun previous,
            String requestJson,
            ObjectNode responseJson,
            LocalDateTime now
    ) {
        try {
            ObjectNode input = previous.getInputJson() == null || previous.getInputJson().isBlank()
                    ? objectMapper.createObjectNode()
                    : (ObjectNode) objectMapper.readTree(previous.getInputJson());
            ObjectNode context = input.path("clarification_context").isObject()
                    ? (ObjectNode) input.path("clarification_context").deepCopy()
                    : objectMapper.createObjectNode();
            ArrayNode responses = context.path("clarification_responses").isArray()
                    ? (ArrayNode) context.path("clarification_responses").deepCopy()
                    : objectMapper.createArrayNode();
            responses.add(responseJson);
            context.set("clarification_request", objectMapper.readTree(requestJson));
            context.set("clarification_responses", responses);
            int currentRound = objectMapper.readTree(requestJson).path("round").asInt(1);
            context.put("round", currentRound + 1);
            context.put("lock_version", responseJson.path("expected_lock_version").asInt(0) + 1);
            input.put("clarification_protocol", "clarification-v1");
            input.set("clarification_context", context);

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
            revision.setInputJson(input.toString());
            revision.setExecutionBundleHash(previous.getExecutionBundleHash());
            revision.setFencingToken(0L);
            revision.setLockVersion(0);
            revision.setCreatedAt(now);
            revision.setUpdatedAt(now);
            nodeRunMapper.insert(revision);
        } catch (JsonProcessingException | ClassCastException exception) {
            throw badRequest("Workflow clarification input is not a JSON object");
        }
    }

    private JsonNode parseJson(String value, String context) {
        try {
            JsonNode parsed = objectMapper.readTree(value);
            if (parsed == null || !parsed.isObject()) {
                throw badRequest(context + " must be a JSON object");
            }
            return parsed;
        } catch (JsonProcessingException exception) {
            throw badRequest(context + " must be valid JSON");
        }
    }

    private String requiredJsonText(JsonNode object, String field) {
        String value = object.path(field).asText("");
        if (value.isBlank()) {
            throw badRequest("clarification request field is required: " + field);
        }
        return value;
    }

    private void approveNode(
            WorkflowApproval approval,
            WorkflowNodeRun nodeRun,
            String editedOutput,
            LocalDateTime now,
            long userId
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
        if (editedOutput == null
                && "AFTER_NODE".equals(approval.getMode())
                && approval.getCandidateArtifactId() != null) {
            int approved = artifactMapper.update(null, new LambdaUpdateWrapper<Artifact>()
                    .eq(Artifact::getId, approval.getCandidateArtifactId())
                    .eq(Artifact::getStatus, "PENDING_REVIEW")
                    .set(Artifact::getStatus, "APPROVED")
                    .set(Artifact::getApprovedAt, now)
                    .set(Artifact::getUpdatedAt, now));
            if (approved == 0) {
                throw conflict("Approval candidate artifact is no longer pending review");
            }
            Artifact candidate = artifactMapper.selectById(approval.getCandidateArtifactId());
            approvalOutboxService.enqueue(candidate);
        }
        if ("AFTER_NODE".equals(approval.getMode())
                && "product_manager".equals(nodeRun.getNodeId())
                && requirementBaselineService != null) {
            Long artifactId = approval.getRevisedArtifactId() != null
                    ? approval.getRevisedArtifactId()
                    : approval.getCandidateArtifactId();
            Artifact approvedPrd = artifactId == null ? null : artifactMapper.selectById(artifactId);
            requirementBaselineService.freeze(
                    requireRun(nodeRun.getWorkflowRunId()),
                    nodeRun,
                    approvedPrd,
                    userId
            );
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
        revised.setContentHash(ContentHash.sha256(editedContent));
        revised.setSchemaVersion(candidate.getSchemaVersion());
        revised.setPromptKey(candidate.getPromptKey());
        revised.setPromptVersion(candidate.getPromptVersion());
        revised.setModelProvider(candidate.getModelProvider());
        revised.setModelName(candidate.getModelName());
        revised.setSourceCitationsJson(candidate.getSourceCitationsJson());
        revised.setProvenanceJson(candidate.getProvenanceJson());
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
                .set(WorkflowRun::getResponseStatus, "APPROVAL_REJECTED")
                .set(WorkflowRun::getErrorMessage,
                        reason == null || reason.isBlank() ? "Approval rejected" : reason)
                .set(WorkflowRun::getReservedTokens, 0L)
                .set(WorkflowRun::getReservedCost, java.math.BigDecimal.ZERO)
                .set(WorkflowRun::getReservedModelCalls, 0)
                .set(WorkflowRun::getCompletedAt, now)
                .set(WorkflowRun::getUpdatedAt, now));
        cancelRemainingNodes(run.getId(), "APPROVAL_REJECTED", now);
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
                .set(WorkflowRun::getResponseStatus, "CANCELLED")
                .set(WorkflowRun::getErrorMessage, "Cancelled by approval decision")
                .set(WorkflowRun::getReservedTokens, 0L)
                .set(WorkflowRun::getReservedCost, java.math.BigDecimal.ZERO)
                .set(WorkflowRun::getReservedModelCalls, 0)
                .set(WorkflowRun::getCompletedAt, now)
                .set(WorkflowRun::getUpdatedAt, now));
        cancelRemainingNodes(run.getId(), "APPROVAL_CANCELLED", now);
        transition(nodeRun, "WAITING_APPROVAL", "CANCELLED", "APPROVAL_CANCELLED", now);
    }

    private void cancelRemainingNodes(Long runId, String errorCode, LocalDateTime now) {
        nodeRunMapper.update(null, new LambdaUpdateWrapper<WorkflowNodeRun>()
                .eq(WorkflowNodeRun::getWorkflowRunId, runId)
                .in(WorkflowNodeRun::getStatus,
                        "PENDING", "READY", "QUEUED", "RUNNING", "RETRY_WAIT",
                        "FALLBACK_READY", "WAITING_APPROVAL", "STALE", "ORPHANED")
                .set(WorkflowNodeRun::getStatus, WorkflowNodeStatus.CANCELLED.name())
                .set(WorkflowNodeRun::getErrorCode, errorCode)
                .set(WorkflowNodeRun::getErrorMessage,
                        "Parent workflow run was terminated by approval")
                .set(WorkflowNodeRun::getFinishedAt, now)
                .set(WorkflowNodeRun::getUpdatedAt, now));
        nodeRunMapper.update(null, new LambdaUpdateWrapper<WorkflowNodeRun>()
                .eq(WorkflowNodeRun::getWorkflowRunId, runId)
                .eq(WorkflowNodeRun::getBudgetStatus, "RESERVED")
                .set(WorkflowNodeRun::getBudgetStatus, "RELEASED")
                .set(WorkflowNodeRun::getBudgetSettledAt, now)
                .set(WorkflowNodeRun::getUpdatedAt, now));
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

    private OptimisticLockConflictException optimisticConflict(
            WorkflowApproval current,
            int expectedLockVersion
    ) {
        int currentLockVersion = valueOrZero(current.getLockVersion());
        return new OptimisticLockConflictException(
                "workflowApproval",
                current.getId(),
                expectedLockVersion,
                currentLockVersion,
                current.getId(),
                currentLockVersion
        );
    }

    private ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }
}
