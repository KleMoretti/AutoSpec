package com.autospec.dto;

import com.autospec.entity.ExternalCallLog;

import java.time.LocalDateTime;

public record ExternalCallLogResponse(
        Long id,
        String targetService,
        String operation,
        String status,
        Integer durationMs,
        String requestContext,
        String errorMessage,
        LocalDateTime startedAt,
        LocalDateTime completedAt
) {

    public static ExternalCallLogResponse from(ExternalCallLog log) {
        return new ExternalCallLogResponse(
                log.getId(),
                log.getTargetService(),
                log.getOperation(),
                log.getStatus(),
                log.getDurationMs(),
                log.getRequestContext(),
                log.getErrorMessage(),
                log.getStartedAt(),
                log.getCompletedAt()
        );
    }
}
