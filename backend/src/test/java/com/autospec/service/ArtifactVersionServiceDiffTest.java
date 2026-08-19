package com.autospec.service;

import com.autospec.dto.ArtifactDiffResponse;
import com.autospec.entity.Artifact;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ArtifactVersionServiceDiffTest {
    @Mock
    private ArtifactService artifactService;
    @Mock
    private ProjectService projectService;
    @Mock
    private KnowledgeIndexService knowledgeIndexService;

    private ArtifactVersionService versionService;

    @BeforeEach
    void setUp() {
        versionService = new ArtifactVersionService(
                artifactService,
                projectService,
                knowledgeIndexService,
                new ObjectMapper()
        );
    }

    @Test
    void reportsStableFieldLevelJsonPathsBetweenVersions() {
        when(artifactService.getById(1L)).thenReturn(artifact(1L, """
                {"features":[{"name":"create"},{"name":"review"}],"settings":{"enabled":true}}
                """));
        when(artifactService.getById(2L)).thenReturn(artifact(2L, """
                {"features":[{"name":"create"},{"name":"approve"}],"settings":{"enabled":false}}
                """));

        ArtifactDiffResponse diff = versionService.diff(7L, 1L, 2L);

        assertThat(diff.changed()).isTrue();
        assertThat(diff.changedPaths()).containsExactly(
                "$.features[1].name",
                "$.settings.enabled"
        );
    }

    @Test
    void fallsBackToWholeDocumentDiffForNonJsonArtifacts() {
        when(artifactService.getById(1L)).thenReturn(artifact(1L, "first markdown"));
        when(artifactService.getById(2L)).thenReturn(artifact(2L, "second markdown"));

        ArtifactDiffResponse diff = versionService.diff(7L, 1L, 2L);

        assertThat(diff.changedPaths()).containsExactly("$");
    }

    private Artifact artifact(long id, String content) {
        Artifact artifact = new Artifact();
        artifact.setId(id);
        artifact.setProjectId(7L);
        artifact.setType("PRD");
        artifact.setContent(content);
        return artifact;
    }
}
