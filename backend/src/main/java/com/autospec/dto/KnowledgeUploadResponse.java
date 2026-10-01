package com.autospec.dto;

public record KnowledgeUploadResponse(
        Long projectId,
        Long artifactId,
        String fileName,
        Integer version,
        String contentHash,
        String status,
        Long corpusEpoch
) {
}
