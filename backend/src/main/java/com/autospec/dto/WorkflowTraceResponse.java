package com.autospec.dto;

import java.util.List;

public record WorkflowTraceResponse(
        Long workflowRunId,
        String correlationId,
        String status,
        Long executionBundleId,
        String executionBundleHash,
        List<WorkflowTraceNodeResponse> nodes,
        List<WorkflowFailureClusterResponse> failureClusters,
        List<WorkflowFailureCaseResponse> offlineCases
) {
}
