package com.autospec.dto;

import com.autospec.entity.AgentTask;

public record RetryTaskResponse(
        Long taskId,
        String agentName,
        String nodeName,
        String status,
        Long retryOfTaskId
) {

    public static RetryTaskResponse from(AgentTask task) {
        return new RetryTaskResponse(
                task.getId(),
                task.getAgentName(),
                task.getNodeName(),
                task.getStatus(),
                task.getRetryOfTaskId()
        );
    }
}
