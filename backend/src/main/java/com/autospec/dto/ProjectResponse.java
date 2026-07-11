package com.autospec.dto;

import com.autospec.entity.Project;

import java.time.LocalDateTime;

public record ProjectResponse(
        Long projectId,
        String name,
        String originalRequirement,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static ProjectResponse from(Project project) {
        return new ProjectResponse(
                project.getId(),
                project.getName(),
                project.getOriginalRequirement(),
                project.getStatus(),
                project.getCreatedAt(),
                project.getUpdatedAt()
        );
    }
}
