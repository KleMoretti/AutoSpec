package com.autospec.workflow.spec;

import java.util.List;

public record WorkflowNodeDocument(
        String nodeId,
        List<String> dependsOn,
        WorkflowApprovalDocument approval,
        String agentName,
        String artifactType,
        int timeoutMs
) {
    public WorkflowNodeDocument {
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        approval = approval == null ? WorkflowApprovalDocument.none() : approval;
    }

    public WorkflowNodeDocument(
            String nodeId,
            List<String> dependsOn,
            WorkflowApprovalDocument approval
    ) {
        this(nodeId, dependsOn, approval, null, null, 30000);
    }

    public WorkflowNodeDocument(String nodeId, List<String> dependsOn) {
        this(nodeId, dependsOn, WorkflowApprovalDocument.none());
    }
}
