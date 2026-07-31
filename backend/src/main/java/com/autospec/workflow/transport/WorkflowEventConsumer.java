package com.autospec.workflow.transport;

import com.autospec.entity.ProcessedWorkflowEvent;
import com.autospec.entity.WorkflowNodeRun;
import com.autospec.entity.WorkflowRun;
import com.autospec.mapper.ProcessedWorkflowEventMapper;
import com.autospec.mapper.WorkflowNodeRunMapper;
import com.autospec.mapper.WorkflowRunMapper;
import com.autospec.observability.WorkflowEventTracer;
import com.autospec.observability.WorkflowLogContext;
import com.autospec.workflow.runtime.RetryPolicyEvaluator;
import com.autospec.workflow.runtime.WorkflowApprovalCoordinator;
import com.autospec.workflow.runtime.WorkflowArtifactProjector;
import com.autospec.workflow.runtime.WorkflowFailureDecisionService;
import com.autospec.workflow.runtime.ReviewerReworkCoordinator;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

public class WorkflowEventConsumer {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkflowEventConsumer.class);

    private final ProcessedWorkflowEventMapper processedEventMapper;
    private final WorkflowNodeRunMapper nodeRunMapper;
    private final WorkflowRunMapper runMapper;
    private final WorkflowRunReconciliationTrigger reconciliationTrigger;
    private final WorkflowFailureDecisionService failureDecisionService;
    private final ObjectMapper objectMapper;
    private final WorkflowApprovalCoordinator approvalCoordinator;
    private final WorkflowArtifactProjector artifactProjector;
    private final ReviewerReworkCoordinator reworkCoordinator;
    private final WorkflowEventTracer eventTracer;

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
        this(
                processedEventMapper,
                nodeRunMapper,
                reconciliationTrigger,
                failureDecisionService,
                objectMapper,
                approvalCoordinator,
                artifactProjector,
                reworkCoordinator,
                runMapper,
                WorkflowEventTracer.noop()
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
            WorkflowRunMapper runMapper,
            WorkflowEventTracer eventTracer
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
        this.eventTracer = eventTracer;
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
        WorkflowEventTracer.TraceScope traceScope = eventTracer.start(event);
        try (traceScope; WorkflowLogContext ignored = WorkflowLogContext.open(
                event.correlationId(),
                event.traceparent(),
                event.workflowRunId(),
                event.nodeRunId(),
                event.executionId()
        )) {
            try {
                WorkflowEventOutcome outcome = consume(event);
                traceScope.outcome(outcome);
                logOutcome(event, outcome);
                return outcome;
            } catch (RuntimeException exception) {
                traceScope.error(exception);
                throw exception;
            }
        }
    }

    private WorkflowEventOutcome consume(WorkflowExecutionEvent event) {
        ProcessedWorkflowEvent processed = new ProcessedWorkflowEvent();
        processed.setEventId(event.eventId());
        processed.setEventType(event.eventType());
        processed.setProcessedAt(LocalDateTime.now());
        if (processedEventMapper.insertIfAbsent(processed) == 0) {
            recordDuplicate(event.workflowRunId());
            return WorkflowEventOutcome.DUPLICATE;
        }

        int updated = apply(event);
        if (updated == 0) {
            return WorkflowEventOutcome.STALE;
        }
        if (event.isTerminal()) {
            reconciliationTrigger.reconcile(event.workflowRunId());
        }
        return WorkflowEventOutcome.ACCEPTED;
    }

    private void logOutcome(WorkflowExecutionEvent event, WorkflowEventOutcome outcome) {
        if ("NODE_HEARTBEAT".equals(event.eventType())) {
            LOGGER.debug(
                    "workflow event consumed outcome={} eventType={}",
                    outcome,
                    event.eventType()
            );
            return;
        }
        LOGGER.info(
                "workflow event consumed outcome={} eventType={}",
                outcome,
                event.eventType()
        );
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
