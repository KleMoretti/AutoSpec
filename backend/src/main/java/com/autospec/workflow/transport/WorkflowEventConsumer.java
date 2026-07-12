package com.autospec.workflow.transport;

import com.autospec.entity.ProcessedWorkflowEvent;
import com.autospec.entity.WorkflowNodeRun;
import com.autospec.mapper.ProcessedWorkflowEventMapper;
import com.autospec.mapper.WorkflowNodeRunMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

public class WorkflowEventConsumer {
    private final ProcessedWorkflowEventMapper processedEventMapper;
    private final WorkflowNodeRunMapper nodeRunMapper;
    private final WorkflowRunReconciliationTrigger reconciliationTrigger;
    private final ObjectMapper objectMapper;

    public WorkflowEventConsumer(
            ProcessedWorkflowEventMapper processedEventMapper,
            WorkflowNodeRunMapper nodeRunMapper,
            WorkflowRunReconciliationTrigger reconciliationTrigger,
            ObjectMapper objectMapper
    ) {
        this.processedEventMapper = processedEventMapper;
        this.nodeRunMapper = nodeRunMapper;
        this.reconciliationTrigger = reconciliationTrigger;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public WorkflowEventOutcome consume(String payloadJson) {
        WorkflowExecutionEvent event = parse(payloadJson);
        if (processedEventMapper.selectCount(new QueryWrapper<ProcessedWorkflowEvent>()
                .eq("event_id", event.eventId())) > 0) {
            return WorkflowEventOutcome.DUPLICATE;
        }

        ProcessedWorkflowEvent processed = new ProcessedWorkflowEvent();
        processed.setEventId(event.eventId());
        processed.setEventType(event.eventType());
        processed.setProcessedAt(LocalDateTime.now());
        processedEventMapper.insert(processed);

        int updated = apply(event);
        if (updated == 0) {
            return WorkflowEventOutcome.STALE;
        }
        if (event.isTerminal()) {
            reconciliationTrigger.reconcile(event.workflowRunId());
        }
        return WorkflowEventOutcome.ACCEPTED;
    }

    private int apply(WorkflowExecutionEvent event) {
        LocalDateTime now = LocalDateTime.now();
        UpdateWrapper<WorkflowNodeRun> update = new UpdateWrapper<WorkflowNodeRun>()
                .eq("id", event.nodeRunId())
                .eq("execution_id", event.executionId())
                .in("status", "QUEUED", "RUNNING");
        if ("NODE_HEARTBEAT".equals(event.eventType())) {
            return nodeRunMapper.update(null, update
                    .set("status", "RUNNING")
                    .set("heartbeat_at", now)
                    .set("updated_at", now));
        }
        if ("NODE_SUCCEEDED".equals(event.eventType())) {
            return nodeRunMapper.update(null, update
                    .set("status", "SUCCEEDED")
                    .set("output_json", event.outputPayload() == null ? null : event.outputPayload().toString())
                    .set("finished_at", now)
                    .set("updated_at", now)
                    .setSql("lock_version = lock_version + 1"));
        }
        if ("NODE_FAILED".equals(event.eventType())) {
            return nodeRunMapper.update(null, update
                    .set("status", "FAILED")
                    .set("error_code", event.errorCode())
                    .set("error_message", event.errorMessage())
                    .set("finished_at", now)
                    .set("updated_at", now)
                    .setSql("lock_version = lock_version + 1"));
        }
        throw new IllegalArgumentException("Unsupported workflow event type: " + event.eventType());
    }

    private WorkflowExecutionEvent parse(String payloadJson) {
        try {
            return objectMapper.readValue(payloadJson, WorkflowExecutionEvent.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid workflow event payload", exception);
        }
    }
}
