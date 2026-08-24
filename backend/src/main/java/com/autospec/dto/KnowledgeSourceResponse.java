package com.autospec.dto;

import com.autospec.entity.KnowledgeDocument;
import com.autospec.entity.KnowledgeChunk;

public record KnowledgeSourceResponse(
        Long artifactId,
        String artifactType,
        String title,
        Integer artifactVersion,
        Long chunkId,
        Integer chunkIndex,
        String citationLocation,
        String content,
        String retrievalStrategy,
        Double relevanceScore
) {

    public KnowledgeSourceResponse(
            Long artifactId,
            String artifactType,
            String title,
            Integer artifactVersion,
            String content
    ) {
        this(
                artifactId,
                artifactType,
                title,
                artifactVersion,
                null,
                null,
                null,
                content,
                null,
                null
        );
    }

    public static KnowledgeSourceResponse from(KnowledgeDocument document, String content) {
        return new KnowledgeSourceResponse(
                document.getArtifactId(),
                document.getArtifactType(),
                document.getTitle(),
                document.getArtifactVersion(),
                content
        );
    }

    public static KnowledgeSourceResponse from(
            KnowledgeDocument document,
            KnowledgeChunk chunk,
            String retrievalStrategy,
            double relevanceScore
    ) {
        return new KnowledgeSourceResponse(
                document.getArtifactId(),
                document.getArtifactType(),
                document.getTitle(),
                document.getArtifactVersion(),
                chunk.getId(),
                chunk.getChunkIndex(),
                "chunk[" + chunk.getChunkIndex() + "]",
                chunk.getContent(),
                retrievalStrategy,
                relevanceScore
        );
    }
}
