package com.autospec.service;

import com.sun.net.httpserver.HttpServer;
import com.autospec.entity.Artifact;
import com.autospec.entity.WorkflowOutbox;
import com.autospec.mapper.WorkflowOutboxMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmbeddingProviderTest {
    @Test
    void approvalOutboxStampsCurrentEmbeddingVersion() throws Exception {
        var mapper = mock(WorkflowOutboxMapper.class);
        var provider = mock(KnowledgeEmbeddingService.class);
        when(provider.modelVersion()).thenReturn("openai-compatible:semantic:0123456789ab");
        var outbox = new ArtifactApprovalOutboxService(mapper, new ObjectMapper(),
                mock(KnowledgeDocumentService.class), mock(KnowledgeCorpusEpochService.class), provider);
        var artifact = new Artifact();
        artifact.setId(5L);
        artifact.setProjectId(7L);
        artifact.setType("PRD");
        artifact.setVersion(1);
        artifact.setStatus("APPROVED");
        artifact.setContent("approved content");
        outbox.enqueue(artifact);
        var captured = ArgumentCaptor.forClass(WorkflowOutbox.class);
        verify(mapper).insert(captured.capture());
        var payload = new ObjectMapper().readTree(captured.getValue().getPayloadJson());
        assertThat(payload.path("embedding_model").asText())
                .isEqualTo("openai-compatible:semantic:0123456789ab");
    }

    @Test
    void productionCannotUseHashFixture() {
        assertThatThrownBy(() -> new KnowledgeEmbeddingService(
                "production", "fixture", "", "", "", 0))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void liveProviderReadsRealEndpointShapeAndChecksDimensions() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer test-key");
            byte[] response = "{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2,0.3]}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();
        try {
            var provider = new OpenAiEmbeddingProvider(
                    "http://localhost:" + server.getAddress().getPort() + "/v1", "test-key", "test-model", 3);
            assertThat(provider.embed("API contract")).containsExactly(0.1, 0.2, 0.3);
            assertThat(provider.modelVersion()).contains("test-model");
            var wrongDimensions = new OpenAiEmbeddingProvider(
                    "http://localhost:" + server.getAddress().getPort() + "/v1", "test-key", "test-model", 4);
            assertThatThrownBy(() -> wrongDimensions.embed("API contract"))
                    .hasMessageContaining("dimensions");
        } finally {
            server.stop(0);
        }
    }
}
