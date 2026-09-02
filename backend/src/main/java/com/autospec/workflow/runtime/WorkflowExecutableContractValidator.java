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
            "route_key",
            "provider_key",
            "model_name",
            "temperature",
            "context_window_tokens",
            "max_output_tokens",
            "max_calls",
            "input_cost_per_million",
            "cached_input_cost_per_million",
            "output_cost_per_million",
            "required_capabilities"
    );
    private static final Set<String> CONTEXT_FIELDS = Set.of(
            "version",
            "tokenizer",
            "max_input_tokens",
            "prompt_token_reserve",
            "manifest_token_reserve",
            "field_priority",
            "required_paths",
            "compression_strategy",
            "rag_token_budget",
            "long_text_token_budget",
            "max_single_source_ratio"
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
        if (spec.protocolVersion() < 1 || spec.protocolVersion() > 2) {
            throw new IllegalArgumentException(
                    "unsupported workflow protocol_version: " + spec.protocolVersion()
            );
        }
        for (WorkflowNodeDocument node : spec.nodes()) {
            validateNode(node, spec.protocolVersion());
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

    private static void validateNode(WorkflowNodeDocument node, int protocolVersion) {
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
        int contextWindow = model.path("context_window_tokens").asInt(128_000);
        int maxOutput = model.path("max_output_tokens").asInt(4_096);
        int maxCalls = model.path("max_calls").asInt(1);
        if (contextWindow < 1_024 || maxOutput < 1 || maxOutput >= contextWindow) {
            throw new IllegalArgumentException(
                    "Node model token limits are invalid: " + node.nodeId()
            );
        }
        if (maxCalls < 1 || maxCalls > 8) {
            throw new IllegalArgumentException(
                    "Node model_policy max_calls must be between 1 and 8: " + node.nodeId()
            );
        }
        for (String price : Set.of(
                "input_cost_per_million",
                "cached_input_cost_per_million",
                "output_cost_per_million"
        )) {
            if (model.has(price)
                    && (!model.path(price).isNumber() || model.path(price).asDouble() < 0)) {
                throw new IllegalArgumentException(
                        "Node model_policy " + price + " must not be negative: " + node.nodeId()
                );
            }
        }
        if (model.has("required_capabilities")
                && !model.path("required_capabilities").isArray()) {
            throw new IllegalArgumentException(
                    "Node model_policy required_capabilities must be an array: " + node.nodeId()
            );
        }

        if (protocolVersion >= 2) {
            validateContextPolicy(node, contextWindow, maxOutput);
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

    private static void validateContextPolicy(
            WorkflowNodeDocument node,
            int contextWindow,
            int maxOutput
    ) {
        JsonNode context = node.contextPolicy();
        if (context == null || context.isEmpty()) {
            throw new IllegalArgumentException(
                    "Node context_policy is required by protocol version 2: " + node.nodeId()
            );
        }
        rejectUnknown(context, CONTEXT_FIELDS, "context_policy", node.nodeId());
        if (text(context, "version") == null || text(context, "tokenizer") == null) {
            throw new IllegalArgumentException(
                    "Node context_policy version and tokenizer are required: " + node.nodeId()
            );
        }
        if (!"schema-aware-v2".equals(text(context, "compression_strategy"))) {
            throw new IllegalArgumentException(
                    "Node context_policy compression_strategy must be schema-aware-v2: "
                            + node.nodeId()
            );
        }
        int maxInput = context.path("max_input_tokens").asInt(0);
        int promptReserve = context.path("prompt_token_reserve").asInt(0);
        int manifestReserve = context.path("manifest_token_reserve").asInt(0);
        int ragBudget = context.path("rag_token_budget").asInt(0);
        int longTextBudget = context.path("long_text_token_budget").asInt(0);
        double sourceRatio = context.path("max_single_source_ratio").asDouble(0);
        if (maxInput < 256
                || promptReserve < 0
                || manifestReserve < 0
                || promptReserve + manifestReserve >= maxInput
                || maxInput + maxOutput > contextWindow
                || ragBudget < 0
                || longTextBudget < 0
                || ragBudget > maxInput - promptReserve - manifestReserve
                || longTextBudget > maxInput - promptReserve - manifestReserve
                || !Double.isFinite(sourceRatio)
                || sourceRatio <= 0
                || sourceRatio > 1) {
            throw new IllegalArgumentException(
                    "Node context_policy token quotas are invalid: " + node.nodeId()
            );
        }
        if (!context.path("field_priority").isArray()
                || context.path("field_priority").isEmpty()
                || !context.path("required_paths").isArray()
                || context.path("required_paths").isEmpty()) {
            throw new IllegalArgumentException(
                    "Node context_policy priorities and required paths must be non-empty arrays: "
                            + node.nodeId()
            );
        }
        context.path("required_paths").forEach(path -> {
            if (!path.isTextual() || !path.asText().startsWith("$.")) {
                throw new IllegalArgumentException(
                        "Node context_policy required_paths must use $. prefixes: " + node.nodeId()
                );
            }
        });
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
