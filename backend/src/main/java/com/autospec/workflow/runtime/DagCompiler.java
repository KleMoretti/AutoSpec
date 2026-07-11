package com.autospec.workflow.runtime;

import com.autospec.workflow.spec.WorkflowEdgeDocument;
import com.autospec.workflow.spec.WorkflowNodeDocument;
import com.autospec.workflow.spec.WorkflowSpecDocument;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DagCompiler {

    public CompiledWorkflow compile(WorkflowSpecDocument spec) {
        if (spec.nodes().isEmpty()) {
            throw new InvalidWorkflowGraphException("EMPTY_GRAPH", "workflow has no nodes");
        }

        Map<String, WorkflowNodeDocument> nodes = new LinkedHashMap<>();
        spec.nodes().stream()
                .sorted(Comparator.comparing(WorkflowNodeDocument::nodeId))
                .forEach(node -> {
                    if (node.nodeId() == null || node.nodeId().isBlank()) {
                        throw new InvalidWorkflowGraphException("INVALID_NODE", "node id is blank");
                    }
                    if (nodes.putIfAbsent(node.nodeId(), node) != null) {
                        throw new InvalidWorkflowGraphException("DUPLICATE_NODE", node.nodeId());
                    }
                });

        Map<String, Set<String>> predecessorSets = emptySets(nodes.keySet());
        Map<String, Set<String>> successorSets = emptySets(nodes.keySet());
        Map<String, Set<String>> reworkSets = emptySets(nodes.keySet());

        for (WorkflowNodeDocument node : nodes.values()) {
            for (String dependency : node.dependsOn()) {
                addOrdinaryEdge(dependency, node.nodeId(), nodes, predecessorSets, successorSets);
            }
        }
        for (WorkflowEdgeDocument edge : spec.edges()) {
            requireNode(edge.fromNode(), nodes);
            requireNode(edge.toNode(), nodes);
            if (edge.isRework()) {
                reworkSets.get(edge.fromNode()).add(edge.toNode());
            } else {
                addOrdinaryEdge(edge.fromNode(), edge.toNode(), nodes, predecessorSets, successorSets);
            }
        }

        List<String> derivedEntries = predecessorSets.entrySet().stream()
                .filter(entry -> entry.getValue().isEmpty())
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        List<String> entries = spec.entryNodes().isEmpty()
                ? derivedEntries
                : spec.entryNodes().stream().sorted().toList();
        entries.forEach(entry -> requireNode(entry, nodes));

        List<List<String>> layers = topologicalLayers(predecessorSets, successorSets);
        verifyReachability(entries, successorSets, nodes.keySet());
        List<String> terminals = successorSets.entrySet().stream()
                .filter(entry -> entry.getValue().isEmpty())
                .map(Map.Entry::getKey)
                .sorted()
                .toList();

        return new CompiledWorkflow(
                spec.workflowKey(),
                spec.version(),
                spec.maxParallelNodes(),
                Map.copyOf(nodes),
                immutableLists(predecessorSets),
                immutableLists(successorSets),
                immutableLists(reworkSets),
                List.copyOf(layers),
                List.copyOf(entries),
                List.copyOf(terminals)
        );
    }

    private void addOrdinaryEdge(
            String source,
            String target,
            Map<String, WorkflowNodeDocument> nodes,
            Map<String, Set<String>> predecessors,
            Map<String, Set<String>> successors
    ) {
        requireNode(source, nodes);
        requireNode(target, nodes);
        predecessors.get(target).add(source);
        successors.get(source).add(target);
    }

    private void requireNode(String nodeId, Map<String, WorkflowNodeDocument> nodes) {
        if (!nodes.containsKey(nodeId)) {
            throw new InvalidWorkflowGraphException("UNKNOWN_NODE", String.valueOf(nodeId));
        }
    }

    private void verifyReachability(
            List<String> entries,
            Map<String, Set<String>> successors,
            Set<String> nodeIds
    ) {
        Set<String> reached = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(entries);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            if (reached.add(current)) {
                queue.addAll(successors.get(current));
            }
        }
        List<String> unreachable = nodeIds.stream().filter(node -> !reached.contains(node)).sorted().toList();
        if (!unreachable.isEmpty()) {
            throw new InvalidWorkflowGraphException("UNREACHABLE", String.join(",", unreachable));
        }
    }

    private List<List<String>> topologicalLayers(
            Map<String, Set<String>> predecessors,
            Map<String, Set<String>> successors
    ) {
        Map<String, Integer> indegree = new LinkedHashMap<>();
        predecessors.forEach((node, values) -> indegree.put(node, values.size()));
        List<String> ready = indegree.entrySet().stream()
                .filter(entry -> entry.getValue() == 0)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        List<List<String>> layers = new ArrayList<>();
        int visited = 0;
        while (!ready.isEmpty()) {
            List<String> layer = List.copyOf(ready);
            layers.add(layer);
            visited += layer.size();
            List<String> next = new ArrayList<>();
            for (String source : layer) {
                for (String target : successors.get(source)) {
                    int remaining = indegree.compute(target, (ignored, value) -> value - 1);
                    if (remaining == 0) {
                        next.add(target);
                    }
                }
            }
            ready = next.stream().sorted().toList();
        }
        if (visited != predecessors.size()) {
            throw new InvalidWorkflowGraphException("CYCLE", "ordinary workflow edges contain a cycle");
        }
        return layers;
    }

    private Map<String, Set<String>> emptySets(Set<String> nodeIds) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        nodeIds.forEach(node -> result.put(node, new LinkedHashSet<>()));
        return result;
    }

    private Map<String, List<String>> immutableLists(Map<String, Set<String>> values) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        values.forEach((key, set) -> result.put(key, set.stream().sorted().toList()));
        return Map.copyOf(result);
    }
}
