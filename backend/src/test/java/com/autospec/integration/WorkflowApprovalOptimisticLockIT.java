package com.autospec.integration;

import com.autospec.entity.Project;
import com.autospec.entity.WorkflowApproval;
import com.autospec.entity.WorkflowNodeRun;
import com.autospec.entity.WorkflowRun;
import com.autospec.exception.OptimisticLockConflictException;
import com.autospec.mapper.WorkflowApprovalMapper;
import com.autospec.mapper.WorkflowNodeRunMapper;
import com.autospec.mapper.WorkflowRunMapper;
import com.autospec.service.ProjectService;
import com.autospec.service.WorkflowApprovalService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("integration-test")
class WorkflowApprovalOptimisticLockIT extends MySqlIntegrationTestSupport {

    @Autowired
    private ProjectService projectService;

    @Autowired
    private WorkflowRunMapper runMapper;

    @Autowired
    private WorkflowNodeRunMapper nodeRunMapper;

    @Autowired
    private WorkflowApprovalMapper approvalMapper;

    @Autowired
    private WorkflowApprovalService approvalService;

    @Test
    void concurrentDecisionsPersistExactlyOneEffectiveDecision() throws Exception {
        WorkflowApproval approval = pendingApproval();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<DecisionOutcome> first = executor.submit(() -> decide(
                    approval.getId(), "decision-1-" + UUID.randomUUID(), ready, start
            ));
            Future<DecisionOutcome> second = executor.submit(() -> decide(
                    approval.getId(), "decision-2-" + UUID.randomUUID(), ready, start
            ));

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<DecisionOutcome> outcomes = List.of(
                    first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS)
            );

            assertThat(outcomes).extracting(DecisionOutcome::status)
                    .containsExactlyInAnyOrder("SUCCESS", "CONFLICT");
            OptimisticLockConflictException conflict = outcomes.stream()
                    .map(DecisionOutcome::conflict)
                    .filter(java.util.Objects::nonNull)
                    .findFirst()
                    .orElseThrow();
            assertThat(conflict.getDetails())
                    .containsEntry("resourceType", "workflowApproval")
                    .containsEntry("expectedLockVersion", "0")
                    .containsEntry("currentLockVersion", "1");
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        WorkflowApproval stored = approvalMapper.selectById(approval.getId());
        assertThat(stored.getStatus()).isEqualTo("DECIDED");
        assertThat(stored.getDecision()).isEqualTo("REJECT");
        assertThat(stored.getLockVersion()).isEqualTo(1);
        assertThat(nodeRunMapper.selectById(stored.getNodeRunId()).getStatus()).isEqualTo("FAILED");
        assertThat(runMapper.selectById(stored.getWorkflowRunId()).getStatus()).isEqualTo("FAILED");
    }

    private DecisionOutcome decide(
            long approvalId,
            String idempotencyKey,
            CountDownLatch ready,
            CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent approval start timed out");
        }
        try {
            WorkflowApproval decided = approvalService.decide(
                    approvalId,
                    0,
                    new WorkflowApprovalService.ApprovalDecision(
                            "REJECT",
                            "concurrent decision",
                            null,
                            null,
                            idempotencyKey,
                            1L
                    )
            );
            return new DecisionOutcome("SUCCESS", decided, null);
        } catch (OptimisticLockConflictException conflict) {
            return new DecisionOutcome("CONFLICT", null, conflict);
        }
    }

    private WorkflowApproval pendingApproval() {
        Project project = new Project();
        project.setUserId(0L);
        project.setName("Workflow approval optimistic lock");
        project.setOriginalRequirement("Accept exactly one concurrent approval decision.");
        project.setStatus("GENERATING");
        projectService.save(project);

        WorkflowRun run = new WorkflowRun();
        run.setProjectId(project.getId());
        run.setOperation("APPROVAL_LOCK_TEST");
        run.setIdempotencyKey(UUID.randomUUID().toString());
        run.setWorkflowSnapshotJson("""
                {
                  "workflow_key":"approval-lock-test",
                  "version":"v1",
                  "runtime":{"max_parallel_nodes":1},
                  "nodes":[{
                    "node_id":"review",
                    "depends_on":[],
                    "approval":{"mode":"AFTER_NODE","allowed_actions":["REJECT"]}
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

        WorkflowNodeRun nodeRun = new WorkflowNodeRun();
        nodeRun.setWorkflowRunId(run.getId());
        nodeRun.setNodeId("review");
        nodeRun.setRevision(1);
        nodeRun.setAttempt(1);
        nodeRun.setExecutionId("approval-lock:" + UUID.randomUUID());
        nodeRun.setStatus("WAITING_APPROVAL");
        nodeRun.setHandlerKey("review_agent");
        nodeRun.setHandlerVersion("v1");
        nodeRun.setTimeoutMs(30000);
        nodeRun.setInputJson("{}");
        nodeRun.setOutputJson("{}");
        nodeRun.setLockVersion(0);
        nodeRunMapper.insert(nodeRun);

        WorkflowApproval approval = new WorkflowApproval();
        approval.setWorkflowRunId(run.getId());
        approval.setNodeRunId(nodeRun.getId());
        approval.setMode("AFTER_NODE");
        approval.setStatus("PENDING");
        approval.setIdempotencyKey("pending:" + UUID.randomUUID());
        approval.setLockVersion(0);
        approval.setCreatedAt(LocalDateTime.now());
        approval.setUpdatedAt(LocalDateTime.now());
        approvalMapper.insert(approval);
        return approval;
    }

    private record DecisionOutcome(
            String status,
            WorkflowApproval approval,
            OptimisticLockConflictException conflict
    ) {
    }
}
