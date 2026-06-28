package com.autospec.service;

public interface AgentEngineClient {

    AgentGenerationResult generate(String requirement);

    AgentGenerationResult generatePrd(String requirement);

    AgentGenerationResult continueAfterPrd(String requirement, String approvedPrdJson);

    AgentEngineExecutionRecord runNode(String nodeName, String inputJson);
}
