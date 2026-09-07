package com.autospec.workflow.transport;

public interface WorkflowAgentStepRecorder {
    void record(WorkflowExecutionEvent event);

    static WorkflowAgentStepRecorder none() {
        return event -> { };
    }
}
