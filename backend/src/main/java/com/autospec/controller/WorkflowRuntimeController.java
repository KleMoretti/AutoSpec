package com.autospec.controller;

import com.autospec.dto.CursorPageResponse;
import com.autospec.dto.CursorPaginationRequest;
import com.autospec.dto.WorkflowNodeRunResponse;
import com.autospec.dto.WorkflowExecutionPolicyRequest;
import com.autospec.dto.WorkflowReplayRequest;
import com.autospec.dto.WorkflowRunResponse;
import com.autospec.dto.WorkflowRunStartRequest;
import com.autospec.dto.WorkflowRuntimeMetricsResponse;
import com.autospec.dto.WorkflowTraceResponse;
import com.autospec.entity.WorkflowNodeRun;
import com.autospec.entity.WorkflowRun;
import com.autospec.mapper.WorkflowNodeRunMapper;
import com.autospec.mapper.WorkflowRunMapper;
import com.autospec.service.ProjectAccessService;
import com.autospec.service.KnowledgeEmbeddingService;
import com.autospec.service.KnowledgeIndexService;
import com.autospec.service.KnowledgeQueryRewriter;
import com.autospec.service.WorkflowReplayService;
import com.autospec.service.WorkflowRunCreationService;
import com.autospec.service.WorkflowRuntimeMetricsService;
import com.autospec.service.WorkflowTraceService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/workflow-runs")
public class WorkflowRuntimeController {
    private final WorkflowRunMapper runMapper;
    private final WorkflowNodeRunMapper nodeRunMapper;
    private final ProjectAccessService projectAccessService;
    private final WorkflowReplayService replayService;
    private final WorkflowRunCreationService runCreationService;
    private final WorkflowRuntimeMetricsService metricsService;
    private final KnowledgeIndexService knowledgeIndexService;
    private final WorkflowTraceService traceService;
    private final ObjectMapper objectMapper;

    @Autowired
    public WorkflowRuntimeController(
            WorkflowRunMapper runMapper,
            WorkflowNodeRunMapper nodeRunMapper,
            ProjectAccessService projectAccessService,
            WorkflowReplayService replayService,
            WorkflowRunCreationService runCreationService,
            WorkflowRuntimeMetricsService metricsService,
            KnowledgeIndexService knowledgeIndexService,
            WorkflowTraceService traceService,
            ObjectMapper objectMapper
    ) {
        this.runMapper = runMapper;
        this.nodeRunMapper = nodeRunMapper;
        this.projectAccessService = projectAccessService;
        this.replayService = replayService;
        this.runCreationService = runCreationService;
        this.metricsService = metricsService;
        this.knowledgeIndexService = knowledgeIndexService;
        this.traceService = traceService;
        this.objectMapper = objectMapper;
    }

    public WorkflowRuntimeController(
            WorkflowRunMapper runMapper,
            WorkflowNodeRunMapper nodeRunMapper,
            ProjectAccessService projectAccessService,
            WorkflowReplayService replayService,
            WorkflowRunCreationService runCreationService,
            WorkflowRuntimeMetricsService metricsService,
            KnowledgeIndexService knowledgeIndexService,
            ObjectMapper objectMapper
    ) {
        this(
                runMapper,
                nodeRunMapper,
                projectAccessService,
                replayService,
                runCreationService,
                metricsService,
                knowledgeIndexService,
                null,
                objectMapper
        );
    }

    @PostMapping
    public WorkflowRunResponse start(
            @Valid @RequestBody WorkflowRunStartRequest request,
            @RequestHeader(value = "X-AutoSpec-Session-Token", required = false) String sessionToken
    ) {
        Long actorUserId = projectAccessService.resolveUserId(sessionToken);
        projectAccessService.requireProjectRole(
                request.projectId(),
                actorUserId,
                "OWNER",
                "EDITOR"
        );
        WorkflowExecutionPolicyRequest policy = request.executionPolicy();
        Map<String, Object> trustedInput = trustedInput(
                request.input(), request.projectId(), actorUserId
        );
        try {
            return WorkflowRunResponse.from(runCreationService.start(
                    new WorkflowRunCreationService.StartCommand(
                            request.projectId(),
                            request.workflowVersionId(),
                            objectMapper.writeValueAsString(trustedInput),
                            request.idempotencyKey(),
                            policy == null ? null : policy.qualityProfile(),
                            policy == null ? null : policy.maxTokens(),
                            policy == null ? null : policy.maxCost(),
                            policy == null ? null : policy.maxModelCalls(),
                            policy == null ? null : policy.maxWallTimeMs(),
                            actorUserId
                    )
            ));
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid workflow input", exception);
        }
    }

