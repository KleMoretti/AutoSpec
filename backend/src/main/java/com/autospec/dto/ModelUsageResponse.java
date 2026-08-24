package com.autospec.dto;

import java.math.BigDecimal;

public record ModelUsageResponse(
        String providerKey,
        String modelName,
        int invocationCount,
        int modelCallCount,
        long inputTokens,
        long outputTokens,
        long cacheTokens,
        BigDecimal estimatedCost
) {
}
