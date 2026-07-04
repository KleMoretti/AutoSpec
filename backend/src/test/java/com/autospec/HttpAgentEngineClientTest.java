package com.autospec;

import com.autospec.dto.KnowledgeSourceResponse;
import com.autospec.service.AgentGenerationResult;
import com.autospec.service.HttpAgentEngineClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class HttpAgentEngineClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void generateV4PostsRequirementSourcesAndMapsAgentRecords() throws Exception {
        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/generate/v4", exchange -> {
            requestPath.set(exchange.getRequestURI().getPath());
            requestBody.set(objectMapper.readTree(exchange.getRequestBody()));
            respondJson(exchange, """
                    {
                      "prd": {"project_name": "Traceable Agent"},
                      "architecture_design": {"modules": ["api"]},
                      "backend_design": {"apis": [{"path": "/projects"}]},
                      "frontend_skeleton": {"routes": ["/projects"]},
                      "review_report": {"score": 91, "issues": []},
                      "evaluation_report": {"overall_score": 93},
                      "records": [{
                        "node_name": "product_manager",
                        "agent_name": "ProductManagerAgent_v1",
                        "status": "SUCCEEDED",
                        "input_payload": {"requirement": "Build traceable agent"},
                        "output_payload": {"project_name": "Traceable Agent"},
                        "error_message": null,
                        "duration_ms": 42,
                        "prompt_key": "ProductManagerAgent",
                        "provider_key": "local",
                        "model_name": "deterministic-fixture"
                      }]
                    }
                    """);
        });
        server.start();

        HttpAgentEngineClient client = new HttpAgentEngineClient(
                RestClient.builder(),
                objectMapper,
                "http://localhost:" + server.getAddress().getPort()
        );
        AgentGenerationResult result = client.generateV4(
                "Build traceable agent",
                List.of(new KnowledgeSourceResponse(7L, "PRD", "Source PRD", 2, "approved source"))
        );

        assertThat(requestPath.get()).isEqualTo("/generate/v4");
        assertThat(requestBody.get().path("requirement").asText()).isEqualTo("Build traceable agent");
        assertThat(requestBody.get().path("retrieved_sources").get(0).path("artifactId").asLong()).isEqualTo(7L);

        assertThat(result.prdJson()).contains("Traceable Agent");
        assertThat(result.evaluationReportJson()).contains("overall_score");
        assertThat(result.records()).hasSize(1);
        assertThat(result.records().get(0).nodeName()).isEqualTo("product_manager");
        assertThat(result.records().get(0).durationMs()).isEqualTo(42);
        assertThat(result.records().get(0).providerKey()).isEqualTo("local");
        assertThat(result.records().get(0).modelName()).isEqualTo("deterministic-fixture");
    }

    private void respondJson(HttpExchange exchange, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
