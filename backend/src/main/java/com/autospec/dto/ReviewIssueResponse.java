package com.autospec.dto;

public record ReviewIssueResponse(
        String severity,
        String issueType,
        String description,
        String suggestion,
        String status
) {
}
