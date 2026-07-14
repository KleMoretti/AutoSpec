package com.autospec.workflow.runtime;

import com.autospec.workflow.spec.WorkflowEdgeDocument;
import com.autospec.workflow.spec.WorkflowApprovalDocument;
import com.autospec.workflow.spec.WorkflowNodeDocument;
import com.autospec.workflow.spec.WorkflowSpecDocument;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class WorkflowSnapshotParser {
    private final ObjectMapper objectMapper;

    public WorkflowSnapshotParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public WorkflowSpecDocument parse(String snapshotJson) {
        if (snapshotJson == null || snapshotJson.isBlank()) {
            throw new IllegalArgumentException("workflow snapshot is required");
        }
        try {
            JsonNode root = objectMapper.readTree(snapshotJson);
            return new WorkflowSpecDocument(
                    requiredText(root, "workflow_key"),
                    requiredText(root, "version"),
                    root.path("runtime").path("max_parallel_nodes").asInt(1),
                    nodes(root.path("nodes")),
                    edges(root.path("edges")),
                    strings(root.path("entry_nodes"))
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("invalid workflow snapshot", exception);
        }
    }

    private List<WorkflowNodeDocument> nodes(JsonNode values) {
        if (!values.isArray()) {
            throw new IllegalArgumentException("workflow snapshot nodes must be an array");
        }
        List<WorkflowNodeDocument> result = new ArrayList<>();
        values.forEach(node -> result.add(new WorkflowNodeDocument(
                requiredText(node, "node_id"),
                strings(node.path("depends_on")),
                approval(node.path("approval"))
        )));
        return result;
    }

    private WorkflowApprovalDocument approval(JsonNode value) {
        if (value.isMissingNode() || value.isNull()) {
            return WorkflowApprovalDocument.none();
        }
        if (!value.isObject()) {
            throw new IllegalArgumentException("workflow node approval must be an object");
        }
        return new WorkflowApprovalDocument(
                value.path("mode").asText("NONE"),
                strings(value.path("allowed_actions"))
        );
    }

    private List<WorkflowEdgeDocument> edges(JsonNode values) {
        if (values.isMissingNode() || values.isNull()) {
            return List.of();
        }
        if (!values.isArray()) {
            throw new IllegalArgumentException("workflow snapshot edges must be an array");
        }
        List<WorkflowEdgeDocument> result = new ArrayList<>();
        values.forEach(edge -> result.add(new WorkflowEdgeDocument(
                requiredText(edge, "from_node"),
                requiredText(edge, "to_node"),
                edge.path("edge_type").asText("NORMAL")
        )));
        return result;
    }

    private List<String> strings(JsonNode values) {
        if (values.isMissingNode() || values.isNull()) {
            return List.of();
        }
        if (!values.isArray()) {
            throw new IllegalArgumentException("expected an array");
        }
        List<String> result = new ArrayList<>();
        values.forEach(value -> result.add(value.asText()));
        return result;
    }

    private String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value.isBlank()) {
            throw new IllegalArgumentException("workflow snapshot field is required: " + field);
        }
        return value;
    }
}
