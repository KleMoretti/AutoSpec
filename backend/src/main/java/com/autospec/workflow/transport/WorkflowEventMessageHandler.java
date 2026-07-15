package com.autospec.workflow.transport;

@FunctionalInterface
public interface WorkflowEventMessageHandler {
    void handle(String payloadJson);
}
