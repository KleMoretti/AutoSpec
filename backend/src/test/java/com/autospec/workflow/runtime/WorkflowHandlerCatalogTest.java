package com.autospec.workflow.runtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowHandlerCatalogTest {
    @Test
    void supportsVersionedReviewerAndEvaluatorHandlersUsedByCurrentCandidate() {
        WorkflowHandlerCatalog catalog = new WorkflowHandlerCatalog(
                "ArchitectAgent:v3,BackendEngineerAgent:v4,BackendEngineerAgent:v5,BackendEngineerAgent:v6,FrontendEngineerAgent:v3,ReviewerAgent:v3,ReviewerAgent:v4,EvaluatorAgent:v3"
        );

        assertThat(catalog.isAvailable("ArchitectAgent", "v3")).isTrue();
        assertThat(catalog.isAvailable("BackendEngineerAgent", "v4")).isTrue();
        assertThat(catalog.isAvailable("BackendEngineerAgent", "v5")).isTrue();
        assertThat(catalog.isAvailable("BackendEngineerAgent", "v6")).isTrue();
        assertThat(catalog.isAvailable("FrontendEngineerAgent", "v3")).isTrue();
        assertThat(catalog.isAvailable("ReviewerAgent", "v3")).isTrue();
        assertThat(catalog.isAvailable("ReviewerAgent", "v4")).isTrue();
        assertThat(catalog.isAvailable("EvaluatorAgent", "v3")).isTrue();
        assertThat(catalog.isAvailable("EvaluatorAgent", "v4")).isFalse();
    }
}
