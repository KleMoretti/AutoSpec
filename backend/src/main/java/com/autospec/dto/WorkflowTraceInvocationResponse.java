package com.autospec.dto;

import java.math.BigDecimal;

public record WorkflowTraceInvocationResponse(
        Long id,
        String callType,
        String providerKey,
        String modelName,
        String promptKey,
        String promptVersion,
        String routeKey,
        String toolName,
        String toolVersion,
        String status,
        Integer durationMs,
        Integer inputTokens,
        Integer outputTokens,
        Integer cacheTokens,
        BigDecimal estimatedCost,
        String errorCode
) {
}
