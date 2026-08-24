package com.autospec.workflow.runtime;

import java.util.List;

public record ReconciliationResult(
        List<String> queuedNodes,
        List<String> concurrentlyChangedNodes,
        List<String> blockedNodes,
        List<String> skippedNodes
) {
    public ReconciliationResult {
        queuedNodes = List.copyOf(queuedNodes);
        concurrentlyChangedNodes = List.copyOf(concurrentlyChangedNodes);
        blockedNodes = List.copyOf(blockedNodes);
        skippedNodes = List.copyOf(skippedNodes);
    }

    public ReconciliationResult(
            List<String> queuedNodes,
            List<String> concurrentlyChangedNodes,
            List<String> blockedNodes
    ) {
        this(queuedNodes, concurrentlyChangedNodes, blockedNodes, List.of());
    }
}
