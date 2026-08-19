package com.autospec.workflow.transport;

public interface WorkflowEventDeadLetterSink {
    void quarantine(WorkflowStreamEventMessage message, InvalidWorkflowEventException failure);

    static WorkflowEventDeadLetterSink none() {
        return (message, failure) -> {
        };
    }
}
