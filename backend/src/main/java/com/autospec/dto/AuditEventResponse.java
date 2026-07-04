package com.autospec.dto;

import com.autospec.entity.AuditEvent;

import java.time.LocalDateTime;

public record AuditEventResponse(
        Long id,
        Long projectId,
        Long actorUserId,
        String eventType,
        String entityType,
        Long entityId,
        String message,
        String metadata,
        LocalDateTime createdAt
) {

    public static AuditEventResponse from(AuditEvent event) {
        return new AuditEventResponse(
                event.getId(),
                event.getProjectId(),
                event.getActorUserId(),
                event.getEventType(),
                event.getEntityType(),
                event.getEntityId(),
                event.getMessage(),
                event.getMetadata(),
                event.getCreatedAt()
        );
    }
}
