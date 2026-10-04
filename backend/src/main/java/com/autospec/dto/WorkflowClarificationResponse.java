package com.autospec.dto;

import com.autospec.entity.WorkflowClarification;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;

public record WorkflowClarificationResponse(
        Long id,
        Long workflowRunId,
        Long nodeRunId,
        Integer revision,
        Integer round,
        String requestId,
        JsonNode request,
        JsonNode response,
        String status,
        Long approvalId,
        Integer lockVersion,
        String idempotencyKey,
        LocalDateTime createdAt,
        LocalDateTime answeredAt,
        LocalDateTime expiredAt,
        LocalDateTime updatedAt
) {
    public static WorkflowClarificationResponse from(
            WorkflowClarification clarification,
            JsonNode request,
            JsonNode response
    ) {
        return new WorkflowClarificationResponse(
                clarification.getId(),
                clarification.getWorkflowRunId(),
                clarification.getNodeRunId(),
                clarification.getRevision(),
                clarification.getRound(),
                clarification.getRequestId(),
                request,
                response,
                clarification.getStatus(),
                clarification.getApprovalId(),
                clarification.getLockVersion(),
                clarification.getIdempotencyKey(),
                clarification.getCreatedAt(),
                clarification.getAnsweredAt(),
                clarification.getExpiredAt(),
                clarification.getUpdatedAt()
        );
    }
}
