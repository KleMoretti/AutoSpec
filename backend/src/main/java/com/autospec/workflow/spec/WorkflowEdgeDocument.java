package com.autospec.workflow.spec;

public record WorkflowEdgeDocument(String fromNode, String toNode, String edgeType) {
    public WorkflowEdgeDocument {
        edgeType = edgeType == null ? "NORMAL" : edgeType;
    }

    public boolean isRework() {
        return "REWORK".equals(edgeType);
    }
}
