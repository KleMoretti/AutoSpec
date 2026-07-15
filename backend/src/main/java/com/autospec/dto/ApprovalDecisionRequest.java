package com.autospec.dto;

import jakarta.validation.constraints.NotBlank;

public record ApprovalDecisionRequest(
        @NotBlank String decision,
        String reason,
        String editedContent,
        String rollbackNodeId,
        @NotBlank String idempotencyKey
) {
}
