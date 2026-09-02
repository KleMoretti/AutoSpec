package com.autospec.workflow.transport;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** One physical model or tool invocation emitted by a Worker execution. */
public record WorkflowCallRecord(
        @JsonProperty("call_id") String callId,
        @JsonProperty("call_type") String callType,
        @JsonProperty("execution_id") String executionId,
        @JsonProperty("call_sequence") Integer callSequence,
        Integer attempt,
        @JsonProperty("provider_key") String providerKey,
        @JsonProperty("model_name") String modelName,
        @JsonProperty("prompt_key") String promptKey,
        @JsonProperty("prompt_version") String promptVersion,
        @JsonProperty("prompt_checksum") String promptChecksum,
        @JsonProperty("schema_version") String schemaVersion,
        @JsonProperty("contract_hash") String contractHash,
        @JsonProperty("input_tokens") Integer inputTokens,
        @JsonProperty("output_tokens") Integer outputTokens,
        @JsonProperty("cache_tokens") Integer cacheTokens,
        @JsonProperty("estimated_cost") BigDecimal estimatedCost,
        @JsonProperty("reserved_input_tokens") Integer reservedInputTokens,
        @JsonProperty("reserved_output_tokens") Integer reservedOutputTokens,
        @JsonProperty("reserved_cost") BigDecimal reservedCost,
        @JsonProperty("route_key") String routeKey,
        @JsonProperty("route_reason") String routeReason,
        @JsonProperty("fallback_used") Boolean fallbackUsed,
        @JsonProperty("normalized_params_hash") String normalizedParamsHash,
        @JsonProperty("result_hash") String resultHash,
        String status,
        @JsonProperty("error_code") String errorCode,
        @JsonProperty("error_message") String errorMessage,
        @JsonProperty("duration_ms") Integer durationMs,
        @JsonProperty("deadline_epoch_ms") Long deadlineEpochMs,
        @JsonProperty("idempotency_key") String idempotencyKey,
        @JsonProperty("tool_name") String toolName,
        @JsonProperty("tool_version") String toolVersion,
        @JsonProperty("permission_policy") String permissionPolicy,
        @JsonProperty("reference_sources") List<String> referenceSources,
        @JsonProperty("redacted_params") JsonNode redactedParams
) {
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final BigDecimal COST_TOLERANCE = new BigDecimal("0.000001");

    public WorkflowCallRecord {
        if (callId == null || callId.isBlank()
                || !Set.of("MODEL", "TOOL").contains(callType)
                || callSequence == null || callSequence < 1
                || attempt == null || attempt < 1
                || !Set.of("SUCCEEDED", "FAILED").contains(status)) {
            throw new IllegalArgumentException("workflow call record identity is invalid");
        }
        if (safe(inputTokens) < 0
                || safe(outputTokens) < 0
                || safe(cacheTokens) < 0
                || safe(reservedInputTokens) < 0
                || safe(reservedOutputTokens) < 0
                || safe(durationMs) < 0
                || nonNegative(estimatedCost).signum() < 0
                || nonNegative(reservedCost).signum() < 0
                || deadlineEpochMs != null && deadlineEpochMs < 0) {
            throw new IllegalArgumentException("workflow call record usage is invalid");
        }
        referenceSources = referenceSources == null ? List.of() : List.copyOf(referenceSources);
        if ("FAILED".equals(status) && isBlank(errorCode)) {
            throw new IllegalArgumentException("failed workflow call requires error_code");
        }
        if ("MODEL".equals(callType)) {
            if (isBlank(providerKey) || isBlank(modelName) || isBlank(promptKey)
                    || isBlank(promptVersion) || !isHash(promptChecksum)
                    || isBlank(schemaVersion) || !isHash(contractHash)
                    || !isHash(normalizedParamsHash) || isBlank(idempotencyKey)
                    || deadlineEpochMs == null || deadlineEpochMs < 1
                    || "SUCCEEDED".equals(status) && !isHash(resultHash)) {
                throw new IllegalArgumentException("model workflow call record is incomplete");
            }
            if (safe(inputTokens) > safe(reservedInputTokens)
                    || safe(outputTokens) > safe(reservedOutputTokens)
                    || nonNegative(estimatedCost).subtract(nonNegative(reservedCost))
                    .compareTo(COST_TOLERANCE) > 0) {
                throw new IllegalArgumentException(
                        "model workflow call exceeded its frozen call reservation"
                );
            }
        } else if (isBlank(toolName) || isBlank(toolVersion)
                || isBlank(permissionPolicy) || isBlank(idempotencyKey)
                || !isHash(normalizedParamsHash) || !isHash(contractHash)
                || isBlank(schemaVersion)) {
            throw new IllegalArgumentException("tool workflow call record is incomplete");
        }
    }

    public boolean isModelCall() {
        return "MODEL".equals(callType);
    }

    public boolean isToolCall() {
        return "TOOL".equals(callType);
    }

    private static int safe(Integer value) {
        return value == null ? 0 : value;
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean isHash(String value) {
        return value != null && SHA256.matcher(value).matches();
    }
}
