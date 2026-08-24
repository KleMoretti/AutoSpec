package com.autospec.service;

import com.autospec.entity.WorkflowRun;

import java.math.BigDecimal;

public interface WorkflowRunCreationService {
    WorkflowRun start(StartCommand command);

    record StartCommand(
            long projectId,
            long workflowVersionId,
            String inputJson,
            String idempotencyKey,
            String qualityProfile,
            Long maxTokens,
            BigDecimal maxCost,
            Integer maxModelCalls,
            Long maxWallTimeMs
    ) {
        public StartCommand(
                long projectId,
                long workflowVersionId,
                String inputJson,
                String idempotencyKey
        ) {
            this(projectId, workflowVersionId, inputJson, idempotencyKey,
                    null, null, null, null, null);
        }
    }
}
