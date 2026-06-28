package com.autospec.dto;

public record AgentStepStatus(
        Long taskId,
        String agentName,
        String nodeName,
        String status,
        Integer durationMs,
        Long retryOfTaskId,
        String errorMessage
) {

    public AgentStepStatus(String agentName, String status) {
        this(null, agentName, null, status, null, null, null);
    }
}
