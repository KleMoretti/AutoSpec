package com.autospec.dto;

import java.math.BigDecimal;
import java.util.Map;

public record WorkflowNodeMetricsResponse(
        String nodeId,
        String handlerKey,
        String handlerVersion,
        int attemptCount,
        int successCount,
        int failureCount,
        int retryCount,
        long queueP50Ms,
        long queueP95Ms,
        long executionP50Ms,
        long executionP95Ms,
        long tokenCount,
        BigDecimal estimatedCost,
        int modelCallCount,
        int toolCallCount,
        Map<String, Integer> routeDistribution,
        String latestStatus
) {
}
