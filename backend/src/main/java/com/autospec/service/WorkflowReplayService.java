package com.autospec.service;

import com.autospec.entity.WorkflowRun;

public interface WorkflowReplayService {

    WorkflowRun replay(long sourceRunId, ReplayCommand command);

    record ReplayCommand(
            String mode,
            Long selectedWorkflowVersionId,
            String idempotencyKey
    ) {
    }
}
