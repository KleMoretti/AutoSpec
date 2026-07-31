package com.autospec.workflow.runtime;

import com.autospec.entity.WorkflowNodeRun;
import com.autospec.observability.WorkflowTraceContextFactory;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

public record QueuedNodeCommand(
        @JsonProperty("event_id") String eventId,
        @JsonProperty("workflow_run_id") long workflowRunId,
        @JsonProperty("node_run_id") long nodeRunId,
        @JsonProperty("node_id") String nodeId,
        int revision,
        int attempt,
        @JsonProperty("execution_id") String executionId,
        @JsonProperty("handler_key") String handlerKey,
        @JsonProperty("handler_version") String handlerVersion,
        @JsonProperty("timeout_ms") int timeoutMs,
        @JsonProperty("input_payload") JsonNode inputPayload,
        @JsonProperty("correlation_id") String correlationId,
        @JsonProperty("traceparent") String traceparent,
        @JsonProperty("tracestate") String tracestate
) {
    public QueuedNodeCommand {
        inputPayload = inputPayload == null ? JsonNodeFactory.instance.objectNode() : inputPayload;
        if (!inputPayload.isObject()) {
            throw new IllegalArgumentException("inputPayload must be a JSON object");
        }
        if (timeoutMs < 1) {
            throw new IllegalArgumentException("timeoutMs must be positive");
        }
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

    public QueuedNodeCommand(
            String eventId,
            long workflowRunId,
            long nodeRunId,
            String nodeId,
            int revision,
            int attempt,
            String executionId,
            String handlerKey,
            String handlerVersion,
            int timeoutMs,
            JsonNode inputPayload
    ) {
        this(
                eventId,
                workflowRunId,
                nodeRunId,
                nodeId,
                revision,
                attempt,
                executionId,
                handlerKey,
                handlerVersion,
                timeoutMs,
                inputPayload,
                null,
                null,
                null
        );
    }

    public static QueuedNodeCommand fromNodeRun(
            String eventId,
            WorkflowNodeRun nodeRun,
            String executionId,
            ObjectMapper objectMapper
    ) {
        return fromNodeRun(eventId, nodeRun, executionId, null, objectMapper);
    }

    public static QueuedNodeCommand fromNodeRun(
            String eventId,
            WorkflowNodeRun nodeRun,
            String executionId,
            WorkflowTraceContextFactory.Context traceContext,
            ObjectMapper objectMapper
    ) {
        try {
            String inputJson = nodeRun.getInputJson();
            JsonNode input = inputJson == null || inputJson.isBlank()
                    ? JsonNodeFactory.instance.objectNode()
                    : objectMapper.readTree(inputJson);
            return new QueuedNodeCommand(
                    eventId,
                    nodeRun.getWorkflowRunId(),
                    nodeRun.getId(),
                    nodeRun.getNodeId(),
                    nodeRun.getRevision(),
                    nodeRun.getAttempt(),
                    executionId,
                    nodeRun.getHandlerKey(),
                    nodeRun.getHandlerVersion(),
                    nodeRun.getTimeoutMs() == null ? 30000 : nodeRun.getTimeoutMs(),
                    input,
                    traceContext == null ? null : traceContext.correlationId(),
                    traceContext == null ? null : traceContext.traceparent(),
                    traceContext == null ? null : traceContext.tracestate()
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid workflow node input JSON", exception);
        }
    }
}
