package com.autospec.workflow.transport;

public record WorkflowStreamEventMessage(String messageId, String payloadJson) {
}
