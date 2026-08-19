package com.autospec.integration;

import com.autospec.entity.Project;
import com.autospec.entity.WorkflowNodeRun;
import com.autospec.entity.WorkflowOutbox;
import com.autospec.entity.WorkflowRun;
import com.autospec.mapper.ProcessedWorkflowEventMapper;
import com.autospec.mapper.WorkflowNodeRunMapper;
import com.autospec.mapper.WorkflowOutboxMapper;
import com.autospec.mapper.WorkflowRunMapper;
import com.autospec.service.ProjectService;
import com.autospec.workflow.runtime.ReviewerReworkCoordinator;
import com.autospec.workflow.runtime.WorkflowArtifactProjector;
import com.autospec.workflow.runtime.WorkflowFailureDecisionService;
import com.autospec.workflow.runtime.WorkflowRecoveryService;
import com.autospec.workflow.transport.WorkflowEventConsumer;
import com.autospec.workflow.transport.WorkflowEventOutcome;
import com.autospec.workflow.transport.WorkflowRunReconciliationTrigger;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class ModelTimeoutRecoveryIT {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModelTimeoutRecoveryIT.class);
    private static final Duration FAILURE_HANDLING_RTO = Duration.ofSeconds(5);

    @Autowired
    private ProjectService projectService;

    @Autowired
    private WorkflowRunMapper runMapper;

    @Autowired
    private WorkflowNodeRunMapper nodeRunMapper;

    @Autowired
    private WorkflowOutboxMapper outboxMapper;

    @Autowired
    private ProcessedWorkflowEventMapper processedEventMapper;

    @Autowired
    private WorkflowFailureDecisionService failureDecisionService;

    @Autowired
    private WorkflowRecoveryService recoveryService;

    @Autowired
    private WorkflowRunReconciliationTrigger reconciliationTrigger;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void modelTimeoutRetriesOnceThenFailsClosedWithoutDuplicateProgression() {
        WorkflowNodeRun firstAttempt = runningNode();
        WorkflowEventConsumer consumer = consumer();
        Instant failureAt = Instant.now();

        WorkflowEventOutcome firstOutcome = consumer.consume(timeoutEvent(firstAttempt));
        WorkflowNodeRun retryWait = nodeRunMapper.selectById(firstAttempt.getId());
        long detectionMillis = Duration.between(failureAt, Instant.now()).toMillis();

        assertThat(firstOutcome).isEqualTo(WorkflowEventOutcome.ACCEPTED);
        assertThat(retryWait.getStatus()).isEqualTo("RETRY_WAIT");
        assertThat(retryWait.getErrorCode()).isEqualTo("MODEL_TIMEOUT");
        assertThat(retryWait.getNextRetryAt()).isNotNull();

        recoveryService.recover(
                retryWait.getNextRetryAt().plusSeconds(1),
                Duration.ofSeconds(30)
        );
        WorkflowNodeRun secondAttempt = attempts(firstAttempt.getWorkflowRunId()).stream()
                .filter(node -> node.getAttempt() == 2)
                .findFirst()
                .orElseThrow();
        long retryReadyMillis = Duration.between(failureAt, Instant.now()).toMillis();

        assertThat(secondAttempt.getStatus()).isEqualTo("QUEUED");
        assertThat(outboxes(firstAttempt.getWorkflowRunId()))
                .singleElement()
                .satisfies(outbox -> assertThat(outbox.getPayloadJson())
                        .contains("\"node_run_id\":" + secondAttempt.getId()));

        String finalTimeoutEvent = timeoutEvent(secondAttempt);
        assertThat(consumer.consume(finalTimeoutEvent)).isEqualTo(WorkflowEventOutcome.ACCEPTED);
        assertThat(consumer.consume(finalTimeoutEvent)).isEqualTo(WorkflowEventOutcome.STALE);

        WorkflowNodeRun failed = nodeRunMapper.selectById(secondAttempt.getId());
        WorkflowRun failedRun = runMapper.selectById(firstAttempt.getWorkflowRunId());
        int retryAttempts = attempts(firstAttempt.getWorkflowRunId()).size() - 1;
        int acceptedDuplicates = failedRun.getAcceptedDuplicateEventCount();
        long finalizationMillis = Duration.between(failureAt, Instant.now()).toMillis();
        LOGGER.info(
                "failureDrill=model-timeout detectionMs={} retryReadyMs={} finalizationMs={} "
                        + "retryAttempts={} acceptedDuplicates={} finalNodeStatus={} finalRunStatus={}",
                detectionMillis,
                retryReadyMillis,
                finalizationMillis,
                retryAttempts,
                acceptedDuplicates,
                failed.getStatus(),
                failedRun.getStatus()
        );

        assertThat(detectionMillis).isLessThan(FAILURE_HANDLING_RTO.toMillis());
        assertThat(retryReadyMillis).isLessThan(FAILURE_HANDLING_RTO.toMillis());
        assertThat(finalizationMillis).isLessThan(FAILURE_HANDLING_RTO.toMillis());
        assertThat(retryAttempts).isEqualTo(1);
        assertThat(acceptedDuplicates).isZero();
        assertThat(failed.getStatus()).isEqualTo("FAILED");
        assertThat(failed.getErrorCode()).isEqualTo("MODEL_TIMEOUT");
        assertThat(failedRun.getStatus()).isEqualTo("FAILED");
    }

    private WorkflowEventConsumer consumer() {
        return new WorkflowEventConsumer(
                processedEventMapper,
                nodeRunMapper,
                reconciliationTrigger,
                failureDecisionService,
                objectMapper,
                null,
                WorkflowArtifactProjector.none(),
                ReviewerReworkCoordinator.none(),
                runMapper
        );
    }

    private WorkflowNodeRun runningNode() {
        Project project = new Project();
        project.setUserId(0L);
        project.setName("model-timeout-" + UUID.randomUUID());
        project.setOriginalRequirement("Retry a timed out model call and fail closed.");
        project.setStatus("GENERATING");
        projectService.save(project);

        WorkflowRun run = new WorkflowRun();
        run.setProjectId(project.getId());
        run.setOperation("MODEL_TIMEOUT_DRILL");
        run.setIdempotencyKey(UUID.randomUUID().toString());
        run.setCorrelationId(UUID.randomUUID().toString());
        run.setWorkflowSnapshotJson("""
                {
                  "workflow_key":"model-timeout-drill",
                  "version":"v1",
                  "runtime":{"max_parallel_nodes":1},
                  "nodes":[{
                    "node_id":"model_node",
                    "depends_on":[],
                    "timeout_ms":10,
                    "retry_policy":{
                      "max_attempts":2,
                      "retryable_errors":["MODEL_TIMEOUT"],
                      "initial_delay_ms":1,
                      "max_delay_ms":1,
                      "multiplier":1.0
                    },
                    "fallback":{"enabled":false}
                  }],
                  "edges":[]
                }
                """);
        run.setReviewRound(0);
        run.setMaxReviewRounds(0);
        run.setLockVersion(0);
        run.setStatus("RUNNING");
        run.setStartedAt(LocalDateTime.now());
        runMapper.insert(run);

        WorkflowNodeRun node = new WorkflowNodeRun();
        node.setWorkflowRunId(run.getId());
        node.setNodeId("model_node");
        node.setRevision(1);
        node.setAttempt(1);
        node.setExecutionId(run.getId() + ":model_node:1:1");
        node.setStatus("RUNNING");
        node.setHandlerKey("FixtureAgent");
        node.setHandlerVersion("v1");
        node.setTimeoutMs(10);
        node.setInputJson("{}");
        node.setLockVersion(0);
        node.setStartedAt(LocalDateTime.now());
        node.setHeartbeatAt(LocalDateTime.now());
        nodeRunMapper.insert(node);
        return node;
    }

    private List<WorkflowNodeRun> attempts(long runId) {
        return nodeRunMapper.selectList(new LambdaQueryWrapper<WorkflowNodeRun>()
                .eq(WorkflowNodeRun::getWorkflowRunId, runId)
                .eq(WorkflowNodeRun::getNodeId, "model_node")
                .orderByAsc(WorkflowNodeRun::getAttempt));
    }

    private List<WorkflowOutbox> outboxes(long runId) {
        return outboxMapper.selectList(new LambdaQueryWrapper<WorkflowOutbox>()
                .eq(WorkflowOutbox::getAggregateId, Long.toString(runId))
                .eq(WorkflowOutbox::getEventType, "EXECUTE_NODE"));
    }

    private String timeoutEvent(WorkflowNodeRun node) {
        return """
                {
                  "event_id":"%s:failed:MODEL_TIMEOUT",
                  "source_event_id":"command-%d",
                  "event_type":"NODE_FAILED",
                  "workflow_run_id":%d,
                  "node_run_id":%d,
                  "node_id":"%s",
                  "revision":%d,
                  "attempt":%d,
                  "execution_id":"%s",
                  "duration_ms":10,
                  "error_code":"MODEL_TIMEOUT",
                  "error_message":"node exceeded timeout of 10 ms"
                }
                """.formatted(
                node.getExecutionId(),
                node.getId(),
                node.getWorkflowRunId(),
                node.getId(),
                node.getNodeId(),
                node.getRevision(),
                node.getAttempt(),
                node.getExecutionId()
        );
    }
}
