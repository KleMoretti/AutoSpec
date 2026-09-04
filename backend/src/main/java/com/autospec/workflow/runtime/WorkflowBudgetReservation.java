package com.autospec.workflow.runtime;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Worst-case model usage atomically reserved before an executable command is published. */
public record WorkflowBudgetReservation(
        @JsonProperty("reservation_id") String reservationId,
        @JsonProperty("input_tokens") long inputTokens,
        @JsonProperty("output_tokens") long outputTokens,
        @JsonProperty("model_calls") int modelCalls,
        @JsonProperty("estimated_cost") BigDecimal estimatedCost
) {
    public WorkflowBudgetReservation {
        if (reservationId == null || reservationId.isBlank()) {
            throw new IllegalArgumentException("reservationId is required");
        }
        if (inputTokens < 0 || outputTokens < 0 || modelCalls < 0
                || estimatedCost == null || estimatedCost.signum() < 0) {
            throw new IllegalArgumentException("budget reservation values must not be negative");
        }
        estimatedCost = estimatedCost.setScale(6, RoundingMode.HALF_UP);
    }

    public static WorkflowBudgetReservation from(
            String executionId,
            JsonNode contextPolicy,
            JsonNode modelPolicy
    ) {
        int maxInput = requiredPositive(contextPolicy, "max_input_tokens");
        int maxOutput = requiredPositive(modelPolicy, "max_output_tokens");
        int maxCalls = requiredPositive(modelPolicy, "max_calls");
        BigDecimal inputRate = nonNegative(modelPolicy, "input_cost_per_million");
        BigDecimal cachedInputRate = nonNegative(
                modelPolicy,
                "cached_input_cost_per_million"
        );
        BigDecimal outputRate = nonNegative(modelPolicy, "output_cost_per_million");
        long reservedInput = Math.multiplyExact((long) maxInput, maxCalls);
        long reservedOutput = Math.multiplyExact((long) maxOutput, maxCalls);
        BigDecimal cost = BigDecimal.valueOf(reservedInput)
                .multiply(inputRate.max(cachedInputRate))
                .add(BigDecimal.valueOf(reservedOutput).multiply(outputRate))
                .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);
        return new WorkflowBudgetReservation(
                executionId,
                reservedInput,
                reservedOutput,
                maxCalls,
                cost
        );
    }

    public long totalTokens() {
        return Math.addExact(inputTokens, outputTokens);
    }

    private static int requiredPositive(JsonNode value, String field) {
        int parsed = value == null ? 0 : value.path(field).asInt(0);
        if (parsed < 1) {
            throw new IllegalArgumentException("budget policy field must be positive: " + field);
        }
        return parsed;
    }

    private static BigDecimal nonNegative(JsonNode value, String field) {
        JsonNode node = value == null ? null : value.get(field);
        BigDecimal parsed = node == null || !node.isNumber()
                ? BigDecimal.ZERO
                : node.decimalValue();
        if (parsed.signum() < 0) {
            throw new IllegalArgumentException("budget policy field must not be negative: " + field);
        }
        return parsed;
    }
}
