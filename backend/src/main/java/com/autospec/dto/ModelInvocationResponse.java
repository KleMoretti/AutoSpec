package com.autospec.dto;

import com.autospec.entity.ModelInvocation;

import java.math.BigDecimal;

public record ModelInvocationResponse(
        Long id,
        Long taskId,
        String providerKey,
        String modelName,
        String agentNode,
        String status,
        Integer durationMs,
        BigDecimal score,
        String errorMessage
) {

    public static ModelInvocationResponse from(ModelInvocation invocation) {
        return new ModelInvocationResponse(
                invocation.getId(),
                invocation.getTaskId(),
                invocation.getProviderKey(),
                invocation.getModelName(),
                invocation.getAgentNode(),
                invocation.getStatus(),
                invocation.getDurationMs(),
                invocation.getScore(),
                invocation.getErrorMessage()
        );
    }
}
