package com.autospec.workflow.runtime;

import com.autospec.entity.WorkflowNodeRun;

import java.time.LocalDateTime;

public interface WorkflowApprovalCoordinator {
    boolean pauseBeforeIfRequired(CompiledWorkflow graph, WorkflowNodeRun nodeRun);

    Integer pauseAfterIfRequired(
            WorkflowNodeRun nodeRun,
            String executionId,
            String outputJson,
            LocalDateTime completedAt
    );

    static WorkflowApprovalCoordinator none() {
        return new WorkflowApprovalCoordinator() {
            @Override
            public boolean pauseBeforeIfRequired(
                    CompiledWorkflow graph,
                    WorkflowNodeRun nodeRun
            ) {
                return false;
            }

            @Override
            public Integer pauseAfterIfRequired(
                    WorkflowNodeRun nodeRun,
                    String executionId,
                    String outputJson,
                    LocalDateTime completedAt
            ) {
                return null;
            }
        };
    }
}
