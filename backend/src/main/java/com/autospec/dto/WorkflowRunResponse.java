package com.autospec.dto;

import com.autospec.entity.WorkflowRun;

import java.time.LocalDateTime;

public record WorkflowRunResponse(
        Long id,
        String operation,
        String idempotencyKey,
        String correlationId,
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
                run.getOperation(),
                run.getIdempotencyKey(),
                run.getCorrelationId(),
                run.getStatus(),
                run.getResponseStatus(),
                run.getResponsePercent(),
                run.getErrorMessage(),
                run.getStartedAt(),
                run.getCompletedAt()
        );
    }
}
