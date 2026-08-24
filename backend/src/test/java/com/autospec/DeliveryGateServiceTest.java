package com.autospec;

import com.autospec.entity.Artifact;
import com.autospec.entity.CodeGenerationJob;
import com.autospec.entity.WorkflowNodeRun;
import com.autospec.entity.WorkflowRun;
import com.autospec.mapper.CodeGenerationJobMapper;
import com.autospec.mapper.ReviewIssueMapper;
import com.autospec.mapper.WorkflowApprovalMapper;
import com.autospec.mapper.WorkflowNodeRunMapper;
import com.autospec.service.ArtifactService;
import com.autospec.service.DeliveryGateService;
import com.autospec.service.WorkflowRunService;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DeliveryGateServiceTest {

    private WorkflowRunService workflowRunService;
    private ArtifactService artifactService;
    private WorkflowNodeRunMapper workflowNodeRunMapper;
    private WorkflowApprovalMapper workflowApprovalMapper;
    private ReviewIssueMapper reviewIssueMapper;
    private CodeGenerationJobMapper codeGenerationJobMapper;
    private DeliveryGateService deliveryGateService;

    @BeforeEach
    void setUp() {
        workflowRunService = mock(WorkflowRunService.class);
        artifactService = mock(ArtifactService.class);
        workflowNodeRunMapper = mock(WorkflowNodeRunMapper.class);
        workflowApprovalMapper = mock(WorkflowApprovalMapper.class);
        reviewIssueMapper = mock(ReviewIssueMapper.class);
        codeGenerationJobMapper = mock(CodeGenerationJobMapper.class);
        deliveryGateService = new DeliveryGateService(
                workflowRunService,
                artifactService,
                workflowNodeRunMapper,
                new ObjectMapper(),
                workflowApprovalMapper,
                reviewIssueMapper,
                codeGenerationJobMapper
        );
    }

    @Test
    void preservesLegacyDeliveryWhenProjectHasNoV5Run() {
        when(workflowRunService.list(org.mockito.ArgumentMatchers.<Wrapper<WorkflowRun>>any()))
                .thenReturn(List.of());
        when(artifactService.list(org.mockito.ArgumentMatchers.<Wrapper<Artifact>>any()))
                .thenReturn(List.of());

        assertThatCode(() -> deliveryGateService.requireDeliverable(9L)).doesNotThrowAnyException();
        verifyNoInteractions(workflowNodeRunMapper);
    }

    @Test
    void rejectsDeliveryUntilLatestV5RunCompletes() {
        when(workflowRunService.list(org.mockito.ArgumentMatchers.<Wrapper<WorkflowRun>>any()))
                .thenReturn(List.of(run("RUNNING")));

        assertThatThrownBy(() -> deliveryGateService.requireDeliverable(9L))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void rejectsCompletedRunWithoutSuccessfulEvaluator() {
        when(workflowRunService.list(org.mockito.ArgumentMatchers.<Wrapper<WorkflowRun>>any()))
                .thenReturn(List.of(run("COMPLETED")));
        when(workflowNodeRunMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> deliveryGateService.requireDeliverable(9L))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test
    void rejectsBlockedOrPriorRunEvaluationReport() {
        when(workflowRunService.list(org.mockito.ArgumentMatchers.<Wrapper<WorkflowRun>>any()))
                .thenReturn(List.of(run("COMPLETED")));
        when(workflowNodeRunMapper.selectOne(any())).thenReturn(evaluator(71L));
        when(artifactService.list(org.mockito.ArgumentMatchers.<Wrapper<Artifact>>any()))
                .thenReturn(List.of(evaluation(70L, "PASSED")));

        assertThatThrownBy(() -> deliveryGateService.requireDeliverable(9L))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getReason()).contains("latest run"));

        when(artifactService.list(org.mockito.ArgumentMatchers.<Wrapper<Artifact>>any()))
                .thenReturn(List.of(evaluation(71L, "BLOCKED")));
        assertThatThrownBy(() -> deliveryGateService.requireDeliverable(9L))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.getReason()).contains("not passed");
                });
    }

    @Test
    void acceptsPassedEvaluationFromLatestCompletedRun() {
        when(workflowRunService.list(org.mockito.ArgumentMatchers.<Wrapper<WorkflowRun>>any()))
                .thenReturn(List.of(run("COMPLETED")));
        WorkflowNodeRun evaluator = evaluator(71L);
        when(workflowNodeRunMapper.selectOne(any())).thenReturn(evaluator);
        when(workflowNodeRunMapper.selectList(any())).thenReturn(List.of(evaluator));
        when(artifactService.list(org.mockito.ArgumentMatchers.<Wrapper<Artifact>>any()))
                .thenReturn(
                        List.of(evaluation(71L, "PASSED")),
                        completeArtifacts(71L)
                );

        assertThat(deliveryGateService.requireDeliverable(9L))
                .extracting(Artifact::getType)
                .containsExactly(
                        "PRD",
                        "ARCHITECTURE_DESIGN",
                        "BACKEND_DESIGN",
                        "FRONTEND_SKELETON",
                        "REVIEW_REPORT",
                        "EVALUATION_REPORT"
                );
    }

    @Test
    void doesNotReuseBuildVerificationFromDifferentArtifactVersions() {
        when(workflowRunService.list(org.mockito.ArgumentMatchers.<Wrapper<WorkflowRun>>any()))
                .thenReturn(List.of(run("COMPLETED")));
        WorkflowNodeRun evaluator = evaluator(71L);
        when(workflowNodeRunMapper.selectOne(any())).thenReturn(evaluator);
        when(workflowNodeRunMapper.selectList(any())).thenReturn(List.of(evaluator));
        when(artifactService.list(org.mockito.ArgumentMatchers.<Wrapper<Artifact>>any()))
                .thenReturn(
                        List.of(evaluation(71L, "PASSED")),
                        completeArtifacts(71L)
                );
        CodeGenerationJob staleJob = new CodeGenerationJob();
        staleJob.setId(88L);
        staleJob.setProjectId(9L);
        staleJob.setStatus("SUCCEEDED");
        staleJob.setGateStatus("PASSED");
        staleJob.setManifest("{\"artifacts\":[{\"artifact_id\":999}]}");
        when(codeGenerationJobMapper.selectOne(any())).thenReturn(staleJob);

        assertThat(deliveryGateService.readiness(9L))
                .satisfies(readiness -> {
                    assertThat(readiness.specReady()).isTrue();
                    assertThat(readiness.buildReady()).isFalse();
                    assertThat(readiness.status()).isEqualTo("BUILD_REQUIRED");
                });
    }

    private WorkflowRun run(String status) {
        WorkflowRun run = new WorkflowRun();
        run.setId(44L);
        run.setProjectId(9L);
        run.setOperation("GENERATE_V5");
        run.setStatus(status);
        return run;
    }

    private WorkflowNodeRun evaluator(Long id) {
        WorkflowNodeRun node = new WorkflowNodeRun();
        node.setId(id);
        node.setWorkflowRunId(44L);
        node.setNodeId("evaluator");
        node.setStatus("SUCCEEDED");
        return node;
    }

    private Artifact evaluation(Long nodeRunId, String gateStatus) {
        Artifact artifact = new Artifact();
        artifact.setProjectId(9L);
        artifact.setId(600L);
        artifact.setType("EVALUATION_REPORT");
        artifact.setVersion(1);
        artifact.setStatus("GENERATED");
        artifact.setWorkflowNodeRunId(nodeRunId);
        artifact.setContent("{\"gate_status\":\"" + gateStatus + "\"}");
        return artifact;
    }

    private List<Artifact> completeArtifacts(Long nodeRunId) {
        return List.of(
                artifact(nodeRunId, "PRD", "APPROVED", "{}"),
                artifact(nodeRunId, "ARCHITECTURE_DESIGN", "GENERATED", "{}"),
                artifact(nodeRunId, "BACKEND_DESIGN", "GENERATED", "{}"),
                artifact(nodeRunId, "FRONTEND_SKELETON", "GENERATED", "{}"),
                artifact(nodeRunId, "REVIEW_REPORT", "GENERATED", "{}"),
                evaluation(nodeRunId, "PASSED")
        );
    }

    private Artifact artifact(Long nodeRunId, String type, String status, String content) {
        Artifact artifact = new Artifact();
        artifact.setId((long) type.hashCode() & 0x7fffffffL);
        artifact.setProjectId(9L);
        artifact.setType(type);
        artifact.setVersion(1);
        artifact.setStatus(status);
        artifact.setWorkflowNodeRunId(nodeRunId);
        artifact.setContent(content);
        return artifact;
    }
}
