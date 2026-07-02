package com.autospec.service;

import com.autospec.dto.KnowledgeSourceResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class HttpAgentEngineClient implements AgentEngineClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public HttpAgentEngineClient(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            @Value("${autospec.agent-engine.base-url:http://localhost:8000}") String baseUrl
    ) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentGenerationResult generate(String requirement) {
        return generate(requirement, List.of());
    }

    @Override
    public AgentGenerationResult generate(String requirement, List<KnowledgeSourceResponse> retrievedSources) {
        JsonNode root = restClient.post()
                .uri("/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("requirement", requirement, "retrieved_sources", retrievedSources))
                .retrieve()
                .body(JsonNode.class);

        return result(root);
    }

    @Override
    public AgentGenerationResult generatePrd(String requirement) {
        return generatePrd(requirement, List.of());
    }

    @Override
    public AgentGenerationResult generatePrd(String requirement, List<KnowledgeSourceResponse> retrievedSources) {
        JsonNode root = restClient.post()
                .uri("/generate/prd")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("requirement", requirement, "retrieved_sources", retrievedSources))
                .retrieve()
                .body(JsonNode.class);

        return result(root);
    }

    @Override
    public AgentGenerationResult continueAfterPrd(String requirement, String approvedPrdJson) {
        return continueAfterPrd(requirement, approvedPrdJson, List.of());
    }

    @Override
    public AgentGenerationResult continueAfterPrd(
            String requirement,
            String approvedPrdJson,
            List<KnowledgeSourceResponse> retrievedSources
    ) {
        JsonNode prd;
        try {
            prd = objectMapper.readTree(approvedPrdJson);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid approved PRD JSON", ex);
        }
        JsonNode root = restClient.post()
                .uri("/generate/v2/continue")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("requirement", requirement, "prd", prd, "retrieved_sources", retrievedSources))
                .retrieve()
                .body(JsonNode.class);

        return result(root);
    }

    @Override
    public AgentEngineExecutionRecord runNode(String nodeName, String inputJson) {
        JsonNode payload;
        try {
            payload = objectMapper.readTree(inputJson);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid node input JSON", ex);
        }
        JsonNode root = restClient.post()
                .uri("/nodes/{nodeName}/run", nodeName)
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body(JsonNode.class);
        if (root == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Invalid node response");
        }
        return record(root);
    }

    private AgentGenerationResult result(JsonNode root) {
        if (root == null || (root.path("prd").isMissingNode()
                && root.path("backend_design").isMissingNode()
                && root.path("review_report").isMissingNode()
                && root.path("architecture_design").isMissingNode())) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Invalid agent engine response");
        }

        return new AgentGenerationResult(
                nullableJson(root.get("prd")),
                nullableJson(root.get("architecture_design")),
                nullableJson(root.get("backend_design")),
                nullableJson(root.get("frontend_skeleton")),
                nullableJson(root.get("review_report")),
                records(root.path("records"))
        );
    }

    private List<AgentEngineExecutionRecord> records(JsonNode recordsNode) {
        List<AgentEngineExecutionRecord> records = new ArrayList<>();
        if (!recordsNode.isArray()) {
            return records;
        }
        for (JsonNode recordNode : recordsNode) {
            records.add(record(recordNode));
        }
        return records;
    }

    private AgentEngineExecutionRecord record(JsonNode recordNode) {
        return new AgentEngineExecutionRecord(
                text(recordNode, "node_name"),
                text(recordNode, "agent_name"),
                text(recordNode, "status"),
                nullableJson(recordNode.get("input_payload")),
                nullableJson(recordNode.get("output_payload")),
                text(recordNode, "error_message"),
                recordNode.path("duration_ms").isMissingNode() || recordNode.path("duration_ms").isNull()
                        ? null
                        : recordNode.path("duration_ms").asInt(),
                text(recordNode, "prompt_key"),
                text(recordNode, "provider_key"),
                text(recordNode, "model_name")
        );
    }

    private String text(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        return value == null || value.isNull() ? null : value.asText();
    }

    private String nullableJson(JsonNode node) {
        return node == null || node.isNull() ? null : toJson(node);
    }

    private String toJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Invalid agent engine JSON", ex);
        }
    }
}
