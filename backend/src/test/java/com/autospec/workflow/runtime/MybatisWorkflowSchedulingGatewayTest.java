package com.autospec.workflow.runtime;

import com.autospec.entity.Project;
import com.autospec.entity.WorkflowNodeRun;
import com.autospec.entity.WorkflowOutbox;
import com.autospec.entity.WorkflowRun;
import com.autospec.mapper.WorkflowNodeRunMapper;
import com.autospec.mapper.WorkflowOutboxMapper;
import com.autospec.mapper.WorkflowRunMapper;
import com.autospec.service.ProjectService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MybatisWorkflowSchedulingGatewayTest {

    @Autowired
    private MybatisWorkflowSchedulingGateway gateway;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private WorkflowRunMapper workflowRunMapper;

    @Autowired
    private WorkflowNodeRunMapper nodeRunMapper;

    @Autowired
    private WorkflowOutboxMapper outboxMapper;

    @Test
    void conditionallyQueuesNodeAndAppendsExactlyOneOutboxCommand() {
        WorkflowNodeRun nodeRun = persistPendingNode();
        QueuedNodeCommand command = command(nodeRun);

        boolean first = gateway.reserveAndAppendCommand(nodeRun, command);
        boolean duplicate = gateway.reserveAndAppendCommand(nodeRun, command);

        WorkflowNodeRun stored = nodeRunMapper.selectById(nodeRun.getId());
        assertThat(first).isTrue();
        assertThat(duplicate).isFalse();
        assertThat(stored.getStatus()).isEqualTo("QUEUED");
        assertThat(stored.getExecutionId()).isEqualTo(command.executionId());
        assertThat(stored.getLockVersion()).isEqualTo(1);
        assertThat(outboxMapper.selectCount(new LambdaQueryWrapper<WorkflowOutbox>()
                .eq(WorkflowOutbox::getEventId, command.eventId()))).isEqualTo(1);
    }

    @Test
    void protocolV2AtomicallyReservesWorstCaseUsageBeforePublishing() {
        WorkflowNodeRun nodeRun = persistPendingNode();
        QueuedNodeCommand command = frozenCommand(nodeRun);

        assertThat(gateway.reserveAndAppendCommand(nodeRun, command)).isTrue();

        WorkflowNodeRun stored = nodeRunMapper.selectById(nodeRun.getId());
        WorkflowRun run = workflowRunMapper.selectById(nodeRun.getWorkflowRunId());
        assertThat(stored.getBudgetStatus()).isEqualTo("RESERVED");
        assertThat(stored.getBudgetReservationId()).isEqualTo(command.executionId());
        assertThat(stored.getReservedInputTokens()).isEqualTo(1024L);
        assertThat(stored.getReservedOutputTokens()).isEqualTo(256L);
        assertThat(stored.getReservedModelCalls()).isEqualTo(2);
        assertThat(run.getReservedTokens()).isEqualTo(1280L);
        assertThat(run.getReservedModelCalls()).isEqualTo(2);
        assertThat(outboxMapper.selectCount(new LambdaQueryWrapper<WorkflowOutbox>()
                .eq(WorkflowOutbox::getEventId, command.eventId()))).isEqualTo(1);
    }

    @Test
    void protocolV2BudgetRejectionOccursBeforeOutboxPublication() {
        WorkflowNodeRun nodeRun = persistPendingNode();
        WorkflowRun run = workflowRunMapper.selectById(nodeRun.getWorkflowRunId());
        run.setMaxTokens(100L);
        workflowRunMapper.updateById(run);
        QueuedNodeCommand command = frozenCommand(nodeRun);

        assertThat(gateway.reserveAndAppendCommand(nodeRun, command)).isFalse();

        WorkflowNodeRun stored = nodeRunMapper.selectById(nodeRun.getId());
        WorkflowRun unchangedRun = workflowRunMapper.selectById(nodeRun.getWorkflowRunId());
        assertThat(stored.getStatus()).isEqualTo("FAILED");
        assertThat(stored.getErrorCode()).isEqualTo("BUDGET_PREAUTH_FAILED");
        assertThat(stored.getBudgetStatus()).isEqualTo("REJECTED");
        assertThat(unchangedRun.getReservedTokens()).isZero();
        assertThat(unchangedRun.getStatus()).isEqualTo("RUNNING");
        assertThat(outboxMapper.selectCount(new LambdaQueryWrapper<WorkflowOutbox>()
                .eq(WorkflowOutbox::getEventId, command.eventId()))).isZero();
    }

    private WorkflowNodeRun persistPendingNode() {
        Project project = new Project();
        project.setUserId(1L);
        project.setName("gateway-" + UUID.randomUUID());
        project.setOriginalRequirement("test atomic workflow scheduling");
        project.setStatus("GENERATING");
        projectService.save(project);

        WorkflowRun workflowRun = new WorkflowRun();
        workflowRun.setProjectId(project.getId());
        workflowRun.setOperation("GENERATE_V5");
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
        return new QueuedNodeCommand(
                UUID.randomUUID().toString(),
                nodeRun.getWorkflowRunId(),
                nodeRun.getId(),
                nodeRun.getNodeId(),
                nodeRun.getRevision(),
                nodeRun.getAttempt(),
                nodeRun.getWorkflowRunId() + ":" + nodeRun.getNodeId() + ":1:1",
                nodeRun.getHandlerKey(),
                nodeRun.getHandlerVersion(),
                30000,
                JsonNodeFactory.instance.objectNode()
        );
    }

    private QueuedNodeCommand frozenCommand(WorkflowNodeRun nodeRun) {
        String executionId = nodeRun.getWorkflowRunId() + ":" + nodeRun.getNodeId() + ":1:1";
        ObjectNode context = JsonNodeFactory.instance.objectNode();
        context.put("version", "context-v2");
        context.put("tokenizer", "conservative-multilingual-v1");
        context.put("max_input_tokens", 512);
        context.put("prompt_token_reserve", 64);
        context.put("manifest_token_reserve", 64);
        context.putArray("field_priority").add("requirement");
        context.putArray("required_paths").add("$.requirement");
        context.put("compression_strategy", "schema-aware-v2");
        context.put("rag_token_budget", 0);
        context.put("long_text_token_budget", 128);
        context.put("max_single_source_ratio", 0.35);

        ObjectNode model = JsonNodeFactory.instance.objectNode();
        model.put("route_key", "balanced");
        model.put("temperature", 0);
        model.put("context_window_tokens", 2048);
        model.put("max_output_tokens", 128);
        model.put("max_calls", 2);
        model.put("input_cost_per_million", 2);
        model.put("cached_input_cost_per_million", 5);
        model.put("output_cost_per_million", 10);
        model.putArray("required_capabilities").add("json_object");

        ObjectNode retry = JsonNodeFactory.instance.objectNode();
        retry.put("max_attempts", 2);
        WorkflowBudgetReservation reservation = WorkflowBudgetReservation.from(
                executionId, context, model
        );
        return new QueuedNodeCommand(
                UUID.randomUUID().toString(),
                nodeRun.getWorkflowRunId(),
                nodeRun.getId(),
                nodeRun.getNodeId(),
                nodeRun.getRevision(),
                nodeRun.getAttempt(),
                executionId,
                nodeRun.getHandlerKey(),
                nodeRun.getHandlerVersion(),
                30000,
                JsonNodeFactory.instance.objectNode(),
                "correlation-v2",
                null,
                null,
                2,
                "1".repeat(64),
                "BackendDesignInput",
                "2".repeat(64),
                "BackendDesignArtifact",
                "3".repeat(64),
                "backend_engineer",
                "v1",
                "4".repeat(64),
                context,
                model,
                retry,
                JsonNodeFactory.instance.objectNode(),
                reservation,
                System.currentTimeMillis() + 30000
        );
    }
}
