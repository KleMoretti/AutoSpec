package com.autospec.dto;

import com.autospec.entity.WorkflowRun;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record WorkflowRunResponse(
        Long id,
        Long projectId,
        String operation,
        String idempotencyKey,
        String correlationId,
        Long workflowVersionId,
        Long replayOfRunId,
        Integer reviewRound,
        Integer maxReviewRounds,
        String qualityProfile,
        Long maxTokens,
        BigDecimal maxCost,
        Integer maxModelCalls,
        Long maxWallTimeMs,
        Long consumedTokens,
        BigDecimal consumedCost,
        Integer modelCallCount,
        Long reservedTokens,
        BigDecimal reservedCost,
        Integer reservedModelCalls,
        String status,
        String responseStatus,
        Integer responsePercent,
        String errorMessage,
        LocalDateTime startedAt,
        LocalDateTime completedAt
) {

    public static WorkflowRunResponse from(WorkflowRun run) {
        return new WorkflowRunResponse(
                run.getId(),
                run.getProjectId(),
                run.getOperation(),
                run.getIdempotencyKey(),
                run.getCorrelationId(),
                run.getWorkflowVersionId(),
                run.getReplayOfRunId(),
                run.getReviewRound(),
                run.getMaxReviewRounds(),
                run.getQualityProfile(),
                run.getMaxTokens(),
                run.getMaxCost(),
                run.getMaxModelCalls(),
                run.getMaxWallTimeMs(),
                run.getConsumedTokens(),
                run.getConsumedCost(),
                run.getModelCallCount(),
                run.getReservedTokens(),
                run.getReservedCost(),
                run.getReservedModelCalls(),
                run.getStatus(),
                run.getResponseStatus(),
                run.getResponsePercent(),
                run.getErrorMessage(),
                run.getStartedAt(),
                run.getCompletedAt()
        );
    }
}
