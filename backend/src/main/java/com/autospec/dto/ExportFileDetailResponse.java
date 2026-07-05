package com.autospec.dto;

import com.autospec.entity.ExportFile;

import java.time.LocalDateTime;

public record ExportFileDetailResponse(
        Long id,
        Long jobId,
        String fileName,
        String mediaType,
        String encoding,
        String content,
        LocalDateTime createdAt
) {

    public static ExportFileDetailResponse from(ExportFile file) {
        return new ExportFileDetailResponse(
                file.getId(),
                file.getJobId(),
                file.getFileName(),
                file.getMediaType(),
                file.getEncoding(),
                file.getContent(),
                file.getCreatedAt()
        );
    }
}
