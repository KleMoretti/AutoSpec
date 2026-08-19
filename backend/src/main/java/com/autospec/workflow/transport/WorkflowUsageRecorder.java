package com.autospec.workflow.transport;

public interface WorkflowUsageRecorder {
    UsageDecision record(WorkflowExecutionEvent event);

    static WorkflowUsageRecorder none() {
        return event -> UsageDecision.ALLOWED;
    }

    enum UsageDecision {
        ALLOWED,
        BUDGET_EXCEEDED
    }
}
