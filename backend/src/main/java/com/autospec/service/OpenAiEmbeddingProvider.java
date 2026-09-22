package com.autospec.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.autospec.util.ContentHash;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** OpenAI-compatible embedding API; credentials and response bodies never enter exceptions. */
public final class OpenAiEmbeddingProvider implements EmbeddingProvider {
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final int dimensions;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    public OpenAiEmbeddingProvider(String baseUrl, String apiKey, String model, int dimensions) {
        if (baseUrl == null || baseUrl.isBlank() || apiKey == null || apiKey.isBlank()
                || model == null || model.isBlank() || model.length() > 80 || dimensions <= 0) {
            throw new IllegalArgumentException("Embedding URL, key, model and dimensions are required");
        }
        URI base = URI.create(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
        if (!"https".equalsIgnoreCase(base.getScheme())
                && !("http".equalsIgnoreCase(base.getScheme()) && "localhost".equalsIgnoreCase(base.getHost()))) {
            throw new IllegalArgumentException("Embedding endpoint must use HTTPS");
        }
        this.endpoint = base.resolve("embeddings");
        this.apiKey = apiKey;
        this.model = model;
        this.dimensions = dimensions;
    }

    @Override
    public String modelVersion() {
        return "openai-compatible:" + model + ":" + ContentHash.sha256(endpoint.toString()).substring(0, 12);
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public double[] embed(String text) {
        try {
            String payload = mapper.writeValueAsString(java.util.Map.of("model", model, "input", text));
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(12))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Embedding endpoint failed with HTTP " + response.statusCode());
            }
            JsonNode vector = mapper.readTree(response.body()).path("data").path(0).path("embedding");
            if (!vector.isArray() || vector.size() != dimensions) {
                throw new IllegalStateException("Embedding endpoint returned incompatible dimensions");
            }
            double[] result = new double[dimensions];
            for (int i = 0; i < dimensions; i++) {
                result[i] = vector.get(i).asDouble(Double.NaN);
                if (!Double.isFinite(result[i])) {
                    throw new IllegalStateException("Embedding endpoint returned non-finite values");
                }
            }
            return result;
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Embedding endpoint request interrupted");
        } catch (Exception exception) {
            throw new IllegalStateException("Embedding endpoint request failed");
        }
    }
}
