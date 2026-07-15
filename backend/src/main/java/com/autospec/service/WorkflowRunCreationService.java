package com.autospec.service;

import com.autospec.entity.WorkflowRun;

public interface WorkflowRunCreationService {
    WorkflowRun start(StartCommand command);

    record StartCommand(
            long projectId,
            long workflowVersionId,
            String inputJson,
            String idempotencyKey
    ) {
    }
}
