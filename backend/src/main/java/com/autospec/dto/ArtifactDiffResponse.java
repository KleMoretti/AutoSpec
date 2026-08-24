package com.autospec.dto;

import java.util.List;

public record ArtifactDiffResponse(
        Long baseArtifactId,
        Long targetArtifactId,
        boolean changed,
        List<String> changedPaths
) {
}
