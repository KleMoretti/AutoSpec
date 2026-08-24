package com.autospec.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record UpdateArtifactRequest(
        @NotBlank String content,
        @NotNull @PositiveOrZero Integer expectedLockVersion
) {
}
