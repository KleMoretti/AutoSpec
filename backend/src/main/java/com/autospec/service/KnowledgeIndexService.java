package com.autospec.service;

import com.autospec.dto.KnowledgeSourceResponse;
import com.autospec.entity.Artifact;
import com.autospec.entity.KnowledgeChunk;
import com.autospec.entity.KnowledgeDocument;
import com.autospec.entity.ProjectMember;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class KnowledgeIndexService {

    private final KnowledgeDocumentService knowledgeDocumentService;
    private final KnowledgeChunkService knowledgeChunkService;
    private final ProjectMemberService projectMemberService;

    public KnowledgeIndexService(
            KnowledgeDocumentService knowledgeDocumentService,
            KnowledgeChunkService knowledgeChunkService,
            ProjectMemberService projectMemberService
    ) {
        this.knowledgeDocumentService = knowledgeDocumentService;
        this.knowledgeChunkService = knowledgeChunkService;
        this.projectMemberService = projectMemberService;
    }

    @Transactional
    public void indexApprovedArtifact(Artifact artifact) {
        boolean exists = knowledgeDocumentService.lambdaQuery()
                .eq(KnowledgeDocument::getArtifactId, artifact.getId())
                .exists();
        if (exists) {
            return;
        }

        KnowledgeDocument document = new KnowledgeDocument();
        document.setProjectId(artifact.getProjectId());
        document.setArtifactId(artifact.getId());
        document.setArtifactType(artifact.getType());
        document.setArtifactVersion(artifact.getVersion());
        document.setTitle(artifact.getTitle());
        document.setStatus("INDEXED");
        knowledgeDocumentService.save(document);

        List<String> chunks = splitIntoChunks(artifact.getContent(), 900);
        for (int index = 0; index < chunks.size(); index++) {
            KnowledgeChunk chunk = new KnowledgeChunk();
            chunk.setDocumentId(document.getId());
            chunk.setChunkIndex(index);
            chunk.setContent(chunks.get(index));
            chunk.setTokenHint(Math.max(1, chunks.get(index).length() / 4));
            chunk.setRetrievalTerms(extractTerms(chunks.get(index)));
            knowledgeChunkService.save(chunk);
        }
    }

    public List<KnowledgeSourceResponse> sources(Long projectId) {
        return knowledgeDocumentService.lambdaQuery()
                .eq(KnowledgeDocument::getProjectId, projectId)
                .orderByAsc(KnowledgeDocument::getId)
                .list()
                .stream()
                .map(document -> KnowledgeSourceResponse.from(document, firstChunk(document.getId())))
                .toList();
    }

    public List<KnowledgeSourceResponse> retrieve(String query, int limit) {
        return retrieveFromDocuments(query, limit, knowledgeDocumentService.list());
    }

    public List<KnowledgeSourceResponse> retrieve(String query, int limit, Long userId) {
        Set<Long> accessibleProjectIds = projectMemberService.lambdaQuery()
                .eq(ProjectMember::getUserId, userId)
                .list()
                .stream()
                .map(ProjectMember::getProjectId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (accessibleProjectIds.isEmpty()) {
            return List.of();
        }
        return retrieveFromDocuments(
                query,
                limit,
                knowledgeDocumentService.lambdaQuery()
                        .in(KnowledgeDocument::getProjectId, accessibleProjectIds)
                        .list()
        );
    }

    private List<KnowledgeSourceResponse> retrieveFromDocuments(
            String query,
            int limit,
            List<KnowledgeDocument> documents
    ) {
        Set<String> queryTerms = terms(query);
        if (queryTerms.isEmpty()) {
            return List.of();
        }
        return documents
                .stream()
                .map(document -> scoredSource(document, queryTerms))
                .filter(scored -> scored.score() > 0)
                .sorted(Comparator.comparingInt(ScoredSource::score).reversed())
                .limit(limit)
                .map(ScoredSource::source)
                .toList();
    }

    private ScoredSource scoredSource(KnowledgeDocument document, Set<String> queryTerms) {
        List<KnowledgeChunk> chunks = knowledgeChunkService.lambdaQuery()
                .eq(KnowledgeChunk::getDocumentId, document.getId())
                .orderByAsc(KnowledgeChunk::getChunkIndex)
                .list();
        int score = 0;
        StringBuilder content = new StringBuilder();
        for (KnowledgeChunk chunk : chunks) {
            Set<String> chunkTerms = terms(chunk.getRetrievalTerms());
            for (String queryTerm : queryTerms) {
                if (chunkTerms.contains(queryTerm)) {
                    score++;
                }
            }
            if (content.length() < 1200) {
                content.append(chunk.getContent()).append('\n');
            }
        }
        return new ScoredSource(KnowledgeSourceResponse.from(document, content.toString().trim()), score);
    }

    private String firstChunk(Long documentId) {
        return knowledgeChunkService.lambdaQuery()
                .eq(KnowledgeChunk::getDocumentId, documentId)
                .orderByAsc(KnowledgeChunk::getChunkIndex)
                .last("limit 1")
                .oneOpt()
                .map(KnowledgeChunk::getContent)
                .orElse("");
    }

    private List<String> splitIntoChunks(String content, int maxLength) {
        if (content == null || content.isBlank()) {
            return List.of("");
        }
        List<String> chunks = new ArrayList<>();
        for (int start = 0; start < content.length(); start += maxLength) {
            chunks.add(content.substring(start, Math.min(content.length(), start + maxLength)));
        }
        return chunks;
    }

    private String extractTerms(String content) {
        return String.join(" ", terms(content));
    }

    private Set<String> terms(String content) {
        Set<String> result = new LinkedHashSet<>();
        if (content == null) {
            return result;
        }
        String normalized = content.toLowerCase(Locale.ROOT).replaceAll("[^\\p{IsHan}\\p{Alnum}]+", " ");
        for (String token : normalized.split("\\s+")) {
            if (token.length() >= 2) {
                result.add(token);
            }
            if (result.size() >= 40) {
                break;
            }
        }
        return result;
    }

    private record ScoredSource(KnowledgeSourceResponse source, int score) {
    }
}
