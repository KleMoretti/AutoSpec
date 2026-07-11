package com.autospec.service;

import com.autospec.entity.Artifact;
import com.autospec.dto.ProjectDiagnosticsResponse;
import com.autospec.entity.AgentTask;
import com.autospec.entity.AuditEvent;
import com.autospec.entity.CodeGenerationJob;
import com.autospec.entity.ExternalCallLog;
import com.autospec.entity.ModelInvocation;
import com.autospec.entity.ReviewIssue;
import com.autospec.entity.WorkflowRun;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

@Service
public class ProjectDiagnosticsService {

    private final WorkflowRunService workflowRunService;
    private final AgentTaskService agentTaskService;
    private final AuditEventService auditEventService;
    private final ExternalCallLogService externalCallLogService;
    private final ModelInvocationService modelInvocationService;
    private final CodeGenerationJobService codeGenerationJobService;
    private final ReviewIssueService reviewIssueService;
    private final ArtifactService artifactService;
    private final ObjectMapper objectMapper;

    public ProjectDiagnosticsService(
            WorkflowRunService workflowRunService,
            AgentTaskService agentTaskService,
            AuditEventService auditEventService,
            ExternalCallLogService externalCallLogService,
            ModelInvocationService modelInvocationService,
            CodeGenerationJobService codeGenerationJobService,
            ReviewIssueService reviewIssueService,
            ArtifactService artifactService,
            ObjectMapper objectMapper
    ) {
        this.workflowRunService = workflowRunService;
        this.agentTaskService = agentTaskService;
        this.auditEventService = auditEventService;
        this.externalCallLogService = externalCallLogService;
        this.modelInvocationService = modelInvocationService;
        this.codeGenerationJobService = codeGenerationJobService;
        this.reviewIssueService = reviewIssueService;
        this.artifactService = artifactService;
        this.objectMapper = objectMapper;
    }

