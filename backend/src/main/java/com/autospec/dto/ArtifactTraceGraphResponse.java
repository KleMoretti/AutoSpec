package com.autospec.dto;

import com.autospec.entity.ArtifactComponent;
import com.autospec.entity.ArtifactTraceEdge;

import java.util.List;

public record ArtifactTraceGraphResponse(
        Long workflowRunId,
        List<Component> components,
        List<Edge> edges
) {
    public record Component(
            Long artifactId,
            String artifactType,
            String componentKey,
            String componentType,
            String displayName,
            String jsonPath,
            String contentHash
    ) {
        public static Component from(ArtifactComponent value) {
            return new Component(
                    value.getArtifactId(),
                    value.getArtifactType(),
                    value.getComponentKey(),
                    value.getComponentType(),
                    value.getDisplayName(),
                    value.getJsonPath(),
                    value.getContentHash()
            );
        }
    }

    public record Edge(
            Long sourceArtifactId,
            String fromComponentKey,
            String toComponentKey,
            String relationType
    ) {
        public static Edge from(ArtifactTraceEdge value) {
            return new Edge(
                    value.getSourceArtifactId(),
                    value.getFromComponentKey(),
                    value.getToComponentKey(),
                    value.getRelationType()
            );
        }
    }
}
