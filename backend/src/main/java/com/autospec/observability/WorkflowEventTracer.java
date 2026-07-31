package com.autospec.observability;

import com.autospec.workflow.transport.WorkflowEventOutcome;
import com.autospec.workflow.transport.WorkflowExecutionEvent;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;

import java.util.LinkedHashMap;
import java.util.Map;

public final class WorkflowEventTracer {
    private static final String INSTRUMENTATION_NAME = "autospec.backend.workflow";
    private static final TextMapGetter<Map<String, String>> MAP_GETTER =
            new TextMapGetter<>() {
                @Override
                public Iterable<String> keys(Map<String, String> carrier) {
                    return carrier.keySet();
                }

                @Override
                public String get(Map<String, String> carrier, String key) {
                    return carrier.get(key);
                }
            };

    private final Tracer tracer;

    public WorkflowEventTracer(OpenTelemetry openTelemetry) {
        this.tracer = openTelemetry.getTracer(INSTRUMENTATION_NAME);
    }

    public static WorkflowEventTracer noop() {
        return new WorkflowEventTracer(OpenTelemetry.noop());
    }

    public TraceScope start(WorkflowExecutionEvent event) {
        Map<String, String> carrier = new LinkedHashMap<>();
        put(carrier, "traceparent", event.traceparent());
        put(carrier, "tracestate", event.tracestate());
        Context parent = W3CTraceContextPropagator.getInstance().extract(
                Context.current(),
                carrier,
                MAP_GETTER
        );
        Span span = tracer.spanBuilder("workflow.event.consume")
                .setParent(parent)
                .setSpanKind(SpanKind.CONSUMER)
                .startSpan();
        set(span, "messaging.system", "redis");
        set(span, "messaging.message.id", event.eventId());
        set(span, "autospec.workflow.event.type", event.eventType());
        set(span, "autospec.workflow.run.id", event.workflowRunId());
        set(span, "autospec.workflow.node.run.id", event.nodeRunId());
        set(span, "autospec.workflow.node.id", event.nodeId());
        set(span, "autospec.workflow.execution.id", event.executionId());
        set(span, "autospec.correlation.id", event.correlationId());
        return new TraceScope(span, span.makeCurrent());
    }

    private void put(Map<String, String> carrier, String key, String value) {
        if (value != null && !value.isBlank()) {
            carrier.put(key, value);
        }
    }

    private void set(Span span, String key, String value) {
        if (value != null && !value.isBlank()) {
            span.setAttribute(key, value);
        }
    }

    private void set(Span span, String key, Long value) {
        if (value != null) {
            span.setAttribute(AttributeKey.longKey(key), value);
        }
    }

    public static final class TraceScope implements AutoCloseable {
        private final Span span;
        private final Scope scope;

        private TraceScope(Span span, Scope scope) {
            this.span = span;
            this.scope = scope;
        }

        public void outcome(WorkflowEventOutcome outcome) {
            span.setAttribute("autospec.workflow.event.outcome", outcome.name());
        }

        public void error(RuntimeException exception) {
            span.recordException(exception);
            span.setStatus(StatusCode.ERROR, exception.getClass().getSimpleName());
        }

        @Override
        public void close() {
            scope.close();
            span.end();
        }
    }
}
