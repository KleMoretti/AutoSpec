package com.autospec.workflow.transport;

import com.autospec.entity.ProcessedWorkflowEvent;
import com.autospec.entity.WorkflowNodeRun;
import com.autospec.entity.WorkflowRun;
import com.autospec.mapper.ProcessedWorkflowEventMapper;
import com.autospec.mapper.WorkflowNodeRunMapper;
import com.autospec.mapper.WorkflowRunMapper;
import com.autospec.workflow.runtime.RetryPolicyEvaluator;
import com.autospec.workflow.runtime.WorkflowApprovalCoordinator;
import com.autospec.workflow.runtime.WorkflowArtifactProjector;
import com.autospec.workflow.runtime.WorkflowFailureDecisionService;
import com.autospec.workflow.runtime.ReviewerReworkCoordinator;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

public class WorkflowEventConsumer {
    private final ProcessedWorkflowEventMapper processedEventMapper;
    private final WorkflowNodeRunMapper nodeRunMapper;
    private final WorkflowRunMapper runMapper;
    private final WorkflowRunReconciliationTrigger reconciliationTrigger;
    private final WorkflowFailureDecisionService failureDecisionService;
    private final ObjectMapper objectMapper;
    private final WorkflowApprovalCoordinator approvalCoordinator;
    private final WorkflowArtifactProjector artifactProjector;
    private final ReviewerReworkCoordinator reworkCoordinator;

    public WorkflowEventConsumer(
            ProcessedWorkflowEventMapper processedEventMapper,
            WorkflowNodeRunMapper nodeRunMapper,
            WorkflowRunReconciliationTrigger reconciliationTrigger,
            WorkflowFailureDecisionService failureDecisionService,
            ObjectMapper objectMapper,
            WorkflowApprovalCoordinator approvalCoordinator
    ) {
        this(
                processedEventMapper,
                nodeRunMapper,
                reconciliationTrigger,
                failureDecisionService,
                objectMapper,
                approvalCoordinator,
                WorkflowArtifactProjector.none(),
                ReviewerReworkCoordinator.none(),
                null
        );
    }

    public WorkflowEventConsumer(
            ProcessedWorkflowEventMapper processedEventMapper,
            WorkflowNodeRunMapper nodeRunMapper,
            WorkflowRunReconciliationTrigger reconciliationTrigger,
            WorkflowFailureDecisionService failureDecisionService,
            ObjectMapper objectMapper,
            WorkflowApprovalCoordinator approvalCoordinator,
            WorkflowArtifactProjector artifactProjector,
            ReviewerReworkCoordinator reworkCoordinator
    ) {
        this(
                processedEventMapper,
                nodeRunMapper,
                reconciliationTrigger,
                failureDecisionService,
                objectMapper,
                approvalCoordinator,
                artifactProjector,
                reworkCoordinator,
                null
        );
    }

    public WorkflowEventConsumer(
            ProcessedWorkflowEventMapper processedEventMapper,
            WorkflowNodeRunMapper nodeRunMapper,
            WorkflowRunReconciliationTrigger reconciliationTrigger,
            WorkflowFailureDecisionService failureDecisionService,
            ObjectMapper objectMapper,
            WorkflowApprovalCoordinator approvalCoordinator,
            WorkflowArtifactProjector artifactProjector,
            ReviewerReworkCoordinator reworkCoordinator,
            WorkflowRunMapper runMapper
    ) {
        this.processedEventMapper = processedEventMapper;
        this.nodeRunMapper = nodeRunMapper;
        this.runMapper = runMapper;
        this.reconciliationTrigger = reconciliationTrigger;
        this.failureDecisionService = failureDecisionService;
        this.objectMapper = objectMapper;
        this.approvalCoordinator = approvalCoordinator;
        this.artifactProjector = artifactProjector;
        this.reworkCoordinator = reworkCoordinator;
    }

    public WorkflowEventConsumer(
            ProcessedWorkflowEventMapper processedEventMapper,
            WorkflowNodeRunMapper nodeRunMapper,
            WorkflowRunReconciliationTrigger reconciliationTrigger,
            WorkflowFailureDecisionService failureDecisionService,
            ObjectMapper objectMapper
    ) {
        this(
                processedEventMapper,
                nodeRunMapper,
                reconciliationTrigger,
                failureDecisionService,
                objectMapper,
                null,
                WorkflowArtifactProjector.none(),
                ReviewerReworkCoordinator.none(),
                null
        );
    }

