package com.autospec.controller;

import com.autospec.dto.KnowledgeSourceResponse;
import com.autospec.dto.WorkflowExecutionPolicyRequest;
import com.autospec.dto.WorkflowRunStartRequest;
import com.autospec.entity.WorkflowRun;
import com.autospec.mapper.WorkflowNodeRunMapper;
import com.autospec.mapper.WorkflowRunMapper;
import com.autospec.service.KnowledgeIndexService;
import com.autospec.service.ProjectAccessService;
import com.autospec.service.WorkflowReplayService;
import com.autospec.service.WorkflowRunCreationService;
import com.autospec.service.WorkflowRuntimeMetricsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowRuntimeControllerTest {
    @Mock
    private WorkflowRunMapper runMapper;
    @Mock
    private WorkflowNodeRunMapper nodeRunMapper;
    @Mock
    private ProjectAccessService projectAccessService;
    @Mock
    private WorkflowReplayService replayService;
    @Mock
    private WorkflowRunCreationService runCreationService;
    @Mock
    private WorkflowRuntimeMetricsService metricsService;
    @Mock
    private KnowledgeIndexService knowledgeIndexService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private WorkflowRuntimeController controller;

    @BeforeEach
    void setUp() {
        controller = new WorkflowRuntimeController(
                runMapper,
                nodeRunMapper,
                projectAccessService,
                replayService,
                runCreationService,
                metricsService,
                knowledgeIndexService,
                objectMapper
        );
    }

    @Test
    void startOverwritesClientSourcesWithActorAccessibleTrustedCitationsAndFreezesPolicy()
            throws Exception {
        when(projectAccessService.resolveUserId("session")).thenReturn(42L);
        when(knowledgeIndexService.retrieveForProject("Build a clinic", 5, 7L, 42L)).thenReturn(List.of(
                new KnowledgeSourceResponse(
                        7L,
                        9L,
                        "PRD",
                        "Approved clinic PRD",
                        3,
                        91L,
                        2,
                        "chunk[2]",
                        "trusted excerpt",
                        "HYBRID_RRF_HASHING_V1",
                        "structured-text-900-120-v1",
                        "autospec-hashing-ngram-v1",
                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                        "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                        32.4
                )
        ));
        WorkflowRun created = new WorkflowRun();
        created.setId(100L);
        created.setProjectId(7L);
        created.setOperation("GENERATE_V5");
        created.setQualityProfile("DEEP");
        created.setConsumedTokens(0L);
        created.setConsumedCost(BigDecimal.ZERO);
        created.setModelCallCount(0);
        created.setStatus("RUNNING");
        created.setStartedAt(LocalDateTime.now());
        when(runCreationService.start(any())).thenReturn(created);

        controller.start(
                new WorkflowRunStartRequest(
                        7L,
                        5L,
                        Map.of(
                                "requirement", "  Build a clinic  ",
                                "retrieved_sources", List.of(Map.of("content", "untrusted browser data")),
                                "rework_directive", Map.of("issue_ids", List.of("CLIENT-INJECTED")),
                                "context_manifest", Map.of("policy", "client-controlled")
                        ),
                        "start-1",
                        new WorkflowExecutionPolicyRequest(
                                "DEEP",
                                250_000L,
                                new BigDecimal("12.50"),
                                24,
                                900_000L
                        )
                ),
                "session"
        );

        ArgumentCaptor<WorkflowRunCreationService.StartCommand> command =
                ArgumentCaptor.forClass(WorkflowRunCreationService.StartCommand.class);
        verify(runCreationService).start(command.capture());
        JsonNode input = objectMapper.readTree(command.getValue().inputJson());
        assertThat(input.path("requirement").asText()).isEqualTo("Build a clinic");
        assertThat(input.path("retrieved_sources").size()).isEqualTo(1);
        assertThat(input.path("retrieved_sources").get(0).path("citation_id").asText())
                .isEqualTo("artifact:9:v3:chunk:2");
        assertThat(input.path("retrieved_sources").get(0).path("chunk_id").asLong())
                .isEqualTo(91L);
        assertThat(input.path("retrieved_sources").get(0).path("project_id").asLong())
                .isEqualTo(7L);
        assertThat(input.path("retrieved_sources").get(0).path("chunker_version").asText())
                .isEqualTo("structured-text-900-120-v1");
        assertThat(input.path("retrieved_sources").get(0).path("artifact_content_hash").asText())
                .hasSize(64);
        assertThat(input.toString()).doesNotContain("untrusted browser data");
        assertThat(input.toString())
                .doesNotContain("CLIENT-INJECTED", "client-controlled");
        assertThat(input.path("retrieval_project_id").asLong()).isEqualTo(7L);
        assertThat(input.path("retrieval_policy").asText())
                .isEqualTo("PROJECT_KNOWLEDGE_BM25_EMBEDDING_RRF_RERANK_V2");
        assertThat(input.path("retrieval_trace").path("reranker_version").asText())
                .isEqualTo("deterministic-rerank-v1");
        assertThat(input.path("retrieval_trace").path("filters").path("status").asText())
                .isEqualTo("ACTIVE");
        verify(knowledgeIndexService).retrieveForProject("Build a clinic", 5, 7L, 42L);
        assertThat(command.getValue().qualityProfile()).isEqualTo("DEEP");
        assertThat(command.getValue().maxTokens()).isEqualTo(250_000L);
        assertThat(command.getValue().maxCost()).isEqualByComparingTo("12.50");
        assertThat(command.getValue().maxModelCalls()).isEqualTo(24);
        assertThat(command.getValue().maxWallTimeMs()).isEqualTo(900_000L);
    }
}
