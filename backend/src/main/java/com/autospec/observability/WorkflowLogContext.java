package com.autospec.observability;

import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.Map;

public final class WorkflowLogContext implements AutoCloseable {
    private static final String[] KEYS = {
            "traceId",
            "spanId",
            "correlationId",
            "workflowRunId",
            "nodeRunId",
            "executionId"
    };

    private final Map<String, String> previousValues = new LinkedHashMap<>();

    private WorkflowLogContext(
            String correlationId,
            String traceparent,
            long workflowRunId,
            long nodeRunId,
            String executionId
    ) {
        for (String key : KEYS) {
            previousValues.put(key, MDC.get(key));
        }
        put("traceId", WorkflowTraceContextFactory.extractTraceId(traceparent));
        put("spanId", WorkflowTraceContextFactory.extractSpanId(traceparent));
        put("correlationId", correlationId);
        put("workflowRunId", Long.toString(workflowRunId));
        put("nodeRunId", Long.toString(nodeRunId));
        put("executionId", executionId);
    }

    public static WorkflowLogContext open(
            String correlationId,
            String traceparent,
            long workflowRunId,
            long nodeRunId,
            String executionId
    ) {
        return new WorkflowLogContext(
                correlationId,
                traceparent,
                workflowRunId,
                nodeRunId,
                executionId
        );
    }

    @Override
    public void close() {
        previousValues.forEach((key, value) -> {
            if (value == null) {
                MDC.remove(key);
            } else {
                MDC.put(key, value);
            }
        });
    }

    private void put(String key, String value) {
        if (value == null || value.isBlank()) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }
}
