package com.autospec.dto;

public record CodeGenerationResponse(
        String format,
        String content,
        String fileName,
        String mediaType,
        String encoding
) {
}
