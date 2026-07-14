package com.autospec.dto;

import com.autospec.entity.WorkflowDefinition;
import com.autospec.entity.WorkflowVersion;

import java.time.LocalDateTime;

public record WorkflowVersionResponse(
        Long id,
        Long definitionId,
        String workflowKey,
        String version,
        String specJson,
        String contentHash,
        String status,
        LocalDateTime publishedAt,
        LocalDateTime createdAt
) {
    public static WorkflowVersionResponse from(WorkflowDefinition definition, WorkflowVersion version) {
        return new WorkflowVersionResponse(
                version.getId(),
                version.getDefinitionId(),
                definition.getWorkflowKey(),
                version.getVersion(),
                version.getSpecJson(),
                version.getContentHash(),
                version.getStatus(),
                version.getPublishedAt(),
                version.getCreatedAt()
        );
    }
}
