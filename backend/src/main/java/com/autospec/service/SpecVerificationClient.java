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
            @Value("${autospec.agent-engine.service-token:}") String serviceToken
    ) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.serviceToken = serviceToken == null ? "" : serviceToken;
        this.httpClient = HttpClient.newBuilder()
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
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/verify"))
                    .timeout(Duration.ofSeconds(35))
                    .header("Content-Type", "application/json")
                    .header(ToolGatewayService.SERVICE_HEADER, serviceToken)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("spec verifier rejected the request: HTTP " + response.statusCode());
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
}
