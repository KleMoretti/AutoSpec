package com.autospec.workflow.runtime;

import com.autospec.entity.WorkflowNodeRun;

public interface ReviewerReworkCoordinator {
    boolean applyIfRequested(WorkflowNodeRun reviewerNode, String outputJson);

    static ReviewerReworkCoordinator none() {
        return (reviewerNode, outputJson) -> false;
    }
}
