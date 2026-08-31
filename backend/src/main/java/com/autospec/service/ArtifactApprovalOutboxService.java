package com.autospec.service;

import com.autospec.entity.Artifact;
import com.autospec.entity.KnowledgeDocument;
import com.autospec.entity.WorkflowOutbox;
import com.autospec.mapper.WorkflowOutboxMapper;
import com.autospec.util.ContentHash;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class ArtifactApprovalOutboxService {
    public static final String EVENT_TYPE = "ARTIFACT_APPROVED";

    private final WorkflowOutboxMapper outboxMapper;
    private final ObjectMapper objectMapper;
    private final KnowledgeDocumentService knowledgeDocumentService;

    public ArtifactApprovalOutboxService(
            WorkflowOutboxMapper outboxMapper,
            ObjectMapper objectMapper,
            KnowledgeDocumentService knowledgeDocumentService
    ) {
        this.outboxMapper = outboxMapper;
        this.objectMapper = objectMapper;
        this.knowledgeDocumentService = knowledgeDocumentService;
    }

    @Transactional
    public WorkflowOutbox enqueue(Artifact artifact) {
        if (artifact == null || artifact.getId() == null || artifact.getProjectId() == null) {
            throw new IllegalArgumentException("approved artifact identity is required");
        }
        if (!"APPROVED".equals(artifact.getStatus())) {
            throw new IllegalArgumentException("ARTIFACT_APPROVED requires an approved artifact");
        }
        String contentHash = artifact.getContentHash() == null
                || artifact.getContentHash().isBlank()
                ? ContentHash.sha256(artifact.getContent())
                : artifact.getContentHash();
        String indexKey = indexKey(artifact, contentHash);
        String eventId = "artifact-approved:" + artifact.getId() + ":" + indexKey;
        WorkflowOutbox existing = outboxMapper.selectOne(
                new LambdaQueryWrapper<WorkflowOutbox>()
                        .eq(WorkflowOutbox::getEventId, eventId)
                        .last("limit 1")
        );
        if (existing != null) {
            return existing;
        }

        LocalDateTime now = LocalDateTime.now();
        WorkflowOutbox outbox = new WorkflowOutbox();
        outbox.setEventId(eventId);
        outbox.setAggregateId("artifact:" + artifact.getId());
        outbox.setEventType(EVENT_TYPE);
        outbox.setPayloadJson(payload(artifact, contentHash, indexKey));
        outbox.setStatus("PENDING");
        outbox.setRetryCount(0);
        outbox.setCreatedAt(now);
        outbox.setUpdatedAt(now);
        outboxMapper.insert(outbox);
        return outbox;
    }

    @Transactional
    public boolean enqueueRebuild(
            Artifact artifact,
            KnowledgeDocument document,
            String reason
    ) {
        if (artifact == null || document == null
                || !"APPROVED".equals(artifact.getStatus())
                || !artifact.getId().equals(document.getArtifactId())) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        String failureMessage = reason == null || reason.isBlank()
                ? "Knowledge index metadata or embedding is stale"
                : reason;
        if (failureMessage.length() > 1000) {
            failureMessage = failureMessage.substring(0, 1000);
        }
        int claimed = knowledgeDocumentService.lambdaUpdate()
                .eq(KnowledgeDocument::getId, document.getId())
                .eq(KnowledgeDocument::getStatus, KnowledgeIndexService.STATUS_ACTIVE)
                .set(KnowledgeDocument::getStatus, KnowledgeIndexService.STATUS_FAILED)
                .set(KnowledgeDocument::getFailureMessage, failureMessage)
                .set(KnowledgeDocument::getUpdatedAt, now)
                .update() ? 1 : 0;
        if (claimed == 0) {
            return false;
        }

        String contentHash = artifact.getContentHash() == null
                || artifact.getContentHash().isBlank()
                ? ContentHash.sha256(artifact.getContent())
                : artifact.getContentHash();
        String indexKey = indexKey(artifact, contentHash);
        WorkflowOutbox outbox = new WorkflowOutbox();
        outbox.setEventId("knowledge-rebuild:" + document.getId() + ":" + UUID.randomUUID());
        outbox.setAggregateId("artifact:" + artifact.getId());
        outbox.setEventType(EVENT_TYPE);
        outbox.setPayloadJson(payload(artifact, contentHash, indexKey));
        outbox.setStatus("PENDING");
        outbox.setRetryCount(0);
        outbox.setCreatedAt(now);
        outbox.setUpdatedAt(now);
        outboxMapper.insert(outbox);
        return true;
    }

    private String payload(Artifact artifact, String contentHash, String indexKey) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("event_type", EVENT_TYPE);
        payload.put("artifact_id", artifact.getId());
        payload.put("project_id", artifact.getProjectId());
        payload.put("artifact_type", artifact.getType());
        payload.put("artifact_version", artifact.getVersion());
        payload.put("content_hash", contentHash);
        payload.put("chunker_version", KnowledgeIndexService.CHUNKER_VERSION);
        payload.put("embedding_model", KnowledgeEmbeddingService.MODEL_VERSION);
        payload.put("index_key", indexKey);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize ARTIFACT_APPROVED event", exception);
        }
    }

    private String indexKey(Artifact artifact, String contentHash) {
        return ContentHash.sha256(
                artifact.getId() + "|" + contentHash + "|"
                        + KnowledgeIndexService.CHUNKER_VERSION + "|"
                        + KnowledgeEmbeddingService.MODEL_VERSION
        );
    }
}
