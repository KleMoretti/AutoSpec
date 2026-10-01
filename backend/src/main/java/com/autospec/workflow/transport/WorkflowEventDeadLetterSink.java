package com.autospec.workflow.transport;

public interface WorkflowEventDeadLetterSink {
    void quarantine(WorkflowStreamEventMessage message, InvalidWorkflowEventException failure);

    default void quarantinePoison(WorkflowStreamEventMessage message, RuntimeException failure) {
        quarantine(message, new InvalidWorkflowEventException(
                "message exceeded the persistent delivery-attempt limit", failure
        ));
    }

    static WorkflowEventDeadLetterSink none() {
        return (message, failure) -> {
        };
    }
}
