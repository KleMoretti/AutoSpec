package com.autospec.workflow.spec;

import java.util.List;

public record WorkflowApprovalDocument(String mode, List<String> allowedActions) {
    public WorkflowApprovalDocument {
        mode = mode == null || mode.isBlank() ? "NONE" : mode;
        allowedActions = allowedActions == null ? List.of() : List.copyOf(allowedActions);
    }

    public static WorkflowApprovalDocument none() {
        return new WorkflowApprovalDocument("NONE", List.of());
    }
}
