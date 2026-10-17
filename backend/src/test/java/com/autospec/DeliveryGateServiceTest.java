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
    void bundleManifestRequiresActualContentAndVersionInsteadOfHashShape() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Artifact artifact = artifact(71L, "BACKEND_DESIGN", "GENERATED", "{}");
        var entries = mapper.createArrayNode();
        var binding = entries.addObject().put("artifact_id", artifact.getId()).put("type", artifact.getType())
                .put("version", 1).put("content_hash", com.autospec.util.ContentHash.sha256(artifact.getContent()));
        assertThat(com.autospec.util.ArtifactBindings.manifestMatches(entries, List.of(artifact))).isTrue();
        binding.put("content_hash", "a".repeat(64));
        assertThat(com.autospec.util.ArtifactBindings.manifestMatches(entries, List.of(artifact))).isFalse();
        binding.put("content_hash", com.autospec.util.ContentHash.sha256(artifact.getContent())).put("version", 2);
        assertThat(com.autospec.util.ArtifactBindings.manifestMatches(entries, List.of(artifact))).isFalse();
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
    void acceptsCurrentVerifiedMaterialsAndRejectsChangedContentOrVersion() throws Exception {
        when(workflowRunService.list(org.mockito.ArgumentMatchers.<Wrapper<WorkflowRun>>any()))
                .thenReturn(List.of(verifiedRun("COMPLETED")));
        ObjectMapper mapper = new ObjectMapper();
        WorkflowNodeRun evaluator = evaluator(71L);
        Artifact evaluation = evaluationWithVerification(71L, System.currentTimeMillis() + 60_000);
        var report = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(evaluation.getContent());
        var fact = (com.fasterxml.jackson.databind.node.ObjectNode) report.path("verification_fact");
        fact.put("workflow_run_id", 44L).put("node_run_id", 72L).put("fencing_token", 1L);
        evaluation.setContent(report.toString());
        evaluator.setOutputJson(evaluation.getContent());
        var artifacts = new java.util.ArrayList<>(completeArtifacts(71L));
        artifacts.removeIf(value -> "EVALUATION_REPORT".equals(value.getType()));
        artifacts.add(0, evaluation);
        Artifact review = artifacts.stream().filter(value -> "REVIEW_REPORT".equals(value.getType())).findFirst().orElseThrow();
        review.setWorkflowNodeRunId(72L);
        review.setContent("{\"verification_fact\":" + fact + "}");
        WorkflowNodeRun verifier = evaluator(72L);
        verifier.setFencingToken(1L);
        verifier.setOutputJson(review.getContent());
        var input = mapper.createObjectNode();
        var bindings = input.putObject("artifact_bindings");
        var fields = java.util.Map.of("PRD", "prd", "ARCHITECTURE_DESIGN", "architecture_design",
                "BACKEND_DESIGN", "backend_design", "FRONTEND_SKELETON", "frontend_skeleton", "REVIEW_REPORT", "review_report");
        for (Artifact artifact : artifacts) {
            if (!fields.containsKey(artifact.getType())) continue;
            String field = fields.get(artifact.getType());
            input.set(field, mapper.readTree(artifact.getContent()));
            bindings.putObject(field).put("artifact_id", artifact.getId()).put("type", artifact.getType())
                    .put("version", artifact.getVersion()).put("content_hash", com.autospec.util.ContentHash.sha256(artifact.getContent()));
        }
        evaluator.setInputJson(input.toString());
        verifier.setInputJson(input.toString());
        when(workflowNodeRunMapper.selectOne(any())).thenReturn(evaluator);
        when(workflowNodeRunMapper.selectById(72L)).thenReturn(verifier);
        when(workflowNodeRunMapper.selectList(any())).thenReturn(List.of(evaluator, verifier));
        when(artifactService.list(org.mockito.ArgumentMatchers.<Wrapper<Artifact>>any()))
                .thenAnswer(invocation -> artifacts);

        assertThatCode(() -> deliveryGateService.requireDeliverable(9L)).doesNotThrowAnyException();
        Artifact backend = artifacts.stream().filter(value -> "BACKEND_DESIGN".equals(value.getType())).findFirst().orElseThrow();
        backend.setContent("{\"changed\":true}");
        assertThatThrownBy(() -> deliveryGateService.requireDeliverable(9L)).hasMessageContaining("current delivery materials");
        // A fresh evaluator cannot reuse a review over older materials, even if the verifier digest is unchanged.
        input.set("backend_design", mapper.readTree(backend.getContent()));
        ((com.fasterxml.jackson.databind.node.ObjectNode) bindings.path("backend_design"))
                .put("content_hash", com.autospec.util.ContentHash.sha256(backend.getContent()));
        evaluator.setInputJson(input.toString());
        assertThatThrownBy(() -> deliveryGateService.requireDeliverable(9L)).hasMessageContaining("current delivery materials");
        backend.setContent("{}");
        backend.setVersion(2);
        assertThatThrownBy(() -> deliveryGateService.requireDeliverable(9L)).hasMessageContaining("current delivery materials");
    }

    @Test
    void rejectsExpiredFullVerificationEvidence() {
        when(workflowRunService.list(org.mockito.ArgumentMatchers.<Wrapper<WorkflowRun>>any()))
                .thenReturn(List.of(verifiedRun("COMPLETED")));
        WorkflowNodeRun evaluator = evaluator(71L);
        when(workflowNodeRunMapper.selectOne(any())).thenReturn(evaluator);
        when(workflowNodeRunMapper.selectList(any())).thenReturn(List.of(evaluator));
        when(artifactService.list(org.mockito.ArgumentMatchers.<Wrapper<Artifact>>any()))
                .thenReturn(
                        List.of(evaluationWithVerification(71L, 1L)),
                        completeArtifacts(71L)
                );

        assertThatThrownBy(() -> deliveryGateService.requireDeliverable(9L))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.getReason()).contains("missing, expired, or below");
                });
    }

    @Test
    void rejectsHumanEditedEvaluationEvenWhenFactLooksValid() {
        when(workflowRunService.list(org.mockito.ArgumentMatchers.<Wrapper<WorkflowRun>>any()))
                .thenReturn(List.of(verifiedRun("COMPLETED")));
        WorkflowNodeRun evaluator = evaluator(71L);
        when(workflowNodeRunMapper.selectOne(any())).thenReturn(evaluator);
        Artifact edited = evaluationWithVerification(71L, System.currentTimeMillis() + 60_000);
        edited.setStatus("PENDING_REVIEW");
        edited.setSourceAgent("HUMAN_EDITOR");
        when(artifactService.list(org.mockito.ArgumentMatchers.<Wrapper<Artifact>>any()))
                .thenReturn(List.of(edited));

        assertThatThrownBy(() -> deliveryGateService.requireDeliverable(9L))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.getReason()).contains("generated evaluation report");
                });
    }

    @Test
    void doesNotReuseBuildVerificationFromDifferentArtifactVersions() throws Exception {
        when(workflowRunService.list(org.mockito.ArgumentMatchers.<Wrapper<WorkflowRun>>any()))
                .thenReturn(List.of(run("COMPLETED")));
        WorkflowNodeRun evaluator = evaluator(71L);
        when(workflowNodeRunMapper.selectOne(any())).thenReturn(evaluator);
        when(workflowNodeRunMapper.selectList(any())).thenReturn(List.of(evaluator));
        var artifacts = new java.util.ArrayList<>(completeArtifacts(71L));
        artifacts.sort(java.util.Comparator.comparing(value -> !"EVALUATION_REPORT".equals(value.getType())));
        when(artifactService.list(org.mockito.ArgumentMatchers.<Wrapper<Artifact>>any())).thenAnswer(invocation -> artifacts);
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
        ObjectMapper mapper = new ObjectMapper();
        var manifest = mapper.createObjectNode();
        var entries = manifest.putArray("artifacts");
        for (Artifact artifact : artifacts) {
            entries.addObject().put("artifact_id", artifact.getId()).put("type", artifact.getType())
                    .put("version", artifact.getVersion()).put("content_hash", com.autospec.util.ContentHash.sha256(artifact.getContent()));
        }
        staleJob.setManifest(manifest.toString());
        String digest = com.autospec.util.ContentHash.sha256(staleJob.getManifest());
        staleJob.setManifestHash(digest);
        staleJob.setVerificationJson("{\"status\":\"PASSED\",\"manifest_sha256\":\"" + digest + "\"}");
        assertThat(deliveryGateService.readiness(9L).buildReady()).isTrue();
        staleJob.setManifestHash("a".repeat(64));
        assertThat(deliveryGateService.readiness(9L).buildReady()).isFalse();

    }

    private WorkflowRun run(String status) {
        WorkflowRun run = new WorkflowRun();
        run.setId(44L);
        run.setProjectId(9L);
        run.setOperation("GENERATE_V5");
        run.setStatus(status);
        return run;
    }

    private WorkflowRun verifiedRun(String status) {
        WorkflowRun run = run(status);
        run.setExecutionBundleJson("""
                {"nodes":[
                  {"node_id":"backend_engineer","verification_policy":{"enabled":true,"scope":"BACKEND","required_level":"L1","policy_hash":"07f90333460fec2a396e2dabd8e275cfac90edf9e842af49083ff878f75e207e"}},
                  {"node_id":"reviewer","verification_policy":{"enabled":true,"scope":"FULL","required_level":"L1","policy_hash":"cf6d443ed5f255c2e811b179632fb7b23862466f71baa45341a2b0d832ae3794"}}
                ]}
                """);
        return run;
    }

    private WorkflowNodeRun evaluator(Long id) {
        WorkflowNodeRun node = new WorkflowNodeRun();
        node.setId(id);
        node.setWorkflowRunId(44L);
        node.setNodeId("evaluator");
        node.setHandlerKey("EvaluatorAgent");
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
        artifact.setSourceAgent("EvaluatorAgent");
        artifact.setWorkflowNodeRunId(nodeRunId);
        artifact.setContent("{\"gate_status\":\"" + gateStatus + "\"}");
        return artifact;
    }

    private Artifact evaluationWithVerification(Long nodeRunId, long expiresAtEpochMs) {
        Artifact artifact = evaluation(nodeRunId, "PASSED");
        artifact.setContent("""
                {"gate_status":"PASSED","blocking_issue_count":0,"verification_fact":{
                  "scope":"FULL","policy_hash":"cf6d443ed5f255c2e811b179632fb7b23862466f71baa45341a2b0d832ae3794",
                  "source_digest":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "status":"PASSED","achieved_level":"L1","expires_at_epoch_ms":%d
                }}
                """.formatted(expiresAtEpochMs));
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
