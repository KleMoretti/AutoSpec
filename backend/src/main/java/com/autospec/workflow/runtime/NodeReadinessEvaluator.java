package com.autospec.workflow.runtime;

import com.autospec.workflow.spec.WorkflowEdgeDocument;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class NodeReadinessEvaluator {
    private final RestrictedConditionEvaluator conditionEvaluator = new RestrictedConditionEvaluator();

    public NodeSchedulingPlan evaluate(
            CompiledWorkflow graph,
            Map<String, WorkflowNodeStatus> statuses,
            int activeNodeCount
    ) {
        return evaluate(graph, statuses, Map.of(), activeNodeCount);
    }

    public NodeSchedulingPlan evaluate(
            CompiledWorkflow graph,
            Map<String, WorkflowNodeStatus> statuses,
            Map<String, JsonNode> outputs,
            int activeNodeCount
    ) {
        int capacity = Math.max(0, graph.maxParallelNodes() - Math.max(0, activeNodeCount));
        List<String> ready = new ArrayList<>();
        List<String> waiting = new ArrayList<>();
        List<String> blocked = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

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
                if (!conditionsMatch(graph, nodeId, statuses, outputs)) {
                    skipped.add(nodeId);
                    continue;
                }
                if (ready.size() < capacity) {
                    ready.add(nodeId);
                } else {
                    waiting.add(nodeId);
                }
            }
        }
        return new NodeSchedulingPlan(ready, waiting, blocked, skipped);
    }

    private boolean conditionsMatch(
            CompiledWorkflow graph,
            String nodeId,
            Map<String, WorkflowNodeStatus> statuses,
            Map<String, JsonNode> outputs
    ) {
        for (WorkflowEdgeDocument edge : graph.incomingEdges().getOrDefault(nodeId, List.of())) {
            if (!edge.isConditional()) {
                continue;
            }
            if (statuses.getOrDefault(edge.fromNode(), WorkflowNodeStatus.PENDING)
                    == WorkflowNodeStatus.SKIPPED) {
                return false;
            }
            JsonNode sourceOutput = outputs.get(edge.fromNode());
            if (sourceOutput == null || !conditionEvaluator.evaluate(sourceOutput, edge.condition())) {
                return false;
            }
        }
        return true;
    }
}
