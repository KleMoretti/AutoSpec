package com.autospec.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record ApprovalDecisionRequest(
        @NotBlank String decision,
        String reason,
        String editedContent,
        String rollbackNodeId,
        @NotBlank String idempotencyKey,
        @NotNull @PositiveOrZero Integer expectedLockVersion
) {
}
