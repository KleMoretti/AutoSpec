package com.autospec.workflow.transport;

import com.autospec.observability.WorkflowTraceContextFactory;
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
        @JsonProperty("error_message") String errorMessage,
        @JsonProperty("correlation_id") String correlationId,
        @JsonProperty("traceparent") String traceparent,
        @JsonProperty("tracestate") String tracestate
) {
    public WorkflowExecutionEvent {
        if (correlationId != null && (correlationId.isBlank() || correlationId.length() > 128)) {
            throw new IllegalArgumentException(
                    "correlationId must contain between 1 and 128 characters when provided"
            );
        }
        if (traceparent != null && !WorkflowTraceContextFactory.isValidTraceparent(traceparent)) {
            throw new IllegalArgumentException("traceparent must be a valid W3C trace parent");
        }
        if (tracestate != null && tracestate.length() > 512) {
            throw new IllegalArgumentException("tracestate must not exceed 512 characters");
        }
    }

    public boolean isTerminal() {
        return "NODE_SUCCEEDED".equals(eventType) || "NODE_FAILED".equals(eventType);
    }
}
