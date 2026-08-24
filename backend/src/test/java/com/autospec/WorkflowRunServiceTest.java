package com.autospec;

import com.autospec.entity.Project;
import com.autospec.entity.WorkflowRun;
import com.autospec.entity.WorkflowNodeRun;
import com.autospec.entity.WorkflowOutbox;
import com.autospec.mapper.WorkflowNodeRunMapper;
import com.autospec.mapper.WorkflowOutboxMapper;
import com.autospec.service.ProjectService;
import com.autospec.service.WorkflowRunService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class WorkflowRunServiceTest {

    @Autowired
    private WorkflowRunService workflowRunService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private WorkflowNodeRunMapper nodeRunMapper;

    @Autowired
    private WorkflowOutboxMapper outboxMapper;

    @Test
    void staleRunningWorkflowRunsCanBeTimedOut() throws Exception {
        Project project = new Project();
        project.setUserId(0L);
        project.setName("Workflow Timeout Recovery Project");
        project.setOriginalRequirement("Recover stale workflow runs.");
        project.setStatus("GENERATING");
        projectService.save(project);

        LocalDateTime now = LocalDateTime.now();
        WorkflowRun staleRunning = workflowRun(project.getId(), "stale-run", "RUNNING", now.minusHours(2));
        WorkflowRun recentRunning = workflowRun(project.getId(), "recent-run", "RUNNING", now.minusMinutes(5));
        WorkflowRun alreadyFailed = workflowRun(project.getId(), "failed-run", "FAILED", now.minusHours(3));

        int timedOut = workflowRunService.timeoutRunningRunsBefore(now.minusMinutes(30));

        assertThat(timedOut).isEqualTo(1);
        WorkflowRun timedOutRun = workflowRunService.getById(staleRunning.getId());
        assertThat(timedOutRun.getStatus()).isEqualTo("FAILED");
        assertThat(timedOutRun.getErrorMessage()).isEqualTo("Timed out while running workflow run");
        assertThat(timedOutRun.getCompletedAt()).isNotNull();
        assertThat(workflowRunService.getById(recentRunning.getId()).getStatus()).isEqualTo("RUNNING");
        assertThat(workflowRunService.getById(alreadyFailed.getId()).getStatus()).isEqualTo("FAILED");
    }

    @Test
    void runningWorkflowRunCanBeCancelledByProjectScopedId() {
        Project project = new Project();
        project.setUserId(0L);
        project.setName("Workflow Cancellation Project");
        project.setOriginalRequirement("Cancel a running workflow.");
        project.setStatus("GENERATING");
        projectService.save(project);

        LocalDateTime now = LocalDateTime.now();
        WorkflowRun running = workflowRun(project.getId(), "running-cancel-key", "RUNNING", now.minusMinutes(5));
        WorkflowRun completed = workflowRun(project.getId(), "completed-cancel-key", "COMPLETED", now.minusMinutes(10));
        WorkflowNodeRun node = new WorkflowNodeRun();
        node.setWorkflowRunId(running.getId());
        node.setNodeId("architect");
        node.setRevision(1);
        node.setAttempt(1);
        node.setExecutionId(running.getId() + ":architect:1:1");
        node.setStatus("QUEUED");
        node.setHandlerKey("ArchitectAgent");
        node.setHandlerVersion("v1");
        node.setLockVersion(0);
        nodeRunMapper.insert(node);
        WorkflowOutbox outbox = new WorkflowOutbox();
        outbox.setEventId("cancel-command-" + running.getId());
        outbox.setAggregateId(Long.toString(running.getId()));
        outbox.setEventType("EXECUTE_NODE");
        outbox.setPayloadJson("{}");
        outbox.setStatus("PENDING");
        outbox.setRetryCount(0);
        outboxMapper.insert(outbox);

        WorkflowRun cancelled = workflowRunService.cancelRunningRun(project.getId(), running.getId());

        assertThat(cancelled.getStatus()).isEqualTo("CANCELLED");
        assertThat(cancelled.getErrorMessage()).isEqualTo("Cancelled by user request");
        assertThat(cancelled.getCompletedAt()).isNotNull();
        assertThat(nodeRunMapper.selectById(node.getId()).getStatus()).isEqualTo("CANCELLED");
        assertThat(outboxMapper.selectById(outbox.getId()).getStatus()).isEqualTo("CLOSED");

        assertThatThrownBy(() -> workflowRunService.cancelRunningRun(project.getId(), completed.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Only running workflow runs can be cancelled");
    }

    private WorkflowRun workflowRun(Long projectId, String idempotencyKey, String status, LocalDateTime createdAt) {
        WorkflowRun run = new WorkflowRun();
        run.setProjectId(projectId);
        run.setOperation("GENERATE_V4");
        run.setIdempotencyKey(idempotencyKey);
        run.setStatus(status);
        run.setCreatedAt(createdAt);
        workflowRunService.save(run);
        return run;
    }
}
