package com.autospec.dto;

import com.autospec.service.WorkflowVersionManagementService;

import java.util.List;

public record WorkflowValidationResponse(
        long workflowVersionId,
        boolean valid,
        List<String> errors,
        List<List<String>> topologicalLayers
) {
    public static WorkflowValidationResponse from(
            WorkflowVersionManagementService.ValidationResult result
    ) {
        return new WorkflowValidationResponse(
                result.workflowVersionId(),
                result.valid(),
                result.errors(),
                result.topologicalLayers()
        );
    }
}
