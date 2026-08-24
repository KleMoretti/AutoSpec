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
        when(knowledgeIndexService.retrieve("Build a clinic", 5, 42L)).thenReturn(List.of(
                new KnowledgeSourceResponse(
                        9L,
                        "PRD",
                        "Approved clinic PRD",
                        3,
                        91L,
                        2,
                        "chunk[2]",
                        "trusted excerpt",
                        "HYBRID_RRF_HASHING_V1",
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
                                "retrieved_sources", List.of(Map.of("content", "untrusted browser data"))
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
        assertThat(input.toString()).doesNotContain("untrusted browser data");
        assertThat(input.path("retrieval_policy").asText())
                .isEqualTo("ACTOR_ACCESSIBLE_APPROVED_ARTIFACTS_V1");
        assertThat(command.getValue().qualityProfile()).isEqualTo("DEEP");
        assertThat(command.getValue().maxTokens()).isEqualTo(250_000L);
        assertThat(command.getValue().maxCost()).isEqualByComparingTo("12.50");
        assertThat(command.getValue().maxModelCalls()).isEqualTo(24);
        assertThat(command.getValue().maxWallTimeMs()).isEqualTo(900_000L);
    }
}
