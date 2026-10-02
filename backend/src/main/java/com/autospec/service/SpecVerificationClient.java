package com.autospec.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Internal, fail-closed client for the sandboxed executable-spec verifier. */
@Service
public class SpecVerificationClient {
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String baseUrl;
    private final String serviceToken;

    public SpecVerificationClient(
            ObjectMapper objectMapper,
            @Value("${autospec.spec-verifier.url:http://spec-verifier:8010}") String baseUrl,
            @Value("${autospec.spec-verifier.service-token:}") String serviceToken
    ) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.serviceToken = serviceToken == null ? "" : serviceToken;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    public JsonNode verify(String executionId, JsonNode arguments) {
        if (baseUrl.isBlank() || serviceToken.isBlank()) {
            throw new IllegalStateException("spec verifier is not configured");
        }
        if (arguments == null || !arguments.isObject()
                || !arguments.path("contract").isObject()
                || !arguments.path("required_level").isTextual()) {
            throw new IllegalArgumentException("spec.verify requires contract and required_level");
        }
        try {
            var payload = objectMapper.createObjectNode();
            payload.put("execution_id", executionId);
            payload.set("contract", arguments.path("contract").deepCopy());
            if (arguments.has("scope")) {
                payload.set("scope", arguments.path("scope").deepCopy());
            }
            payload.put("required_level", arguments.path("required_level").asText());
            if (arguments.has("rule_profile")) {
                payload.set("rule_profile", arguments.path("rule_profile").deepCopy());
            }
            if (arguments.has("source_digest")) {
                payload.set("source_digest", arguments.path("source_digest").deepCopy());
            }
            if (arguments.has("timeout_ms")) {
                payload.set("timeout_ms", arguments.path("timeout_ms").deepCopy());
            }
            String payloadJson = objectMapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/verify"))
                    .timeout(Duration.ofSeconds(35))
                    .header("Content-Type", "application/json")
                    .header(ToolGatewayService.SERVICE_HEADER, serviceToken)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payloadJson.getBytes(StandardCharsets.UTF_8)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                String responseBody = response.body() == null ? "" : response.body().trim();
                if (responseBody.length() > 512) {
                    responseBody = responseBody.substring(0, 512);
                }
                throw new IllegalStateException(
                        "spec verifier rejected the request: HTTP " + response.statusCode()
                                + (responseBody.isBlank() ? "" : ": " + responseBody)
                                + " (request=" + requestSummary(payload) + ")"
                );
            }
            return objectMapper.readTree(response.body());
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("spec verifier returned invalid JSON", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("spec verifier is unavailable", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("spec verifier is unavailable", exception);
        }
    }

    private String requestSummary(com.fasterxml.jackson.databind.node.ObjectNode payload) {
        JsonNode executionId = payload.get("execution_id");
        JsonNode contract = payload.get("contract");
        JsonNode scope = payload.get("scope");
        JsonNode requiredLevel = payload.get("required_level");
        JsonNode ruleProfile = payload.get("rule_profile");
        JsonNode sourceDigest = payload.get("source_digest");
        JsonNode timeoutMs = payload.get("timeout_ms");
        return "execution_id=" + textNodeSummary(executionId)
                + ", contract_object=" + (contract != null && contract.isObject())
                + ", scope=" + textNodeValue(scope)
                + ", required_level=" + textNodeValue(requiredLevel)
                + ", rule_profile=" + textNodeSummary(ruleProfile)
                + ", source_digest=" + digestSummary(sourceDigest)
                + ", timeout_ms=" + timeoutSummary(timeoutMs);
    }

    private String textNodeSummary(JsonNode node) {
        if (node == null || node.isNull()) {
            return "missing";
        }
        return node.isTextual() ? "text(" + node.textValue().length() + ")" : nodeType(node);
    }

    private String textNodeValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return "missing";
        }
        return node.isTextual() ? node.textValue() : nodeType(node);
    }

    private String nodeType(JsonNode node) {
        return node == null || node.isNull() ? "missing" : node.getNodeType().name();
    }

    private String digestSummary(JsonNode node) {
        return textNodeSummary(node)
                + (node != null && node.isTextual()
                ? ",hex64=" + node.textValue().matches("[0-9a-f]{64}")
                : "");
    }

    private String timeoutSummary(JsonNode node) {
        if (node == null || node.isNull()) {
            return "missing";
        }
        if (!node.isIntegralNumber()) {
            return nodeType(node);
        }
        long value = node.asLong();
        return "integer(" + value + "),range1000to900000=" + (value >= 1_000 && value <= 900_000);
    }
}
