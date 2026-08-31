package com.autospec.service;

import com.autospec.entity.Artifact;
import com.autospec.entity.KnowledgeDocument;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;

public class KnowledgeIndexRecoveryJob {
    private final KnowledgeDocumentService knowledgeDocumentService;
    private final ArtifactService artifactService;
    private final KnowledgeIndexService knowledgeIndexService;
    private final ArtifactApprovalOutboxService approvalOutboxService;
    private final int batchSize;
    private long afterDocumentId;

    public KnowledgeIndexRecoveryJob(
            KnowledgeDocumentService knowledgeDocumentService,
            ArtifactService artifactService,
            KnowledgeIndexService knowledgeIndexService,
            ArtifactApprovalOutboxService approvalOutboxService,
            int batchSize
    ) {
        this.knowledgeDocumentService = knowledgeDocumentService;
        this.artifactService = artifactService;
        this.knowledgeIndexService = knowledgeIndexService;
        this.approvalOutboxService = approvalOutboxService;
        this.batchSize = Math.max(1, Math.min(batchSize, 100));
    }

    @Scheduled(
            fixedDelayString = "${autospec.knowledge.recovery.fixed-delay:60000}",
            initialDelayString = "${autospec.knowledge.recovery.initial-delay:30000}"
    )
    public void recover() {
        scanAndEnqueue();
    }

    synchronized int scanAndEnqueue() {
        List<KnowledgeDocument> active = knowledgeDocumentService.lambdaQuery()
                .eq(KnowledgeDocument::getStatus, KnowledgeIndexService.STATUS_ACTIVE)
                .gt(afterDocumentId > 0, KnowledgeDocument::getId, afterDocumentId)
                .orderByAsc(KnowledgeDocument::getId)
                .last("limit " + batchSize)
                .list();
        if (active.isEmpty() && afterDocumentId > 0) {
            afterDocumentId = 0;
            active = knowledgeDocumentService.lambdaQuery()
                    .eq(KnowledgeDocument::getStatus, KnowledgeIndexService.STATUS_ACTIVE)
                    .orderByAsc(KnowledgeDocument::getId)
                    .last("limit " + batchSize)
                    .list();
        }
        if (!active.isEmpty()) {
            afterDocumentId = active.get(active.size() - 1).getId();
        }
        int enqueued = 0;
        for (KnowledgeDocument document : active) {
            if (!knowledgeIndexService.requiresRebuild(document)) {
                continue;
            }
            Artifact artifact = artifactService.getById(document.getArtifactId());
            if (approvalOutboxService.enqueueRebuild(
                    artifact,
                    document,
                    "Knowledge index metadata or stored embedding is stale or corrupt"
            )) {
                enqueued++;
            }
        }
        return enqueued;
    }
}
