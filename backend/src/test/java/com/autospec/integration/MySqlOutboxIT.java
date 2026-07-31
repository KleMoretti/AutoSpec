package com.autospec.integration;

import com.autospec.dto.WorkflowOutboxBacklogSnapshot;
import com.autospec.dto.PaginationRequest;
import com.autospec.entity.Project;
import com.autospec.entity.WorkflowNodeRun;
import com.autospec.entity.WorkflowOutbox;
import com.autospec.entity.WorkflowRun;
import com.autospec.mapper.WorkflowNodeRunMapper;
import com.autospec.mapper.WorkflowOutboxMapper;
import com.autospec.mapper.WorkflowRunMapper;
import com.autospec.service.ProjectService;
import com.autospec.service.WorkflowDeadLetterService;
import com.autospec.workflow.runtime.MybatisWorkflowSchedulingGateway;
import com.autospec.workflow.runtime.QueuedNodeCommand;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("integration-test")
class MySqlOutboxIT extends MySqlIntegrationTestSupport {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private WorkflowRunMapper workflowRunMapper;

    @Autowired
    private WorkflowNodeRunMapper nodeRunMapper;

    @Autowired
    private WorkflowOutboxMapper outboxMapper;

    @Autowired
    private WorkflowDeadLetterService deadLetterService;

    @Autowired
    private MybatisWorkflowSchedulingGateway gateway;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void flywayCreatesRuntimeTablesAndPublishIndexInMySql() {
        Integer migrationApplied = jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where version = '73' and success = 1",
                Integer.class
        );
        Integer publishIndex = jdbcTemplate.queryForObject(
                """
                        select count(*)
                        from information_schema.statistics
                        where table_schema = database()
                          and table_name = 'workflow_outbox'
                          and index_name = 'idx_workflow_outbox_publish'
                        """,
                Integer.class
        );