    @Transactional
    public WorkflowEventOutcome consume(String payloadJson) {
        WorkflowExecutionEvent event = parse(payloadJson);
        if (processedEventMapper.selectCount(new QueryWrapper<ProcessedWorkflowEvent>()
                .eq("event_id", event.eventId())) > 0) {
            recordDuplicate(event.workflowRunId());
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
            WorkflowNodeRun nodeRun = nodeRunMapper.selectById(event.nodeRunId());
            return nodeRunMapper.update(null, update
                    .set("status", "RUNNING")
                    .set(nodeRun != null && nodeRun.getStartedAt() == null, "started_at", now)
                    .set("heartbeat_at", now)
                    .set("updated_at", now));
        }
        if ("NODE_SUCCEEDED".equals(event.eventType())) {
            WorkflowNodeRun currentNodeRun = nodeRunMapper.selectById(event.nodeRunId());
            if (approvalCoordinator != null) {
                if (currentNodeRun == null) {
                    return 0;
                }
                Integer paused = approvalCoordinator.pauseAfterIfRequired(
                        currentNodeRun,
                        event.executionId(),
                        event.outputPayload() == null ? null : event.outputPayload().toString(),
                        now
                );
                if (paused != null) {
                    persistTiming(event, now, currentNodeRun);
                    return paused;
                }
            }
            int succeeded = nodeRunMapper.update(null, update
                    .set("status", "SUCCEEDED")
                    .set("duration_ms", event.durationMs())
                    .set(currentNodeRun == null || currentNodeRun.getStartedAt() == null,
                            "started_at", startedAt(event, now))
                    .set("output_json", event.outputPayload() == null ? null : event.outputPayload().toString())
                    .set("finished_at", now)
                    .set("updated_at", now)
                    .setSql("lock_version = lock_version + 1"));
            if (succeeded == 1) {
                WorkflowNodeRun nodeRun = nodeRunMapper.selectById(event.nodeRunId());
                String outputJson = event.outputPayload() == null
                        ? null
                        : event.outputPayload().toString();
                artifactProjector.project(
                        nodeRun,
                        outputJson,
                        "GENERATED"
                );
                reworkCoordinator.applyIfRequested(nodeRun, outputJson);
            }
            return succeeded;
        }
        if ("NODE_FAILED".equals(event.eventType())) {
            WorkflowNodeRun nodeRun = nodeRunMapper.selectById(event.nodeRunId());
            if (nodeRun == null) {
                return 0;
            }
            RetryPolicyEvaluator.Decision decision = failureDecisionService.decide(
                    nodeRun, event.errorCode(), now
            );
            UpdateWrapper<WorkflowNodeRun> failedUpdate = update
                    .set("error_code", event.errorCode())
                    .set("error_message", event.errorMessage())
                    .set("duration_ms", event.durationMs())
                    .set(nodeRun.getStartedAt() == null, "started_at", startedAt(event, now))
                    .set("finished_at", now)
                    .set("updated_at", now)
                    .setSql("lock_version = lock_version + 1");
            if (decision.action() == RetryPolicyEvaluator.Action.RETRY) {
                failedUpdate.set("status", "RETRY_WAIT")
                        .set("next_retry_at", decision.nextRetryAt());
            } else if (decision.action() == RetryPolicyEvaluator.Action.FALLBACK) {
                failedUpdate.set("status", "FALLBACK_READY")
                        .set("handler_key", decision.handlerKey())
                        .set("next_retry_at", decision.nextRetryAt());
            } else {
                failedUpdate.set("status", "FAILED");
            }
            return nodeRunMapper.update(null, failedUpdate);
        }
        throw new IllegalArgumentException("Unsupported workflow event type: " + event.eventType());
    }

    private LocalDateTime startedAt(WorkflowExecutionEvent event, LocalDateTime now) {
        long durationMs = event.durationMs() == null ? 0 : Math.max(0, event.durationMs());
        return now.minusNanos(durationMs * 1_000_000L);
    }

    private void persistTiming(
            WorkflowExecutionEvent event,
            LocalDateTime now,
            WorkflowNodeRun nodeRun
    ) {
        nodeRunMapper.update(null, new UpdateWrapper<WorkflowNodeRun>()
                .eq("id", event.nodeRunId())
                .set("duration_ms", event.durationMs())
                .set(nodeRun.getStartedAt() == null, "started_at", startedAt(event, now))
                .set("updated_at", now));
    }

    private void recordDuplicate(long workflowRunId) {
        if (runMapper == null) {
            return;
        }
        runMapper.update(null, new UpdateWrapper<WorkflowRun>()
                .eq("id", workflowRunId)
                .setSql("accepted_duplicate_event_count = accepted_duplicate_event_count + 1")
                .set("updated_at", LocalDateTime.now()));
    }

    private WorkflowExecutionEvent parse(String payloadJson) {
        try {
            return objectMapper.readValue(payloadJson, WorkflowExecutionEvent.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid workflow event payload", exception);
        }
    }
}
