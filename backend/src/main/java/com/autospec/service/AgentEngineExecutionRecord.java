package com.autospec.service;

public record AgentEngineExecutionRecord(
        String nodeName,
        String agentName,
        String status,
        String inputJson,
        String outputJson,
        String errorMessage,
        Integer durationMs,
        String promptKey
) {

    public AgentEngineExecutionRecord(
            String agentName,
            String status,
            String inputJson,
            String outputJson,
            String errorMessage
    ) {
        this(null, agentName, status, inputJson, outputJson, errorMessage, null, null);
    }
}
