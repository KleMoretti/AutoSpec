package com.autospec.workflow.runtime;

import com.autospec.entity.WorkflowRun;
import com.autospec.mapper.WorkflowRunMapper;
import com.autospec.workflow.transport.WorkflowRunReconciliationTrigger;
import org.springframework.stereotype.Service;

@Service
public class WorkflowRunReconciliationService implements WorkflowRunReconciliationTrigger {
    private final WorkflowRunMapper workflowRunMapper;
    private final WorkflowSnapshotParser snapshotParser;
    private final DagCompiler dagCompiler;
    private final WorkflowReconciler workflowReconciler;

    public WorkflowRunReconciliationService(
            WorkflowRunMapper workflowRunMapper,
            WorkflowSnapshotParser snapshotParser,
            DagCompiler dagCompiler,
            WorkflowReconciler workflowReconciler
    ) {
        this.workflowRunMapper = workflowRunMapper;
        this.snapshotParser = snapshotParser;
        this.dagCompiler = dagCompiler;
        this.workflowReconciler = workflowReconciler;
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
    }
}
