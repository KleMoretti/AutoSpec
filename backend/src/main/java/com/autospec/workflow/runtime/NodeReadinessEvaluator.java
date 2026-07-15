package com.autospec.workflow.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class NodeReadinessEvaluator {

    public NodeSchedulingPlan evaluate(
            CompiledWorkflow graph,
            Map<String, WorkflowNodeStatus> statuses,
            int activeNodeCount
    ) {
        int capacity = Math.max(0, graph.maxParallelNodes() - Math.max(0, activeNodeCount));
        List<String> ready = new ArrayList<>();
        List<String> waiting = new ArrayList<>();
        List<String> blocked = new ArrayList<>();

        for (List<String> layer : graph.topologicalLayers()) {
            for (String nodeId : layer) {
                WorkflowNodeStatus current = statuses.getOrDefault(nodeId, WorkflowNodeStatus.PENDING);
                if (current != WorkflowNodeStatus.PENDING) {
                    continue;
                }
                List<WorkflowNodeStatus> dependencyStatuses = graph.predecessors().get(nodeId).stream()
                        .map(dependency -> statuses.getOrDefault(dependency, WorkflowNodeStatus.PENDING))
                        .toList();
                if (dependencyStatuses.stream().anyMatch(WorkflowNodeStatus::isDependencyFailure)) {
                    blocked.add(nodeId);
                    continue;
                }
                boolean dependenciesSucceeded = dependencyStatuses.stream()
                        .allMatch(status -> status == WorkflowNodeStatus.SUCCEEDED
                                || status == WorkflowNodeStatus.SKIPPED);
                if (!dependenciesSucceeded) {
                    waiting.add(nodeId);
                    continue;
                }
                if (ready.size() < capacity) {
                    ready.add(nodeId);
                } else {
                    waiting.add(nodeId);
                }
            }
        }
        return new NodeSchedulingPlan(ready, waiting, blocked);
    }
}
