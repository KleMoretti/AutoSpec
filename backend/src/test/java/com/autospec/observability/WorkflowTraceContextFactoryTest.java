package com.autospec.observability;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowTraceContextFactoryTest {

    @Test
    void createsValidW3cContextWithStableTraceIdForCorrelation() {
        WorkflowTraceContextFactory factory = new WorkflowTraceContextFactory();
        String correlationId = "123e4567-e89b-12d3-a456-426614174000";

        WorkflowTraceContextFactory.Context first = factory.create(correlationId);
        WorkflowTraceContextFactory.Context second = factory.create(correlationId);

        assertThat(first.correlationId()).isEqualTo(correlationId);
        assertThat(first.traceparent()).startsWith(
                "00-123e4567e89b12d3a456426614174000-"
        );
        assertThat(WorkflowTraceContextFactory.isValidTraceparent(first.traceparent())).isTrue();
        assertThat(first.traceparent().split("-")[1])
                .isEqualTo(second.traceparent().split("-")[1]);
        assertThat(first.traceparent().split("-")[2])
                .isNotEqualTo(second.traceparent().split("-")[2]);
    }

    @Test
    void rejectsForbiddenVersionAndAllZeroIdentifiers() {
        assertThat(WorkflowTraceContextFactory.isValidTraceparent(
                "ff-123e4567e89b12d3a456426614174000-123e4567e89b12d3-01"
        )).isFalse();
        assertThat(WorkflowTraceContextFactory.isValidTraceparent(
                "00-00000000000000000000000000000000-123e4567e89b12d3-01"
        )).isFalse();
        assertThat(WorkflowTraceContextFactory.isValidTraceparent(
                "00-123e4567e89b12d3a456426614174000-0000000000000000-01"
        )).isFalse();
    }

    @Test
    void extractsTraceAndSpanIdentifiersFromValidTraceparent() {
        String traceparent = "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01";

        assertThat(WorkflowTraceContextFactory.extractTraceId(traceparent))
                .isEqualTo("0123456789abcdef0123456789abcdef");
        assertThat(WorkflowTraceContextFactory.extractSpanId(traceparent))
                .isEqualTo("0123456789abcdef");
        assertThat(WorkflowTraceContextFactory.extractTraceId("invalid")).isNull();
        assertThat(WorkflowTraceContextFactory.extractSpanId(null)).isNull();
    }
}
