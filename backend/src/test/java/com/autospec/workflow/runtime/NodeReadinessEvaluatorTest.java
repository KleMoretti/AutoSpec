package com.autospec.workflow.runtime;

import com.autospec.workflow.spec.WorkflowNodeDocument;
import com.autospec.workflow.spec.WorkflowSpecDocument;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NodeReadinessEvaluatorTest {

    private final DagCompiler compiler = new DagCompiler();
    private final NodeReadinessEvaluator evaluator = new NodeReadinessEvaluator();

    @Test
    void schedulesRootNodesUpToAvailableParallelCapacity() {
        CompiledWorkflow graph = compile(List.of(node("a"), node("b"), node("c")), 2);

        NodeSchedulingPlan plan = evaluator.evaluate(graph, Map.of(), 0);

        assertThat(plan.readyNodes()).containsExactly("a", "b");
        assertThat(plan.waitingNodes()).containsExactly("c");
    }

    @Test
    void releasesDependentNodeOnlyAfterEveryDependencySucceeds() {
        CompiledWorkflow graph = compile(List.of(
                node("backend"),
                node("frontend"),
                node("reviewer", "backend", "frontend")
        ), 4);

        NodeSchedulingPlan waiting = evaluator.evaluate(graph, Map.of(
                "backend", WorkflowNodeStatus.SUCCEEDED,
                "frontend", WorkflowNodeStatus.RUNNING,
                "reviewer", WorkflowNodeStatus.PENDING
        ), 1);
        NodeSchedulingPlan ready = evaluator.evaluate(graph, Map.of(
                "backend", WorkflowNodeStatus.SUCCEEDED,
                "frontend", WorkflowNodeStatus.SUCCEEDED,
                "reviewer", WorkflowNodeStatus.PENDING
        ), 0);

        assertThat(waiting.readyNodes()).isEmpty();
        assertThat(waiting.waitingNodes()).containsExactly("reviewer");
        assertThat(ready.readyNodes()).containsExactly("reviewer");
    }

    @Test
    void blocksDependentNodeWhenAnyDependencyTerminatesWithFailure() {
        CompiledWorkflow graph = compile(List.of(node("author"), node("reviewer", "author")), 2);

        NodeSchedulingPlan plan = evaluator.evaluate(graph, Map.of(
                "author", WorkflowNodeStatus.FAILED,
                "reviewer", WorkflowNodeStatus.PENDING
        ), 0);

        assertThat(plan.blockedNodes()).containsExactly("reviewer");
        assertThat(plan.readyNodes()).isEmpty();
    }

    @Test
    void doesNotRescheduleNodesThatAlreadyHaveAnActiveOrTerminalState() {
        CompiledWorkflow graph = compile(List.of(node("a"), node("b"), node("c")), 3);

        NodeSchedulingPlan plan = evaluator.evaluate(graph, Map.of(
                "a", WorkflowNodeStatus.QUEUED,
                "b", WorkflowNodeStatus.SUCCEEDED,
                "c", WorkflowNodeStatus.RETRY_WAIT
        ), 1);

        assertThat(plan.readyNodes()).isEmpty();
        assertThat(plan.waitingNodes()).isEmpty();
    }

    private CompiledWorkflow compile(List<WorkflowNodeDocument> nodes, int maxParallelNodes) {
        return compiler.compile(new WorkflowSpecDocument(
                "readiness-test", "v5", maxParallelNodes, nodes, List.of(), List.of()
        ));
    }

    private WorkflowNodeDocument node(String nodeId, String... dependencies) {
        return new WorkflowNodeDocument(nodeId, List.of(dependencies));
    }
}
