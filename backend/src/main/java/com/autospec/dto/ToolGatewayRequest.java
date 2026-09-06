package com.autospec.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/** Internal Worker-to-control-plane request for a bounded tool call. */
public record ToolGatewayRequest(
        @JsonProperty("request_id") String requestId,
        @JsonProperty("execution_id") String executionId,
        @JsonProperty("workflow_run_id") Long workflowRunId,
        @JsonProperty("node_run_id") Long nodeRunId,
        @JsonProperty("node_id") String nodeId,
        @JsonProperty("actor_user_id") Long actorUserId,
        @JsonProperty("project_id") Long projectId,
        @JsonProperty("fencing_token") Long fencingToken,
        @JsonProperty("deadline_epoch_ms") Long deadlineEpochMs,
        @JsonProperty("execution_bundle_hash") String executionBundleHash,
        @JsonProperty("policy_hash") String policyHash,
        @JsonProperty("idempotency_key") String idempotencyKey,
        String name,
        String version,
        JsonNode arguments,
        @JsonProperty("normalized_params_hash") String normalizedParamsHash,
        @JsonProperty("max_result_bytes") Integer maxResultBytes,
        @JsonProperty("correlation_id") String correlationId,
        String traceparent,
        String tracestate
) {
}
