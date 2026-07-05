package com.autospec.dto;

import com.autospec.entity.ExportFile;

import java.time.LocalDateTime;

public record ExportFileResponse(
        Long id,
        Long jobId,
        String fileName,
        String mediaType,
        String encoding,
        LocalDateTime createdAt
) {

    public static ExportFileResponse from(ExportFile file) {
        return new ExportFileResponse(
                file.getId(),
                file.getJobId(),
                file.getFileName(),
                file.getMediaType(),
                file.getEncoding(),
                file.getCreatedAt()
        );
    }
}
