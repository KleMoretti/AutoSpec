package com.autospec.dto;

import java.util.List;

public record WorkflowTraceStepResponse(
        Long id,
        Integer step,
        String phase,
        String status,
        String reasonCode,
        String planHash,
        String observationHash,
        String candidateHash,
        String verificationFactRef,
        List<String> validationIssueCodes,
        String modelCallRef,
        String toolCallRef,
        Long startedAtEpochMs,
        Long finishedAtEpochMs,
        Integer durationMs
) {
}
