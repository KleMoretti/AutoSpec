package com.autospec.dto;

public record GenerateProjectResponse(
        Long projectId,
        String status,
        Integer percent
) {
}
