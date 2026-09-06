package com.autospec.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.TreeMap;

/** Canonical JSON utilities used for immutable release identity. */
public final class CanonicalJson {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private CanonicalJson() {
    }

    public static String normalize(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("JSON content is required");
        }
        try {
            JsonNode parsed = OBJECT_MAPPER.readTree(json);
            if (parsed == null) {
                throw new IllegalArgumentException("JSON content is required");
            }
            return OBJECT_MAPPER.writeValueAsString(sort(parsed));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid JSON content", exception);
        }
    }

    public static String sha256(String json) {
        return ContentHash.sha256(normalize(json));
    }

    private static JsonNode sort(JsonNode node) {
        if (node == null || node.isValueNode()) {
            return node == null ? JsonNodeFactory.instance.nullNode() : node;
        }
        if (node.isArray()) {
            ArrayNode result = JsonNodeFactory.instance.arrayNode();
            node.forEach(value -> result.add(sort(value)));
            return result;
        }
        if (node.isObject()) {
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            Map<String, JsonNode> fields = new TreeMap<>();
            node.fields().forEachRemaining(entry -> fields.put(entry.getKey(), sort(entry.getValue())));
            fields.forEach(result::set);
            return result;
        }
        return node;
    }
}
