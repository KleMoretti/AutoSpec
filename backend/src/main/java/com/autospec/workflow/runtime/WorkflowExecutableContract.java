package com.autospec.workflow.runtime;

import com.autospec.workflow.spec.WorkflowNodeDocument;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/** Frozen node capability envelope shared by the Java control plane and Python worker. */
public record WorkflowExecutableContract(
        int protocolVersion,
        String contractHash,
        String inputSchema,
        String inputSchemaHash,
        String outputSchema,
        String outputSchemaHash,
        String promptKey,
        String promptVersion,
        String promptChecksum,
        JsonNode contextPolicy,
        JsonNode modelPolicy,
        JsonNode retryPolicy,
        JsonNode fallback,
        JsonNode toolPolicy,
        JsonNode agentLoopPolicy
) {
    public static WorkflowExecutableContract from(
            int protocolVersion,
            WorkflowNodeDocument node,
            String handlerKey,
            String handlerVersion,
            ObjectMapper objectMapper
    ) {
        if (protocolVersion < 1 || !node.hasExecutableContract()) {
            return null;
        }
        Map<String, Object> material = new TreeMap<>();
        material.put("fallback", generic(objectMapper, node.fallback()));
        material.put("handler_key", handlerKey);
        material.put("handler_version", handlerVersion);
        material.put("input_schema", node.inputSchema());
        material.put("input_schema_hash", node.inputSchemaHash());
        material.put("model_policy", generic(objectMapper, node.modelPolicy()));
        material.put("output_schema", node.outputSchema());
        material.put("output_schema_hash", node.outputSchemaHash());
        material.put("prompt_checksum", node.promptChecksum());
        material.put("prompt_key", node.promptKey());
        material.put("prompt_version", node.promptVersion());
        material.put("protocol_version", protocolVersion);
        material.put("retry_policy", generic(objectMapper, node.retryPolicy()));
        material.put("timeout_ms", node.timeoutMs());
        if (protocolVersion >= 2) {
            material.put("context_policy", generic(objectMapper, node.contextPolicy()));
        }
        if (protocolVersion >= 2 && !node.toolPolicy().isEmpty()) {
            material.put("tool_policy", generic(objectMapper, node.toolPolicy()));
        }
        if (protocolVersion >= 2 && !node.agentLoopPolicy().isEmpty()) {
            material.put("agent_loop_policy", generic(objectMapper, node.agentLoopPolicy()));
        }
        return new WorkflowExecutableContract(
                protocolVersion,
                sha256(canonicalJson(objectMapper, material)),
                node.inputSchema(),
                node.inputSchemaHash(),
                node.outputSchema(),
                node.outputSchemaHash(),
                node.promptKey(),
                node.promptVersion(),
                node.promptChecksum(),
                node.contextPolicy().deepCopy(),
                node.modelPolicy().deepCopy(),
                node.retryPolicy().deepCopy(),
                node.fallback().deepCopy(),
                node.toolPolicy().deepCopy(),
                node.agentLoopPolicy().deepCopy()
        );
    }

    private static Object generic(ObjectMapper objectMapper, JsonNode value) {
        return objectMapper.convertValue(value, Object.class);
    }

    private static String canonicalJson(ObjectMapper objectMapper, Map<String, Object> material) {
        try {
            return objectMapper.copy()
                    .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(material);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to canonicalize workflow node contract", exception);
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
