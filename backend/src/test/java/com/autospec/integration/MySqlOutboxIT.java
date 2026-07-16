package com.autospec.integration;

import com.autospec.entity.Project;
import com.autospec.entity.WorkflowNodeRun;
import com.autospec.entity.WorkflowOutbox;
import com.autospec.entity.WorkflowRun;
import com.autospec.mapper.WorkflowNodeRunMapper;
import com.autospec.mapper.WorkflowOutboxMapper;
import com.autospec.mapper.WorkflowRunMapper;
import com.autospec.service.ProjectService;
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
    private MybatisWorkflowSchedulingGateway gateway;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void flywayCreatesRuntimeTablesAndPublishIndexInMySql() {
        Integer migrationApplied = jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where version = '71' and success = 1",
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

    private static final class ForcedRollbackException extends RuntimeException {
    }
}
