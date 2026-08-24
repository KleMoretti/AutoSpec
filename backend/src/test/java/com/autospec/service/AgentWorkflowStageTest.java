package com.autospec.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentWorkflowStageTest {

    @Test
    void keepsWorkflowOrderAndAgentIdentityInOneCatalog() {
        assertThat(AgentWorkflowStage.V1)
                .extracting(AgentWorkflowStage::agentName)
                .containsExactly(
                        "ProductManagerAgent_v1",
                        "BackendEngineerAgent_v1",
                        "ReviewerAgent_v1"
                );
        assertThat(AgentWorkflowStage.V4)
                .extracting(AgentWorkflowStage::nodeName)
                .containsExactly(
                        "product_manager",
                        "architect",
                        "backend_engineer",
                        "frontend_engineer",
                        "reviewer",
                        "evaluator"
                );
        assertThat(AgentWorkflowStage.LEGACY_GENERATION_OUTPUTS)
                .contains(AgentWorkflowStage.EVALUATOR)
                .doesNotContain(AgentWorkflowStage.ARCHITECT, AgentWorkflowStage.FRONTEND_ENGINEER);
        assertThat(AgentWorkflowStage.AFTER_PRD)
                .doesNotContain(AgentWorkflowStage.PRODUCT_MANAGER);
    }

    @Test
    void mapsEveryGenerationOutputToItsArtifactMetadata() {
        AgentGenerationResult result = new AgentGenerationResult(
                "prd",
                "architecture",
                "backend",
                "frontend",
                "review",
                "evaluation",
                List.of()
        );

        assertThat(AgentWorkflowStage.V4)
                .extracting(stage -> stage.artifactType() + ":" + stage.outputFrom(result))
                .containsExactly(
                        "PRD:prd",
                        "ARCHITECTURE_DESIGN:architecture",
                        "BACKEND_DESIGN:backend",
                        "FRONTEND_SKELETON:frontend",
                        "REVIEW_REPORT:review",
                        "EVALUATION_REPORT:evaluation"
                );
        assertThat(AgentWorkflowStage.REVIEWER.artifactTitle("AutoSpec"))
                .isEqualTo("AutoSpec Review Report");
    }

    @Test
    void resolvesKnownStagesWithoutRecreatingSwitchStatements() {
        assertThat(AgentWorkflowStage.forNode("backend_engineer"))
                .contains(AgentWorkflowStage.BACKEND_ENGINEER);
        assertThat(AgentWorkflowStage.forAgent("EvaluatorAgent_v1"))
                .contains(AgentWorkflowStage.EVALUATOR);
        assertThat(AgentWorkflowStage.forNode("custom"))
                .isEmpty();
    }
}
