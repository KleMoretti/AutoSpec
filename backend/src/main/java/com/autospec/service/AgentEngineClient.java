package com.autospec.service;

import com.autospec.dto.KnowledgeSourceResponse;

import java.util.List;

public interface AgentEngineClient {

    AgentGenerationResult generate(String requirement);

    default AgentGenerationResult generate(String requirement, List<KnowledgeSourceResponse> retrievedSources) {
        return generate(requirement);
    }

    default AgentGenerationResult generateV4(String requirement, List<KnowledgeSourceResponse> retrievedSources) {
        return generate(requirement, retrievedSources);
    }

    AgentGenerationResult generatePrd(String requirement);

    default AgentGenerationResult generatePrd(String requirement, List<KnowledgeSourceResponse> retrievedSources) {
        return generatePrd(requirement);
    }

    AgentGenerationResult continueAfterPrd(String requirement, String approvedPrdJson);

    default AgentGenerationResult continueAfterPrd(
            String requirement,
            String approvedPrdJson,
            List<KnowledgeSourceResponse> retrievedSources
    ) {
        return continueAfterPrd(requirement, approvedPrdJson);
    }

    AgentEngineExecutionRecord runNode(String nodeName, String inputJson);
}
