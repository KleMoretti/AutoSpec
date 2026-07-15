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
        CompiledWorkflow graph = dagCompiler.compile(
                snapshotParser.parse(run.getWorkflowSnapshotJson())
        );
        workflowReconciler.reconcile(workflowRunId, graph);
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
        if (!complete && !failed) {
            return;
        }
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        workflowRunMapper.update(null, new LambdaUpdateWrapper<WorkflowRun>()
                .eq(WorkflowRun::getId, run.getId())
                .eq(WorkflowRun::getStatus, "RUNNING")
                .set(WorkflowRun::getStatus, complete ? "COMPLETED" : "FAILED")
                .set(WorkflowRun::getResponseStatus, complete ? "COMPLETED" : "FAILED")
                .set(WorkflowRun::getResponsePercent, complete ? 100 : run.getResponsePercent())
                .set(WorkflowRun::getCompletedAt, now)
                .set(WorkflowRun::getUpdatedAt, now));
    }
}
