package com.autospec.dto;

import java.math.BigDecimal;

public record WorkflowRuntimeMetricsResponse(
        Long workflowRunId,
        int nodeAttemptCount,
        long queueTimeMs,
        long executionDurationMs,
        int retryCount,
        int recoveryCount,
        long tokenCount,
        BigDecimal estimatedCost,
        int acceptedDuplicateEventCount
) {
}
