package com.autospec.service;

import java.math.BigDecimal;
import java.util.Locale;

public record WorkflowExecutionPolicy(
        String qualityProfile,
        Long maxTokens,
        BigDecimal maxCost,
        Integer maxModelCalls,
        Long maxWallTimeMs
) {
    public static WorkflowExecutionPolicy resolve(
            String qualityProfile,
            Long maxTokens,
            BigDecimal maxCost,
            Integer maxModelCalls,
            Long maxWallTimeMs
    ) {
        String normalized = qualityProfile == null || qualityProfile.isBlank()
                ? "BALANCED"
                : qualityProfile.trim().toUpperCase(Locale.ROOT);
        Defaults defaults = switch (normalized) {
            case "FAST" -> new Defaults(50_000L, new BigDecimal("5.00"), 8, 300_000L);
            case "BALANCED" -> new Defaults(150_000L, new BigDecimal("20.00"), 16, 900_000L);
            case "DEEP" -> new Defaults(500_000L, new BigDecimal("100.00"), 40, 2_700_000L);
            default -> throw new IllegalArgumentException("Unsupported quality profile: " + qualityProfile);
        };
        return new WorkflowExecutionPolicy(
                normalized,
                maxTokens == null ? defaults.maxTokens() : maxTokens,
                maxCost == null ? defaults.maxCost() : maxCost,
                maxModelCalls == null ? defaults.maxModelCalls() : maxModelCalls,
                maxWallTimeMs == null ? defaults.maxWallTimeMs() : maxWallTimeMs
        );
    }

    private record Defaults(
            Long maxTokens,
            BigDecimal maxCost,
            Integer maxModelCalls,
            Long maxWallTimeMs
    ) {
    }
}
