package com.autospec.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

public record WorkflowRunStartRequest(
        @NotNull Long projectId,
        @NotNull Long workflowVersionId,
        @NotNull Map<String, Object> input,
        @Size(max = 128) String idempotencyKey
) {
}
