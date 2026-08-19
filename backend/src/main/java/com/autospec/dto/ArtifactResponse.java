package com.autospec.dto;

import com.autospec.entity.Artifact;

import java.time.LocalDateTime;

public record ArtifactResponse(
        Long id,
        String type,
        String title,
        String content,
        String format,
        Integer version,
        Integer lockVersion,
        String status,
        String sourceAgent,
        Long parentArtifactId,
        Long workflowNodeRunId,
        String contentHash,
        String schemaVersion,
        String promptKey,
        String promptVersion,
        String modelProvider,
        String modelName,
        String sourceCitationsJson,
        String provenanceJson,
        LocalDateTime approvedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public ArtifactResponse(Long id, String type, String title, String content, String format, Integer version) {
        this(
                id, type, title, content, format, version, 0, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null
        );
    }

    public static ArtifactResponse from(Artifact artifact) {
        return new ArtifactResponse(
                artifact.getId(),
                artifact.getType(),
                artifact.getTitle(),
                artifact.getContent(),
                artifact.getFormat(),
                artifact.getVersion(),
                artifact.getLockVersion(),
                artifact.getStatus(),
                artifact.getSourceAgent(),
                artifact.getParentArtifactId(),
                artifact.getWorkflowNodeRunId(),
                artifact.getContentHash(),
                artifact.getSchemaVersion(),
                artifact.getPromptKey(),
                artifact.getPromptVersion(),
                artifact.getModelProvider(),
                artifact.getModelName(),
                artifact.getSourceCitationsJson(),
                artifact.getProvenanceJson(),
                artifact.getApprovedAt(),
                artifact.getCreatedAt(),
                artifact.getUpdatedAt()
        );
    }
}
