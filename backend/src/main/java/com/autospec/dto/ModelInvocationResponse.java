package com.autospec.dto;

import com.autospec.entity.ModelInvocation;

import java.math.BigDecimal;

public record ModelInvocationResponse(
        Long id,
        Long taskId,
        Long workflowRunId,
        Long workflowNodeRunId,
        String correlationId,
        Long promptVersionId,
        String promptKey,
        String providerKey,
        String modelName,
        String routeKey,
        String routeReason,
        Boolean fallbackUsed,
        String contextManifestJson,
        String agentNode,
        String status,
        Integer durationMs,
        Integer inputTokens,
        Integer outputTokens,
        Integer cacheTokens,
        Integer callCount,
        BigDecimal estimatedCost,
        BigDecimal score,
        String errorMessage
) {

    public static ModelInvocationResponse from(ModelInvocation invocation) {
        return new ModelInvocationResponse(
                invocation.getId(),
                invocation.getTaskId(),
                invocation.getWorkflowRunId(),
                invocation.getWorkflowNodeRunId(),
                invocation.getCorrelationId(),
                invocation.getPromptVersionId(),
                invocation.getPromptKey(),
                invocation.getProviderKey(),
                invocation.getModelName(),
                invocation.getRouteKey(),
                invocation.getRouteReason(),
                invocation.getFallbackUsed(),
                invocation.getContextManifestJson(),
                invocation.getAgentNode(),
                invocation.getStatus(),
                invocation.getDurationMs(),
                invocation.getInputTokens(),
                invocation.getOutputTokens(),
                invocation.getCacheTokens(),
                invocation.getCallCount(),
                invocation.getEstimatedCost(),
                invocation.getScore(),
                invocation.getErrorMessage()
        );
    }
}
