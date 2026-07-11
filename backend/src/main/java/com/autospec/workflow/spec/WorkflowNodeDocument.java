package com.autospec.workflow.spec;

import java.util.List;

public record WorkflowNodeDocument(String nodeId, List<String> dependsOn) {
    public WorkflowNodeDocument {
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
    }
}
