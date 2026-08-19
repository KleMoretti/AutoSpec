package com.autospec.workflow.runtime;

import com.autospec.workflow.spec.WorkflowEdgeDocument;
import com.autospec.workflow.spec.WorkflowNodeDocument;
import com.autospec.workflow.spec.WorkflowSpecDocument;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Fail-closed validation for the executable workflow protocol.
 *
 * <p>Snapshots without {@code protocol_version} remain readable for historical runs. A snapshot
 * that opts in to protocol version 1 must carry every piece of execution metadata that the worker
 * verifies before invoking a handler.</p>
 */
public final class WorkflowExecutableContractValidator {
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Set<String> MODEL_FIELDS = Set.of(
            "route_key", "provider_key", "model_name", "temperature"
    );
    private static final Set<String> RETRY_FIELDS = Set.of(
            "max_attempts",
            "retry_on_validation_error",
            "initial_delay_ms",
            "max_delay_ms",
            "multiplier",
            "retryable_errors"
    );
    private static final Set<String> FALLBACK_FIELDS = Set.of(
            "enabled", "handler", "model_policy"
    );

    private WorkflowExecutableContractValidator() {
    }

    public static void validate(WorkflowSpecDocument spec) {
        if (spec.protocolVersion() == 0) {
            return;
        }
        if (spec.protocolVersion() != 1) {
            throw new IllegalArgumentException(
                    "unsupported workflow protocol_version: " + spec.protocolVersion()
            );
        }
        for (WorkflowNodeDocument node : spec.nodes()) {
            validateNode(node);
        }
        boolean hasRework = false;
        RestrictedConditionEvaluator conditionEvaluator = new RestrictedConditionEvaluator();
        for (WorkflowEdgeDocument edge : spec.edges()) {
            if (!Set.of("NORMAL", "CONDITIONAL", "REWORK").contains(edge.edgeType())) {
                throw new IllegalArgumentException("unsupported workflow edge_type: " + edge.edgeType());
            }
            if ("NORMAL".equals(edge.edgeType()) && edge.condition() != null) {
                throw new IllegalArgumentException("NORMAL workflow edge cannot declare condition");
            }
            if ((edge.isConditional() || edge.isRework()) && edge.condition() == null) {
                throw new IllegalArgumentException(edge.edgeType() + " workflow edge requires condition");
            }
            if (edge.condition() != null) {
                conditionEvaluator.evaluate(JsonNodeFactory.instance.objectNode(), edge.condition());
            }
            hasRework |= edge.isRework();
        }
        if (hasRework && spec.maxReviewRounds() < 1) {
            throw new IllegalArgumentException("REWORK workflow edges require max_review_rounds greater than zero");
        }
    }

    private static void validateNode(WorkflowNodeDocument node) {
        if (node.agentName() == null || node.agentName().isBlank()) {
            throw new IllegalArgumentException("Node agent_name is required: " + node.nodeId());
        }
        if (!node.hasExecutableContract()) {
            throw new IllegalArgumentException(
                    "Node executable contract is incomplete: " + node.nodeId()
            );
        }
        requireHash(node.inputSchemaHash(), "input_schema_hash", node.nodeId());
        requireHash(node.outputSchemaHash(), "output_schema_hash", node.nodeId());
        requireHash(node.promptChecksum(), "prompt_checksum", node.nodeId());
        rejectUnknown(node.modelPolicy(), MODEL_FIELDS, "model_policy", node.nodeId());
        rejectUnknown(node.retryPolicy(), RETRY_FIELDS, "retry_policy", node.nodeId());
        rejectUnknown(node.fallback(), FALLBACK_FIELDS, "fallback", node.nodeId());

        JsonNode model = node.modelPolicy();
        boolean hasRoute = text(model, "route_key") != null;
        boolean hasProvider = text(model, "provider_key") != null;
        boolean hasModel = text(model, "model_name") != null;
        if (!hasRoute && !(hasProvider && hasModel)) {
            throw new IllegalArgumentException(
                    "Node model_policy requires route_key or provider_key plus model_name: "
                            + node.nodeId()
            );
        }
        if (model.has("temperature")) {
            double temperature = model.path("temperature").asDouble(Double.NaN);
            if (!Double.isFinite(temperature) || temperature < 0 || temperature > 2) {
                throw new IllegalArgumentException(
                        "Node model_policy temperature must be between 0 and 2: " + node.nodeId()
                );
            }
        }

        JsonNode retry = node.retryPolicy();
        int maxAttempts = retry.path("max_attempts").asInt(1);
        if (maxAttempts < 1 || maxAttempts > 5) {
            throw new IllegalArgumentException(
                    "Node retry_policy max_attempts must be between 1 and 5: " + node.nodeId()
            );
        }
        if (retry.has("retryable_errors") && !retry.path("retryable_errors").isArray()) {
            throw new IllegalArgumentException(
                    "Node retry_policy retryable_errors must be an array: " + node.nodeId()
            );
        }
    }

    private static void requireHash(String value, String field, String nodeId) {
        if (value == null || !SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Node " + field + " must be a lowercase SHA-256 digest: " + nodeId
            );
        }
    }

    private static void rejectUnknown(
            JsonNode object,
            Set<String> allowed,
            String field,
            String nodeId
    ) {
        object.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) {
                throw new IllegalArgumentException(
                        "Unsupported node " + field + " field " + name + ": " + nodeId
                );
            }
        });
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() && !value.asText().isBlank()
                ? value.asText()
                : null;
    }
}
