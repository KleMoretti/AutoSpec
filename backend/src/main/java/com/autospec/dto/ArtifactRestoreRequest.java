package com.autospec.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ArtifactRestoreRequest(
        @NotNull @Min(0) Integer expectedLatestLockVersion
) {
}
