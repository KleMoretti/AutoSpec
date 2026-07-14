package com.autospec.service.impl;

import com.autospec.entity.WorkflowNodeRun;
import com.autospec.entity.WorkflowRun;
import com.autospec.entity.WorkflowTransition;
import com.autospec.entity.WorkflowVersion;
import com.autospec.mapper.WorkflowNodeRunMapper;
import com.autospec.mapper.WorkflowRunMapper;
import com.autospec.mapper.WorkflowTransitionMapper;
import com.autospec.mapper.WorkflowVersionMapper;
import com.autospec.service.WorkflowReplayService;
import com.autospec.workflow.runtime.CompiledWorkflow;
import com.autospec.workflow.runtime.DagCompiler;
import com.autospec.workflow.runtime.WorkflowHandlerCatalog;
import com.autospec.workflow.runtime.WorkflowRunReconciliationService;
import com.autospec.workflow.runtime.WorkflowSnapshotParser;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class WorkflowReplayServiceImpl implements WorkflowReplayService {
    private static final String ORIGINAL_SNAPSHOT = "ORIGINAL_SNAPSHOT";
    private static final String SELECTED_VERSION = "SELECTED_VERSION";

    private final WorkflowRunMapper runMapper;
    private final WorkflowNodeRunMapper nodeRunMapper;
    private final WorkflowVersionMapper versionMapper;
    private final WorkflowTransitionMapper transitionMapper;
    private final WorkflowSnapshotParser snapshotParser;
    private final DagCompiler dagCompiler;
    private final WorkflowHandlerCatalog handlerCatalog;
    private final WorkflowRunReconciliationService reconciliationService;
    private final ObjectMapper objectMapper;

    public WorkflowReplayServiceImpl(
            WorkflowRunMapper runMapper,
            WorkflowNodeRunMapper nodeRunMapper,
            WorkflowVersionMapper versionMapper,
            WorkflowTransitionMapper transitionMapper,
            WorkflowSnapshotParser snapshotParser,
            DagCompiler dagCompiler,
            WorkflowHandlerCatalog handlerCatalog,
            WorkflowRunReconciliationService reconciliationService,
            ObjectMapper objectMapper
    ) {
        this.runMapper = runMapper;
        this.nodeRunMapper = nodeRunMapper;
        this.versionMapper = versionMapper;
        this.transitionMapper = transitionMapper;
        this.snapshotParser = snapshotParser;
        this.dagCompiler = dagCompiler;
        this.handlerCatalog = handlerCatalog;
        this.reconciliationService = reconciliationService;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public WorkflowRun replay(long sourceRunId, ReplayCommand command) {
        WorkflowRun source = requireRun(sourceRunId);
        String mode = normalizeMode(command.mode());
        String idempotencyKey = command.idempotencyKey() == null || command.idempotencyKey().isBlank()
                ? UUID.randomUUID().toString()
                : command.idempotencyKey().trim();
        WorkflowRun duplicate = findDuplicate(source.getProjectId(), idempotencyKey);
        if (duplicate != null) {
            if (!Long.valueOf(sourceRunId).equals(duplicate.getReplayOfRunId())) {
                throw conflict("Replay idempotency key already belongs to another source run");
            }
            return duplicate;
        }

        ReplaySnapshot replaySnapshot = resolveSnapshot(source, mode, command.selectedWorkflowVersionId());
        CompiledWorkflow graph = dagCompiler.compile(snapshotParser.parse(replaySnapshot.snapshotJson()));
        Map<String, WorkflowNodeRun> originalInputs = originalInputs(sourceRunId);
        validateReplayInputs(graph, originalInputs);

        LocalDateTime now = LocalDateTime.now();
        WorkflowRun replay = new WorkflowRun();
        replay.setProjectId(source.getProjectId());
        replay.setOperation("REPLAY_V5");
        replay.setIdempotencyKey(idempotencyKey);
        replay.setCorrelationId(UUID.randomUUID().toString());
        replay.setWorkflowVersionId(replaySnapshot.workflowVersionId());
        replay.setWorkflowSnapshotJson(replaySnapshot.snapshotJson());
        replay.setReplayOfRunId(sourceRunId);
        replay.setReviewRound(0);
        replay.setMaxReviewRounds(source.getMaxReviewRounds() == null ? 0 : source.getMaxReviewRounds());
        replay.setLockVersion(0);
        replay.setLastHeartbeatAt(now);
        replay.setStatus("RUNNING");
        replay.setResponseStatus("GENERATING");
        replay.setResponsePercent(0);
        replay.setStartedAt(now);
        replay.setCreatedAt(now);
        replay.setUpdatedAt(now);
        runMapper.insert(replay);

        for (String nodeId : graph.nodes().keySet().stream().sorted().toList()) {
            insertReplayNode(replay.getId(), nodeId, originalInputs.get(nodeId), now);
        }
        insertReplayTransition(replay, sourceRunId, mode, now);
        reconciliationService.reconcile(replay.getId());
        return runMapper.selectById(replay.getId());
    }

    private ReplaySnapshot resolveSnapshot(WorkflowRun source, String mode, Long selectedVersionId) {
        if (ORIGINAL_SNAPSHOT.equals(mode)) {
            if (source.getWorkflowSnapshotJson() == null || source.getWorkflowSnapshotJson().isBlank()) {
                throw conflict("Source run has no frozen workflow snapshot");
            }
            return new ReplaySnapshot(source.getWorkflowVersionId(), source.getWorkflowSnapshotJson());
        }
        if (selectedVersionId == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "selectedWorkflowVersionId is required for SELECTED_VERSION"
            );
        }
        WorkflowVersion version = versionMapper.selectById(selectedVersionId);
        if (version == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow version not found");
        }
        if (!"PUBLISHED".equals(version.getStatus())) {
            throw conflict("Selected workflow version must be PUBLISHED");
        }
        return new ReplaySnapshot(version.getId(), version.getSpecJson());
    }

    private void validateReplayInputs(
            CompiledWorkflow graph,
            Map<String, WorkflowNodeRun> originalInputs
    ) {
        for (String nodeId : graph.nodes().keySet()) {
            WorkflowNodeRun template = originalInputs.get(nodeId);
            if (template == null) {
                throw conflict("Original input unavailable for selected node: " + nodeId);
            }
            if (!handlerCatalog.isAvailable(template.getHandlerKey(), template.getHandlerVersion())) {
                throw conflict(
                        "RUNTIME_VERSION_UNAVAILABLE: "
                                + template.getHandlerKey() + ":" + template.getHandlerVersion()
                );
            }
        }
    }

    private Map<String, WorkflowNodeRun> originalInputs(long sourceRunId) {
        List<WorkflowNodeRun> values = nodeRunMapper.selectList(
                new LambdaQueryWrapper<WorkflowNodeRun>()
                        .eq(WorkflowNodeRun::getWorkflowRunId, sourceRunId)
                        .orderByAsc(WorkflowNodeRun::getRevision)
                        .orderByAsc(WorkflowNodeRun::getAttempt)
                        .orderByAsc(WorkflowNodeRun::getId)
        );
        Map<String, WorkflowNodeRun> result = new LinkedHashMap<>();
        for (WorkflowNodeRun value : values) {
            result.putIfAbsent(value.getNodeId(), value);
        }
        return result;
    }

    private void insertReplayNode(
            long replayRunId,
            String nodeId,
            WorkflowNodeRun source,
            LocalDateTime now
    ) {
        WorkflowNodeRun node = new WorkflowNodeRun();
        node.setWorkflowRunId(replayRunId);
        node.setNodeId(nodeId);
        node.setRevision(1);
        node.setAttempt(1);
        node.setExecutionId(replayRunId + ":" + nodeId + ":1:1");
        node.setStatus("PENDING");
        node.setHandlerKey(source.getHandlerKey());
        node.setHandlerVersion(source.getHandlerVersion());
        node.setTimeoutMs(source.getTimeoutMs());
        node.setInputJson(source.getInputJson());
        node.setLockVersion(0);
        node.setCreatedAt(now);
        node.setUpdatedAt(now);
        nodeRunMapper.insert(node);
    }

    private void insertReplayTransition(
            WorkflowRun replay,
            long sourceRunId,
            String mode,
            LocalDateTime now
    ) {
        WorkflowTransition transition = new WorkflowTransition();
        transition.setWorkflowRunId(replay.getId());
        transition.setFromStatus(null);
        transition.setToStatus("RUNNING");
        transition.setEventType("WORKFLOW_REPLAY_CREATED");
        transition.setEventId(UUID.randomUUID().toString());
        transition.setMetadataJson(metadata(sourceRunId, mode, replay.getWorkflowVersionId()));
        transition.setCreatedAt(now);
        transitionMapper.insert(transition);
    }

    private String metadata(long sourceRunId, String mode, Long workflowVersionId) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "source_run_id", sourceRunId,
                    "replay_mode", mode,
                    "workflow_version_id", workflowVersionId == null ? "" : workflowVersionId
            ));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize replay provenance", exception);
        }
    }

    private WorkflowRun findDuplicate(long projectId, String idempotencyKey) {
        return runMapper.selectOne(new LambdaQueryWrapper<WorkflowRun>()
                .eq(WorkflowRun::getProjectId, projectId)
                .eq(WorkflowRun::getOperation, "REPLAY_V5")
                .eq(WorkflowRun::getIdempotencyKey, idempotencyKey));
    }

    private WorkflowRun requireRun(long runId) {
        WorkflowRun run = runMapper.selectById(runId);
        if (run == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow run not found");
        }
        return run;
    }

    private String normalizeMode(String mode) {
        String normalized = mode == null ? "" : mode.trim().toUpperCase();
        if (!ORIGINAL_SNAPSHOT.equals(normalized) && !SELECTED_VERSION.equals(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported replay mode: " + mode);
        }
        return normalized;
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private record ReplaySnapshot(Long workflowVersionId, String snapshotJson) {
    }
}
