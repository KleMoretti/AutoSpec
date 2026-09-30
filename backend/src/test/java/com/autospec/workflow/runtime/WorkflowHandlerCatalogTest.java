package com.autospec.workflow.runtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowHandlerCatalogTest {
    @Test
    void supportsVersionedReviewerAndEvaluatorHandlersUsedByCurrentCandidate() {
        WorkflowHandlerCatalog catalog = new WorkflowHandlerCatalog(
                "ReviewerAgent:v3,EvaluatorAgent:v3"
        );

        assertThat(catalog.isAvailable("ReviewerAgent", "v3")).isTrue();
        assertThat(catalog.isAvailable("EvaluatorAgent", "v3")).isTrue();
        assertThat(catalog.isAvailable("EvaluatorAgent", "v4")).isFalse();
    }
}
