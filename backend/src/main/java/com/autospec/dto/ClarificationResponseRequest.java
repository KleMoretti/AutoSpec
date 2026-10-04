package com.autospec.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.List;

public record ClarificationResponseRequest(
        @JsonProperty("expected_lock_version")
        @NotNull @PositiveOrZero Integer expectedLockVersion,
        @JsonProperty("idempotency_key")
        @NotBlank String idempotencyKey,
        JsonNode answers,
        @JsonProperty("accepted_assumption_ids")
        List<String> acceptedAssumptionIds,
        @JsonProperty("conflict_resolutions")
        JsonNode conflictResolutions
) {
}
