package com.autospec.service;

import com.autospec.dto.KnowledgeSourceResponse;
import com.autospec.entity.Artifact;
import com.autospec.entity.KnowledgeChunk;
import com.autospec.entity.KnowledgeDocument;
import com.autospec.entity.ProjectMember;
import com.autospec.entity.Project;
import com.autospec.mapper.ProjectMapper;
import com.autospec.util.ContentHash;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class KnowledgeIndexService {
    public static final String CHUNKER_VERSION = "structured-text-900-120-v1";
    public static final String STATUS_INDEXING = "INDEXING";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_SUPERSEDED = "SUPERSEDED";
    public static final String STATUS_FAILED = "FAILED";
    private static final Pattern TERM_PATTERN = Pattern.compile("[\\p{IsHan}]+|[\\p{Alnum}]+");
    public static final String RETRIEVAL_STRATEGY = "HYBRID_BM25_EMBEDDING_RRF_RERANK_V2";
    public static final String RETRIEVAL_POLICY = "PROJECT_KNOWLEDGE_BM25_EMBEDDING_RRF_RERANK_V2";
    public static final String RERANKER_VERSION = "deterministic-rerank-v1";
    public static final String ACCESS_POLICY_VERSION = "project-member-active-expiry-v1";
    private static final int RRF_CONSTANT = 60;

    private final KnowledgeDocumentService knowledgeDocumentService;
    private final KnowledgeChunkService knowledgeChunkService;
    private final ProjectMemberService projectMemberService;
    private final KnowledgeEmbeddingService embeddingService;
    private final ObjectMapper objectMapper;
    private final ProjectMapper projectMapper;
    private final KnowledgeQueryRewriter queryRewriter;
    private final KnowledgeCorpusEpochService corpusEpochService;

    public KnowledgeIndexService(
            KnowledgeDocumentService knowledgeDocumentService,
            KnowledgeChunkService knowledgeChunkService,
            ProjectMemberService projectMemberService,
            KnowledgeEmbeddingService embeddingService,
            ObjectMapper objectMapper,
            ProjectMapper projectMapper,
            KnowledgeQueryRewriter queryRewriter,
            KnowledgeCorpusEpochService corpusEpochService
    ) {
        this.knowledgeDocumentService = knowledgeDocumentService;
        this.knowledgeChunkService = knowledgeChunkService;
        this.projectMemberService = projectMemberService;
        this.embeddingService = embeddingService;
        this.objectMapper = objectMapper;
        this.projectMapper = projectMapper;
        this.queryRewriter = queryRewriter;
        this.corpusEpochService = corpusEpochService;
    }

    public long currentCorpusEpoch(Long projectId) {
        return corpusEpochService.current(projectId);
    }

    public String actorScopeHash(Long projectId, Long userId) {
        if (projectId == null || userId == null) {
            return null;
        }
        ProjectMember member = projectMemberService.lambdaQuery()
                .eq(ProjectMember::getProjectId, projectId)
                .eq(ProjectMember::getUserId, userId)
                .last("limit 1")
                .oneOpt()
                .orElse(null);
        if (member == null || member.getRole() == null || member.getRole().isBlank()) {
            return null;
        }
        return ContentHash.sha256(
                projectId + "|" + userId + "|" + member.getRole().trim().toUpperCase(Locale.ROOT)
                        + "|" + KnowledgeCorpusEpochService.ACCESS_POLICY_VERSION
        );
    }

    public String retrievalCacheKey(String query, int limit, Long projectId, Long userId) {
        String scopeHash = actorScopeHash(projectId, userId);
        if (scopeHash == null) {
            return null;
        }
        long epoch = currentCorpusEpoch(projectId);
        String queryHash = ContentHash.sha256(query == null ? "" : query.trim());
        String policyHash = ContentHash.sha256(
                RETRIEVAL_STRATEGY + "|" + KnowledgeQueryRewriter.VERSION + "|"
                        + KnowledgeEmbeddingService.MODEL_VERSION + "|" + RERANKER_VERSION + "|"
                        + ACCESS_POLICY_VERSION + "|ALL_PROJECT_CORPORA"
        );
        int safeLimit = Math.max(1, Math.min(limit, 50));
        return "autospec-cache:rag_query:v1:" + ContentHash.sha256(
                projectId + "|" + scopeHash + "|" + epoch + "|" + queryHash + "|"
                        + policyHash + "|" + safeLimit
        );
    }

    @Transactional
    public void indexApprovedArtifact(Artifact artifact) {
        validateApprovedArtifact(artifact);
        lockProject(artifact.getProjectId());
        String contentHash = artifact.getContentHash() == null
                || artifact.getContentHash().isBlank()
                ? ContentHash.sha256(artifact.getContent())
                : artifact.getContentHash();
        KnowledgeDocument document = documentForArtifact(artifact.getId());
        if (document != null
                && STATUS_ACTIVE.equals(document.getStatus())
                && contentHash.equals(document.getContentHash())
                && CHUNKER_VERSION.equals(document.getChunkerVersion())
                && KnowledgeEmbeddingService.MODEL_VERSION.equals(document.getEmbeddingModel())
                && KnowledgeCorpus.fromArtifactType(artifact.getType()).name()
                .equals(KnowledgeCorpus.normalize(document.getCorpusType()))
                && hasHealthyChunks(document)) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        if (document == null) {
            document = new KnowledgeDocument();
            document.setProjectId(artifact.getProjectId());
            document.setArtifactId(artifact.getId());
            document.setArtifactType(artifact.getType());
            document.setCorpusType(KnowledgeCorpus.fromArtifactType(artifact.getType()).name());
            document.setArtifactVersion(artifact.getVersion());
            document.setTitle(artifact.getTitle());
            document.setStatus(STATUS_INDEXING);
            document.setContentHash(contentHash);
            document.setChunkerVersion(CHUNKER_VERSION);
            document.setEmbeddingModel(KnowledgeEmbeddingService.MODEL_VERSION);
            document.setCreatedAt(now);
            document.setUpdatedAt(now);
            try {
                knowledgeDocumentService.save(document);
            } catch (DuplicateKeyException duplicate) {
                document = documentForArtifact(artifact.getId());
                if (document == null) {
                    throw duplicate;
                }
                prepareForIndexing(document, artifact, contentHash, now);
            }
        } else {
            prepareForIndexing(document, artifact, contentHash, now);
        }

        knowledgeChunkService.lambdaUpdate()
                .eq(KnowledgeChunk::getDocumentId, document.getId())
                .remove();

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

        boolean newerVersionIsActive = knowledgeDocumentService.lambdaQuery()
                .eq(KnowledgeDocument::getProjectId, artifact.getProjectId())
                .eq(KnowledgeDocument::getArtifactType, artifact.getType())
                .eq(KnowledgeDocument::getStatus, STATUS_ACTIVE)
                .gt(KnowledgeDocument::getArtifactVersion, artifact.getVersion())
                .exists();
        if (newerVersionIsActive) {
            markSuperseded(document.getId(), now);
            return;
        }

        boolean activated = knowledgeDocumentService.lambdaUpdate()
                .eq(KnowledgeDocument::getProjectId, artifact.getProjectId())
                .eq(KnowledgeDocument::getArtifactType, artifact.getType())
                .eq(KnowledgeDocument::getStatus, STATUS_ACTIVE)
                .ne(KnowledgeDocument::getId, document.getId())
                .set(KnowledgeDocument::getStatus, STATUS_SUPERSEDED)
                .set(KnowledgeDocument::getSupersededAt, now)
                .set(KnowledgeDocument::getUpdatedAt, now)
                .update();
        knowledgeDocumentService.lambdaUpdate()
                .eq(KnowledgeDocument::getId, document.getId())
                .eq(KnowledgeDocument::getStatus, STATUS_INDEXING)
                .set(KnowledgeDocument::getStatus, STATUS_ACTIVE)
                .set(KnowledgeDocument::getFailureMessage, null)
                .set(KnowledgeDocument::getActivatedAt, now)
                .set(KnowledgeDocument::getSupersededAt, null)
                .set(KnowledgeDocument::getUpdatedAt, now)
                .update();
        if (activated) {
            corpusEpochService.bump(artifact.getProjectId(), "INDEX_REBUILD_COMPLETED");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordIndexFailure(Artifact artifact, Throwable failure) {
        if (artifact == null || artifact.getId() == null) {
            return;
        }
        String contentHash = artifact.getContentHash() == null
                || artifact.getContentHash().isBlank()
                ? ContentHash.sha256(artifact.getContent())
                : artifact.getContentHash();
        LocalDateTime now = LocalDateTime.now();
        KnowledgeDocument document = documentForArtifact(artifact.getId());
        if (document == null) {
            document = new KnowledgeDocument();
            document.setProjectId(artifact.getProjectId());
            document.setArtifactId(artifact.getId());
            document.setArtifactType(artifact.getType());
            document.setCorpusType(KnowledgeCorpus.fromArtifactType(artifact.getType()).name());
            document.setArtifactVersion(artifact.getVersion());
            document.setTitle(artifact.getTitle());
            document.setCreatedAt(now);
            try {
                knowledgeDocumentService.save(document);
            } catch (DuplicateKeyException ignored) {
                document = documentForArtifact(artifact.getId());
            }
        }
        if (document == null) {
            throw new IllegalStateException("Unable to persist failed knowledge index state");
        }
        boolean failed = knowledgeDocumentService.lambdaUpdate()
                .eq(KnowledgeDocument::getId, document.getId())
                .set(KnowledgeDocument::getStatus, STATUS_FAILED)
                .set(KnowledgeDocument::getContentHash, contentHash)
                .set(KnowledgeDocument::getChunkerVersion, CHUNKER_VERSION)
                .set(KnowledgeDocument::getEmbeddingModel, KnowledgeEmbeddingService.MODEL_VERSION)
                .set(KnowledgeDocument::getCorpusType,
                        KnowledgeCorpus.fromArtifactType(artifact.getType()).name())
                .set(KnowledgeDocument::getFailureMessage, failureMessage(failure))
                .set(KnowledgeDocument::getUpdatedAt, now)
                .update();
        if (failed) {
            corpusEpochService.bump(artifact.getProjectId(), "INDEX_FAILURE_INVALIDATED");
        }
    }

    public List<KnowledgeSourceResponse> sources(Long projectId) {
        return sources(projectId, null);
    }

    public List<KnowledgeSourceResponse> sources(Long projectId, String corpusType) {
        return knowledgeDocumentService.lambdaQuery()
                .eq(KnowledgeDocument::getProjectId, projectId)
                .eq(KnowledgeDocument::getStatus, STATUS_ACTIVE)
                .eq(corpusType != null && !corpusType.isBlank(),
                        KnowledgeDocument::getCorpusType,
                        KnowledgeCorpus.normalize(corpusType))
                .and(wrapper -> wrapper
                        .isNull(KnowledgeDocument::getExpiresAt)
                        .or()
                        .gt(KnowledgeDocument::getExpiresAt, LocalDateTime.now()))
                .orderByAsc(KnowledgeDocument::getId)
                .list()
                .stream()
                .map(this::firstChunkSource)
                .toList();
    }

    private List<KnowledgeSourceResponse> retrieveWithinProject(
            String query,
            int limit,
            Long projectId,
            String corpusType
    ) {
        LocalDateTime now = LocalDateTime.now();
        return retrieveFromDocuments(
                query,
                limit,
                knowledgeDocumentService.lambdaQuery()
                        .eq(KnowledgeDocument::getProjectId, projectId)
                        .eq(KnowledgeDocument::getStatus, STATUS_ACTIVE)
                        .eq(corpusType != null && !corpusType.isBlank(),
                                KnowledgeDocument::getCorpusType,
                                KnowledgeCorpus.normalize(corpusType))
                        .and(wrapper -> wrapper
                                .isNull(KnowledgeDocument::getExpiresAt)
                                .or()
                                .gt(KnowledgeDocument::getExpiresAt, now))
                        .list()
        );
    }

    public List<KnowledgeSourceResponse> retrieveForProject(
            String query,
            int limit,
            Long projectId,
            Long userId
    ) {
        return retrieveForProject(
                query,
                limit,
                projectId,
                userId,
                null
        );
    }

    public List<KnowledgeSourceResponse> retrieveForProject(
            String query,
            int limit,
            Long projectId,
            Long userId,
            String corpusType
    ) {
        boolean authorized = projectMemberService.lambdaQuery()
                .eq(ProjectMember::getProjectId, projectId)
                .eq(ProjectMember::getUserId, userId)
                .exists();
        if (!authorized) {
            return List.of();
        }
        return retrieveWithinProject(query, limit, projectId, corpusType);
    }

    public boolean requiresRebuild(KnowledgeDocument document) {
        return document == null
                || !STATUS_ACTIVE.equals(document.getStatus())
                || document.getContentHash() == null
                || !document.getContentHash().matches("^[0-9a-f]{64}$")
                || !CHUNKER_VERSION.equals(document.getChunkerVersion())
                || !KnowledgeEmbeddingService.MODEL_VERSION.equals(document.getEmbeddingModel())
                || !hasHealthyChunks(document);
    }

    private void lockProject(Long projectId) {
        Project project = projectMapper.selectOne(new LambdaQueryWrapper<Project>()
                .eq(Project::getId, projectId)
                .last("for update"));
        if (project == null) {
            throw new IllegalArgumentException("knowledge project does not exist: " + projectId);
        }
    }

    private List<KnowledgeSourceResponse> retrieveFromDocuments(
            String query,
            int limit,
            List<KnowledgeDocument> documents
    ) {
        KnowledgeQueryRewriter.Rewrite rewrite = queryRewriter.rewrite(query);
        Set<String> queryTerms = rewrite.terms();
        if (queryTerms.isEmpty() || documents.isEmpty()) {
            return List.of();
        }
        List<double[]> queryEmbeddings = rewrite.variants().stream()
                .map(embeddingService::embed)
                .toList();
        Map<Long, KnowledgeDocument> documentsById = documents.stream()
                .filter(document -> document.getId() != null)
                .collect(Collectors.toMap(KnowledgeDocument::getId, document -> document));
        if (documentsById.isEmpty()) {
            return List.of();
        }
        List<KnowledgeChunk> chunks = knowledgeChunkService.lambdaQuery()
                .in(KnowledgeChunk::getDocumentId, documentsById.keySet())
                .orderByAsc(KnowledgeChunk::getDocumentId)
                .orderByAsc(KnowledgeChunk::getChunkIndex)
                .list();
        if (chunks.isEmpty()) {
            return List.of();
        }
        List<ChunkFeatures> features = new ArrayList<>();
        Map<String, Integer> documentFrequency = new HashMap<>();
        long totalTermLength = 0;
        for (KnowledgeDocument document : documents) {
            if (!STATUS_ACTIVE.equals(document.getStatus())
                    || !CHUNKER_VERSION.equals(document.getChunkerVersion())
                    || !KnowledgeEmbeddingService.MODEL_VERSION.equals(document.getEmbeddingModel())) {
                continue;
            }
            Set<String> titleTerms = terms(document.getTitle() + " " + document.getArtifactType());
            for (KnowledgeChunk chunk : chunks) {
                if (!document.getId().equals(chunk.getDocumentId())) {
                    continue;
                }
                Set<String> chunkTerms = terms(chunk.getContent() + " " + chunk.getRetrievalTerms());
                double[] chunkEmbedding = readStoredEmbedding(chunk);
                if (chunkEmbedding == null) {
                    continue;
                }
                double vectorScore = queryEmbeddings.stream()
                        .mapToDouble(queryEmbedding -> embeddingService.cosine(queryEmbedding, chunkEmbedding))
                        .max()
                        .orElse(0.0);
                ChunkFeatures feature = new ChunkFeatures(
                        document,
                        chunk,
                        chunkTerms,
                        titleTerms,
                        vectorScore
                );
                features.add(feature);
                totalTermLength += chunkTerms.size();
                for (String term : chunkTerms) {
                    documentFrequency.merge(term, 1, Integer::sum);
                }
            }
        }
        if (features.isEmpty()) {
            return List.of();
        }
        double averageTermLength = (double) totalTermLength / features.size();
        List<ChunkCandidate> candidates = features.stream()
                .map(feature -> new ChunkCandidate(
                        feature.document(),
                        feature.chunk(),
                        bm25Score(queryTerms, feature, documentFrequency, features.size(), averageTermLength),
                        feature.vectorScore()
                ))
                .filter(candidate -> candidate.keywordScore() > 0 || candidate.vectorScore() >= 0.10)
                .toList();
        if (candidates.isEmpty()) {
            return List.of();
        }

        Map<String, Integer> keywordRanks = ranks(
                candidates,
                Comparator.comparingDouble(ChunkCandidate::keywordScore)
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
        int topN = Math.min(100, Math.max(20, safeLimit * 4));
        List<RankedChunk> rankedChunks = candidates.stream()
                .sorted(Comparator.comparingDouble((ChunkCandidate candidate) ->
                                rrfScore(candidate, keywordRanks, vectorRanks))
                        .reversed()
                        .thenComparing(candidate -> candidate.document().getId())
                        .thenComparing(candidate -> candidate.chunk().getChunkIndex()))
                .limit(topN)
                .map(candidate -> rerank(candidate, keywordRanks, vectorRanks))
                .sorted(Comparator.comparingDouble(RankedChunk::score)
                        .reversed()
                        .thenComparing(ranked -> ranked.candidate().document().getId())
                        .thenComparing(ranked -> ranked.candidate().chunk().getChunkIndex()))
                .toList();
        Map<Long, Integer> perDocument = new HashMap<>();
        List<KnowledgeSourceResponse> result = new ArrayList<>();
        for (RankedChunk ranked : rankedChunks) {
            Long documentId = ranked.candidate().document().getId();
            if (perDocument.getOrDefault(documentId, 0) >= 2) {
                continue;
            }
            perDocument.merge(documentId, 1, Integer::sum);
            result.add(KnowledgeSourceResponse.from(
                    ranked.candidate().document(),
                    ranked.candidate().chunk(),
                    RETRIEVAL_STRATEGY,
                    roundScore(ranked.score())
            ));
            if (result.size() >= safeLimit) {
                break;
            }
        }
        return result;
    }

    private RankedChunk rerank(
            ChunkCandidate candidate,
            Map<String, Integer> keywordRanks,
            Map<String, Integer> vectorRanks
    ) {
        String key = key(candidate);
        double reciprocalRankFusion = rrfScore(candidate, keywordRanks, vectorRanks);
        double score = reciprocalRankFusion * 1_000
                + candidate.keywordScore() * 0.35
                + Math.max(0.0, candidate.vectorScore()) * 2.0;
        return new RankedChunk(candidate, score);
    }

    private double rrfScore(
            ChunkCandidate candidate,
            Map<String, Integer> keywordRanks,
            Map<String, Integer> vectorRanks
    ) {
        String key = key(candidate);
        return 1.0 / (RRF_CONSTANT + keywordRanks.get(key))
                + 1.0 / (RRF_CONSTANT + vectorRanks.get(key));
    }

    private double bm25Score(
            Set<String> queryTerms,
            ChunkFeatures feature,
            Map<String, Integer> documentFrequency,
            int documentCount,
            double averageLength
    ) {
        double score = 0.0;
        double lengthNorm = 1.0 - 0.75
                + 0.75 * feature.terms().size() / Math.max(1.0, averageLength);
        for (String term : queryTerms) {
            int frequency = termFrequency(feature.chunk().getContent(), term);
            if (frequency == 0) {
                continue;
            }
            int documentFrequencyValue = documentFrequency.getOrDefault(term, 0);
            double idf = Math.log(1.0 + (documentCount - documentFrequencyValue + 0.5)
                    / (documentFrequencyValue + 0.5));
            score += idf * (frequency * 2.2 / (frequency + 1.2 * lengthNorm));
        }
        return score + overlap(queryTerms, feature.titleTerms()) * 0.8;
    }

    private int termFrequency(String content, String term) {
        String source = content == null ? "" : content.toLowerCase(Locale.ROOT);
        String needle = term.toLowerCase(Locale.ROOT);
        if (needle.isBlank()) {
            return 0;
        }
        int frequency = 0;
        int offset = 0;
        while ((offset = source.indexOf(needle, offset)) >= 0) {
            frequency++;
            offset += needle.length();
        }
        return frequency;
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

    private double[] readStoredEmbedding(KnowledgeChunk chunk) {
        if (KnowledgeEmbeddingService.MODEL_VERSION.equals(chunk.getEmbeddingModel())
                && chunk.getEmbeddingDimensions() != null
                && chunk.getEmbeddingDimensions() == KnowledgeEmbeddingService.DIMENSIONS
                && chunk.getEmbeddingJson() != null) {
            try {
                return objectMapper.readValue(chunk.getEmbeddingJson(), double[].class);
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private void validateApprovedArtifact(Artifact artifact) {
        if (artifact == null || artifact.getId() == null
                || artifact.getProjectId() == null || artifact.getVersion() == null) {
            throw new IllegalArgumentException("approved artifact identity is required");
        }
        if (!"APPROVED".equals(artifact.getStatus())) {
            throw new IllegalArgumentException("knowledge indexing requires an APPROVED artifact");
        }
    }

    private KnowledgeDocument documentForArtifact(Long artifactId) {
        return knowledgeDocumentService.lambdaQuery()
                .eq(KnowledgeDocument::getArtifactId, artifactId)
                .last("limit 1")
                .oneOpt()
                .orElse(null);
    }

    private void prepareForIndexing(
            KnowledgeDocument document,
            Artifact artifact,
            String contentHash,
            LocalDateTime now
    ) {
        knowledgeDocumentService.lambdaUpdate()
                .eq(KnowledgeDocument::getId, document.getId())
                .set(KnowledgeDocument::getProjectId, artifact.getProjectId())
                .set(KnowledgeDocument::getArtifactType, artifact.getType())
                .set(KnowledgeDocument::getCorpusType,
                        KnowledgeCorpus.fromArtifactType(artifact.getType()).name())
                .set(KnowledgeDocument::getArtifactVersion, artifact.getVersion())
                .set(KnowledgeDocument::getTitle, artifact.getTitle())
                .set(KnowledgeDocument::getStatus, STATUS_INDEXING)
                .set(KnowledgeDocument::getContentHash, contentHash)
                .set(KnowledgeDocument::getChunkerVersion, CHUNKER_VERSION)
                .set(KnowledgeDocument::getEmbeddingModel, KnowledgeEmbeddingService.MODEL_VERSION)
                .set(KnowledgeDocument::getFailureMessage, null)
                .set(KnowledgeDocument::getUpdatedAt, now)
                .update();
    }

    private void markSuperseded(Long documentId, LocalDateTime now) {
        knowledgeDocumentService.lambdaUpdate()
                .eq(KnowledgeDocument::getId, documentId)
                .set(KnowledgeDocument::getStatus, STATUS_SUPERSEDED)
                .set(KnowledgeDocument::getActivatedAt, null)
                .set(KnowledgeDocument::getSupersededAt, now)
                .set(KnowledgeDocument::getUpdatedAt, now)
                .update();
    }

    private boolean hasHealthyChunks(KnowledgeDocument document) {
        List<KnowledgeChunk> chunks = knowledgeChunkService.lambdaQuery()
                .eq(KnowledgeChunk::getDocumentId, document.getId())
                .list();
        return !chunks.isEmpty() && chunks.stream().allMatch(chunk ->
                readStoredEmbedding(chunk) != null
                        && Objects.equals(
                                chunk.getContentHash(),
                                ContentHash.sha256(chunk.getContent())
                        ));
    }

    private String failureMessage(Throwable failure) {
        if (failure == null) {
            return "Knowledge indexing failed";
        }
        String message = failure.getClass().getSimpleName() + ": "
                + (failure.getMessage() == null ? "indexing failed" : failure.getMessage());
        return message.length() <= 1000 ? message : message.substring(0, 1000);
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
            double keywordScore,
            double vectorScore
    ) {
    }

    private record ChunkFeatures(
            KnowledgeDocument document,
            KnowledgeChunk chunk,
            Set<String> terms,
            Set<String> titleTerms,
            double vectorScore
    ) {
    }

    private record RankedChunk(ChunkCandidate candidate, double score) {
    }
}
