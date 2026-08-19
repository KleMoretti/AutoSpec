package com.autospec.workflow.spec;

import com.fasterxml.jackson.databind.JsonNode;

public record WorkflowEdgeDocument(
        String fromNode,
        String toNode,
        String edgeType,
        JsonNode condition
) {
    public WorkflowEdgeDocument {
        edgeType = edgeType == null ? "NORMAL" : edgeType;
        condition = condition == null || condition.isMissingNode() || condition.isNull()
                ? null
                : condition.deepCopy();
    }

    public WorkflowEdgeDocument(String fromNode, String toNode, String edgeType) {
        this(fromNode, toNode, edgeType, null);
    }

    public boolean isRework() {
        return "REWORK".equals(edgeType);
    }

    public boolean isConditional() {
        return "CONDITIONAL".equals(edgeType);
    }
}