        assertThat(migrationApplied).isEqualTo(1);
        assertThat(publishIndex).isGreaterThan(0);
        assertThat(jdbcTemplate.queryForObject(
                """
                        select count(*)
                        from information_schema.statistics
                        where table_schema = database()
                          and table_name = 'workflow_outbox'
                          and index_name = 'idx_workflow_outbox_aggregate_status_id'
                        """,
                Integer.class
        )).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject(
                """
                        select count(*)
                        from information_schema.columns
                        where table_schema = database()
                          and table_name = 'workflow_outbox'
                          and column_name in (
                              'last_error_type',
                              'last_error_at',
                              'dead_lettered_at',
                              'closed_at'
                          )
                        """,
                Integer.class
        )).isEqualTo(4);
    }

    @Test
    void deadLetterManagementUsesMySqlCompareAndSetTransitions() {
        WorkflowNodeRun nodeRun = persistPendingNode();
        long workflowRunId = nodeRun.getWorkflowRunId();
        WorkflowOutbox replayCandidate = persistDeadLetter(workflowRunId, nodeRun.getId());
        WorkflowOutbox closeCandidate = persistDeadLetter(workflowRunId, nodeRun.getId());

        assertThat(deadLetterService.listByWorkflowRunId(
                workflowRunId,
                "DEAD_LETTER",
                new PaginationRequest(10, 0)
        )).extracting(WorkflowOutbox::getId)
                .contains(replayCandidate.getId(), closeCandidate.getId());

        WorkflowOutbox replayed = deadLetterService.replay(workflowRunId, replayCandidate.getId());
        WorkflowOutbox replayedFromDatabase = outboxMapper.selectById(replayCandidate.getId());
        assertThat(replayed.getEventId()).isEqualTo(replayCandidate.getEventId());
        assertThat(replayedFromDatabase.getStatus()).isEqualTo("PENDING");
        assertThat(replayedFromDatabase.getRetryCount()).isZero();
        assertThat(replayedFromDatabase.getNextRetryAt()).isNull();

        deadLetterService.close(workflowRunId, closeCandidate.getId());
        WorkflowOutbox closedFromDatabase = outboxMapper.selectById(closeCandidate.getId());
        assertThat(closedFromDatabase.getStatus()).isEqualTo("CLOSED");
        assertThat(closedFromDatabase.getClosedAt()).isNotNull();
    }

    @Test
    void committedReservationPersistsNodeStateAndOutboxTogether() {
        WorkflowNodeRun nodeRun = persistPendingNode();
        QueuedNodeCommand command = command(nodeRun);

        boolean reserved = gateway.reserveAndAppendCommand(nodeRun, command);

        assertThat(reserved).isTrue();
        assertThat(nodeRunMapper.selectById(nodeRun.getId()).getStatus()).isEqualTo("QUEUED");
        assertThat(outboxCount(command.eventId())).isEqualTo(1);
    }

    @Test
    void outerTransactionRollbackLeavesNoOrphanOutboxCommand() {
        WorkflowNodeRun nodeRun = persistPendingNode();
        QueuedNodeCommand command = command(nodeRun);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            assertThat(gateway.reserveAndAppendCommand(nodeRun, command)).isTrue();
            throw new ForcedRollbackException();
        })).isInstanceOf(ForcedRollbackException.class);

        assertThat(nodeRunMapper.selectById(nodeRun.getId()).getStatus()).isEqualTo("PENDING");
        assertThat(outboxCount(command.eventId())).isZero();
    }

    @Test
    void pendingBacklogProjectionUsesMySqlAggregation() {
        WorkflowOutboxBacklogSnapshot before = outboxMapper.selectPendingBacklog();
        long previousCount = before.getPendingCount();
        LocalDateTime createdAt = LocalDateTime.now().minusMinutes(2);
        WorkflowOutbox pending = new WorkflowOutbox();
        pending.setEventId("metrics-" + UUID.randomUUID());
        pending.setAggregateId("metrics");
        pending.setEventType("EXECUTE_NODE");
        pending.setPayloadJson("{}");
        pending.setStatus("PENDING");
        pending.setRetryCount(0);
        pending.setCreatedAt(createdAt);
        pending.setUpdatedAt(createdAt);
        outboxMapper.insert(pending);

        WorkflowOutboxBacklogSnapshot snapshot = outboxMapper.selectPendingBacklog();

        assertThat(snapshot.getPendingCount()).isEqualTo(previousCount + 1);
        assertThat(snapshot.getOldestCreatedAt()).isBeforeOrEqualTo(createdAt.plusSeconds(1));
    }

    private WorkflowNodeRun persistPendingNode() {
        Project project = new Project();
        project.setUserId(1L);
        project.setName("mysql-it-" + UUID.randomUUID());
        project.setOriginalRequirement("verify transactional outbox");
        project.setStatus("GENERATING");
        projectService.save(project);

        WorkflowRun workflowRun = new WorkflowRun();
        workflowRun.setProjectId(project.getId());
        workflowRun.setOperation("MYSQL_INTEGRATION_TEST");
        workflowRun.setIdempotencyKey(UUID.randomUUID().toString());
        workflowRun.setStatus("RUNNING");
        workflowRun.setStartedAt(LocalDateTime.now());
        workflowRunMapper.insert(workflowRun);

        WorkflowNodeRun nodeRun = new WorkflowNodeRun();
        nodeRun.setWorkflowRunId(workflowRun.getId());
        nodeRun.setNodeId("backend_engineer");
        nodeRun.setRevision(1);
        nodeRun.setAttempt(1);
        nodeRun.setExecutionId("pending:" + UUID.randomUUID());
        nodeRun.setStatus("PENDING");
        nodeRun.setHandlerKey("BackendEngineerAgent");
        nodeRun.setHandlerVersion("v2");
        nodeRun.setLockVersion(0);
        nodeRunMapper.insert(nodeRun);
        return nodeRun;
    }

    private QueuedNodeCommand command(WorkflowNodeRun nodeRun) {
        String eventId = UUID.randomUUID().toString();
        return new QueuedNodeCommand(
                eventId,
                nodeRun.getWorkflowRunId(),
                nodeRun.getId(),
                nodeRun.getNodeId(),
                nodeRun.getRevision(),
                nodeRun.getAttempt(),
                nodeRun.getWorkflowRunId() + ":" + nodeRun.getNodeId() + ":1:1",
                nodeRun.getHandlerKey(),
                nodeRun.getHandlerVersion(),
                30_000,
                JsonNodeFactory.instance.objectNode()
        );
    }

    private long outboxCount(String eventId) {
        return outboxMapper.selectCount(new LambdaQueryWrapper<WorkflowOutbox>()
                .eq(WorkflowOutbox::getEventId, eventId));
    }

    private WorkflowOutbox persistDeadLetter(long workflowRunId, long nodeRunId) {
        LocalDateTime now = LocalDateTime.now();
        WorkflowOutbox outbox = new WorkflowOutbox();
        outbox.setEventId(UUID.randomUUID().toString());
        outbox.setAggregateId(Long.toString(workflowRunId));
        outbox.setEventType("EXECUTE_NODE");
        outbox.setPayloadJson("{\"node_run_id\":" + nodeRunId + "}");
        outbox.setStatus("DEAD_LETTER");
        outbox.setRetryCount(5);
        outbox.setLastErrorType("IllegalStateException");
        outbox.setLastErrorAt(now);
        outbox.setDeadLetteredAt(now);
        outbox.setCreatedAt(now);
        outbox.setUpdatedAt(now);
        outboxMapper.insert(outbox);
        return outbox;
    }

    private static final class ForcedRollbackException extends RuntimeException {
    }
}
