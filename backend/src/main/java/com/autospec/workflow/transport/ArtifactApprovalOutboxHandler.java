package com.autospec.workflow.transport;

import com.autospec.entity.Artifact;
import com.autospec.entity.WorkflowOutbox;
import com.autospec.mapper.ArtifactMapper;
import com.autospec.service.ArtifactApprovalOutboxService;
import com.autospec.service.KnowledgeIndexService;
import com.autospec.util.ContentHash;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class ArtifactApprovalOutboxHandler {
    private final ArtifactMapper artifactMapper;
    private final KnowledgeIndexService knowledgeIndexService;
    private final ObjectMapper objectMapper;

    public ArtifactApprovalOutboxHandler(
            ArtifactMapper artifactMapper,
            KnowledgeIndexService knowledgeIndexService,
            ObjectMapper objectMapper
    ) {
        this.artifactMapper = artifactMapper;
        this.knowledgeIndexService = knowledgeIndexService;
        this.objectMapper = objectMapper;
    }

    public void handle(WorkflowOutbox outbox) {
        if (!ArtifactApprovalOutboxService.EVENT_TYPE.equals(outbox.getEventType())) {
            throw new IllegalArgumentException("Unsupported local outbox event: " + outbox.getEventType());
        }
        JsonNode payload = payload(outbox.getPayloadJson());
        long artifactId = payload.path("artifact_id").asLong(0);
        Artifact artifact = artifactMapper.selectById(artifactId);
        String artifactContentHash = artifact == null
                ? null
                : artifact.getContentHash() == null || artifact.getContentHash().isBlank()
                ? ContentHash.sha256(artifact.getContent())
                : artifact.getContentHash();
        if (artifact == null
                || payload.path("project_id").asLong(0) != artifact.getProjectId()
                || !payload.path("artifact_type").asText().equals(artifact.getType())
                || payload.path("artifact_version").asInt(0) != artifact.getVersion()
                || !payload.path("content_hash").asText().equals(artifactContentHash)
                || !"APPROVED".equals(artifact.getStatus())) {
            throw new IllegalStateException("ARTIFACT_APPROVED payload no longer matches artifact " + artifactId);
        }
        try {
            knowledgeIndexService.indexApprovedArtifact(artifact);
        } catch (RuntimeException indexingFailure) {
            try {
                knowledgeIndexService.recordIndexFailure(artifact, indexingFailure);
            } catch (RuntimeException diagnosticFailure) {
                indexingFailure.addSuppressed(diagnosticFailure);
            }
            throw indexingFailure;
        }
    }

    private JsonNode payload(String value) {
        try {
            return objectMapper.readTree(value == null ? "{}" : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid ARTIFACT_APPROVED payload", exception);
        }
    }
}
