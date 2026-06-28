package com.autospec.dto;

import com.autospec.entity.AgentEvent;

import java.time.LocalDateTime;

public record AgentEventResponse(
        Long id,
        Long projectId,
        Long taskId,
        String eventType,
        String nodeName,
        String message,
        String payload,
        LocalDateTime createdAt
) {

    public static AgentEventResponse from(AgentEvent event) {
        return new AgentEventResponse(
                event.getId(),
                event.getProjectId(),
                event.getTaskId(),
                event.getEventType(),
                event.getNodeName(),
                event.getMessage(),
                event.getPayload(),
                event.getCreatedAt()
        );
    }
}
