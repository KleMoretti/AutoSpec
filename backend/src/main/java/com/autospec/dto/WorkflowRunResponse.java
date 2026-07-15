package com.autospec.dto;

import com.autospec.entity.WorkflowRun;

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
                run.getStatus(),
                run.getResponseStatus(),
                run.getResponsePercent(),
                run.getErrorMessage(),
                run.getStartedAt(),
                run.getCompletedAt()
        );
    }
}
