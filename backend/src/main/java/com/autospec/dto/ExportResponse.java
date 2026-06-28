package com.autospec.dto;

public record ExportResponse(
        String format,
        String content,
        String fileName,
        String mediaType,
        String encoding
) {

    public ExportResponse(String format, String content) {
        this(format, content, null, "text/markdown;charset=utf-8", "text");
    }
}
