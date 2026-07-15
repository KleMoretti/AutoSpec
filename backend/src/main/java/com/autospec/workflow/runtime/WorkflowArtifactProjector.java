package com.autospec.workflow.runtime;

import com.autospec.entity.Artifact;
import com.autospec.entity.WorkflowNodeRun;

public interface WorkflowArtifactProjector {
    Artifact project(WorkflowNodeRun nodeRun, String outputJson, String status);

    static WorkflowArtifactProjector none() {
        return (nodeRun, outputJson, status) -> null;
    }
}
