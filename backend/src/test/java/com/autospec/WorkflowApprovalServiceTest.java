package com.autospec;

import com.autospec.entity.Artifact;
import com.autospec.entity.Project;
import com.autospec.entity.WorkflowApproval;
import com.autospec.entity.WorkflowNodeRun;
import com.autospec.entity.WorkflowOutbox;
import com.autospec.entity.WorkflowRun;
import com.autospec.mapper.ArtifactMapper;
import com.autospec.mapper.ProcessedWorkflowEventMapper;
import com.autospec.mapper.WorkflowApprovalMapper;
import com.autospec.mapper.WorkflowNodeRunMapper;
import com.autospec.mapper.WorkflowOutboxMapper;
import com.autospec.mapper.WorkflowRunMapper;
import com.autospec.service.ProjectService;
import com.autospec.service.WorkflowApprovalService;
import com.autospec.workflow.runtime.WorkflowApprovalCoordinator;
import com.autospec.workflow.runtime.WorkflowFailureDecisionService;
import com.autospec.workflow.runtime.WorkflowRunReconciliationService;
import com.autospec.workflow.transport.WorkflowEventConsumer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class WorkflowApprovalServiceTest {
    @Autowired
    private WorkflowApprovalService approvalService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private WorkflowRunMapper runMapper;

    @Autowired
    private WorkflowNodeRunMapper nodeRunMapper;

    @Autowired
    private WorkflowApprovalMapper approvalMapper;

    @Autowired
    private WorkflowOutboxMapper outboxMapper;

    @Autowired
    private ArtifactMapper artifactMapper;

    @Autowired
    private ProcessedWorkflowEventMapper processedEventMapper;

    @Autowired
    private WorkflowRunReconciliationService reconciliationService;

    @Autowired
    private WorkflowFailureDecisionService failureDecisionService;

    @Autowired
    private WorkflowApprovalCoordinator approvalCoordinator;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void beforeNodePolicyAutomaticallyPersistsPauseBeforeAnyCommand() {
        RuntimeFixture fixture = runtimeFixture("BEFORE_NODE", "PENDING");

        reconciliationService.reconcile(fixture.run().getId());

        WorkflowNodeRun stored = nodeRunMapper.selectById(fixture.author().getId());
        assertThat(stored.getStatus()).isEqualTo("WAITING_APPROVAL");
        assertThat(outboxes(fixture.run().getId())).isEmpty();
        assertThat(approvalMapper.selectList(new LambdaQueryWrapper<WorkflowApproval>()
                .eq(WorkflowApproval::getNodeRunId, fixture.author().getId())))
                .extracting(WorkflowApproval::getMode, WorkflowApproval::getStatus)
                .containsExactly(Tuple.tuple("BEFORE_NODE", "PENDING"));
    }

    @Test
    void afterNodePolicyPersistsCandidateOutputAndContinuesOnlyAfterDecision() {
        RuntimeFixture fixture = runtimeFixture("AFTER_NODE", "QUEUED");
        WorkflowEventConsumer consumer = new WorkflowEventConsumer(
                processedEventMapper,
                nodeRunMapper,
                reconciliationService,
                failureDecisionService,
                objectMapper,
                approvalCoordinator
        );

        consumer.consume(successEvent(fixture));

        WorkflowNodeRun waiting = nodeRunMapper.selectById(fixture.author().getId());
        assertThat(waiting.getStatus()).isEqualTo("WAITING_APPROVAL");
        assertThat(waiting.getOutputJson()).isEqualTo("{\"candidate\":true}");
        assertThat(outboxes(fixture.run().getId())).isEmpty();
        WorkflowApproval approval = approvalMapper.selectOne(
                new LambdaQueryWrapper<WorkflowApproval>()
                        .eq(WorkflowApproval::getNodeRunId, fixture.author().getId())
        );

        approvalService.decide(
                approval.getId(),
                decision("APPROVE", "after-runtime-" + UUID.randomUUID(), null, null)
        );

        assertThat(nodeRunMapper.selectById(fixture.author().getId()).getStatus())
                .isEqualTo("SUCCEEDED");
        assertThat(nodeRunMapper.selectById(fixture.reviewer().getId()).getStatus())
                .isEqualTo("QUEUED");
        assertThat(outboxes(fixture.run().getId()))
                .singleElement()
                .satisfies(outbox -> assertThat(outbox.getPayloadJson())
                        .contains("\"node_id\":\"reviewer\""));
    }

    @Test
    void approvingBeforeNodeContinuesOriginalRunAndQueuesNode() {
        Fixture fixture = fixture("BEFORE_NODE", "WAITING_APPROVAL", false);

        WorkflowApproval decided = approvalService.decide(
                fixture.approval().getId(),
                decision("APPROVE", "before-approve-" + UUID.randomUUID(), null, null)
        );

        assertThat(decided.getDecision()).isEqualTo("APPROVE");
        assertThat(nodeRunMapper.selectById(fixture.approvalNode().getId()).getStatus())
                .isEqualTo("QUEUED");
        assertThat(outboxes(fixture.run().getId()))
                .singleElement()
                .satisfies(outbox -> assertThat(outbox.getPayloadJson())
                        .contains("\"node_id\":\"author\""));
    }

    @Test
    void editAndApproveCreatesChildArtifactAndPreservesAgentCandidate() {
        Fixture fixture = fixture("AFTER_NODE", "WAITING_APPROVAL", true);
        String edited = "{\"title\":\"Human approved\"}";

        WorkflowApproval decided = approvalService.decide(
                fixture.approval().getId(),
                decision(
                        "EDIT_AND_APPROVE",
                        "edit-approve-" + UUID.randomUUID(),
                        edited,
                        null
                )
        );

        Artifact candidate = artifactMapper.selectById(fixture.candidateArtifact().getId());
        Artifact revised = artifactMapper.selectById(decided.getRevisedArtifactId());
        assertThat(candidate.getContent()).isEqualTo("{\"title\":\"Agent draft\"}");
        assertThat(candidate.getStatus()).isEqualTo("PENDING_REVIEW");
        assertThat(revised.getParentArtifactId()).isEqualTo(candidate.getId());
        assertThat(revised.getSourceAgent()).isEqualTo("HUMAN_EDITOR");
        assertThat(revised.getStatus()).isEqualTo("APPROVED");
        assertThat(revised.getContent()).isEqualTo(edited);
        WorkflowNodeRun storedNode = nodeRunMapper.selectById(fixture.approvalNode().getId());
        assertThat(storedNode.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(storedNode.getOutputJson()).isEqualTo(edited);
    }

    @Test
    void duplicateDecisionWithSameIdempotencyKeyDoesNotResumeTwice() {
        Fixture fixture = fixture("BEFORE_NODE", "WAITING_APPROVAL", false);
        String key = "duplicate-" + UUID.randomUUID();

        WorkflowApproval first = approvalService.decide(
                fixture.approval().getId(), decision("APPROVE", key, null, null)
        );
        WorkflowApproval duplicate = approvalService.decide(
                fixture.approval().getId(), decision("APPROVE", key, null, null)
        );

        assertThat(duplicate.getId()).isEqualTo(first.getId());
        assertThat(outboxes(fixture.run().getId())).hasSize(1);
        assertThatThrownBy(() -> approvalService.decide(
                fixture.approval().getId(),
                decision("APPROVE", "different-" + UUID.randomUUID(), null, null)
        ))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already decided");
    }

    @Test
    void rejectingApprovalFailsNodeAndWorkflowWithoutScheduling() {
        Fixture fixture = fixture("AFTER_NODE", "WAITING_APPROVAL", false);

        approvalService.decide(
                fixture.approval().getId(),
                decision("REJECT", "reject-" + UUID.randomUUID(), null, null)
        );

        assertThat(nodeRunMapper.selectById(fixture.approvalNode().getId()).getStatus())
                .isEqualTo("FAILED");
        assertThat(runMapper.selectById(fixture.run().getId()).getStatus()).isEqualTo("FAILED");
        assertThat(outboxes(fixture.run().getId())).isEmpty();
    }

    @Test
    void rollbackInvalidatesTargetAndApprovalNodeThenQueuesNewTargetRevision() {
        Fixture fixture = fixture("AFTER_NODE", "WAITING_APPROVAL", false);

        approvalService.decide(
                fixture.approval().getId(),
                decision(
                        "ROLLBACK_TO_NODE",
                        "rollback-" + UUID.randomUUID(),
                        null,
                        "author"
                )
        );

        List<WorkflowNodeRun> runs = nodeRunMapper.selectList(
                new LambdaQueryWrapper<WorkflowNodeRun>()
                        .eq(WorkflowNodeRun::getWorkflowRunId, fixture.run().getId())
                        .orderByAsc(WorkflowNodeRun::getNodeId)
                        .orderByAsc(WorkflowNodeRun::getRevision)
        );
        assertThat(runs)
                .extracting(
                        WorkflowNodeRun::getNodeId,
                        WorkflowNodeRun::getRevision,
                        WorkflowNodeRun::getStatus
                )
                .containsExactly(
                        Tuple.tuple("author", 1, "STALE"),
                        Tuple.tuple("author", 2, "QUEUED"),
                        Tuple.tuple("reviewer", 1, "STALE"),
                        Tuple.tuple("reviewer", 2, "PENDING")
                );
    }

    private Fixture fixture(String mode, String nodeStatus, boolean withArtifact) {
        Project project = new Project();
        project.setUserId(1L);
        project.setName("approval-" + UUID.randomUUID());
        project.setOriginalRequirement("test configurable workflow approval");
        project.setStatus("GENERATING");
        projectService.save(project);

        WorkflowRun run = new WorkflowRun();
        run.setProjectId(project.getId());
        run.setOperation("GENERATE_V5");
        run.setIdempotencyKey(UUID.randomUUID().toString());
        run.setWorkflowSnapshotJson(snapshot(mode));
        run.setReviewRound(0);
        run.setMaxReviewRounds(2);
        run.setLockVersion(0);
        run.setStatus("RUNNING");
        run.setStartedAt(LocalDateTime.now());
        runMapper.insert(run);

        WorkflowNodeRun author = nodeRun(run, "author", "SUCCEEDED");
        WorkflowNodeRun approvalNode;
        if ("BEFORE_NODE".equals(mode)) {
            author.setStatus(nodeStatus);
            nodeRunMapper.updateById(author);
            approvalNode = author;
        } else {
            approvalNode = nodeRun(run, "reviewer", nodeStatus);
        }

        Artifact candidate = null;
        if (withArtifact) {
            candidate = new Artifact();
            candidate.setProjectId(project.getId());
            candidate.setType("PRD");
            candidate.setTitle("Agent PRD");
            candidate.setContent("{\"title\":\"Agent draft\"}");
            candidate.setFormat("JSON");
            candidate.setVersion(1);
            candidate.setStatus("PENDING_REVIEW");
            candidate.setSourceAgent("author_agent");
            candidate.setWorkflowNodeRunId(approvalNode.getId());
            artifactMapper.insert(candidate);
        }

        WorkflowApproval approval = new WorkflowApproval();
        approval.setWorkflowRunId(run.getId());
        approval.setNodeRunId(approvalNode.getId());
        approval.setMode(mode);
        approval.setStatus("PENDING");
        approval.setCandidateArtifactId(candidate == null ? null : candidate.getId());
        approval.setIdempotencyKey("pending:" + UUID.randomUUID());
        approval.setCreatedAt(LocalDateTime.now());
        approval.setUpdatedAt(LocalDateTime.now());
        approvalMapper.insert(approval);
        return new Fixture(run, approvalNode, approval, candidate);
    }

    private RuntimeFixture runtimeFixture(String mode, String authorStatus) {
        Project project = new Project();
        project.setUserId(1L);
        project.setName("approval-runtime-" + UUID.randomUUID());
        project.setOriginalRequirement("test runtime approval pause");
        project.setStatus("GENERATING");
        projectService.save(project);

        WorkflowRun run = new WorkflowRun();
        run.setProjectId(project.getId());
        run.setOperation("GENERATE_V5");
        run.setIdempotencyKey(UUID.randomUUID().toString());
        run.setWorkflowSnapshotJson(snapshot(mode));
        run.setReviewRound(0);
        run.setMaxReviewRounds(2);
        run.setLockVersion(0);
        run.setStatus("RUNNING");
        run.setStartedAt(LocalDateTime.now());
        runMapper.insert(run);

        WorkflowNodeRun author = nodeRun(run, "author", authorStatus);
        WorkflowNodeRun reviewer = nodeRun(run, "reviewer", "PENDING");
        return new RuntimeFixture(run, author, reviewer);
    }

    private String successEvent(RuntimeFixture fixture) {
        return """
                {
                  "event_id":"%s",
                  "source_event_id":"command-1",
                  "event_type":"NODE_SUCCEEDED",
                  "workflow_run_id":%d,
                  "node_run_id":%d,
                  "node_id":"author",
                  "revision":1,
                  "attempt":1,
                  "execution_id":"%s",
                  "duration_ms":12,
                  "output_payload":{"candidate":true}
                }
                """.formatted(
                UUID.randomUUID(),
                fixture.run().getId(),
                fixture.author().getId(),
                fixture.author().getExecutionId()
        );
    }

    private WorkflowNodeRun nodeRun(WorkflowRun run, String nodeId, String status) {
        WorkflowNodeRun nodeRun = new WorkflowNodeRun();
        nodeRun.setWorkflowRunId(run.getId());
        nodeRun.setNodeId(nodeId);
        nodeRun.setRevision(1);
        nodeRun.setAttempt(1);
        nodeRun.setExecutionId(run.getId() + ":" + nodeId + ":1:1");
        nodeRun.setStatus(status);
        nodeRun.setHandlerKey(nodeId + "_agent");
        nodeRun.setHandlerVersion("v1");
        nodeRun.setTimeoutMs(30000);
        nodeRun.setInputJson("{}");
        nodeRun.setOutputJson("{}");
        nodeRun.setLockVersion(0);
        nodeRunMapper.insert(nodeRun);
        return nodeRun;
    }

    private List<WorkflowOutbox> outboxes(long runId) {
        return outboxMapper.selectList(new QueryWrapper<WorkflowOutbox>()
                .eq("aggregate_id", Long.toString(runId)));
    }

    private WorkflowApprovalService.ApprovalDecision decision(
            String action,
            String key,
            String editedContent,
            String rollbackNodeId
    ) {
        return new WorkflowApprovalService.ApprovalDecision(
                action,
                "test decision",
                editedContent,
                rollbackNodeId,
                key,
                1L
        );
    }

    private String snapshot(String mode) {
        return """
                {
                  "workflow_key":"approval-test",
                  "version":"v5",
                  "runtime":{"max_parallel_nodes":2},
                  "nodes":[
                    {
                      "node_id":"author",
                      "depends_on":[],
                      "approval":{
                        "mode":"%s",
                        "allowed_actions":[
                          "APPROVE","REJECT","EDIT_AND_APPROVE",
                          "ROLLBACK_TO_NODE","CANCEL_WORKFLOW"
                        ]
                      }
                    },
                    {
                      "node_id":"reviewer",
                      "depends_on":["author"],
                      "approval":{
                        "mode":"%s",
                        "allowed_actions":[
                          "APPROVE","REJECT","EDIT_AND_APPROVE",
                          "ROLLBACK_TO_NODE","CANCEL_WORKFLOW"
                        ]
                      }
                    }
                  ],
                  "edges":[]
                }
                """.formatted(mode, mode);
    }

    private record Fixture(
            WorkflowRun run,
            WorkflowNodeRun approvalNode,
            WorkflowApproval approval,
            Artifact candidateArtifact
    ) {
    }

    private record RuntimeFixture(
            WorkflowRun run,
            WorkflowNodeRun author,
            WorkflowNodeRun reviewer
    ) {
    }
}
