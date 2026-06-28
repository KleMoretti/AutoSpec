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
        String status,
        String sourceAgent,
        Long parentArtifactId,
        LocalDateTime approvedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public ArtifactResponse(Long id, String type, String title, String content, String format, Integer version) {
        this(id, type, title, content, format, version, null, null, null, null, null, null);
    }

    public static ArtifactResponse from(Artifact artifact) {
        return new ArtifactResponse(
                artifact.getId(),
                artifact.getType(),
                artifact.getTitle(),
                artifact.getContent(),
                artifact.getFormat(),
                artifact.getVersion(),
                artifact.getStatus(),
                artifact.getSourceAgent(),
                artifact.getParentArtifactId(),
                artifact.getApprovedAt(),
                artifact.getCreatedAt(),
                artifact.getUpdatedAt()
        );
    }
}
