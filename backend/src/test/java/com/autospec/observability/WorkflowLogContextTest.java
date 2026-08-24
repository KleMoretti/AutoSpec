package com.autospec.observability;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowLogContextTest {
    private static final String TRACEPARENT =
            "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01";

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void installsWorkflowFieldsAndRestoresPreviousContext() {
        MDC.put("correlationId", "outer-correlation");

        try (WorkflowLogContext ignored = WorkflowLogContext.open(
                "workflow-correlation",
                TRACEPARENT,
                7L,
                11L,
                "7:fixture:1:1"
        )) {
            assertThat(MDC.get("traceId"))
                    .isEqualTo("0123456789abcdef0123456789abcdef");
            assertThat(MDC.get("spanId")).isEqualTo("0123456789abcdef");
            assertThat(MDC.get("correlationId")).isEqualTo("workflow-correlation");
            assertThat(MDC.get("workflowRunId")).isEqualTo("7");
            assertThat(MDC.get("nodeRunId")).isEqualTo("11");
            assertThat(MDC.get("executionId")).isEqualTo("7:fixture:1:1");
        }

        assertThat(MDC.get("correlationId")).isEqualTo("outer-correlation");
        assertThat(MDC.get("traceId")).isNull();
        assertThat(MDC.get("spanId")).isNull();
        assertThat(MDC.get("workflowRunId")).isNull();
        assertThat(MDC.get("nodeRunId")).isNull();
        assertThat(MDC.get("executionId")).isNull();
    }
}
