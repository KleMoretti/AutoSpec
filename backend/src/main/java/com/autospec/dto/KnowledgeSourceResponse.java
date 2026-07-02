package com.autospec.dto;

import com.autospec.entity.KnowledgeDocument;

public record KnowledgeSourceResponse(
        Long artifactId,
        String artifactType,
        String title,
        Integer artifactVersion,
        String content
) {

    public static KnowledgeSourceResponse from(KnowledgeDocument document, String content) {
        return new KnowledgeSourceResponse(
                document.getArtifactId(),
                document.getArtifactType(),
                document.getTitle(),
                document.getArtifactVersion(),
                content
        );
    }
}
