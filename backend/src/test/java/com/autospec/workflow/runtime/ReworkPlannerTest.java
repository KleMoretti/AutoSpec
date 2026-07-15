package com.autospec.workflow.runtime;

import com.autospec.entity.WorkflowNodeRun;
import com.autospec.workflow.spec.WorkflowEdgeDocument;
import com.autospec.workflow.spec.WorkflowNodeDocument;
import com.autospec.workflow.spec.WorkflowSpecDocument;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReworkPlannerTest {
    private final ReworkPlanner planner = new ReworkPlanner();
    private final CompiledWorkflow graph = graph();
    private final Map<String, WorkflowNodeRun> latestRuns = completedRuns();

    @Test
    void invalidatesOnlyTargetAndItsDownstreamWhilePreservingSiblingBranch() {
        ReworkPlanner.ReworkPlan plan = planner.plan(
                graph, "reviewer", List.of("backend"), latestRuns, 0, 2
        );

        assertThat(plan.action()).isEqualTo(ReworkPlanner.Action.REWORK);
        assertThat(plan.targetRevisions()).containsExactlyEntriesOf(Map.of("backend", 2));
        assertThat(plan.staleNodeIds()).containsExactly("backend", "evaluator", "reviewer");
        assertThat(plan.preservedNodeIds()).contains("architect", "frontend");
        assertThat(plan.nextReviewRound()).isEqualTo(1);
    }

    @Test
    void unionsDownstreamInvalidationForMultipleOwnedTargets() {
        ReworkPlanner.ReworkPlan plan = planner.plan(
                graph, "reviewer", List.of("backend", "frontend"), latestRuns, 1, 2
        );

        assertThat(plan.targetRevisions()).containsExactlyInAnyOrderEntriesOf(
                Map.of("backend", 2, "frontend", 2)
        );
        assertThat(plan.staleNodeIds())
                .containsExactly("backend", "evaluator", "frontend", "reviewer");
        assertThat(plan.preservedNodeIds()).containsExactly("architect");
        assertThat(plan.nextReviewRound()).isEqualTo(2);
    }

    @Test
    void rejectsTargetsOutsideReviewerAllowlist() {
        assertThatThrownBy(() -> planner.plan(
                graph, "reviewer", List.of("evaluator"), latestRuns, 0, 2
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("evaluator")
                .hasMessageContaining("allowlist");
    }

    @Test
    void transitionsToManualInterventionAfterConfiguredRounds() {
        ReworkPlanner.ReworkPlan plan = planner.plan(
                graph, "reviewer", List.of("backend"), latestRuns, 2, 2
        );

        assertThat(plan.action()).isEqualTo(ReworkPlanner.Action.MANUAL_INTERVENTION);
        assertThat(plan.staleNodeIds()).isEmpty();
        assertThat(plan.targetRevisions()).isEmpty();
        assertThat(plan.nextReviewRound()).isEqualTo(2);
    }

    private CompiledWorkflow graph() {
        return new DagCompiler().compile(new WorkflowSpecDocument(
                "rework-test",
                "v5",
                2,
                List.of(
                        node("architect"),
                        node("backend", "architect"),
                        node("frontend", "architect"),
                        node("reviewer", "backend", "frontend"),
                        node("evaluator", "reviewer")
                ),
                List.of(
                        new WorkflowEdgeDocument("reviewer", "architect", "REWORK"),
                        new WorkflowEdgeDocument("reviewer", "backend", "REWORK"),
                        new WorkflowEdgeDocument("reviewer", "frontend", "REWORK")
                ),
                List.of()
        ));
    }

    private Map<String, WorkflowNodeRun> completedRuns() {
        Map<String, WorkflowNodeRun> runs = new LinkedHashMap<>();
        long id = 10;
        for (String nodeId : List.of("architect", "backend", "frontend", "reviewer", "evaluator")) {
            WorkflowNodeRun run = new WorkflowNodeRun();
            run.setId(id++);
            run.setNodeId(nodeId);
            run.setRevision(1);
            run.setAttempt(1);
            run.setStatus("SUCCEEDED");
            runs.put(nodeId, run);
        }
        return runs;
    }

    private WorkflowNodeDocument node(String nodeId, String... dependencies) {
        return new WorkflowNodeDocument(nodeId, List.of(dependencies));
    }
}
