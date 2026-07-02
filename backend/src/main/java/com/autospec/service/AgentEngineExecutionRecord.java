package com.autospec.service;

public record AgentEngineExecutionRecord(
        String nodeName,
        String agentName,
        String status,
        String inputJson,
        String outputJson,
        String errorMessage,
        Integer durationMs,
        String promptKey,
        String providerKey,
        String modelName
) {

    public AgentEngineExecutionRecord(
            String agentName,
            String status,
            String inputJson,
            String outputJson,
            String errorMessage
    ) {
        this(null, agentName, status, inputJson, outputJson, errorMessage, null, null, null, null);
    }

    public AgentEngineExecutionRecord(
            String nodeName,
            String agentName,
            String status,
            String inputJson,
            String outputJson,
            String errorMessage,
            Integer durationMs,
            String promptKey
    ) {
        this(nodeName, agentName, status, inputJson, outputJson, errorMessage, durationMs, promptKey, null, null);
    }
}
