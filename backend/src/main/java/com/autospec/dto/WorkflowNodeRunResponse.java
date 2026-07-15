package com.autospec.dto;

import com.autospec.entity.WorkflowNodeRun;

import java.time.LocalDateTime;

public record WorkflowNodeRunResponse(
        Long id,
        Long workflowRunId,
        String nodeId,
        Integer revision,
        Integer attempt,
        String executionId,
        String status,
        String handlerKey,
        String handlerVersion,
        Integer timeoutMs,
        Integer durationMs,
        String inputJson,
        String outputJson,
        String errorCode,
        String errorMessage,
        LocalDateTime queuedAt,
        LocalDateTime startedAt,
        LocalDateTime heartbeatAt,
        LocalDateTime finishedAt,
        String workerId
) {
    public static WorkflowNodeRunResponse from(WorkflowNodeRun run) {
        return new WorkflowNodeRunResponse(
                run.getId(),
                run.getWorkflowRunId(),
                run.getNodeId(),
                run.getRevision(),
                run.getAttempt(),
                run.getExecutionId(),
                run.getStatus(),
                run.getHandlerKey(),
                run.getHandlerVersion(),
                run.getTimeoutMs(),
                run.getDurationMs(),
                run.getInputJson(),
                run.getOutputJson(),
                run.getErrorCode(),
                run.getErrorMessage(),
                run.getQueuedAt(),
                run.getStartedAt(),
                run.getHeartbeatAt(),
                run.getFinishedAt(),
                run.getWorkerId()
        );
    }
}
