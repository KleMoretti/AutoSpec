package com.autospec.workflow.transport;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * A durable, transport-safe summary of one bounded agent-loop step.
 *
 * The event deliberately carries hashes and references rather than the full
 * prompt, tool parameters, or model output. Those payloads remain in their
 * respective ledgers and can be joined through the references below.
 */
public record WorkflowAgentStep(
        Integer step,
        String phase,
        String status,
        @JsonProperty("reason_code") String reasonCode,
        @JsonProperty("plan_hash") String planHash,
        @JsonProperty("observation_hash") String observationHash,
        @JsonProperty("validation_issue_codes") List<String> validationIssueCodes,
        @JsonProperty("model_call_ref") String modelCallRef,
        @JsonProperty("tool_call_ref") String toolCallRef,
        @JsonProperty("started_at_epoch_ms") Long startedAtEpochMs,
        @JsonProperty("finished_at_epoch_ms") Long finishedAtEpochMs,
        @JsonProperty("duration_ms") Integer durationMs
) {
    public WorkflowAgentStep {
        validationIssueCodes = validationIssueCodes == null
                ? List.of()
                : List.copyOf(validationIssueCodes);
        if (step == null || step < 1
                || phase == null || phase.isBlank()
                || status == null || status.isBlank()) {
            throw new IllegalArgumentException(
                    "agent step identity fields are required"
            );
        }
        if (startedAtEpochMs != null && startedAtEpochMs < 0
                || finishedAtEpochMs != null && finishedAtEpochMs < 0
                || durationMs != null && durationMs < 0) {
            throw new IllegalArgumentException(
                    "agent step timing values must not be negative"
            );
        }
        if (startedAtEpochMs != null && finishedAtEpochMs != null
                && finishedAtEpochMs < startedAtEpochMs) {
            throw new IllegalArgumentException(
                    "agent step finished_at_epoch_ms must not precede started_at_epoch_ms"
            );
        }
    }
}
