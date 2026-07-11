package com.autospec.workflow.runtime;

import com.autospec.entity.Project;
import com.autospec.entity.WorkflowNodeRun;
import com.autospec.entity.WorkflowRun;
import com.autospec.mapper.WorkflowNodeRunMapper;
import com.autospec.mapper.WorkflowOutboxMapper;
import com.autospec.mapper.WorkflowRunMapper;
import com.autospec.service.ProjectService;
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
        assertThat(outboxMapper.selectCount(null)).isEqualTo(1);
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
                nodeRun.getWorkflowRunId() + ":" + nodeRun.getNodeId() + ":1:1"
        );
    }
}
