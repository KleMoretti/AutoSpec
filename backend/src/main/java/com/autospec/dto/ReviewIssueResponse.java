package com.autospec.dto;

public record ReviewIssueResponse(
        Long id,
        String issueKey,
        String severity,
        String issueType,
        String artifactType,
        String artifactPath,
        String requirementId,
        String description,
        String suggestion,
        String evidence,
        String status,
        Long ownerUserId,
        String resolution,
        Long resolvedInArtifactId
) {
}
