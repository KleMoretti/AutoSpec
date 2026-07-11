package com.autospec.workflow.runtime;

public record QueuedNodeCommand(
        String eventId,
        long workflowRunId,
        long nodeRunId,
        String nodeId,
        int revision,
        int attempt,
        String executionId
) {
}
