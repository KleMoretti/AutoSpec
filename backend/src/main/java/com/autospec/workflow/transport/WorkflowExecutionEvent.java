package com.autospec.workflow.transport;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

public record WorkflowExecutionEvent(
        @JsonProperty("event_id") String eventId,
        @JsonProperty("source_event_id") String sourceEventId,
        @JsonProperty("event_type") String eventType,
        @JsonProperty("workflow_run_id") Long workflowRunId,
        @JsonProperty("node_run_id") Long nodeRunId,
        @JsonProperty("node_id") String nodeId,
        Integer revision,
        Integer attempt,
        @JsonProperty("execution_id") String executionId,
        @JsonProperty("duration_ms") Integer durationMs,
        @JsonProperty("output_payload") JsonNode outputPayload,
        @JsonProperty("error_code") String errorCode,
        @JsonProperty("error_message") String errorMessage
) {
    public boolean isTerminal() {
        return "NODE_SUCCEEDED".equals(eventType) || "NODE_FAILED".equals(eventType);
    }
}
