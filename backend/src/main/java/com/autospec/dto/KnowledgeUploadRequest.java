package com.autospec.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record KnowledgeUploadRequest(
        @NotBlank @Size(max = 256)
        @Pattern(regexp = "(?i)^.*\\.(txt|md|markdown)$")
        String fileName,
        @NotBlank @Size(max = 1_000_000)
        String content,
        @Size(max = 128)
        String contentType,
        @NotBlank @Size(max = 128)
        @Pattern(regexp = "^[A-Za-z0-9_-]+$")
        String idempotencyKey
) {
}
