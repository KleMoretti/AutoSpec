package com.autospec.service;

import com.autospec.entity.WorkflowVersion;

import java.util.List;

public interface WorkflowVersionManagementService {
    WorkflowVersion createDraft(CreateDraftCommand command);

    ValidationResult validate(long versionId);

    WorkflowVersion publish(long versionId);

    record CreateDraftCommand(
            String workflowKey,
            String name,
            String description,
            String version,
            String specJson
    ) {
    }

    record ValidationResult(
            long workflowVersionId,
            boolean valid,
            List<String> errors,
            List<List<String>> topologicalLayers
    ) {
    }
}
