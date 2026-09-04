package com.autospec.dto;

import java.math.BigDecimal;

public record WorkflowMetricSliceResponse(
        String dimension,
        String key,
        String version,
        int invocationCount,
        int failureCount,
        long tokenCount,
        BigDecimal estimatedCost,
        long p95DurationMs
) {
}