    private Map<String, Object> trustedInput(
            Map<String, Object> requested,
            Long projectId,
            Long actorUserId
    ) {
        Map<String, Object> trusted = new LinkedHashMap<>(requested);
        trusted.remove("rework_directive");
        trusted.remove("context_manifest");
        Object rawRequirement = trusted.get("requirement");
        String requirement = rawRequirement instanceof String value ? value.trim() : "";
        if (requirement.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "input.requirement is required");
        }
        List<Map<String, Object>> sources = knowledgeIndexService.retrieveForProject(
                        requirement,
                        5,
                        projectId,
                        actorUserId
                ).stream()
                .filter(source -> projectId.equals(source.projectId()))
                .map(source -> {
                    Map<String, Object> value = new LinkedHashMap<>();
                    String citationId = "artifact:" + source.artifactId()
                            + ":v" + source.artifactVersion();
                    if (source.chunkIndex() != null) {
                        citationId += ":chunk:" + source.chunkIndex();
                    }
                    value.put("citation_id", citationId);
                    value.put("project_id", source.projectId());
                    value.put("artifact_id", source.artifactId());
                    value.put("artifact_type", source.artifactType());
                    value.put("corpus_type", source.corpusType());
                    value.put("title", source.title());
                    value.put("artifact_version", source.artifactVersion());
                    value.put("chunk_id", source.chunkId());
                    value.put("chunk_index", source.chunkIndex());
                    value.put("citation_location", source.citationLocation());
                    value.put("content", source.content());
                    value.put("retrieval_strategy", source.retrievalStrategy());
                    value.put("chunker_version", source.chunkerVersion());
                    value.put("embedding_model", source.embeddingModel());
                    value.put("artifact_content_hash", source.artifactContentHash());
                    value.put("chunk_content_hash", source.chunkContentHash());
                    value.put("relevance_score", source.relevanceScore());
                    return value;
                })
                .toList();
        trusted.put("requirement", requirement);
        trusted.put("retrieved_sources", sources);
        trusted.put("retrieval_project_id", projectId);
        trusted.put("retrieval_policy", KnowledgeIndexService.RETRIEVAL_POLICY);
        long corpusEpoch = knowledgeIndexService.currentCorpusEpoch(projectId);
        String actorScopeHash = knowledgeIndexService.actorScopeHash(projectId, actorUserId);
        String retrievalCacheKey = knowledgeIndexService.retrievalCacheKey(
                requirement,
                5,
                projectId,
                actorUserId
        );
        trusted.put("corpus_epoch", corpusEpoch);
        if (actorScopeHash != null) {
            trusted.put("actor_scope_hash", actorScopeHash);
        }
        if (retrievalCacheKey != null) {
            trusted.put("retrieval_cache_key", retrievalCacheKey);
        }
        Map<String, Object> retrievalCache = new LinkedHashMap<>();
        retrievalCache.put("layer", "RAG_QUERY");
        retrievalCache.put("mode", "SHADOW");
        retrievalCache.put("hit", false);
        retrievalCache.put("corpus_epoch", corpusEpoch);
        retrievalCache.put("invalidation_reason", "SHADOW_READ_ONLY");
        if (retrievalCacheKey != null) {
            retrievalCache.put("key", retrievalCacheKey);
        }
        trusted.put("retrieval_cache", retrievalCache);
        trusted.put("retrieval_trace", Map.of(
                "retriever_version", KnowledgeIndexService.RETRIEVAL_STRATEGY,
                "query_rewrite_version", KnowledgeQueryRewriter.VERSION,
                "embedding_version", KnowledgeEmbeddingService.MODEL_VERSION,
                "reranker_version", KnowledgeIndexService.RERANKER_VERSION,
                "access_policy_version", KnowledgeIndexService.ACCESS_POLICY_VERSION,
                "filters", Map.of(
                        "project_id", projectId,
                        "corpus", "ALL_PROJECT_CORPORA",
                        "status", "ACTIVE",
                        "expiry", "NULL_OR_FUTURE",
                        "user_scope", "PROJECT_MEMBER"
                ),
                "corpus_epoch", corpusEpoch,
                "cache_key", retrievalCacheKey == null ? "UNAVAILABLE" : retrievalCacheKey,
                "hit_count", sources.size(),
                "empty_recall", sources.isEmpty()
        ));
        return trusted;
    }

    @GetMapping("/{runId}")
    public WorkflowRunResponse get(
            @PathVariable Long runId,
            @RequestHeader(value = "X-AutoSpec-Session-Token", required = false) String sessionToken
    ) {
        WorkflowRun run = requireRun(runId);
        requireAccess(run, sessionToken, "OWNER", "EDITOR", "VIEWER");
        return WorkflowRunResponse.from(run);
    }

    @GetMapping("/{runId}/nodes")
    public List<WorkflowNodeRunResponse> nodes(
            @PathVariable Long runId,
            @RequestHeader(value = "X-AutoSpec-Session-Token", required = false) String sessionToken
    ) {
        WorkflowRun run = requireRun(runId);
        requireAccess(run, sessionToken, "OWNER", "EDITOR", "VIEWER");
        return nodeRunMapper.selectList(new LambdaQueryWrapper<WorkflowNodeRun>()
                        .eq(WorkflowNodeRun::getWorkflowRunId, runId)
                        .orderByAsc(WorkflowNodeRun::getNodeId)
                        .orderByAsc(WorkflowNodeRun::getRevision)
                        .orderByAsc(WorkflowNodeRun::getAttempt))
                .stream()
                .map(WorkflowNodeRunResponse::from)
                .toList();
    }

    @GetMapping("/{runId}/nodes/page")
    public CursorPageResponse<WorkflowNodeRunResponse> nodePage(
            @PathVariable Long runId,
            @RequestHeader(value = "X-AutoSpec-Session-Token", required = false) String sessionToken,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor
    ) {
        CursorPaginationRequest pagination = CursorPaginationRequest.of(limit, cursor);
        WorkflowRun run = requireRun(runId);
        requireAccess(run, sessionToken, "OWNER", "EDITOR", "VIEWER");
        List<WorkflowNodeRun> fetched = nodeRunMapper.selectList(new LambdaQueryWrapper<WorkflowNodeRun>()
                .eq(WorkflowNodeRun::getWorkflowRunId, runId)
                .gt(pagination.afterId() != null, WorkflowNodeRun::getId, pagination.afterId())
                .orderByAsc(WorkflowNodeRun::getId)
                .last("limit " + pagination.fetchLimit()));
        boolean hasMore = fetched.size() > pagination.limit();
        List<WorkflowNodeRunResponse> items = fetched.subList(
                        0,
                        Math.min(fetched.size(), pagination.limit())
                ).stream()
                .map(WorkflowNodeRunResponse::from)
                .toList();
        String nextCursor = hasMore
                ? CursorPaginationRequest.encode(items.get(items.size() - 1).id())
                : null;
        return new CursorPageResponse<>(items, nextCursor, hasMore);
    }

    @GetMapping("/{runId}/metrics")
    public WorkflowRuntimeMetricsResponse metrics(
            @PathVariable Long runId,
            @RequestHeader(value = "X-AutoSpec-Session-Token", required = false) String sessionToken
    ) {
        WorkflowRun run = requireRun(runId);
        requireAccess(run, sessionToken, "OWNER", "EDITOR", "VIEWER");
        return metricsService.metrics(runId);
    }

    @GetMapping("/{runId}/trace")
    public WorkflowTraceResponse trace(
            @PathVariable Long runId,
            @RequestHeader(value = "X-AutoSpec-Session-Token", required = false) String sessionToken
    ) {
        WorkflowRun run = requireRun(runId);
        requireAccess(run, sessionToken, "OWNER", "EDITOR", "VIEWER");
        if (traceService == null) {
            throw new IllegalStateException("Workflow trace service is not configured");
        }
        return traceService.trace(runId);
    }

    @PostMapping("/{runId}/replay")
    public WorkflowRunResponse replay(
            @PathVariable Long runId,
            @Valid @RequestBody WorkflowReplayRequest request,
            @RequestHeader(value = "X-AutoSpec-Session-Token", required = false) String sessionToken
    ) {
        WorkflowRun source = requireRun(runId);
        Long actorUserId = projectAccessService.resolveUserId(sessionToken);
        projectAccessService.requireProjectRole(source.getProjectId(), actorUserId, "OWNER", "EDITOR");
        return WorkflowRunResponse.from(replayService.replay(
                runId,
                new WorkflowReplayService.ReplayCommand(
                        request.mode(),
                        request.selectedWorkflowVersionId(),
                        request.idempotencyKey(),
                        actorUserId
                )
        ));
    }

    private void requireAccess(WorkflowRun run, String sessionToken, String... roles) {
        projectAccessService.requireProjectRole(
                run.getProjectId(),
                projectAccessService.resolveUserId(sessionToken),
                roles
        );
    }

    private WorkflowRun requireRun(long runId) {
        WorkflowRun run = runMapper.selectById(runId);
        if (run == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow run not found");
        }
        return run;
    }
}
