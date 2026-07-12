package com.autospec.workflow.transport;

@FunctionalInterface
public interface WorkflowRunReconciliationTrigger {
    void reconcile(Long workflowRunId);
}
