package com.autospec.dto;

import java.math.BigDecimal;
import java.util.List;

public record WorkflowTraceNodeResponse(
        Long nodeRunId,
        String nodeId,
        Integer revision,
        Integer attempt,
        String executionId,
        String status,
        String handlerKey,
        String handlerVersion,
        Integer durationMs,
        Long queueTimeMs,
        Integer actualInputTokens,
        Integer actualOutputTokens,
        Integer actualCacheTokens,
        BigDecimal actualCost,
        Integer actualModelCalls,
        Integer actualToolCalls,
        String errorCode,
        String workerId,
        List<WorkflowTraceInvocationResponse> invocations
) {
}
