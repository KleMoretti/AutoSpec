package com.autospec.dto;

import com.autospec.entity.KnowledgeDocument;
import com.autospec.entity.KnowledgeChunk;

public record KnowledgeSourceResponse(
        Long projectId,
        Long artifactId,
        String artifactType,
        String title,
        Integer artifactVersion,
        Long chunkId,
        Integer chunkIndex,
        String citationLocation,
        String content,
        String retrievalStrategy,
        String chunkerVersion,
        String embeddingModel,
        String artifactContentHash,
        String chunkContentHash,
        Double relevanceScore,
        String corpusType
) {

    public KnowledgeSourceResponse(
            Long projectId,
            Long artifactId,
            String artifactType,
            String title,
            Integer artifactVersion,
            Long chunkId,
            Integer chunkIndex,
            String citationLocation,
            String content,
            String retrievalStrategy,
            String chunkerVersion,
            String embeddingModel,
            String artifactContentHash,
            String chunkContentHash,
            Double relevanceScore
    ) {
        this(
                projectId,
                artifactId,
                artifactType,
                title,
                artifactVersion,
                chunkId,
                chunkIndex,
                citationLocation,
                content,
                retrievalStrategy,
                chunkerVersion,
                embeddingModel,
                artifactContentHash,
                chunkContentHash,
                relevanceScore,
                null
        );
    }

    public KnowledgeSourceResponse(
            Long artifactId,
            String artifactType,
            String title,
            Integer artifactVersion,
            String content
    ) {
        this(
                null,
                artifactId,
                artifactType,
                title,
                artifactVersion,
                null,
                null,
                null,
                content,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    public KnowledgeSourceResponse(
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
        this(
                null,
                artifactId,
                artifactType,
                title,
                artifactVersion,
                chunkId,
                chunkIndex,
                citationLocation,
                content,
                retrievalStrategy,
                null,
                null,
                null,
                null,
                relevanceScore,
                null
        );
    }

    public static KnowledgeSourceResponse from(KnowledgeDocument document, String content) {
        return new KnowledgeSourceResponse(
                document.getProjectId(),
                document.getArtifactId(),
                document.getArtifactType(),
                document.getTitle(),
                document.getArtifactVersion(),
                null,
                null,
                null,
                content,
                null,
                document.getChunkerVersion(),
                document.getEmbeddingModel(),
                document.getContentHash(),
                null,
                null,
                document.getCorpusType()
        );
    }

    public static KnowledgeSourceResponse from(
            KnowledgeDocument document,
            KnowledgeChunk chunk,
            String retrievalStrategy,
            double relevanceScore
    ) {
        return new KnowledgeSourceResponse(
                document.getProjectId(),
                document.getArtifactId(),
                document.getArtifactType(),
                document.getTitle(),
                document.getArtifactVersion(),
                chunk.getId(),
                chunk.getChunkIndex(),
                "chunk[" + chunk.getChunkIndex() + "]",
                chunk.getContent(),
                retrievalStrategy,
                document.getChunkerVersion(),
                document.getEmbeddingModel(),
                document.getContentHash(),
                chunk.getContentHash(),
                relevanceScore,
                document.getCorpusType()
        );
    }
}
