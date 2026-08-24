package com.autospec.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ProjectDashboardItemResponse(
        Long projectId,
        String name,
        String requirementSummary,
        String projectStatus,
        Long latestWorkflowRunId,
        String latestWorkflowStatus,
        long pendingApprovalCount,
        long openReviewIssueCount,
        long blockingReviewIssueCount,
        BigDecimal estimatedCost,
        Integer qualityScore,
        LocalDateTime updatedAt
) {
}
