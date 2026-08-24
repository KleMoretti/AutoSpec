package com.autospec.service;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

record AgentWorkflowStage(
        String nodeName,
        String agentName,
        String artifactType,
        String titleSuffix,
        Function<AgentGenerationResult, String> output
) {
    static final AgentWorkflowStage PRODUCT_MANAGER = stage(
            "product_manager", "ProductManagerAgent_v1", "PRD", " PRD", AgentGenerationResult::prdJson);
    static final AgentWorkflowStage ARCHITECT = stage(
            "architect", "ArchitectAgent_v1", "ARCHITECTURE_DESIGN", " Architecture Design",
            AgentGenerationResult::architectureDesignJson);
    static final AgentWorkflowStage BACKEND_ENGINEER = stage(
            "backend_engineer", "BackendEngineerAgent_v1", "BACKEND_DESIGN", " Backend Design",
            AgentGenerationResult::backendDesignJson);
    static final AgentWorkflowStage FRONTEND_ENGINEER = stage(
            "frontend_engineer", "FrontendEngineerAgent_v1", "FRONTEND_SKELETON", " Frontend Skeleton",
            AgentGenerationResult::frontendSkeletonJson);
    static final AgentWorkflowStage REVIEWER = stage(
            "reviewer", "ReviewerAgent_v1", "REVIEW_REPORT", " Review Report",
            AgentGenerationResult::reviewReportJson);
    static final AgentWorkflowStage EVALUATOR = stage(
            "evaluator", "EvaluatorAgent_v1", "EVALUATION_REPORT", " Evaluation Report",
            AgentGenerationResult::evaluationReportJson);

    static final List<AgentWorkflowStage> V1 = List.of(PRODUCT_MANAGER, BACKEND_ENGINEER, REVIEWER);
    static final List<AgentWorkflowStage> LEGACY_GENERATION_OUTPUTS = List.of(
            PRODUCT_MANAGER, BACKEND_ENGINEER, REVIEWER, EVALUATOR);
    static final List<AgentWorkflowStage> V2 = List.of(
            PRODUCT_MANAGER, ARCHITECT, BACKEND_ENGINEER, FRONTEND_ENGINEER, REVIEWER);
    static final List<AgentWorkflowStage> V4 = List.of(
            PRODUCT_MANAGER, ARCHITECT, BACKEND_ENGINEER, FRONTEND_ENGINEER, REVIEWER, EVALUATOR);
    static final List<AgentWorkflowStage> AFTER_PRD = List.of(
            ARCHITECT, BACKEND_ENGINEER, FRONTEND_ENGINEER, REVIEWER, EVALUATOR);

    String promptKey() {
        return agentName.replaceFirst("_v\\d+$", "");
    }

    String artifactTitle(String projectName) {
        return projectName + titleSuffix;
    }

    String outputFrom(AgentGenerationResult result) {
        return output.apply(result);
    }

    boolean matches(String candidateNodeName, String candidateAgentName) {
        return nodeName.equals(candidateNodeName) || agentName.equals(candidateAgentName);
    }

    static Optional<AgentWorkflowStage> forNode(String nodeName) {
        return V4.stream().filter(stage -> stage.nodeName.equals(nodeName)).findFirst();
    }

    static Optional<AgentWorkflowStage> forAgent(String agentName) {
        return V4.stream().filter(stage -> stage.agentName.equals(agentName)).findFirst();
    }

    private static AgentWorkflowStage stage(
            String nodeName,
            String agentName,
            String artifactType,
            String titleSuffix,
            Function<AgentGenerationResult, String> output
    ) {
        return new AgentWorkflowStage(nodeName, agentName, artifactType, titleSuffix, output);
    }
}
