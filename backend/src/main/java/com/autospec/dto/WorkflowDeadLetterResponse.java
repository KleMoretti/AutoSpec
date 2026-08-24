package com.autospec.dto;

import com.autospec.entity.WorkflowOutbox;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;

public record WorkflowDeadLetterResponse(
        Long id,
        Long workflowRunId,
        Long workflowNodeRunId,
        String nodeId,
        String executionId,
        String eventId,
        String eventType,
        String status,
        Integer retryCount,
        String lastErrorType,
        LocalDateTime lastErrorAt,
        LocalDateTime deadLetteredAt,
        LocalDateTime closedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static WorkflowDeadLetterResponse from(
            long workflowRunId,
            WorkflowOutbox outbox,
            ObjectMapper objectMapper
    ) {
        CommandSummary command = summarize(outbox.getPayloadJson(), objectMapper);
        return new WorkflowDeadLetterResponse(
                outbox.getId(),
                workflowRunId,
                command.workflowNodeRunId(),
                command.nodeId(),
                command.executionId(),
                outbox.getEventId(),
                outbox.getEventType(),
                outbox.getStatus(),
                outbox.getRetryCount(),
                outbox.getLastErrorType(),
                outbox.getLastErrorAt(),
                outbox.getDeadLetteredAt(),
                outbox.getClosedAt(),
                outbox.getCreatedAt(),
                outbox.getUpdatedAt()
        );
    }

    private static CommandSummary summarize(String payloadJson, ObjectMapper objectMapper) {
        try {
            JsonNode payload = objectMapper.readTree(payloadJson);
            return new CommandSummary(
                    nullableLong(payload, "node_run_id"),
                    nullableText(payload, "node_id"),
                    nullableText(payload, "execution_id")
            );
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            return new CommandSummary(null, null, null);
        }
    }

    private static Long nullableLong(JsonNode payload, String fieldName) {
        JsonNode value = payload == null ? null : payload.get(fieldName);
        return value != null && value.canConvertToLong() ? value.longValue() : null;
    }

    private static String nullableText(JsonNode payload, String fieldName) {
        JsonNode value = payload == null ? null : payload.get(fieldName);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private record CommandSummary(Long workflowNodeRunId, String nodeId, String executionId) {
    }
}
