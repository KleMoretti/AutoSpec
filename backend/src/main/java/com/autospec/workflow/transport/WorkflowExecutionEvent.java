package com.autospec.workflow.transport;

import com.autospec.observability.WorkflowTraceContextFactory;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;

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
        @JsonProperty("provider_key") String providerKey,
        @JsonProperty("model_name") String modelName,
        @JsonProperty("prompt_key") String promptKey,
        @JsonProperty("route_key") String routeKey,
        @JsonProperty("route_reason") String routeReason,
        @JsonProperty("fallback_used") Boolean fallbackUsed,
        @JsonProperty("context_manifest") JsonNode contextManifest,
        @JsonProperty("model_call_count") Integer modelCallCount,
        @JsonProperty("input_tokens") Integer inputTokens,
        @JsonProperty("output_tokens") Integer outputTokens,
        @JsonProperty("cache_tokens") Integer cacheTokens,
        @JsonProperty("estimated_cost") BigDecimal estimatedCost,
        @JsonProperty("correlation_id") String correlationId,
        @JsonProperty("traceparent") String traceparent,
        @JsonProperty("tracestate") String tracestate,
        @JsonProperty("protocol_version") Integer protocolVersion,
        @JsonProperty("contract_hash") String contractHash,
        @JsonProperty("input_schema") String inputSchema,
        @JsonProperty("input_schema_hash") String inputSchemaHash,
        @JsonProperty("output_schema") String outputSchema,
        @JsonProperty("output_schema_hash") String outputSchemaHash,
        @JsonProperty("prompt_version") String promptVersion,
        @JsonProperty("prompt_checksum") String promptChecksum,
        @JsonProperty("fencing_token") Long fencingToken,
        @JsonProperty("worker_id") String workerId
) {
    public WorkflowExecutionEvent {
        if (eventId == null || eventId.isBlank()
                || eventType == null || eventType.isBlank()
                || workflowRunId == null || workflowRunId < 1
                || nodeRunId == null || nodeRunId < 1
                || nodeId == null || nodeId.isBlank()
                || revision == null || revision < 1
                || attempt == null || attempt < 1
                || executionId == null || executionId.isBlank()) {
            throw new IllegalArgumentException("workflow event identity fields are required");
        }
        if (durationMs != null && durationMs < 0) {
            throw new IllegalArgumentException("duration_ms must not be negative");
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
        if (safe(modelCallCount) < 0 || safe(inputTokens) < 0
                || safe(outputTokens) < 0 || safe(cacheTokens) < 0
                || estimatedCost != null && estimatedCost.signum() < 0) {
            throw new IllegalArgumentException("model usage values must not be negative");
        }
        if (protocolVersion != null && (protocolVersion < 0 || protocolVersion > 1)) {
            throw new IllegalArgumentException("unsupported protocol_version: " + protocolVersion);
        }
        if (fencingToken != null && fencingToken < 0) {
            throw new IllegalArgumentException("fencing_token must not be negative");
        }
    }

    public WorkflowExecutionEvent(
            String eventId,
            String sourceEventId,
            String eventType,
            Long workflowRunId,
            Long nodeRunId,
            String nodeId,
            Integer revision,
            Integer attempt,
            String executionId,
            Integer durationMs,
            JsonNode outputPayload,
            String errorCode,
            String errorMessage,
            String providerKey,
            String modelName,
            String promptKey,
            String routeKey,
            String routeReason,
            Boolean fallbackUsed,
            JsonNode contextManifest,
            Integer modelCallCount,
            Integer inputTokens,
            Integer outputTokens,
            Integer cacheTokens,
            BigDecimal estimatedCost,
            String correlationId,
            String traceparent,
            String tracestate
    ) {
        this(
                eventId,
                sourceEventId,
                eventType,
                workflowRunId,
                nodeRunId,
                nodeId,
                revision,
                attempt,
                executionId,
                durationMs,
                outputPayload,
                errorCode,
                errorMessage,
                providerKey,
                modelName,
                promptKey,
                routeKey,
                routeReason,
                fallbackUsed,
                contextManifest,
                modelCallCount,
                inputTokens,
                outputTokens,
                cacheTokens,
                estimatedCost,
                correlationId,
                traceparent,
                tracestate,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    public boolean isTerminal() {
        return "NODE_SUCCEEDED".equals(eventType) || "NODE_FAILED".equals(eventType);
    }

    private static int safe(Integer value) {
        return value == null ? 0 : value;
    }
}
