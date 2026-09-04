package com.autospec.dto;

import java.time.LocalDateTime;

public record WorkflowFailureCaseResponse(
        String caseId,
        Long workflowRunId,
        Long nodeRunId,
        String nodeId,
        String handlerKey,
        String handlerVersion,
        String modelName,
        String promptKey,
        String promptVersion,
        String routeKey,
        String errorCode,
        LocalDateTime createdAt
) {
}
