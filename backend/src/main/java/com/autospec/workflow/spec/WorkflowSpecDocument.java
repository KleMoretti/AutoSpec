package com.autospec.workflow.spec;

import java.util.List;

public record WorkflowSpecDocument(
        String workflowKey,
        String version,
        int maxParallelNodes,
        List<WorkflowNodeDocument> nodes,
        List<WorkflowEdgeDocument> edges,
        List<String> entryNodes
) {
    public WorkflowSpecDocument {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
        entryNodes = entryNodes == null ? List.of() : List.copyOf(entryNodes);
    }
}
