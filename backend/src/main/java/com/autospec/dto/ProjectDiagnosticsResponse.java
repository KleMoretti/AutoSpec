package com.autospec.dto;

public record ProjectDiagnosticsResponse(
        Long projectId,
        Long latestWorkflowRunId,
        String latestCorrelationId,
        long workflowRunCount,
        long failedWorkflowRunCount,
        long agentTaskCount,
        long failedAgentTaskCount,
        String latestFailedAgentTaskNodeName,
        String latestFailedAgentTaskErrorMessage,
        long auditEventCount,
        long externalCallCount,
        long failedExternalCallCount,
        String latestFailedExternalCallErrorMessage,
        Integer latestFailedExternalCallDurationMs,
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
        String latestFailedCodeGenerationJobErrorMessage
) {
}
