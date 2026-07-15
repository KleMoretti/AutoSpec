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
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class ReworkPlanExecutionServiceTest {

    @Autowired
    private ReworkPlanExecutionService service;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private WorkflowRunMapper workflowRunMapper;

    @Autowired
    private WorkflowNodeRunMapper nodeRunMapper;

    @Autowired
    private WorkflowOutboxMapper outboxMapper;

    @Test
    void atomicallyInvalidatesAffectedRevisionsCreatesNewOnesAndQueuesTarget() {
        WorkflowRun run = persistCompletedWorkflow(false, 0, 2);

        ReworkPlanner.ReworkPlan plan = service.execute(
                run.getId(), "reviewer", List.of("backend")
        );

        assertThat(plan.action()).isEqualTo(ReworkPlanner.Action.REWORK);
        WorkflowRun storedRun = workflowRunMapper.selectById(run.getId());
        assertThat(storedRun.getReviewRound()).isEqualTo(1);
        assertThat(storedRun.getLockVersion()).isEqualTo(1);

        Map<String, List<WorkflowNodeRun>> runsByNode = nodeRunMapper.selectList(
                        new LambdaQueryWrapper<WorkflowNodeRun>()
                                .eq(WorkflowNodeRun::getWorkflowRunId, run.getId())
                                .orderByAsc(WorkflowNodeRun::getRevision)
                ).stream()
                .collect(Collectors.groupingBy(
                        WorkflowNodeRun::getNodeId,
                        Collectors.mapping(Function.identity(), Collectors.toList())
                ));

        assertThat(runsByNode.get("architect"))
                .extracting(WorkflowNodeRun::getRevision, WorkflowNodeRun::getStatus)
                .containsExactly(Tuple.tuple(1, "SUCCEEDED"));
        assertThat(runsByNode.get("frontend"))
                .extracting(WorkflowNodeRun::getRevision, WorkflowNodeRun::getStatus)
                .containsExactly(Tuple.tuple(1, "SUCCEEDED"));
        assertThat(runsByNode.get("backend"))
                .extracting(WorkflowNodeRun::getRevision, WorkflowNodeRun::getStatus)
                .containsExactly(
                        Tuple.tuple(1, "STALE"),
                        Tuple.tuple(2, "QUEUED")
                );
        assertThat(runsByNode.get("reviewer"))
                .extracting(WorkflowNodeRun::getRevision, WorkflowNodeRun::getStatus)
                .containsExactly(
                        Tuple.tuple(1, "STALE"),
                        Tuple.tuple(2, "PENDING")
                );
        assertThat(runsByNode.get("evaluator"))
                .extracting(WorkflowNodeRun::getRevision, WorkflowNodeRun::getStatus)
                .containsExactly(
                        Tuple.tuple(1, "STALE"),
                        Tuple.tuple(2, "PENDING")
                );

        assertThat(outboxMapper.selectList(new QueryWrapper<WorkflowOutbox>()
                .eq("aggregate_id", run.getId().toString())))
                .singleElement()
                .satisfies(outbox -> {
                    assertThat(outbox.getEventType()).isEqualTo("EXECUTE_NODE");
                    assertThat(outbox.getPayloadJson()).contains("\"node_id\":\"backend\"");
                    assertThat(outbox.getPayloadJson()).contains("\"revision\":2");
                });
    }

    @Test
    void rollsBackStaleAndNewRevisionsWhenReschedulingFails() {
        WorkflowRun run = persistCompletedWorkflow(true, 0, 2);

        assertThatThrownBy(() -> service.execute(
                run.getId(), "reviewer", List.of("backend")
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("input JSON");

        WorkflowRun storedRun = workflowRunMapper.selectById(run.getId());
        assertThat(storedRun.getReviewRound()).isZero();
        assertThat(storedRun.getLockVersion()).isZero();
        assertThat(nodeRunMapper.selectList(new LambdaQueryWrapper<WorkflowNodeRun>()
                .eq(WorkflowNodeRun::getWorkflowRunId, run.getId())))
                .hasSize(5)
                .allMatch(nodeRun -> nodeRun.getRevision() == 1)
                .allMatch(nodeRun -> "SUCCEEDED".equals(nodeRun.getStatus()));
        assertThat(outboxMapper.selectCount(new QueryWrapper<WorkflowOutbox>()
                .eq("aggregate_id", run.getId().toString()))).isZero();
    }

    @Test
    void rejectsOverlappingReworkWhileCurrentTargetRevisionIsQueued() {
        WorkflowRun run = persistCompletedWorkflow(false, 0, 2);
        service.execute(run.getId(), "reviewer", List.of("backend"));

        assertThatThrownBy(() -> service.execute(
                run.getId(), "reviewer", List.of("backend")
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("completed current revision")
                .hasMessageContaining("backend");

        assertThat(workflowRunMapper.selectById(run.getId()).getReviewRound()).isEqualTo(1);
        assertThat(nodeRunMapper.selectCount(new LambdaQueryWrapper<WorkflowNodeRun>()
                .eq(WorkflowNodeRun::getWorkflowRunId, run.getId()))).isEqualTo(8);
        assertThat(outboxMapper.selectCount(new QueryWrapper<WorkflowOutbox>()
                .eq("aggregate_id", run.getId().toString()))).isEqualTo(1);
    }

    @Test
    void movesRunToManualInterventionWithoutInvalidatingNodes() {
        WorkflowRun run = persistCompletedWorkflow(false, 2, 2);

        ReworkPlanner.ReworkPlan plan = service.execute(
                run.getId(), "reviewer", List.of("backend")
        );

        assertThat(plan.action()).isEqualTo(ReworkPlanner.Action.MANUAL_INTERVENTION);
        WorkflowRun storedRun = workflowRunMapper.selectById(run.getId());
        assertThat(storedRun.getStatus()).isEqualTo("MANUAL_INTERVENTION");
        assertThat(storedRun.getReviewRound()).isEqualTo(2);
        assertThat(nodeRunMapper.selectList(new LambdaQueryWrapper<WorkflowNodeRun>()
                .eq(WorkflowNodeRun::getWorkflowRunId, run.getId())))
                .hasSize(5)
                .allMatch(nodeRun -> "SUCCEEDED".equals(nodeRun.getStatus()));
        assertThat(outboxMapper.selectCount(new QueryWrapper<WorkflowOutbox>()
                .eq("aggregate_id", run.getId().toString()))).isZero();
    }

    private WorkflowRun persistCompletedWorkflow(
            boolean invalidBackendInput,
            int reviewRound,
            int maxReviewRounds
    ) {
        Project project = new Project();
        project.setUserId(1L);
        project.setName("rework-execution-" + UUID.randomUUID());
        project.setOriginalRequirement("test transactional reviewer rework");
        project.setStatus("GENERATING");
        projectService.save(project);

        WorkflowRun workflowRun = new WorkflowRun();
        workflowRun.setProjectId(project.getId());
        workflowRun.setOperation("GENERATE_V5");
        workflowRun.setIdempotencyKey(UUID.randomUUID().toString());
        workflowRun.setWorkflowSnapshotJson(snapshot());
        workflowRun.setReviewRound(reviewRound);
        workflowRun.setMaxReviewRounds(maxReviewRounds);
        workflowRun.setLockVersion(0);
        workflowRun.setStatus("RUNNING");
        workflowRun.setStartedAt(LocalDateTime.now());
        workflowRunMapper.insert(workflowRun);

        for (String nodeId : List.of(
                "architect", "backend", "frontend", "reviewer", "evaluator"
        )) {
            WorkflowNodeRun nodeRun = new WorkflowNodeRun();
            nodeRun.setWorkflowRunId(workflowRun.getId());
            nodeRun.setNodeId(nodeId);
            nodeRun.setRevision(1);
            nodeRun.setAttempt(1);
            nodeRun.setExecutionId(workflowRun.getId() + ":" + nodeId + ":1:1");
            nodeRun.setStatus("SUCCEEDED");
            nodeRun.setHandlerKey(nodeId + "_agent");
            nodeRun.setHandlerVersion("v1");
            nodeRun.setTimeoutMs(30000);
            nodeRun.setInputJson(invalidBackendInput && "backend".equals(nodeId)
                    ? "{invalid"
                    : "{}");
            nodeRun.setOutputJson("{}");
            nodeRun.setLockVersion(0);
            nodeRunMapper.insert(nodeRun);
        }
        return workflowRun;
    }

    private String snapshot() {
        return """
                {
                  "workflow_key":"rework-execution-test",
                  "version":"v5",
                  "runtime":{"max_parallel_nodes":2},
                  "nodes":[
                    {"node_id":"architect","depends_on":[]},
                    {"node_id":"backend","depends_on":["architect"]},
                    {"node_id":"frontend","depends_on":["architect"]},
                    {"node_id":"reviewer","depends_on":["backend","frontend"]},
                    {"node_id":"evaluator","depends_on":["reviewer"]}
                  ],
                  "edges":[
                    {"from_node":"reviewer","to_node":"backend","edge_type":"REWORK"},
                    {"from_node":"reviewer","to_node":"frontend","edge_type":"REWORK"}
                  ]
                }
                """;
    }
}
