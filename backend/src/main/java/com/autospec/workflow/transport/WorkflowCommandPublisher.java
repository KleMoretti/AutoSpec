package com.autospec.workflow.transport;

public interface WorkflowCommandPublisher {
    void publish(String stream, String eventId, String payloadJson);
}
