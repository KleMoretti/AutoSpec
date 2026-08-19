package com.autospec.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

public record WorkflowExecutionPolicyRequest(
        @Pattern(regexp = "FAST|BALANCED|DEEP") String qualityProfile,
        @Min(1) Long maxTokens,
        @DecimalMin(value = "0.000001") BigDecimal maxCost,
        @Min(1) Integer maxModelCalls,
        @Min(1000) Long maxWallTimeMs
) {
}
