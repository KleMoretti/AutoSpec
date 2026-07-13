package com.autospec.workflow.runtime;

import com.autospec.entity.WorkflowNodeRun;
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
        @JsonProperty("input_payload") JsonNode inputPayload
) {
    public QueuedNodeCommand {
        inputPayload = inputPayload == null ? JsonNodeFactory.instance.objectNode() : inputPayload;
        if (!inputPayload.isObject()) {
            throw new IllegalArgumentException("inputPayload must be a JSON object");
        }
        if (timeoutMs < 1) {
            throw new IllegalArgumentException("timeoutMs must be positive");
        }
    }

    public static QueuedNodeCommand fromNodeRun(
            String eventId,
            WorkflowNodeRun nodeRun,
            String executionId,
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
                    input
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid workflow node input JSON", exception);
        }
    }
}
