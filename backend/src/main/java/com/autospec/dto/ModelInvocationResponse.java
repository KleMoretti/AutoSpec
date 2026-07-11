package com.autospec.dto;

import com.autospec.entity.ModelInvocation;

import java.math.BigDecimal;

public record ModelInvocationResponse(
        Long id,
        Long taskId,
        Long workflowRunId,
        String correlationId,
        Long promptVersionId,
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
                invocation.getWorkflowRunId(),
                invocation.getCorrelationId(),
                invocation.getPromptVersionId(),
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
