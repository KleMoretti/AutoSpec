package com.autospec.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;

public record ProjectMemoryFactResponse(
        Long id,
        @JsonProperty("project_id") Long projectId,
        @JsonProperty("fact_type") String factType,
        @JsonProperty("fact_key") String factKey,
        JsonNode value,
        @JsonProperty("content_hash") String contentHash,
        Integer version,
        @JsonProperty("conflict_status") String conflictStatus,
        JsonNode provenance,
        @JsonProperty("valid_from_at") LocalDateTime validFromAt,
        @JsonProperty("valid_until_at") LocalDateTime validUntilAt,
        @JsonProperty("expires_at") LocalDateTime expiresAt
) {
}
