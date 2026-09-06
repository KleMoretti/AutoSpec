package com.autospec.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/** Structured response returned by the internal Tool Gateway. */
public record ToolGatewayResponse(
        @JsonProperty("request_id") String requestId,
        @JsonProperty("idempotency_key") String idempotencyKey,
        String status,
        JsonNode result,
        @JsonProperty("result_hash") String resultHash,
        @JsonProperty("error_code") String errorCode,
        @JsonProperty("error_message") String errorMessage,
        boolean cached,
        @JsonProperty("source_execution_id") String sourceExecutionId,
        int attempts,
        @JsonProperty("duration_ms") int durationMs,
        java.util.Map<String, Object> usage
) {
}
