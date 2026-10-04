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

    /**
     * Persist a model-produced user-input request without treating it as a
     * successful artifact-producing node event.
     */
    default Integer pauseForInputRequired(
            WorkflowNodeRun nodeRun,
            String executionId,
            String outputJson,
            LocalDateTime completedAt
    ) {
        return null;
    }

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