    public ProjectDiagnosticsResponse summarize(Long projectId) {
        WorkflowRun latestRun = workflowRunService.lambdaQuery()
                .eq(WorkflowRun::getProjectId, projectId)
                .orderByDesc(WorkflowRun::getId)
                .last("limit 1")
                .oneOpt()
                .orElse(null);
        WorkflowRun latestFailedRun = workflowRunService.lambdaQuery()
                .eq(WorkflowRun::getProjectId, projectId)
                .eq(WorkflowRun::getStatus, "FAILED")
                .orderByDesc(WorkflowRun::getId)
                .last("limit 1")
                .oneOpt()
                .orElse(null);
        AgentTask latestFailedTask = agentTaskService.lambdaQuery()
                .eq(AgentTask::getProjectId, projectId)
                .eq(AgentTask::getStatus, "FAILED")
                .orderByDesc(AgentTask::getId)
                .last("limit 1")
                .oneOpt()
                .orElse(null);
        ExternalCallLog latestFailedExternalCall = externalCallLogService.lambdaQuery()
                .eq(ExternalCallLog::getProjectId, projectId)
                .eq(ExternalCallLog::getStatus, "FAILED")
                .orderByDesc(ExternalCallLog::getId)
                .last("limit 1")
                .oneOpt()
                .orElse(null);
        ModelInvocation latestFailedModelInvocation = modelInvocationService.lambdaQuery()
                .eq(ModelInvocation::getProjectId, projectId)
                .eq(ModelInvocation::getStatus, "FAILED")
                .orderByDesc(ModelInvocation::getId)
                .last("limit 1")
                .oneOpt()
                .orElse(null);
        CodeGenerationJob latestFailedCodeGenerationJob = codeGenerationJobService.lambdaQuery()
                .eq(CodeGenerationJob::getProjectId, projectId)
                .eq(CodeGenerationJob::getStatus, "FAILED")
                .orderByDesc(CodeGenerationJob::getId)
                .last("limit 1")
                .oneOpt()
                .orElse(null);
        ReviewIssue latestOpenReviewIssue = reviewIssueService.lambdaQuery()
                .eq(ReviewIssue::getProjectId, projectId)
                .eq(ReviewIssue::getStatus, "OPEN")
                .orderByDesc(ReviewIssue::getId)
                .last("limit 1")
                .oneOpt()
                .orElse(null);
        EvaluationSummary evaluationSummary = latestEvaluationSummary(projectId);
        return new ProjectDiagnosticsResponse(
                projectId,
                latestRun == null ? null : latestRun.getId(),
                latestRun == null ? null : latestRun.getCorrelationId(),
                workflowRunService.lambdaQuery()
                        .eq(WorkflowRun::getProjectId, projectId)
                        .count(),
                workflowRunService.lambdaQuery()
                        .eq(WorkflowRun::getProjectId, projectId)
                        .eq(WorkflowRun::getStatus, "RUNNING")
                        .count(),
                workflowRunService.lambdaQuery()
                        .eq(WorkflowRun::getProjectId, projectId)
                        .eq(WorkflowRun::getStatus, "FAILED")
                        .count(),
                workflowRunService.lambdaQuery()
                        .eq(WorkflowRun::getProjectId, projectId)
                        .eq(WorkflowRun::getStatus, "CANCELLED")
                        .count(),
                latestFailedRun == null ? null : latestFailedRun.getErrorMessage(),
                agentTaskService.lambdaQuery()
                        .eq(AgentTask::getProjectId, projectId)
                        .count(),
                agentTaskService.lambdaQuery()
                        .eq(AgentTask::getProjectId, projectId)
                        .eq(AgentTask::getStatus, "FAILED")
                        .count(),
                latestFailedTask == null ? null : latestFailedTask.getNodeName(),
                latestFailedTask == null ? null : latestFailedTask.getErrorMessage(),
                auditEventService.lambdaQuery()
                        .eq(AuditEvent::getProjectId, projectId)
                        .count(),
                externalCallLogService.lambdaQuery()
                        .eq(ExternalCallLog::getProjectId, projectId)
                        .count(),
                externalCallLogService.lambdaQuery()
                        .eq(ExternalCallLog::getProjectId, projectId)
                        .eq(ExternalCallLog::getStatus, "FAILED")
                        .count(),
                latestFailedExternalCall == null ? null : latestFailedExternalCall.getErrorMessage(),
                latestFailedExternalCall == null ? null : latestFailedExternalCall.getDurationMs(),
                modelInvocationService.lambdaQuery()
                        .eq(ModelInvocation::getProjectId, projectId)
                        .count(),
                modelInvocationService.lambdaQuery()
                        .eq(ModelInvocation::getProjectId, projectId)
                        .eq(ModelInvocation::getStatus, "FAILED")
                        .count(),
                latestFailedModelInvocation == null ? null : latestFailedModelInvocation.getAgentNode(),
                latestFailedModelInvocation == null ? null : latestFailedModelInvocation.getModelName(),
                latestFailedModelInvocation == null ? null : latestFailedModelInvocation.getDurationMs(),
                latestFailedModelInvocation == null ? null : latestFailedModelInvocation.getErrorMessage(),
                codeGenerationJobService.lambdaQuery()
                        .eq(CodeGenerationJob::getProjectId, projectId)
                        .count(),
                codeGenerationJobService.lambdaQuery()
                        .eq(CodeGenerationJob::getProjectId, projectId)
                        .eq(CodeGenerationJob::getStatus, "RUNNING")
                        .count(),
                codeGenerationJobService.lambdaQuery()
                        .eq(CodeGenerationJob::getProjectId, projectId)
                        .eq(CodeGenerationJob::getStatus, "FAILED")
                        .count(),
                codeGenerationJobService.lambdaQuery()
                        .eq(CodeGenerationJob::getProjectId, projectId)
                        .eq(CodeGenerationJob::getStatus, "CANCELLED")
                        .count(),
                latestFailedCodeGenerationJob == null ? null : latestFailedCodeGenerationJob.getErrorMessage(),
                evaluationSummary.overallScore(),
                evaluationSummary.grade(),
                evaluationSummary.issueCount(),
                reviewIssueService.lambdaQuery()
                        .eq(ReviewIssue::getProjectId, projectId)
                        .count(),
                reviewIssueService.lambdaQuery()
                        .eq(ReviewIssue::getProjectId, projectId)
                        .eq(ReviewIssue::getStatus, "OPEN")
                        .count(),
                reviewIssueService.lambdaQuery()
                        .eq(ReviewIssue::getProjectId, projectId)
                        .eq(ReviewIssue::getStatus, "OPEN")
                        .eq(ReviewIssue::getSeverity, "HIGH")
                        .count(),
                latestOpenReviewIssue == null ? null : latestOpenReviewIssue.getSeverity(),
                latestOpenReviewIssue == null ? null : latestOpenReviewIssue.getIssueType(),
                latestOpenReviewIssue == null ? null : latestOpenReviewIssue.getDescription()
        );
    }

    private EvaluationSummary latestEvaluationSummary(Long projectId) {
        Artifact latestEvaluationReport = artifactService.lambdaQuery()
                .eq(Artifact::getProjectId, projectId)
                .eq(Artifact::getType, "EVALUATION_REPORT")
                .orderByDesc(Artifact::getId)
                .last("limit 1")
                .oneOpt()
                .orElse(null);
        if (latestEvaluationReport == null || latestEvaluationReport.getContent() == null) {
            return new EvaluationSummary(null, null, 0);
        }
        try {
            JsonNode root = objectMapper.readTree(latestEvaluationReport.getContent());
            Integer overallScore = root.path("overall_score").isNumber()
                    ? root.path("overall_score").asInt()
                    : null;
            String grade = root.path("grade").isTextual()
                    ? root.path("grade").asText()
                    : null;
            long issueCount = root.path("issues").isArray()
                    ? root.path("issues").size()
                    : 0;
            return new EvaluationSummary(overallScore, grade, issueCount);
        } catch (JsonProcessingException ex) {
            return new EvaluationSummary(null, null, 0);
        }
    }

    private record EvaluationSummary(Integer overallScore, String grade, long issueCount) {
    }
}
