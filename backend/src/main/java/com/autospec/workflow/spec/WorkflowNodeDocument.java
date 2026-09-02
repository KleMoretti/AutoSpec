package com.autospec.workflow.spec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.util.List;

public record WorkflowNodeDocument(
        String nodeId,
        List<String> dependsOn,
        WorkflowApprovalDocument approval,
        String agentName,
        String inputSchema,
        String inputSchemaHash,
        String outputSchema,
        String outputSchemaHash,
        String artifactType,
        String promptKey,
        String promptVersion,
        String promptChecksum,
        JsonNode contextPolicy,
        JsonNode modelPolicy,
        JsonNode retryPolicy,
        JsonNode fallback,
        int timeoutMs
) {
    public WorkflowNodeDocument {
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        approval = approval == null ? WorkflowApprovalDocument.none() : approval;
        contextPolicy = objectOrEmpty(contextPolicy, "context_policy");
        modelPolicy = objectOrEmpty(modelPolicy, "model_policy");
        retryPolicy = objectOrEmpty(retryPolicy, "retry_policy");
        fallback = objectOrEmpty(fallback, "fallback");
    }

    public WorkflowNodeDocument(
            String nodeId,
            List<String> dependsOn,
            WorkflowApprovalDocument approval,
            String agentName,
            String artifactType,
            int timeoutMs
    ) {
        this(
                nodeId,
                dependsOn,
                approval,
                agentName,
                null,
                null,
                null,
                null,
                artifactType,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                timeoutMs
        );
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

    public boolean hasExecutableContract() {
        return nonBlank(inputSchema)
                && nonBlank(inputSchemaHash)
                && nonBlank(outputSchema)
                && nonBlank(outputSchemaHash)
                && nonBlank(promptKey)
                && nonBlank(promptVersion)
                && nonBlank(promptChecksum)
                && !modelPolicy.isEmpty()
                && !retryPolicy.isEmpty();
    }

    private static JsonNode objectOrEmpty(JsonNode value, String field) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return JsonNodeFactory.instance.objectNode();
        }
        if (!value.isObject()) {
            throw new IllegalArgumentException("workflow node " + field + " must be an object");
        }
        return value.deepCopy();
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }
}
