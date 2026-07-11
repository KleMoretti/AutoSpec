package com.autospec.workflow.runtime;

import java.util.List;

public record ReconciliationResult(
        List<String> queuedNodes,
        List<String> concurrentlyChangedNodes,
        List<String> blockedNodes
) {
    public ReconciliationResult {
        queuedNodes = List.copyOf(queuedNodes);
        concurrentlyChangedNodes = List.copyOf(concurrentlyChangedNodes);
        blockedNodes = List.copyOf(blockedNodes);
    }
}
