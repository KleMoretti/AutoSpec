package com.autospec.workflow.runtime;

import com.autospec.entity.WorkflowRun;
import com.autospec.entity.WorkflowNodeRun;
import com.autospec.mapper.WorkflowNodeRunMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.autospec.mapper.WorkflowRunMapper;
import com.autospec.workflow.transport.WorkflowRunReconciliationTrigger;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class WorkflowRunReconciliationService implements WorkflowRunReconciliationTrigger {
    private final WorkflowRunMapper workflowRunMapper;
    private final WorkflowSnapshotParser snapshotParser;
    private final DagCompiler dagCompiler;
    private final WorkflowReconciler workflowReconciler;
    private final WorkflowNodeRunMapper nodeRunMapper;

    @Autowired
    public WorkflowRunReconciliationService(
            WorkflowRunMapper workflowRunMapper,
            WorkflowSnapshotParser snapshotParser,
            DagCompiler dagCompiler,
            WorkflowReconciler workflowReconciler,
            WorkflowNodeRunMapper nodeRunMapper
    ) {
        this.workflowRunMapper = workflowRunMapper;
        this.snapshotParser = snapshotParser;
        this.dagCompiler = dagCompiler;
        this.workflowReconciler = workflowReconciler;
        this.nodeRunMapper = nodeRunMapper;
    }

    public WorkflowRunReconciliationService(
            WorkflowRunMapper workflowRunMapper,
            WorkflowSnapshotParser snapshotParser,
            DagCompiler dagCompiler,
            WorkflowReconciler workflowReconciler
    ) {
        this(workflowRunMapper, snapshotParser, dagCompiler, workflowReconciler, null);
    }

    @Override
    public void reconcile(Long workflowRunId) {
        WorkflowRun run = workflowRunMapper.selectById(workflowRunId);
        if (run == null) {
            throw new IllegalArgumentException("workflow run not found: " + workflowRunId);
        }
        if (!"RUNNING".equals(run.getStatus())) {
            return;
        }
        CompiledWorkflow graph = dagCompiler.compile(
                snapshotParser.parse(run.getWorkflowSnapshotJson())
        );
        workflowReconciler.reconcile(workflowRunId, run.getCorrelationId(), graph);
        completeIfTerminal(run, graph);
    }

    private void completeIfTerminal(WorkflowRun run, CompiledWorkflow graph) {
        if (nodeRunMapper == null) {
            return;
        }
        java.util.Map<String, WorkflowNodeRun> latest = new java.util.LinkedHashMap<>();
        for (WorkflowNodeRun node : nodeRunMapper.selectList(
                new LambdaQueryWrapper<WorkflowNodeRun>()
                        .eq(WorkflowNodeRun::getWorkflowRunId, run.getId())
                        .orderByDesc(WorkflowNodeRun::getRevision)
                        .orderByDesc(WorkflowNodeRun::getAttempt))) {
            latest.putIfAbsent(node.getNodeId(), node);
        }
        boolean complete = graph.nodes().keySet().stream().allMatch(nodeId -> {
            WorkflowNodeRun node = latest.get(nodeId);
            return node != null && ("SUCCEEDED".equals(node.getStatus()) || "SKIPPED".equals(node.getStatus()));
        });
        boolean failed = latest.values().stream().anyMatch(node -> "FAILED".equals(node.getStatus()));
        boolean active = latest.values().stream().anyMatch(node -> java.util.Set.of(
                "QUEUED", "RUNNING", "RETRY_WAIT", "FALLBACK_READY", "ORPHANED"
        ).contains(node.getStatus()));
        if (failed && active) {
            return;
        }
        if (!complete && !failed) {
            return;
        }
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        String responseStatus = complete
                ? "COMPLETED"
                : latest.values().stream().anyMatch(node ->
                "BUDGET_PREAUTH_FAILED".equals(node.getErrorCode()))
                ? "BUDGET_EXCEEDED"
                : "FAILED";
        workflowRunMapper.update(null, new LambdaUpdateWrapper<WorkflowRun>()
                .eq(WorkflowRun::getId, run.getId())
                .eq(WorkflowRun::getStatus, "RUNNING")
                .set(WorkflowRun::getStatus, complete ? "COMPLETED" : "FAILED")
                .set(WorkflowRun::getResponseStatus, responseStatus)
                .set(!complete, WorkflowRun::getReservedTokens, 0L)
                .set(!complete, WorkflowRun::getReservedCost, java.math.BigDecimal.ZERO)
                .set(!complete, WorkflowRun::getReservedModelCalls, 0)
                .set(complete, WorkflowRun::getResponsePercent, 100)
                .set(WorkflowRun::getCompletedAt, now)
                .set(WorkflowRun::getUpdatedAt, now));
        if (failed) {
            nodeRunMapper.update(null, new LambdaUpdateWrapper<WorkflowNodeRun>()
                    .eq(WorkflowNodeRun::getWorkflowRunId, run.getId())
                    .in(WorkflowNodeRun::getStatus,
                            "PENDING", "READY", "WAITING_APPROVAL", "STALE")
                    .set(WorkflowNodeRun::getStatus, "CANCELLED")
                    .set(WorkflowNodeRun::getErrorCode, responseStatus)
                    .set(WorkflowNodeRun::getErrorMessage,
                            "Parent workflow run reached a terminal failure")
                    .set(WorkflowNodeRun::getFinishedAt, now)
                    .set(WorkflowNodeRun::getUpdatedAt, now));
        }
    }
}
