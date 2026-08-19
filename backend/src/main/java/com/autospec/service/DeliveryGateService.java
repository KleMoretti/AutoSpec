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

    @Autowired
    public DeliveryGateService(
            WorkflowRunService workflowRunService,
            ArtifactService artifactService,
            WorkflowNodeRunMapper workflowNodeRunMapper,
            ObjectMapper objectMapper,
            WorkflowApprovalMapper workflowApprovalMapper,
            ReviewIssueMapper reviewIssueMapper,
            CodeGenerationJobMapper codeGenerationJobMapper
    ) {
        this.workflowRunService = workflowRunService;
        this.artifactService = artifactService;
        this.workflowNodeRunMapper = workflowNodeRunMapper;
        this.objectMapper = objectMapper;
        this.workflowApprovalMapper = workflowApprovalMapper;
        this.reviewIssueMapper = reviewIssueMapper;
        this.codeGenerationJobMapper = codeGenerationJobMapper;
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
                    List.of("No V5 workflow run exists")
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
                    "The latest V5 workflow run is not completed"
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
        try {
            JsonNode report = objectMapper.readTree(evaluation.getContent());
            if (!"PASSED".equals(report.path("gate_status").asText())
                    || report.path("blocking_issue_count").asInt(0) != 0) {
                throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "The evaluation quality gate is not passed"
                );
            }
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
        return REQUIRED_ARTIFACT_TYPES.stream().map(selected::get).toList();
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
            Set<Long> manifestArtifactIds = new java.util.HashSet<>();
            for (JsonNode value : objectMapper.readTree(job.getManifest()).path("artifacts")) {
                if (value.path("artifact_id").canConvertToLong()) {
                    manifestArtifactIds.add(value.path("artifact_id").asLong());
                }
            }
            Set<Long> selectedArtifactIds = artifacts.stream()
                    .map(Artifact::getId)
                    .filter(Objects::nonNull)
                    .collect(java.util.stream.Collectors.toSet());
            return !selectedArtifactIds.isEmpty() && manifestArtifactIds.equals(selectedArtifactIds);
        } catch (Exception ignored) {
            return false;
        }
    }

    private int artifactVersion(Artifact artifact) {
        return artifact.getVersion() == null ? 0 : artifact.getVersion();
    }
}
