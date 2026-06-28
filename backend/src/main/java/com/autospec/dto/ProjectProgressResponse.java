package com.autospec.dto;

import java.util.List;

public record ProjectProgressResponse(
        Long projectId,
        String status,
        String currentAgent,
        Integer percent,
        List<AgentStepStatus> steps
) {

    public ProjectProgressResponse(Long projectId, String currentAgent, Integer percent, List<AgentStepStatus> steps) {
        this(projectId, null, currentAgent, percent, steps);
    }
}
