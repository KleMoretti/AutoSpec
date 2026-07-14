package com.autospec.workflow.runtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class WorkflowHandlerCatalog {
    private final Set<String> availableHandlers;

    public WorkflowHandlerCatalog(@Value("${autospec.workflow.available-handlers:FixtureAgent:v1,ProductManagerAgent:v1,ArchitectAgent:v1,BackendEngineerAgent:v2,FrontendEngineerAgent:v1,ReviewerAgent:v1,EvaluatorAgent:v1,author_agent:v1,reviewer_agent:v1}") String configuredHandlers) {
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
