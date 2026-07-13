package com.autospec.workflow.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.regex.Pattern;

@Component
public class RestrictedConditionEvaluator {
    private static final Pattern PATH = Pattern.compile(
            "^\\$\\.[A-Za-z_][A-Za-z0-9_-]*(?:\\.[A-Za-z_][A-Za-z0-9_-]*)*$"
    );
    private static final Set<String> LEAF_FIELDS = Set.of("path", "operator", "value");

    public boolean evaluate(JsonNode payload, JsonNode condition) {
        if (payload == null || condition == null || !condition.isObject()) {
            throw new IllegalArgumentException("payload and condition objects are required");
        }
        if (condition.has("all")) {
            requireOnlyField(condition, "all");
            return evaluateGroup(payload, condition.get("all"), true);
        }
        if (condition.has("any")) {
            requireOnlyField(condition, "any");
            return evaluateGroup(payload, condition.get("any"), false);
        }
        condition.fieldNames().forEachRemaining(field -> {
            if (!LEAF_FIELDS.contains(field)) {
                throw new IllegalArgumentException("unsupported condition field: " + field);
            }
        });
        return evaluateLeaf(payload, condition);
    }

    private boolean evaluateGroup(JsonNode payload, JsonNode children, boolean all) {
        String name = all ? "all" : "any";
        if (children == null || !children.isArray() || children.isEmpty()) {
            throw new IllegalArgumentException(name + " must be a non-empty array");
        }
        for (JsonNode child : children) {
            boolean matched = evaluate(payload, child);
            if (all && !matched) {
                return false;
            }
            if (!all && matched) {
                return true;
            }
        }
        return all;
    }

    private boolean evaluateLeaf(JsonNode payload, JsonNode condition) {
        String path = requiredText(condition, "path");
        if (!PATH.matcher(path).matches()) {
            throw new IllegalArgumentException("unsupported JSON path: " + path);
        }
        String operator = requiredText(condition, "operator");
        JsonNode actual = resolve(payload, path);
        return switch (operator) {
            case "EXISTS" -> !actual.isMissingNode() && !actual.isNull();
            case "EQ" -> !actual.isMissingNode() && actual.equals(requiredValue(condition, operator));
            case "NE" -> !actual.isMissingNode() && !actual.equals(requiredValue(condition, operator));
            case "IN" -> in(actual, requiredValue(condition, operator));
            default -> throw new IllegalArgumentException("unsupported condition operator: " + operator);
        };
    }

    private boolean in(JsonNode actual, JsonNode candidates) {
        if (!candidates.isArray()) {
            throw new IllegalArgumentException("IN value must be an array");
        }
        if (actual.isMissingNode()) {
            return false;
        }
        for (JsonNode candidate : candidates) {
            if (actual.equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    private JsonNode resolve(JsonNode payload, String path) {
        JsonNode current = payload;
        for (String segment : path.substring(2).split("\\.")) {
            if (!current.isObject() || !current.has(segment)) {
                return MissingNode.getInstance();
            }
            current = current.get(segment);
        }
        return current;
    }

    private JsonNode requiredValue(JsonNode condition, String operator) {
        if (!condition.has("value")) {
            throw new IllegalArgumentException(operator + " requires value");
        }
        return condition.get("value");
    }

    private String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("condition " + field + " is required");
        }
        return value.asText();
    }

    private void requireOnlyField(JsonNode condition, String field) {
        if (condition.size() != 1) {
            throw new IllegalArgumentException(field + " cannot be combined with other fields");
        }
    }
}
