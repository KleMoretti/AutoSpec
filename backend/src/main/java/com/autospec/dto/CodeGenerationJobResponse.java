package com.autospec.dto;

import com.autospec.entity.CodeGenerationJob;

import java.time.LocalDateTime;

public record CodeGenerationJobResponse(
        Long id,
        Long retryOfJobId,
        String status,
        String manifest,
        String errorMessage,
        LocalDateTime createdAt,
        LocalDateTime completedAt,
        LocalDateTime cancelledAt
) {

    public static CodeGenerationJobResponse from(CodeGenerationJob job) {
        return new CodeGenerationJobResponse(
                job.getId(),
                job.getRetryOfJobId(),
                job.getStatus(),
                job.getManifest(),
                job.getErrorMessage(),
                job.getCreatedAt(),
                job.getCompletedAt(),
                job.getCancelledAt()
        );
    }
}
