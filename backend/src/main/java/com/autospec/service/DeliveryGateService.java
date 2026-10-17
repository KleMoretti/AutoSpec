package com.autospec.service;

import com.autospec.entity.Artifact;
import com.autospec.entity.CodeGenerationJob;
import com.autospec.dto.DeliveryReadinessResponse;
import com.autospec.entity.ReviewIssue;
import com.autospec.entity.WorkflowApproval;
import com.autospec.entity.WorkflowNodeRun;
import com.autospec.entity.WorkflowRun;
import com.autospec.mapper.WorkflowNodeRunMapper;
import com.autospec.mapper.CodeGenerationJobMapper;
import com.autospec.mapper.ReviewIssueMapper;
import com.autospec.mapper.WorkflowApprovalMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class DeliveryGateService {
    private static final List<String> REQUIRED_ARTIFACT_TYPES = List.of(
            "PRD",
            "ARCHITECTURE_DESIGN",
            "BACKEND_DESIGN",
            "FRONTEND_SKELETON",
            "REVIEW_REPORT",
            "EVALUATION_REPORT"
    );

    private final WorkflowRunService workflowRunService;
    private final ArtifactService artifactService;
    private final WorkflowNodeRunMapper workflowNodeRunMapper;
    private final ObjectMapper objectMapper;
    private final WorkflowApprovalMapper workflowApprovalMapper;
    private final ReviewIssueMapper reviewIssueMapper;
    private final CodeGenerationJobMapper codeGenerationJobMapper;
    private final RequirementBaselineService requirementBaselineService;

    @Autowired
    public DeliveryGateService(
            WorkflowRunService workflowRunService,
            ArtifactService artifactService,
            WorkflowNodeRunMapper workflowNodeRunMapper,
            ObjectMapper objectMapper,
            WorkflowApprovalMapper workflowApprovalMapper,
            ReviewIssueMapper reviewIssueMapper,
            CodeGenerationJobMapper codeGenerationJobMapper,
            RequirementBaselineService requirementBaselineService
    ) {
        this.workflowRunService = workflowRunService;
        this.artifactService = artifactService;
        this.workflowNodeRunMapper = workflowNodeRunMapper;
        this.objectMapper = objectMapper;
        this.workflowApprovalMapper = workflowApprovalMapper;
        this.reviewIssueMapper = reviewIssueMapper;
        this.codeGenerationJobMapper = codeGenerationJobMapper;
        this.requirementBaselineService = requirementBaselineService;
    }

    public DeliveryGateService(
            WorkflowRunService workflowRunService,
            ArtifactService artifactService,
            WorkflowNodeRunMapper workflowNodeRunMapper,
            ObjectMapper objectMapper,
            WorkflowApprovalMapper workflowApprovalMapper,
            ReviewIssueMapper reviewIssueMapper,
            CodeGenerationJobMapper codeGenerationJobMapper
    ) {
        this(
                workflowRunService,
                artifactService,
                workflowNodeRunMapper,
                objectMapper,
                workflowApprovalMapper,
                reviewIssueMapper,
                codeGenerationJobMapper,
                null
        );
    }

    public DeliveryGateService(
            WorkflowRunService workflowRunService,
            ArtifactService artifactService,
            WorkflowNodeRunMapper workflowNodeRunMapper,
            ObjectMapper objectMapper
    ) {
        this(
                workflowRunService,
                artifactService,
                workflowNodeRunMapper,
                objectMapper,
                null,
                null,
                null,
                null
        );
    }

    public DeliveryReadinessResponse readiness(Long projectId) {
        WorkflowRun latestRun = latestV5Run(projectId);
        if (latestRun == null) {
            return new DeliveryReadinessResponse(
                    false,
                    false,
                    "NOT_STARTED",
                    null,
                    null,
                    List.of("No workflow run exists")
            );
        }
        List<Artifact> deliverableArtifacts;
        try {
            deliverableArtifacts = requireDeliverable(projectId, latestRun);
        } catch (ResponseStatusException exception) {
            return new DeliveryReadinessResponse(
                    false,
                    false,
                    "SPEC_BLOCKED",
                    latestRun.getId(),
                    null,
                    List.of(exception.getReason() == null ? "Specification gate is blocked" : exception.getReason())
            );
        }
        CodeGenerationJob verifiedJob = latestVerifiedJob(projectId, deliverableArtifacts);
        if (verifiedJob == null) {
            return new DeliveryReadinessResponse(
                    true,
                    false,
                    "BUILD_REQUIRED",
                    latestRun.getId(),
                    null,
                    List.of("Generate and verify a delivery bundle")
            );
        }
        return new DeliveryReadinessResponse(
                true,
                true,
                "READY",
                latestRun.getId(),
                verifiedJob.getId(),
                List.of()
        );
    }

    public List<Artifact> requireDeliverable(Long projectId) {
        return requireDeliverable(projectId, latestV5Run(projectId));
    }

    private List<Artifact> requireDeliverable(Long projectId, WorkflowRun latestV5Run) {
        if (latestV5Run == null) {
            return artifactService.list(new LambdaQueryWrapper<Artifact>()
                    .eq(Artifact::getProjectId, projectId));
        }
        if (!"COMPLETED".equals(latestV5Run.getStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "The latest workflow run is not completed"
            );
        }
        if (requiresRequirementBaseline(latestV5Run)
                && (requirementBaselineService == null
                || requirementBaselineService.findForRun(latestV5Run.getId()) == null)) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "A user-confirmed requirement baseline is required before delivery"
            );
        }
        requireNoPendingApprovals(latestV5Run.getId());
        requireNoBlockingReviewIssues(projectId);

        WorkflowNodeRun evaluator = workflowNodeRunMapper.selectOne(
                new LambdaQueryWrapper<WorkflowNodeRun>()
                        .eq(WorkflowNodeRun::getWorkflowRunId, latestV5Run.getId())
                        .eq(WorkflowNodeRun::getNodeId, "evaluator")
                        .eq(WorkflowNodeRun::getStatus, "SUCCEEDED")
                        .orderByDesc(WorkflowNodeRun::getRevision)
                        .orderByDesc(WorkflowNodeRun::getAttempt)
                        .orderByDesc(WorkflowNodeRun::getId)
                        .last("limit 1")
        );
        if (evaluator == null) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "A successful evaluator node is required before delivery"
            );
        }

        Artifact evaluation = artifactService.list(new LambdaQueryWrapper<Artifact>()
                .eq(Artifact::getProjectId, projectId)
                .eq(Artifact::getType, "EVALUATION_REPORT")
                .eq(Artifact::getWorkflowNodeRunId, evaluator.getId())
                .orderByDesc(Artifact::getVersion)
                .orderByDesc(Artifact::getId)
                .last("limit 1"))
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "A passed evaluation report from the latest run is required before delivery"
                ));
        if (!Objects.equals(evaluation.getWorkflowNodeRunId(), evaluator.getId())) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "The evaluation report does not belong to the latest run"
            );
        }
        if (!"GENERATED".equals(evaluation.getStatus())
                || !Objects.equals(evaluator.getHandlerKey(), evaluation.getSourceAgent())) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "A generated evaluation report from the latest evaluator node is required before delivery"
            );
        }
        try {
            JsonNode report = objectMapper.readTree(evaluation.getContent());
            if (!"PASSED".equals(report.path("gate_status").asText())
                    || report.path("blocking_issue_count").asInt(0) != 0) {
                throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "The evaluation quality gate is not passed"
                );
            }
            requireTrustedVerificationEvidence(latestV5Run, report);
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "The evaluation report is invalid",
                    ex
            );
        }

        List<Long> nodeRunIds = workflowNodeRunMapper.selectList(
                        new LambdaQueryWrapper<WorkflowNodeRun>()
                                .eq(WorkflowNodeRun::getWorkflowRunId, latestV5Run.getId())
                ).stream()
                .map(WorkflowNodeRun::getId)
                .filter(Objects::nonNull)
                .toList();
        if (nodeRunIds.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "The latest run has no deliverable node lineage"
            );
        }
        List<Artifact> artifacts = artifactService.list(new LambdaQueryWrapper<Artifact>()
                .eq(Artifact::getProjectId, projectId)
                .in(Artifact::getWorkflowNodeRunId, nodeRunIds));
        if (artifacts.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "The latest run has no deliverable artifacts"
            );
        }
        Set<String> actualTypes = artifacts.stream().map(Artifact::getType).collect(java.util.stream.Collectors.toSet());
        if (!actualTypes.containsAll(REQUIRED_ARTIFACT_TYPES)) {
            Set<String> missing = new java.util.TreeSet<>(REQUIRED_ARTIFACT_TYPES);
            missing.removeAll(actualTypes);
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "The latest run is missing required artifacts: " + String.join(", ", missing)
            );
        }
        Map<String, Artifact> selected = new LinkedHashMap<>();
        for (Artifact artifact : artifacts) {
            selected.merge(artifact.getType(), artifact, (left, right) ->
                    Comparator.comparingInt(this::artifactVersion)
                            .thenComparing(Artifact::getId, Comparator.nullsFirst(Long::compareTo))
                            .compare(left, right) >= 0 ? left : right
            );
        }
        Artifact selectedPrd = selected.get("PRD");
        if (selectedPrd == null || !"APPROVED".equals(selectedPrd.getStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "The PRD must be approved before delivery"
            );
        }
        requireCurrentEvaluationSources(latestV5Run, evaluator, evaluation, selected);
        return REQUIRED_ARTIFACT_TYPES.stream().map(selected::get).toList();
    }

    private void requireCurrentEvaluationSources(
            WorkflowRun run, WorkflowNodeRun evaluator, Artifact evaluation, Map<String, Artifact> selected
    ) {
        try {
            JsonNode report = objectMapper.readTree(evaluation.getContent());
            // Legacy snapshots without verification or clarification retain their delivery behavior.
            if (!report.has("verification_fact") && !requiresRequirementBaseline(run)
                    && (run.getExecutionBundleJson() == null
                    || !run.getExecutionBundleJson().contains("verification_policy"))) {
                return;
            }
            JsonNode input = objectMapper.readTree(evaluator.getInputJson());
            if (input == null || !input.isObject() || evaluator.getOutputJson() == null
                    || !report.equals(objectMapper.readTree(evaluator.getOutputJson()))) {
                throw new IllegalArgumentException("Evaluation output was modified or its input is missing");
            }
            Map<String, String> fields = Map.of(
                    "PRD", "prd", "ARCHITECTURE_DESIGN", "architecture_design",
                    "BACKEND_DESIGN", "backend_design", "FRONTEND_SKELETON", "frontend_skeleton",
                    "REVIEW_REPORT", "review_report"
            );
            for (Map.Entry<String, String> entry : fields.entrySet()) {
                Artifact artifact = selected.get(entry.getKey());
                if (artifact == null || !com.autospec.util.ArtifactBindings.matches(
                        input.path("artifact_bindings").path(entry.getValue()), artifact)) {
                    throw new IllegalArgumentException("Evaluation source identity changed: " + entry.getKey());
                }
                JsonNode content = objectMapper.readTree(artifact.getContent());
                if ("PRD_READY".equals(content.path("kind").asText())) {
                    content = content.path("prd");
                }
                if (!content.equals(input.path(entry.getValue()))) {
                    throw new IllegalArgumentException("Evaluation source content changed: " + entry.getKey());
                }
            }
            if (requirementBaselineService != null) {
                Map<String, Object> baseline = requirementBaselineService.inputForRun(run.getId());
                if (!baseline.isEmpty()
                        && !objectMapper.valueToTree(baseline).equals(input.path("requirement_baseline"))) {
                    throw new IllegalArgumentException("Evaluation requirement baseline changed");
                }
            }
            JsonNode fact = report.path("verification_fact");
            if (fact.isObject()) {
                WorkflowNodeRun verifier = workflowNodeRunMapper.selectById(fact.path("node_run_id").asLong());
                Artifact review = selected.get("REVIEW_REPORT");
                if (verifier == null || !"SUCCEEDED".equals(verifier.getStatus())
                        || !Objects.equals(verifier.getWorkflowRunId(), run.getId())
                        || review == null || !Objects.equals(review.getWorkflowNodeRunId(), verifier.getId())
                        || fact.path("workflow_run_id").asLong() != run.getId()
                        || fact.path("fencing_token").asLong() != verifier.getFencingToken()
                        || !fact.equals(objectMapper.readTree(verifier.getOutputJson()).path("verification_fact"))) {
                    throw new IllegalArgumentException("Verification fact does not belong to the delivered review");
                }
                JsonNode verifierInput = objectMapper.readTree(verifier.getInputJson());
                for (Map.Entry<String, String> entry : fields.entrySet()) {
                    if ("REVIEW_REPORT".equals(entry.getKey())) {
                        continue;
                    }
                    if (verifierInput == null || !com.autospec.util.ArtifactBindings.matches(
                            verifierInput.path("artifact_bindings").path(entry.getValue()), selected.get(entry.getKey()))
                            || !verifierInput.path(entry.getValue()).equals(input.path(entry.getValue()))) {
                        throw new IllegalArgumentException("Verification source changed: " + entry.getKey());
                    }
                }
            }
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Evaluation evidence no longer matches the current delivery materials; evaluate again", exception);
        }
    }

    private void requireTrustedVerificationEvidence(WorkflowRun run, JsonNode evaluationReport) {
        if (run.getExecutionBundleJson() == null || run.getExecutionBundleJson().isBlank()) {
            return;
        }
        try {
            JsonNode nodes = objectMapper.readTree(run.getExecutionBundleJson()).path("nodes");
            JsonNode fact = evaluationReport.path("verification_fact");
            boolean verificationRequired = false;
            boolean matchingScopeFound = false;
            for (JsonNode node : nodes) {
                JsonNode policy = node.path("verification_policy");
                if (!policy.path("enabled").asBoolean(false)
                        || "NONE".equals(policy.path("required_level").asText("NONE"))) {
                    continue;
                }
                verificationRequired = true;
                String policyScope = policy.path("scope").asText("");
                if (fact.isObject()
                        && !policyScope.isBlank()
                        && !policyScope.equals(fact.path("scope").asText(""))) {
                    continue;
                }
                matchingScopeFound = true;
                if (!fact.isObject()) {
                    throw new ResponseStatusException(
                            HttpStatus.UNPROCESSABLE_ENTITY,
                            "Trusted verification evidence is required before delivery"
                    );
                }
                String requiredLevel = policy.path("required_level").asText("L1");
                String achievedLevel = fact.path("achieved_level").asText("");
                int required = "L2".equals(requiredLevel) ? 2 : 1;
                int achieved = "L2".equals(achievedLevel) ? 2 : "L1".equals(achievedLevel) ? 1 : 0;
                if (!"PASSED".equals(fact.path("status").asText())
                        || achieved < required
                        || fact.path("expires_at_epoch_ms").asLong(0) <= System.currentTimeMillis()) {
                    throw new ResponseStatusException(
                            HttpStatus.UNPROCESSABLE_ENTITY,
                            "Trusted verification evidence is missing, expired, or below the required level"
                    );
                }
                for (String field : List.of("source_digest", "policy_hash")) {
                    if (policy.has(field) && !policy.path(field).asText().equals(fact.path(field).asText())) {
                        throw new ResponseStatusException(
                                HttpStatus.UNPROCESSABLE_ENTITY,
                                "Trusted verification evidence does not match the frozen verification policy"
                        );
                    }
                }
                if (!policy.has("policy_hash")) {
                    Map<String, Object> material = objectMapper.convertValue(policy, Map.class);
                    String expected = com.autospec.util.ContentHash.sha256(objectMapper.copy()
                            .enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                            .writeValueAsString(material));
                    if (!expected.equals(fact.path("policy_hash").asText())) {
                        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                                "Trusted verification evidence does not match the frozen verification policy");
                    }
                }
                for (String field : List.of("verifier_version", "compiler_version")) {
                    if (policy.has(field) && !policy.path(field).equals(fact.path(field))) {
                        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                                "Trusted verification evidence does not match the frozen verification policy");
                    }
                }
            }
            if (verificationRequired && !matchingScopeFound) {
                throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "Trusted verification evidence does not match the frozen verification policy"
                );
            }
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "The verification evidence is invalid",
                    exception
            );
        }
    }

    private WorkflowRun latestV5Run(Long projectId) {
        return workflowRunService.list(new LambdaQueryWrapper<WorkflowRun>()
                        .eq(WorkflowRun::getProjectId, projectId)
                        .in(WorkflowRun::getOperation, "GENERATE_V5", "REPLAY_V5")
                        .orderByDesc(WorkflowRun::getId)
                        .last("limit 1"))
                .stream()
                .findFirst()
                .orElse(null);
    }

    private boolean requiresRequirementBaseline(WorkflowRun run) {
        if (run.getWorkflowSnapshotJson() == null || run.getWorkflowSnapshotJson().isBlank()) {
            return false;
        }
        try {
            JsonNode nodes = objectMapper.readTree(run.getWorkflowSnapshotJson()).path("nodes");
            if (!nodes.isArray()) {
                return false;
            }
            for (JsonNode node : nodes) {
                if ("product_manager".equals(node.path("node_id").asText())
                        && "ProductManagerResult".equals(node.path("output_schema").asText())) {
                    return true;
                }
            }
            return false;
        } catch (Exception exception) {
            return false;
        }
    }

    private void requireNoPendingApprovals(Long workflowRunId) {
        if (workflowApprovalMapper == null) {
            return;
        }
        Long count = workflowApprovalMapper.selectCount(
                new LambdaQueryWrapper<WorkflowApproval>()
                        .eq(WorkflowApproval::getWorkflowRunId, workflowRunId)
                        .eq(WorkflowApproval::getStatus, "PENDING")
        );
        if (count != null && count > 0) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Pending workflow approvals must be decided before delivery"
            );
        }
    }

    private void requireNoBlockingReviewIssues(Long projectId) {
        if (reviewIssueMapper == null) {
            return;
        }
        Long count = reviewIssueMapper.selectCount(
                new LambdaQueryWrapper<ReviewIssue>()
                        .eq(ReviewIssue::getProjectId, projectId)
                        .in(ReviewIssue::getStatus, "OPEN", "IN_PROGRESS")
                        .in(ReviewIssue::getSeverity, "CRITICAL", "HIGH")
        );
        if (count != null && count > 0) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Blocking review findings must be resolved before delivery"
            );
        }
    }

    private CodeGenerationJob latestVerifiedJob(Long projectId, List<Artifact> artifacts) {
        if (codeGenerationJobMapper == null) {
            return null;
        }
        CodeGenerationJob job = codeGenerationJobMapper.selectOne(
                new LambdaQueryWrapper<CodeGenerationJob>()
                        .eq(CodeGenerationJob::getProjectId, projectId)
                        .eq(CodeGenerationJob::getStatus, "SUCCEEDED")
                        .eq(CodeGenerationJob::getGateStatus, "PASSED")
                        .orderByDesc(CodeGenerationJob::getId)
                        .last("limit 1")
        );
        return job != null && manifestMatches(job, artifacts) ? job : null;
    }

    private boolean manifestMatches(CodeGenerationJob job, List<Artifact> artifacts) {
        if (job.getManifest() == null || job.getManifest().isBlank()) {
            return false;
        }
        try {
            String manifestHash = com.autospec.util.ContentHash.sha256(job.getManifest());
            JsonNode evidence = objectMapper.readTree(job.getVerificationJson());
            if (!manifestHash.equals(job.getManifestHash()) || evidence == null
                    || !"PASSED".equals(evidence.path("status").asText())
                    || !manifestHash.equals(evidence.path("manifest_sha256").asText())) {
                return false;
            }
            return com.autospec.util.ArtifactBindings.manifestMatches(
                    objectMapper.readTree(job.getManifest()).path("artifacts"), artifacts);
        } catch (Exception ignored) {
            return false;
        }
    }

    private int artifactVersion(Artifact artifact) {
        return artifact.getVersion() == null ? 0 : artifact.getVersion();
    }
}
