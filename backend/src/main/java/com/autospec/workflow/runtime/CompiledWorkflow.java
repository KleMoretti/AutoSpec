package com.autospec.workflow.runtime;

import com.autospec.workflow.spec.WorkflowEdgeDocument;
import com.autospec.workflow.spec.WorkflowNodeDocument;

import java.util.List;
import java.util.Map;

public record CompiledWorkflow(
        String workflowKey,
        String version,
        int protocolVersion,
        int maxParallelNodes,
        Map<String, WorkflowNodeDocument> nodes,
        Map<String, List<String>> predecessors,
        Map<String, List<String>> successors,
        Map<String, List<WorkflowEdgeDocument>> incomingEdges,
        Map<String, List<WorkflowEdgeDocument>> reworkEdges,
        Map<String, List<String>> reworkTargets,
        List<List<String>> topologicalLayers,
        List<String> entryNodes,
        List<String> terminalNodes
) {
}
