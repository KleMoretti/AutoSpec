package com.autospec.dto;

import java.util.List;

public record ReviewResponse(
        Integer score,
        List<ReviewIssueResponse> issues
) {
}
