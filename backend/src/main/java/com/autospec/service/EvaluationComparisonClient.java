package com.autospec.service;

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

/** Internal read-only client for the Agent Engine evaluation evidence endpoint. */
@Service
public class EvaluationComparisonClient {
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String baseUrl;
    private final String serviceToken;

    public EvaluationComparisonClient(
            ObjectMapper objectMapper,
            @Value("${autospec.agent-engine.url:http://agent-engine:8000}") String baseUrl,
            @Value("${autospec.agent-engine.service-token:}") String serviceToken
    ) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.serviceToken = serviceToken == null ? "" : serviceToken;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    public JsonNode fetch() {
        if (baseUrl.isBlank() || serviceToken.isBlank()) {
            throw new IllegalStateException("evaluation comparison service is not configured");
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/evaluation/ablation"))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .header(ToolGatewayService.SERVICE_HEADER, serviceToken)
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("evaluation comparison service returned HTTP " + response.statusCode());
            }
            return objectMapper.readTree(response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("evaluation comparison service is unavailable", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("evaluation comparison service is unavailable", exception);
        }
    }
}
