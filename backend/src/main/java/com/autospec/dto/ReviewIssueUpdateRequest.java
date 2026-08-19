package com.autospec.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ReviewIssueUpdateRequest(
        @NotBlank
        @Pattern(regexp = "OPEN|IN_PROGRESS|RESOLVED|IGNORED")
        String status,
        @Size(max = 2000) String resolution,
        Long resolvedInArtifactId
) {
}
