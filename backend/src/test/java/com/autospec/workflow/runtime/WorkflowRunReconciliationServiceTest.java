package com.autospec.workflow.runtime;

import com.autospec.entity.WorkflowRun;
import com.autospec.mapper.WorkflowRunMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowRunReconciliationServiceTest {

    @Test
    void compilesFrozenSnapshotAndReconcilesRun() {
        WorkflowRunMapper runMapper = mock(WorkflowRunMapper.class);
        WorkflowReconciler reconciler = mock(WorkflowReconciler.class);
        WorkflowRun run = new WorkflowRun();
        run.setId(7L);
        run.setWorkflowSnapshotJson("""
                {
                  "workflow_key":"autospec-v5",
                  "version":"v5",
                  "runtime":{"max_parallel_nodes":2},
                  "nodes":[
                    {"node_id":"architect","depends_on":[]},
                    {"node_id":"backend","depends_on":["architect"]},
                    {"node_id":"frontend","depends_on":["architect"]}
                  ],
                  "edges":[]
                }
                """);
        when(runMapper.selectById(7L)).thenReturn(run);
        WorkflowRunReconciliationService service = new WorkflowRunReconciliationService(
                runMapper, new WorkflowSnapshotParser(new ObjectMapper()),
                new DagCompiler(), reconciler
        );

        service.reconcile(7L);

        ArgumentCaptor<CompiledWorkflow> graph = ArgumentCaptor.forClass(CompiledWorkflow.class);
        verify(reconciler).reconcile(org.mockito.ArgumentMatchers.eq(7L), graph.capture());
        assertThat(graph.getValue().maxParallelNodes()).isEqualTo(2);
        assertThat(graph.getValue().topologicalLayers()).containsExactly(
                java.util.List.of("architect"),
                java.util.List.of("backend", "frontend")
        );
    }

    @Test
    void rejectsMissingRunWithoutSchedulingAnything() {
        WorkflowRunMapper runMapper = mock(WorkflowRunMapper.class);
        WorkflowReconciler reconciler = mock(WorkflowReconciler.class);
        when(runMapper.selectById(404L)).thenReturn(null);
        WorkflowRunReconciliationService service = new WorkflowRunReconciliationService(
                runMapper, new WorkflowSnapshotParser(new ObjectMapper()),
                new DagCompiler(), reconciler
        );

        assertThatThrownBy(() -> service.reconcile(404L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("404");
        verify(reconciler, never()).reconcile(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(CompiledWorkflow.class)
        );
    }
}
