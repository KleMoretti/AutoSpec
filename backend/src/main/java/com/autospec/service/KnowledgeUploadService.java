package com.autospec.service;

import com.autospec.dto.KnowledgeUploadRequest;
import com.autospec.dto.KnowledgeUploadResponse;
import com.autospec.entity.Artifact;
import com.autospec.util.ContentHash;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;

/** Project-scoped TXT/Markdown uploads represented as immutable approved inputs. */
@Service
public class KnowledgeUploadService {
    public static final String UPLOAD_TYPE = "PROJECT_KNOWLEDGE_UPLOAD";
    private final ArtifactService artifactService;
    private final KnowledgeIndexService knowledgeIndexService;

    public KnowledgeUploadService(ArtifactService artifactService, KnowledgeIndexService knowledgeIndexService) {
        this.artifactService = artifactService;
        this.knowledgeIndexService = knowledgeIndexService;
    }

    @Transactional
    public KnowledgeUploadResponse upload(Long projectId, Long userId, KnowledgeUploadRequest request) {
        if (request == null || request.content() == null || request.content().isBlank()) {
            throw new IllegalArgumentException("upload content is required");
        }
        String contentType = request.contentType() == null || request.contentType().isBlank()
                ? "text/plain" : request.contentType().trim().toLowerCase();
        if (!contentType.equals("text/plain") && !contentType.equals("text/markdown")
                && !contentType.equals("text/x-markdown")) {
            throw new IllegalArgumentException("only text/plain and text/markdown uploads are accepted");
        }
        Artifact existing = artifactService.lambdaQuery()
                .eq(Artifact::getProjectId, projectId)
                .eq(Artifact::getType, UPLOAD_TYPE)
                .eq(Artifact::getUploadIdempotencyKey, request.idempotencyKey())
                .oneOpt().orElse(null);
        if (existing != null) {
            return response(existing);
        }
        String contentHash = ContentHash.sha256(request.content());
        Integer version = artifactService.lambdaQuery()
                .eq(Artifact::getProjectId, projectId)
                .eq(Artifact::getType, UPLOAD_TYPE)
                .list().stream()
                .map(Artifact::getVersion)
                .filter(value -> value != null)
                .max(Comparator.naturalOrder())
                .orElse(0) + 1;
        LocalDateTime now = LocalDateTime.now();
        Artifact artifact = new Artifact();
        artifact.setProjectId(projectId);
        artifact.setType(UPLOAD_TYPE);
        artifact.setTitle(request.fileName().trim());
        artifact.setContent(request.content());
        artifact.setFormat(contentType.equals("text/plain") ? "TXT" : "MARKDOWN");
        artifact.setVersion(version);
        artifact.setStatus("APPROVED");
        artifact.setSourceAgent("HUMAN_UPLOAD");
        artifact.setContentHash(contentHash);
        artifact.setSchemaVersion("knowledge-upload-v1");
        artifact.setUploadIdempotencyKey(request.idempotencyKey());
        artifact.setProvenanceJson("{\"uploaded_by_user_id\":" + userId + "}");
        artifact.setApprovedAt(now);
        artifact.setCreatedAt(now);
        artifact.setUpdatedAt(now);
        artifactService.save(artifact);
        knowledgeIndexService.indexApprovedArtifact(artifact);
        return response(artifact);
    }

    private KnowledgeUploadResponse response(Artifact artifact) {
        return new KnowledgeUploadResponse(
                artifact.getProjectId(), artifact.getId(), artifact.getTitle(), artifact.getVersion(),
                artifact.getContentHash(), artifact.getStatus(),
                knowledgeIndexService.currentCorpusEpoch(artifact.getProjectId())
        );
    }
}
