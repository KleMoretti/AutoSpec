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
import java.util.Set;

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
            if (!root.isObject()) {
                throw new IllegalArgumentException("workflow snapshot must be a JSON object");
            }
            int protocolVersion = root.path("protocol_version").asInt(0);
            if (protocolVersion < 0 || protocolVersion > 2) {
                throw new IllegalArgumentException("unsupported workflow protocol_version: " + protocolVersion);
            }
            if (protocolVersion >= 1) {
                rejectUnknownFields(
                        root,
                        Set.of(
                                "workflow_key",
                                "version",
                                "protocol_version",
                                "runtime",
                                "nodes",
                                "edges",
                                "entry_nodes"
                        ),
                        "workflow"
                );
            }
            int maxParallelNodes = root.path("runtime").path("max_parallel_nodes").asInt(1);
            int maxReviewRounds = root.path("runtime").path("max_review_rounds").asInt(0);
            if (maxParallelNodes < 1 || maxParallelNodes > 32) {
                throw new IllegalArgumentException("runtime max_parallel_nodes must be between 1 and 32");
            }
            if (maxReviewRounds < 0 || maxReviewRounds > 10) {
                throw new IllegalArgumentException("runtime max_review_rounds must be between 0 and 10");
            }
            return new WorkflowSpecDocument(
                    requiredText(root, "workflow_key"),
                    requiredText(root, "version"),
                    protocolVersion,
                    maxParallelNodes,
                    maxReviewRounds,
                    nodes(root.path("nodes"), protocolVersion),
                    edges(root.path("edges"), protocolVersion),
                    strings(root.path("entry_nodes"))
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("invalid workflow snapshot", exception);
        }
    }

    private List<WorkflowNodeDocument> nodes(JsonNode values, int protocolVersion) {
        if (!values.isArray()) {
            throw new IllegalArgumentException("workflow snapshot nodes must be an array");
        }
        List<WorkflowNodeDocument> result = new ArrayList<>();
        values.forEach(node -> {
            if (protocolVersion >= 1) {
                rejectUnknownFields(
                        node,
                        Set.of(
                                "node_id",
                                "agent_name",
                                "input_schema",
                                "input_schema_hash",
                                "output_schema",
                                "output_schema_hash",
                                "artifact_type",
                                "prompt_key",
                                "prompt_version",
                                "prompt_checksum",
                                "context_policy",
                                "model_policy",
                                "retry_policy",
                                "fallback",
                                "tool_policy",
                                "retrieval_policy",
                                "timeout_ms",
                                "requires_human_approval",
                                "depends_on",
                                "approval"
                        ),
                        "workflow node"
                );
            }
            int timeoutMs = node.path("timeout_ms").asInt(30000);
            if (timeoutMs < 1 || timeoutMs > 3_600_000) {
                throw new IllegalArgumentException("workflow node timeout_ms must be between 1 and 3600000");
            }
            result.add(new WorkflowNodeDocument(
                    requiredText(node, "node_id"),
                    strings(node.path("depends_on")),
                    approval(node.path("approval")),
                    optionalText(node, "agent_name"),
                    optionalText(node, "input_schema"),
                    optionalText(node, "input_schema_hash"),
                    optionalText(node, "output_schema"),
                    optionalText(node, "output_schema_hash"),
                    optionalText(node, "artifact_type"),
                    optionalText(node, "prompt_key"),
                    optionalText(node, "prompt_version"),
                    optionalText(node, "prompt_checksum"),
                    optionalObject(node, "context_policy"),
                    optionalObject(node, "model_policy"),
                    optionalObject(node, "retry_policy"),
                    optionalObject(node, "fallback"),
                    optionalObject(node, "tool_policy"),
                    timeoutMs,
                    optionalObject(node, "retrieval_policy")
            ));
        });
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

    private List<WorkflowEdgeDocument> edges(JsonNode values, int protocolVersion) {
        if (values.isMissingNode() || values.isNull()) {
            return List.of();
        }
        if (!values.isArray()) {
            throw new IllegalArgumentException("workflow snapshot edges must be an array");
        }
        List<WorkflowEdgeDocument> result = new ArrayList<>();
        values.forEach(edge -> {
            if (protocolVersion >= 1) {
                rejectUnknownFields(
                        edge,
                        Set.of("from_node", "to_node", "edge_type", "condition"),
                        "workflow edge"
                );
            }
            result.add(new WorkflowEdgeDocument(
                    requiredText(edge, "from_node"),
                    requiredText(edge, "to_node"),
                    edge.path("edge_type").asText("NORMAL"),
                    optionalObject(edge, "condition")
            ));
        });
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

    private String optionalText(JsonNode node, String field) {
        String value = node.path(field).asText();
        return value.isBlank() ? null : value;
    }

    private JsonNode optionalObject(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (!value.isObject()) {
            throw new IllegalArgumentException("workflow snapshot field must be an object: " + field);
        }
        return value.deepCopy();
    }

    private void rejectUnknownFields(JsonNode object, Set<String> allowed, String context) {
        if (!object.isObject()) {
            throw new IllegalArgumentException(context + " must be an object");
        }
        object.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) {
                throw new IllegalArgumentException("unsupported " + context + " field: " + field);
            }
        });
    }
}
