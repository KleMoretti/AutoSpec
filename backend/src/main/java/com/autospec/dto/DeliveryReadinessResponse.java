package com.autospec.dto;

import java.util.List;

public record DeliveryReadinessResponse(
        boolean specReady,
        boolean buildReady,
        String status,
        Long workflowRunId,
        Long codeGenerationJobId,
        List<String> blockers
) {
}
