package com.autospec.service.impl;

import com.autospec.entity.Artifact;
import com.autospec.entity.ReviewIssue;
import com.autospec.mapper.ReviewIssueMapper;
import com.autospec.service.ArtifactService;
import com.autospec.service.ReviewIssueService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ReviewIssueServiceImpl extends ServiceImpl<ReviewIssueMapper, ReviewIssue> implements ReviewIssueService {

    private final ArtifactService artifactService;
    private final ObjectMapper objectMapper;

    public ReviewIssueServiceImpl(ArtifactService artifactService, ObjectMapper objectMapper) {
        this.artifactService = artifactService;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<ReviewIssue> listByProjectId(Long projectId, int limit, int offset) {
        return lambdaQuery()
                .eq(ReviewIssue::getProjectId, projectId)
                .orderByAsc(ReviewIssue::getId)
                .last("limit " + limit + " offset " + offset)
                .list();
    }

    @Override
    public int latestReviewScore(Long projectId) {
        Artifact report = artifactService.lambdaQuery()
                .eq(Artifact::getProjectId, projectId)
                .eq(Artifact::getType, "REVIEW_REPORT")
                .orderByDesc(Artifact::getVersion)
                .last("limit 1")
                .oneOpt()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Review report not found"
                ));
        try {
            return objectMapper.readTree(report.getContent()).path("score").asInt(0);
        } catch (Exception ex) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Invalid review report JSON",
                    ex
            );
        }
    }

    @Override
    @Transactional
    public ReviewIssue updateDisposition(
            Long projectId,
            Long issueId,
            String status,
            String resolution,
            Long resolvedInArtifactId,
            Long actorUserId
    ) {
        ReviewIssue issue = getById(issueId);
        if (issue == null || !projectId.equals(issue.getProjectId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Review issue not found");
        }
        String normalized = status.trim().toUpperCase();
        if (("RESOLVED".equals(normalized) || "IGNORED".equals(normalized))
                && (resolution == null || resolution.isBlank())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "resolution is required when resolving or ignoring an issue"
            );
        }
        if (resolvedInArtifactId != null) {
            Artifact artifact = artifactService.getById(resolvedInArtifactId);
            if (artifact == null || !projectId.equals(artifact.getProjectId())) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Resolved artifact is not in this project"
                );
            }
        }
        issue.setStatus(normalized);
        issue.setResolution(resolution == null ? null : resolution.trim());
        issue.setResolvedInArtifactId(resolvedInArtifactId);
        issue.setOwnerUserId(actorUserId);
        issue.setUpdatedAt(LocalDateTime.now());
        updateById(issue);
        return issue;
    }
}
