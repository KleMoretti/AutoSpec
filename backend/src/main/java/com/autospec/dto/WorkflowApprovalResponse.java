package com.autospec.dto;

import com.autospec.entity.WorkflowApproval;

import java.time.LocalDateTime;

public record WorkflowApprovalResponse(
        Long id,
        Long workflowRunId,
        Long nodeRunId,
        String nodeId,
        String mode,
        String status,
        String decision,
        Long candidateArtifactId,
        Long revisedArtifactId,
        String decisionReason,
        LocalDateTime decidedAt,
        LocalDateTime createdAt
) {
    public static WorkflowApprovalResponse from(
            WorkflowApproval approval,
            String nodeId
    ) {
        return new WorkflowApprovalResponse(
                approval.getId(),
                approval.getWorkflowRunId(),
                approval.getNodeRunId(),
                nodeId,
                approval.getMode(),
                approval.getStatus(),
                approval.getDecision(),
                approval.getCandidateArtifactId(),
                approval.getRevisedArtifactId(),
                approval.getDecisionReason(),
                approval.getDecidedAt(),
                approval.getCreatedAt()
        );
    }
}
