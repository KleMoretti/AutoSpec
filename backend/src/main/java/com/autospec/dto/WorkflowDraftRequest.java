package com.autospec.dto;

import jakarta.validation.constraints.NotBlank;

public record WorkflowDraftRequest(
        @NotBlank String workflowKey,
        @NotBlank String name,
        String description,
        @NotBlank String version,
        @NotBlank String specJson
) {
}
