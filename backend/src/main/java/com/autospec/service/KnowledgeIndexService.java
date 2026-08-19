package com.autospec.service;

import com.autospec.dto.KnowledgeSourceResponse;
import com.autospec.entity.Artifact;
import com.autospec.entity.KnowledgeChunk;
import com.autospec.entity.KnowledgeDocument;
import com.autospec.entity.ProjectMember;
import com.autospec.util.ContentHash;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class KnowledgeIndexService {
    private static final Pattern TERM_PATTERN = Pattern.compile("[\\p{IsHan}]+|[\\p{Alnum}]+");
    private static final String RETRIEVAL_STRATEGY = "HYBRID_RRF_HASHING_V1";
    private static final int RRF_CONSTANT = 60;

    private final KnowledgeDocumentService knowledgeDocumentService;
    private final KnowledgeChunkService knowledgeChunkService;
    private final ProjectMemberService projectMemberService;
    private final KnowledgeEmbeddingService embeddingService;
    private final ObjectMapper objectMapper;

    public KnowledgeIndexService(
            KnowledgeDocumentService knowledgeDocumentService,
            KnowledgeChunkService knowledgeChunkService,
            ProjectMemberService projectMemberService,
            KnowledgeEmbeddingService embeddingService,
            ObjectMapper objectMapper
    ) {
        this.knowledgeDocumentService = knowledgeDocumentService;
        this.knowledgeChunkService = knowledgeChunkService;
        this.projectMemberService = projectMemberService;
        this.embeddingService = embeddingService;
        this.objectMapper = objectMapper;
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

        List<String> chunks = splitIntoChunks(artifact.getContent(), 900, 120);
        for (int index = 0; index < chunks.size(); index++) {
            String content = chunks.get(index);
            double[] embedding = embeddingService.embed(content);
            KnowledgeChunk chunk = new KnowledgeChunk();
            chunk.setDocumentId(document.getId());
            chunk.setChunkIndex(index);
            chunk.setContent(content);
            chunk.setTokenHint(Math.max(1, content.length() / 4));
            chunk.setRetrievalTerms(extractTerms(content));
            chunk.setContentHash(ContentHash.sha256(content));
            chunk.setEmbeddingModel(KnowledgeEmbeddingService.MODEL_VERSION);
            chunk.setEmbeddingDimensions(embedding.length);
            chunk.setEmbeddingJson(writeEmbedding(embedding));
            chunk.setVectorRef("knowledge_chunk:" + ContentHash.sha256(content));
            knowledgeChunkService.save(chunk);
        }
    }

    public List<KnowledgeSourceResponse> sources(Long projectId) {
        return knowledgeDocumentService.lambdaQuery()
                .eq(KnowledgeDocument::getProjectId, projectId)
                .orderByAsc(KnowledgeDocument::getId)
                .list()
                .stream()
                .map(this::firstChunkSource)
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

    public List<KnowledgeSourceResponse> retrieveForProject(
            String query,
            int limit,
            Long projectId
    ) {
        return retrieveFromDocuments(
                query,
                limit,
                knowledgeDocumentService.lambdaQuery()
                        .eq(KnowledgeDocument::getProjectId, projectId)
                        .list()
        );
    }

    private List<KnowledgeSourceResponse> retrieveFromDocuments(
            String query,
            int limit,
            List<KnowledgeDocument> documents
    ) {
        Set<String> queryTerms = terms(query);
        if (queryTerms.isEmpty() || documents.isEmpty()) {
            return List.of();
        }
        double[] queryEmbedding = embeddingService.embed(query);
        List<ChunkCandidate> candidates = new ArrayList<>();
        for (KnowledgeDocument document : documents) {
            Set<String> titleTerms = terms(document.getTitle() + " " + document.getArtifactType());
            int titleScore = overlap(queryTerms, titleTerms) * 4;
            List<KnowledgeChunk> chunks = knowledgeChunkService.lambdaQuery()
                    .eq(KnowledgeChunk::getDocumentId, document.getId())
                    .orderByAsc(KnowledgeChunk::getChunkIndex)
                    .list();
            for (KnowledgeChunk chunk : chunks) {
                Set<String> chunkTerms = terms(chunk.getContent() + " " + chunk.getRetrievalTerms());
                int keywordScore = titleScore + overlap(queryTerms, chunkTerms) * 3;
                double vectorScore = embeddingService.cosine(
                        queryEmbedding,
                        readOrCreateEmbedding(chunk)
                );
                if (keywordScore > 0 || vectorScore >= 0.10) {
                    candidates.add(new ChunkCandidate(
                            document,
                            chunk,
                            keywordScore,
                            vectorScore
                    ));
                }
            }
        }
        if (candidates.isEmpty()) {
            return List.of();
        }

        Map<String, Integer> keywordRanks = ranks(
                candidates,
                Comparator.comparingInt(ChunkCandidate::keywordScore)
                        .reversed()
                        .thenComparing(candidate -> candidate.document().getId())
                        .thenComparing(candidate -> candidate.chunk().getChunkIndex())
        );
        Map<String, Integer> vectorRanks = ranks(
                candidates,
                Comparator.comparingDouble(ChunkCandidate::vectorScore)
                        .reversed()
                        .thenComparing(candidate -> candidate.document().getId())
                        .thenComparing(candidate -> candidate.chunk().getChunkIndex())
        );

        int safeLimit = Math.max(1, Math.min(limit, 50));
        return candidates.stream()
                .map(candidate -> rerank(candidate, keywordRanks, vectorRanks))
                .sorted(Comparator.comparingDouble(RankedChunk::score)
                        .reversed()
                        .thenComparing(ranked -> ranked.candidate().document().getId())
                        .thenComparing(ranked -> ranked.candidate().chunk().getChunkIndex()))
                .limit(safeLimit)
                .map(ranked -> KnowledgeSourceResponse.from(
                        ranked.candidate().document(),
                        ranked.candidate().chunk(),
                        RETRIEVAL_STRATEGY,
                        roundScore(ranked.score())
                ))
                .toList();
    }

    private RankedChunk rerank(
            ChunkCandidate candidate,
            Map<String, Integer> keywordRanks,
            Map<String, Integer> vectorRanks
    ) {
        String key = key(candidate);
        double reciprocalRankFusion = 1.0 / (RRF_CONSTANT + keywordRanks.get(key))
                + 1.0 / (RRF_CONSTANT + vectorRanks.get(key));
        double score = reciprocalRankFusion * 1_000
                + candidate.keywordScore() * 0.35
                + Math.max(0.0, candidate.vectorScore()) * 2.0;
        return new RankedChunk(candidate, score);
    }

    private Map<String, Integer> ranks(
            List<ChunkCandidate> candidates,
            Comparator<ChunkCandidate> comparator
    ) {
        List<ChunkCandidate> sorted = candidates.stream().sorted(comparator).toList();
        Map<String, Integer> ranks = new HashMap<>();
        for (int index = 0; index < sorted.size(); index++) {
            ranks.put(key(sorted.get(index)), index + 1);
        }
        return ranks;
    }

    private KnowledgeSourceResponse firstChunkSource(KnowledgeDocument document) {
        return knowledgeChunkService.lambdaQuery()
                .eq(KnowledgeChunk::getDocumentId, document.getId())
                .orderByAsc(KnowledgeChunk::getChunkIndex)
                .last("limit 1")
                .oneOpt()
                .map(chunk -> KnowledgeSourceResponse.from(document, chunk, "INDEX_LIST", 0.0))
                .orElseGet(() -> KnowledgeSourceResponse.from(document, ""));
    }

    private double[] readOrCreateEmbedding(KnowledgeChunk chunk) {
        if (KnowledgeEmbeddingService.MODEL_VERSION.equals(chunk.getEmbeddingModel())
                && chunk.getEmbeddingDimensions() != null
                && chunk.getEmbeddingDimensions() == KnowledgeEmbeddingService.DIMENSIONS
                && chunk.getEmbeddingJson() != null) {
            try {
                return objectMapper.readValue(chunk.getEmbeddingJson(), double[].class);
            } catch (Exception ignored) {
                // Corrupt or stale derived data is safely rebuilt in memory for this query.
            }
        }
        return embeddingService.embed(chunk.getContent());
    }

    private String writeEmbedding(double[] embedding) {
        try {
            return objectMapper.writeValueAsString(embedding);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to serialize knowledge embedding", exception);
        }
    }

    private List<String> splitIntoChunks(String content, int maxLength, int overlap) {
        if (content == null || content.isBlank()) {
            return List.of("");
        }
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < content.length()) {
            int hardEnd = Math.min(content.length(), start + maxLength);
            int end = preferredBoundary(content, start, hardEnd);
            if (end <= start) {
                end = hardEnd;
            }
            chunks.add(content.substring(start, end).trim());
            if (end >= content.length()) {
                break;
            }
            start = Math.max(start + 1, end - overlap);
        }
        return chunks;
    }

    private int preferredBoundary(String content, int start, int hardEnd) {
        if (hardEnd >= content.length()) {
            return hardEnd;
        }
        int minimum = start + (hardEnd - start) / 2;
        int paragraph = content.lastIndexOf("\n\n", hardEnd);
        if (paragraph >= minimum) {
            return paragraph + 2;
        }
        int line = content.lastIndexOf('\n', hardEnd);
        if (line >= minimum) {
            return line + 1;
        }
        int sentence = Math.max(
                content.lastIndexOf('。', hardEnd),
                content.lastIndexOf('.', hardEnd)
        );
        return sentence >= minimum ? sentence + 1 : hardEnd;
    }

    private String extractTerms(String content) {
        return String.join(" ", terms(content));
    }

    private Set<String> terms(String content) {
        Set<String> result = new LinkedHashSet<>();
        if (content == null) {
            return result;
        }
        Matcher matcher = TERM_PATTERN.matcher(content.toLowerCase(Locale.ROOT));
        while (matcher.find() && result.size() < 120) {
            String token = matcher.group();
            if (token.codePoints().allMatch(codePoint ->
                    Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN)) {
                addHanTerms(token, result);
            } else if (token.length() >= 2) {
                result.add(token);
            }
        }
        return result;
    }

    private void addHanTerms(String token, Set<String> result) {
        int[] points = token.codePoints().toArray();
        if (points.length >= 2 && points.length <= 12) {
            result.add(token);
        }
        for (int width : new int[]{2, 3}) {
            for (int start = 0; start + width <= points.length && result.size() < 120; start++) {
                result.add(new String(points, start, width));
            }
        }
    }

    private int overlap(Set<String> queryTerms, Set<String> sourceTerms) {
        int score = 0;
        for (String term : queryTerms) {
            if (sourceTerms.contains(term)) {
                score += term.codePointCount(0, term.length()) >= 3 ? 2 : 1;
            }
        }
        return score;
    }

    private String key(ChunkCandidate candidate) {
        return candidate.document().getId() + ":" + candidate.chunk().getChunkIndex();
    }

    private double roundScore(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private record ChunkCandidate(
            KnowledgeDocument document,
            KnowledgeChunk chunk,
            int keywordScore,
            double vectorScore
    ) {
    }

    private record RankedChunk(ChunkCandidate candidate, double score) {
    }
}
