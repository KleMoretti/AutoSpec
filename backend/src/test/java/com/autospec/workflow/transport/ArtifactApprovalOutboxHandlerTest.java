package com.autospec.workflow.transport;

import com.autospec.entity.Artifact;
import com.autospec.entity.WorkflowOutbox;
import com.autospec.mapper.ArtifactMapper;
import com.autospec.service.KnowledgeIndexService;
import com.autospec.util.ContentHash;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArtifactApprovalOutboxHandlerTest {
    @Test
    void recordsFailedIndexStateAndRethrowsSoTheOutboxRetries() {
        ArtifactMapper artifactMapper = mock(ArtifactMapper.class);
        KnowledgeIndexService indexService = mock(KnowledgeIndexService.class);
        Artifact artifact = new Artifact();
        artifact.setId(9L);
        artifact.setProjectId(7L);
        artifact.setType("PRD");
        artifact.setVersion(3);
        artifact.setStatus("APPROVED");
        artifact.setContent("approved knowledge");
        artifact.setContentHash(ContentHash.sha256(artifact.getContent()));
        when(artifactMapper.selectById(9L)).thenReturn(artifact);
        IllegalStateException failure = new IllegalStateException("embedding unavailable");
        doThrow(failure).when(indexService).indexApprovedArtifact(artifact);
        ArtifactApprovalOutboxHandler handler = new ArtifactApprovalOutboxHandler(
                artifactMapper,
                indexService,
                new ObjectMapper()
        );
        WorkflowOutbox outbox = new WorkflowOutbox();
        outbox.setEventType("ARTIFACT_APPROVED");
        outbox.setPayloadJson("""
                {"artifact_id":9,"project_id":7,"artifact_type":"PRD",
                 "artifact_version":3,"content_hash":"%s"}
                """.formatted(artifact.getContentHash()));

        assertThatThrownBy(() -> handler.handle(outbox)).isSameAs(failure);

        verify(indexService).recordIndexFailure(artifact, failure);
    }
}
