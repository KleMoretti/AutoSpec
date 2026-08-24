package com.autospec.workflow.runtime;

import java.util.List;

public record NodeSchedulingPlan(
        List<String> readyNodes,
        List<String> waitingNodes,
        List<String> blockedNodes,
        List<String> skippedNodes
) {
    public NodeSchedulingPlan {
        readyNodes = List.copyOf(readyNodes);
        waitingNodes = List.copyOf(waitingNodes);
        blockedNodes = List.copyOf(blockedNodes);
        skippedNodes = List.copyOf(skippedNodes);
    }

    public NodeSchedulingPlan(
            List<String> readyNodes,
            List<String> waitingNodes,
            List<String> blockedNodes
    ) {
        this(readyNodes, waitingNodes, blockedNodes, List.of());
    }
}
