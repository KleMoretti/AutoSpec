package com.autospec.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateArtifactRequest(
        @NotBlank String content
) {
}
