package com.autospec.workflow.runtime;

import com.autospec.entity.WorkflowNodeRun;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class ReworkPlanner {

    public ReworkPlan plan(
            CompiledWorkflow graph,
            String reviewerNodeId,
            List<String> requestedTargets,
            Map<String, WorkflowNodeRun> latestRuns,
            int currentReviewRound,
            int maxReviewRounds
    ) {
        if (currentReviewRound < 0 || maxReviewRounds < 0) {
            throw new IllegalArgumentException("review rounds must not be negative");
        }
        List<String> targets = normalizeTargets(requestedTargets);
        validateAllowlist(graph, reviewerNodeId, targets);

        List<String> allNodes = graph.nodes().keySet().stream().sorted().toList();
        if (currentReviewRound >= maxReviewRounds) {
            return new ReworkPlan(
                    Action.MANUAL_INTERVENTION,
                    List.of(),
                    allNodes,
                    Map.of(),
                    currentReviewRound
            );
        }

        Set<String> affected = downstreamClosure(graph, targets);
        List<String> staleNodes = affected.stream()
                .filter(nodeId -> isCompleted(latestRuns.get(nodeId)))
                .sorted()
                .toList();
        List<String> preserved = allNodes.stream()
                .filter(nodeId -> !affected.contains(nodeId))
                .toList();
        Map<String, Integer> targetRevisions = new LinkedHashMap<>();
        for (String target : targets) {
            WorkflowNodeRun latest = latestRuns.get(target);
            if (latest == null || latest.getRevision() == null) {
                throw new IllegalArgumentException("missing latest node run for rework target: " + target);
            }
            targetRevisions.put(target, latest.getRevision() + 1);
        }
        return new ReworkPlan(
                Action.REWORK,
                staleNodes,
                preserved,
                Map.copyOf(targetRevisions),
                currentReviewRound + 1
        );
    }

    private List<String> normalizeTargets(List<String> requestedTargets) {
        if (requestedTargets == null || requestedTargets.isEmpty()) {
            throw new IllegalArgumentException("at least one rework target is required");
        }
        Set<String> targets = new LinkedHashSet<>();
        for (String target : requestedTargets) {
            if (target == null || target.isBlank()) {
                throw new IllegalArgumentException("rework target must not be blank");
            }
            targets.add(target);
        }
        return targets.stream().sorted().toList();
    }

    private void validateAllowlist(
            CompiledWorkflow graph,
            String reviewerNodeId,
            List<String> targets
    ) {
        if (!graph.nodes().containsKey(reviewerNodeId)) {
            throw new IllegalArgumentException("unknown reviewer node: " + reviewerNodeId);
        }
        Set<String> allowed = Set.copyOf(
                graph.reworkTargets().getOrDefault(reviewerNodeId, List.of())
        );
        for (String target : targets) {
            if (!allowed.contains(target)) {
                throw new IllegalArgumentException(
                        "rework target is outside reviewer allowlist: " + target
                );
            }
        }
    }

    private Set<String> downstreamClosure(CompiledWorkflow graph, List<String> targets) {
        Set<String> affected = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(targets);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            if (affected.add(current)) {
                queue.addAll(graph.successors().getOrDefault(current, List.of()));
            }
        }
        return affected;
    }

    private boolean isCompleted(WorkflowNodeRun run) {
        return run != null && (WorkflowNodeStatus.SUCCEEDED.name().equals(run.getStatus())
                || WorkflowNodeStatus.SKIPPED.name().equals(run.getStatus()));
    }

    public enum Action {
        REWORK,
        MANUAL_INTERVENTION
    }

    public record ReworkPlan(
            Action action,
            List<String> staleNodeIds,
            List<String> preservedNodeIds,
            Map<String, Integer> targetRevisions,
            int nextReviewRound
    ) {
        public ReworkPlan {
            staleNodeIds = List.copyOf(staleNodeIds);
            preservedNodeIds = List.copyOf(preservedNodeIds);
            targetRevisions = Map.copyOf(targetRevisions);
        }
    }
}
