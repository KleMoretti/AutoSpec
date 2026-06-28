package com.autospec.service;

import java.util.List;

public record AgentGenerationResult(
        String prdJson,
        String architectureDesignJson,
        String backendDesignJson,
        String frontendSkeletonJson,
        String reviewReportJson,
        List<AgentEngineExecutionRecord> records
) {

    public AgentGenerationResult(
            String prdJson,
            String backendDesignJson,
            String reviewReportJson,
            List<AgentEngineExecutionRecord> records
    ) {
        this(prdJson, null, backendDesignJson, null, reviewReportJson, records);
    }
}
