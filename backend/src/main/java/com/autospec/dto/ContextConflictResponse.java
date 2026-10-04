package com.autospec.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

public record ContextConflictResponse(
        @JsonProperty("conflict_id") String conflictId,
        @JsonProperty("project_id") Long projectId,
        @JsonProperty("fact_type") String factType,
        @JsonProperty("fact_key") String factKey,
        JsonNode value,
        @JsonProperty("source_ref") String sourceRef,
        Integer version,
        boolean blocking,
        String reason,
        String status
) {
}
