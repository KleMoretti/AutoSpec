package com.autospec.dto;

public record ApproveArtifactResponse(
        Long id,
        String status,
        Integer version
) {
}
