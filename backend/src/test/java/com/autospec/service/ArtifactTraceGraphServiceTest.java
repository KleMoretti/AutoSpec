package com.autospec.service;

import com.autospec.entity.Artifact;
import com.autospec.entity.ArtifactComponent;
import com.autospec.entity.ArtifactTraceEdge;
import com.autospec.mapper.ArtifactComponentMapper;
import com.autospec.mapper.ArtifactTraceEdgeMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class ArtifactTraceGraphServiceTest {

    @Test
    void projectsStableRequirementStoryAndAcceptanceEdges() {
        ArtifactComponentMapper componentMapper = mock(ArtifactComponentMapper.class);
        ArtifactTraceEdgeMapper edgeMapper = mock(ArtifactTraceEdgeMapper.class);
        ArtifactTraceGraphService service = new ArtifactTraceGraphService(
                componentMapper,
                edgeMapper,
                new ObjectMapper()
        );
        Artifact artifact = new Artifact();
        artifact.setId(11L);
        artifact.setProjectId(7L);
        artifact.setType("PRD");
        artifact.setContent("""
                {
                  "core_features": [{
                    "requirement_id": "REQ-SELL",
                    "name": "Publish listing",
                    "description": "A student publishes a listing"
                  }],
                  "user_stories": [{
                    "story_id": "STORY-SELL",
                    "goal": "publish a listing",
                    "requirement_refs": ["REQ-SELL"],
                    "acceptance_criteria": [{
                      "acceptance_id": "AC-SELL",
                      "criterion": "The listing is visible",
                      "requirement_refs": ["REQ-SELL"]
                    }]
                  }]
                }
                """);

        service.project(artifact, 31L);

        ArgumentCaptor<ArtifactComponent> components = ArgumentCaptor.forClass(ArtifactComponent.class);
        verify(componentMapper, times(3)).insert(components.capture());
        assertThat(components.getAllValues())
                .extracting(ArtifactComponent::getComponentKey)
                .containsExactly("REQ-SELL", "STORY-SELL", "AC-SELL");
        assertThat(components.getAllValues())
                .allMatch(value -> value.getWorkflowRunId().equals(31L))
                .allMatch(value -> value.getContentHash().length() == 64);

        ArgumentCaptor<ArtifactTraceEdge> edges = ArgumentCaptor.forClass(ArtifactTraceEdge.class);
        verify(edgeMapper, times(3)).insert(edges.capture());
        List<String> relationships = edges.getAllValues().stream()
                .map(value -> value.getFromComponentKey() + "->"
                        + value.getToComponentKey() + ":" + value.getRelationType())
                .toList();
        assertThat(relationships).containsExactly(
                "STORY-SELL->REQ-SELL:SATISFIES",
                "AC-SELL->STORY-SELL:VERIFIES_STORY",
                "AC-SELL->REQ-SELL:VERIFIES"
        );
    }
}
