package com.autospec.dto;

public record ProjectDiagnosticsResponse(
        Long projectId,
        Long latestWorkflowRunId,
        String latestCorrelationId,
        long workflowRunCount,
        long runningWorkflowRunCount,
        long failedWorkflowRunCount,
        long cancelledWorkflowRunCount,
        String latestFailedWorkflowRunErrorMessage,
        long auditEventCount,
        long modelInvocationCount,
        long failedModelInvocationCount,
        String latestFailedModelInvocationAgentNode,
        String latestFailedModelInvocationModelName,
        Integer latestFailedModelInvocationDurationMs,
        String latestFailedModelInvocationErrorMessage,
        long codeGenerationJobCount,
        long runningCodeGenerationJobCount,
        long failedCodeGenerationJobCount,
        long cancelledCodeGenerationJobCount,
        String latestFailedCodeGenerationJobErrorMessage,
        Integer latestEvaluationOverallScore,
        String latestEvaluationGrade,
        long latestEvaluationIssueCount,
        long reviewIssueCount,
        long openReviewIssueCount,
        long blockingReviewIssueCount,
        String latestOpenReviewIssueSeverity,
        String latestOpenReviewIssueType,
        String latestOpenReviewIssueDescription
) {
}
