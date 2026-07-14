package com.autospec.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record WorkflowReplayRequest(
        @NotBlank String mode,
        Long selectedWorkflowVersionId,
        @Size(max = 128) String idempotencyKey
) {
}
