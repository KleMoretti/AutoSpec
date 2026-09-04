package com.autospec.dto;

import java.math.BigDecimal;
import java.util.List;

public record WorkflowRuntimeMetricsResponse(
        Long workflowRunId,
        int nodeAttemptCount,
        long queueTimeMs,
        long executionDurationMs,
        int retryCount,
        int recoveryCount,
        long tokenCount,
        long cacheTokenCount,
        BigDecimal estimatedCost,
        int modelCallCount,
        int acceptedDuplicateEventCount,
        String qualityProfile,
        Long maxTokens,
        BigDecimal maxCost,
        Integer maxModelCalls,
        Long maxWallTimeMs,
        Long reservedTokens,
        BigDecimal reservedCost,
        Integer reservedModelCalls,
        Long remainingTokens,
        BigDecimal remainingCost,
        Integer remainingModelCalls,
        List<ModelUsageResponse> modelUsage,
        List<WorkflowNodeMetricsResponse> nodeMetrics,
        List<WorkflowMetricSliceResponse> versionSlices
) {
}
