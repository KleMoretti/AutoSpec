package com.autospec.dto;

import com.autospec.entity.PromptVersion;

import java.time.LocalDateTime;

public record PromptVersionResponse(
        Long id,
        String promptKey,
        String version,
        String checksum,
        Boolean active,
        LocalDateTime createdAt
) {

    public static PromptVersionResponse from(PromptVersion promptVersion) {
        return new PromptVersionResponse(
                promptVersion.getId(),
                promptVersion.getPromptKey(),
                promptVersion.getVersion(),
                promptVersion.getChecksum(),
                promptVersion.getActive(),
                promptVersion.getCreatedAt()
        );
    }
}
