package com.autospec.workflow.runtime;

import com.autospec.workflow.spec.WorkflowEdgeDocument;
import com.autospec.workflow.spec.WorkflowNodeDocument;
import com.autospec.workflow.spec.WorkflowSpecDocument;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DagCompilerTest {

    private final DagCompiler compiler = new DagCompiler();

    @Test
    void compilesDeterministicLayersWithParallelSiblings() {
        WorkflowSpecDocument spec = spec(
                List.of(
                        node("prd"),
                        node("architect", "prd"),
                        node("backend", "architect"),
                        node("frontend", "architect"),
                        node("reviewer", "backend", "frontend")
                ),
                List.of()
        );

        CompiledWorkflow graph = compiler.compile(spec);

        assertThat(graph.topologicalLayers()).containsExactly(
                List.of("prd"),
                List.of("architect"),
                List.of("backend", "frontend"),
                List.of("reviewer")
        );
        assertThat(graph.entryNodes()).containsExactly("prd");
        assertThat(graph.terminalNodes()).containsExactly("reviewer");
        assertThat(graph.predecessors().get("reviewer")).containsExactly("backend", "frontend");
    }

    @Test
    void rejectsUnknownDependencies() {
        WorkflowSpecDocument spec = spec(List.of(node("reviewer", "missing")), List.of());

        assertThatThrownBy(() -> compiler.compile(spec))
                .isInstanceOf(InvalidWorkflowGraphException.class)
                .hasMessageContaining("UNKNOWN_NODE")
                .hasMessageContaining("missing");
    }

    @Test
    void rejectsOrdinaryCycles() {
        WorkflowSpecDocument spec = spec(
                List.of(node("a", "b"), node("b", "a")),
                List.of()
        );

        assertThatThrownBy(() -> compiler.compile(spec))
                .isInstanceOf(InvalidWorkflowGraphException.class)
                .hasMessageContaining("CYCLE");
    }

    @Test
    void excludesReworkEdgesFromTopologicalOrdering() {
        WorkflowSpecDocument spec = spec(
                List.of(node("author"), node("reviewer", "author")),
                List.of(new WorkflowEdgeDocument("reviewer", "author", "REWORK"))
        );

        CompiledWorkflow graph = compiler.compile(spec);

        assertThat(graph.topologicalLayers()).containsExactly(
                List.of("author"),
                List.of("reviewer")
        );
        assertThat(graph.reworkTargets().get("reviewer")).containsExactly("author");
    }

    @Test
    void rejectsNodesUnreachableFromDeclaredEntryNodes() {
        WorkflowSpecDocument spec = new WorkflowSpecDocument(
                "unreachable",
                "v5",
                2,
                List.of(node("start"), node("reachable", "start"), node("orphan")),
                List.of(),
                List.of("start")
        );

        assertThatThrownBy(() -> compiler.compile(spec))
                .isInstanceOf(InvalidWorkflowGraphException.class)
                .hasMessageContaining("UNREACHABLE")
                .hasMessageContaining("orphan");
    }

    private WorkflowSpecDocument spec(List<WorkflowNodeDocument> nodes, List<WorkflowEdgeDocument> edges) {
        return new WorkflowSpecDocument("test-workflow", "v5", 4, nodes, edges, List.of());
    }

    private WorkflowNodeDocument node(String nodeId, String... dependencies) {
        return new WorkflowNodeDocument(nodeId, List.of(dependencies));
    }
}
