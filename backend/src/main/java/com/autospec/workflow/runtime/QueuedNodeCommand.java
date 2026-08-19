package com.autospec.workflow.runtime;

import com.autospec.entity.WorkflowNodeRun;
import com.autospec.observability.WorkflowTraceContextFactory;
import com.autospec.workflow.spec.WorkflowNodeDocument;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.util.regex.Pattern;

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
        @JsonProperty("tracestate") String tracestate,
        @JsonProperty("protocol_version") int protocolVersion,
        @JsonProperty("contract_hash") String contractHash,
        @JsonProperty("input_schema") String inputSchema,
        @JsonProperty("input_schema_hash") String inputSchemaHash,
        @JsonProperty("output_schema") String outputSchema,
        @JsonProperty("output_schema_hash") String outputSchemaHash,
        @JsonProperty("prompt_key") String promptKey,
        @JsonProperty("prompt_version") String promptVersion,
        @JsonProperty("prompt_checksum") String promptChecksum,
        @JsonProperty("model_policy") JsonNode modelPolicy,
        @JsonProperty("retry_policy") JsonNode retryPolicy,
        @JsonProperty("fallback") JsonNode fallback,
        @JsonProperty("deadline_epoch_ms") long deadlineEpochMs
) {
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    public QueuedNodeCommand {
        inputPayload = inputPayload == null ? JsonNodeFactory.instance.objectNode() : inputPayload;
        modelPolicy = objectOrEmpty(modelPolicy, "modelPolicy");
        retryPolicy = objectOrEmpty(retryPolicy, "retryPolicy");
        fallback = objectOrEmpty(fallback, "fallback");
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
        if (contractHash != null) {
            if (protocolVersion != 1) {
                throw new IllegalArgumentException("contractHash requires protocolVersion 1");
            }
            requireHash(contractHash, "contractHash");
            requireHash(inputSchemaHash, "inputSchemaHash");
            requireHash(outputSchemaHash, "outputSchemaHash");
            requireHash(promptChecksum, "promptChecksum");
            requireText(inputSchema, "inputSchema");
            requireText(outputSchema, "outputSchema");
            requireText(promptKey, "promptKey");
            requireText(promptVersion, "promptVersion");
            if (deadlineEpochMs < 1) {
                throw new IllegalArgumentException("deadlineEpochMs must be positive");
            }
        } else if (protocolVersion < 0 || protocolVersion > 1) {
            throw new IllegalArgumentException("unsupported protocolVersion: " + protocolVersion);
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
                null,
                0,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0
        );
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
            JsonNode inputPayload,
            String correlationId,
            String traceparent,
            String tracestate
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
                correlationId,
                traceparent,
                tracestate,
                0,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0
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
                    traceContext == null ? null : traceContext.tracestate(),
                    0,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    0
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid workflow node input JSON", exception);
        }
    }

    public static QueuedNodeCommand fromNodeRun(
            String eventId,
            WorkflowNodeRun nodeRun,
            String executionId,
            WorkflowTraceContextFactory.Context traceContext,
            int protocolVersion,
            WorkflowNodeDocument nodeSpec,
            ObjectMapper objectMapper
    ) {
        try {
            String inputJson = nodeRun.getInputJson();
            JsonNode input = inputJson == null || inputJson.isBlank()
                    ? JsonNodeFactory.instance.objectNode()
                    : objectMapper.readTree(inputJson);
            WorkflowExecutableContract contract = WorkflowExecutableContract.from(
                    protocolVersion,
                    nodeSpec,
                    nodeRun.getHandlerKey(),
                    nodeRun.getHandlerVersion(),
                    objectMapper
            );
            long deadline = contract == null
                    ? 0
                    : System.currentTimeMillis()
                    + (nodeRun.getTimeoutMs() == null ? 30000 : nodeRun.getTimeoutMs());
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
                    traceContext == null ? null : traceContext.tracestate(),
                    contract == null ? 0 : contract.protocolVersion(),
                    contract == null ? null : contract.contractHash(),
                    contract == null ? null : contract.inputSchema(),
                    contract == null ? null : contract.inputSchemaHash(),
                    contract == null ? null : contract.outputSchema(),
                    contract == null ? null : contract.outputSchemaHash(),
                    contract == null ? null : contract.promptKey(),
                    contract == null ? null : contract.promptVersion(),
                    contract == null ? null : contract.promptChecksum(),
                    contract == null ? null : contract.modelPolicy(),
                    contract == null ? null : contract.retryPolicy(),
                    contract == null ? null : contract.fallback(),
                    deadline
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid workflow node input JSON", exception);
        }
    }

    private static JsonNode objectOrEmpty(JsonNode value, String field) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return JsonNodeFactory.instance.objectNode();
        }
        if (!value.isObject()) {
            throw new IllegalArgumentException(field + " must be a JSON object");
        }
        return value.deepCopy();
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    private static void requireHash(String value, String field) {
        if (value == null || !SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be a lowercase SHA-256 digest");
        }
    }
}
