package com.autospec.service;

import com.autospec.dto.ProjectDashboardItemResponse;
import com.autospec.entity.Artifact;
import com.autospec.entity.ModelInvocation;
import com.autospec.entity.Project;
import com.autospec.entity.ReviewIssue;
import com.autospec.entity.WorkflowApproval;
import com.autospec.entity.WorkflowRun;
import com.autospec.mapper.ArtifactMapper;
import com.autospec.mapper.ModelInvocationMapper;
import com.autospec.mapper.ReviewIssueMapper;
import com.autospec.mapper.WorkflowApprovalMapper;
import com.autospec.mapper.WorkflowRunMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
public class ProjectDashboardService {
    private final WorkflowRunMapper runMapper;
    private final WorkflowApprovalMapper approvalMapper;
    private final ReviewIssueMapper reviewIssueMapper;
    private final ModelInvocationMapper invocationMapper;
    private final ArtifactMapper artifactMapper;
    private final ObjectMapper objectMapper;

    public ProjectDashboardService(
            WorkflowRunMapper runMapper,
            WorkflowApprovalMapper approvalMapper,
            ReviewIssueMapper reviewIssueMapper,
            ModelInvocationMapper invocationMapper,
            ArtifactMapper artifactMapper,
            ObjectMapper objectMapper
    ) {
        this.runMapper = runMapper;
        this.approvalMapper = approvalMapper;
        this.reviewIssueMapper = reviewIssueMapper;
        this.invocationMapper = invocationMapper;
        this.artifactMapper = artifactMapper;
        this.objectMapper = objectMapper;
    }

    public ProjectDashboardItemResponse summarize(Project project) {
        WorkflowRun latestRun = runMapper.selectOne(new LambdaQueryWrapper<WorkflowRun>()
                .eq(WorkflowRun::getProjectId, project.getId())
                .orderByDesc(WorkflowRun::getId)
                .last("limit 1"));
        long pendingApprovals = latestRun == null ? 0 : approvalMapper.selectCount(
                new LambdaQueryWrapper<WorkflowApproval>()
                        .eq(WorkflowApproval::getWorkflowRunId, latestRun.getId())
                        .eq(WorkflowApproval::getStatus, "PENDING")
        );
        long openIssues = reviewIssueMapper.selectCount(new LambdaQueryWrapper<ReviewIssue>()
                .eq(ReviewIssue::getProjectId, project.getId())
                .in(ReviewIssue::getStatus, "OPEN", "IN_PROGRESS"));
        long blockers = reviewIssueMapper.selectCount(new LambdaQueryWrapper<ReviewIssue>()
                .eq(ReviewIssue::getProjectId, project.getId())
                .in(ReviewIssue::getStatus, "OPEN", "IN_PROGRESS")
                .in(ReviewIssue::getSeverity, "CRITICAL", "HIGH"));
        BigDecimal cost = invocationMapper.selectList(new LambdaQueryWrapper<ModelInvocation>()
                        .eq(ModelInvocation::getProjectId, project.getId()))
                .stream()
                .map(ModelInvocation::getEstimatedCost)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Artifact evaluation = artifactMapper.selectOne(new LambdaQueryWrapper<Artifact>()
                .eq(Artifact::getProjectId, project.getId())
                .eq(Artifact::getType, "EVALUATION_REPORT")
                .orderByDesc(Artifact::getVersion)
                .last("limit 1"));
        return new ProjectDashboardItemResponse(
                project.getId(),
                project.getName(),
                summary(project.getOriginalRequirement()),
                project.getStatus(),
                latestRun == null ? null : latestRun.getId(),
                latestRun == null ? null : latestRun.getStatus(),
                pendingApprovals,
                openIssues,
                blockers,
                cost,
                qualityScore(evaluation),
                project.getUpdatedAt()
        );
    }

    private Integer qualityScore(Artifact evaluation) {
        if (evaluation == null || evaluation.getContent() == null) {
            return null;
        }
        try {
            var score = objectMapper.readTree(evaluation.getContent()).path("overall_score");
            return score.isNumber()
                    ? score.asInt()
                    : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String summary(String requirement) {
        if (requirement == null || requirement.length() <= 180) {
            return requirement;
        }
        return requirement.substring(0, 177) + "...";
    }
}
