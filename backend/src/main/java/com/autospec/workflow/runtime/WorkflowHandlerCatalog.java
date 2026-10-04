package com.autospec.workflow.runtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class WorkflowHandlerCatalog {
    private final Set<String> availableHandlers;

    public WorkflowHandlerCatalog(@Value("${autospec.workflow.available-handlers:ProductManagerAgent:v1,ProductManagerAgent:v2,ArchitectAgent:v1,ArchitectAgent:v2,ArchitectAgent:v3,BackendEngineerAgent:v1,BackendEngineerAgent:v2,BackendEngineerAgent:v3,BackendEngineerAgent:v4,BackendEngineerAgent:v5,BackendEngineerAgent:v6,BackendEngineerAgent:v7,BackendEngineerAgent:v8,BackendEngineerAgent:v9,BackendEngineerAgent:v10,FrontendEngineerAgent:v1,FrontendEngineerAgent:v2,FrontendEngineerAgent:v3,FrontendEngineerAgent:v4,FrontendEngineerAgent:v5,FrontendEngineerAgent:v6,FrontendEngineerAgent:v7,FrontendEngineerAgent:v8,FrontendEngineerAgent:v9,FrontendEngineerAgent:v10,ReviewerAgent:v1,ReviewerAgent:v2,ReviewerAgent:v3,ReviewerAgent:v4,ReviewerAgent:v5,EvaluatorAgent:v1,EvaluatorAgent:v2,EvaluatorAgent:v3,EvaluatorAgent:v4}") String configuredHandlers) {
        availableHandlers = Arrays.stream(configuredHandlers.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean isAvailable(String handlerKey, String handlerVersion) {
        return handlerKey != null
                && handlerVersion != null
                && availableHandlers.contains(handlerKey + ":" + handlerVersion);
    }
}
