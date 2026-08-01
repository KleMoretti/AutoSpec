package com.autospec.controller;

import com.autospec.dto.CursorPageResponse;
import com.autospec.dto.CursorPaginationRequest;
import com.autospec.dto.WorkflowNodeRunResponse;
import com.autospec.dto.WorkflowReplayRequest;
import com.autospec.dto.WorkflowRunResponse;
import com.autospec.dto.WorkflowRunStartRequest;
import com.autospec.dto.WorkflowRuntimeMetricsResponse;
import com.autospec.entity.WorkflowNodeRun;
import com.autospec.entity.WorkflowRun;
import com.autospec.mapper.WorkflowNodeRunMapper;
import com.autospec.mapper.WorkflowRunMapper;
import com.autospec.service.ProjectAccessService;
import com.autospec.service.WorkflowReplayService;
import com.autospec.service.WorkflowRunCreationService;
import com.autospec.service.WorkflowRuntimeMetricsService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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

@RestController
@RequestMapping("/api/workflow-runs")
public class WorkflowRuntimeController {
    private final WorkflowRunMapper runMapper;
    private final WorkflowNodeRunMapper nodeRunMapper;
    private final ProjectAccessService projectAccessService;
    private final WorkflowReplayService replayService;
    private final WorkflowRunCreationService runCreationService;
    private final WorkflowRuntimeMetricsService metricsService;
    private final ObjectMapper objectMapper;

    public WorkflowRuntimeController(
            WorkflowRunMapper runMapper,
            WorkflowNodeRunMapper nodeRunMapper,
            ProjectAccessService projectAccessService,
            WorkflowReplayService replayService,
            WorkflowRunCreationService runCreationService,
            WorkflowRuntimeMetricsService metricsService,
            ObjectMapper objectMapper
    ) {
        this.runMapper = runMapper;
        this.nodeRunMapper = nodeRunMapper;
        this.projectAccessService = projectAccessService;
        this.replayService = replayService;
        this.runCreationService = runCreationService;
        this.metricsService = metricsService;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public WorkflowRunResponse start(
            @Valid @RequestBody WorkflowRunStartRequest request,
            @RequestHeader(value = "X-AutoSpec-Session-Token", required = false) String sessionToken
    ) {
        projectAccessService.requireProjectRole(
                request.projectId(),
                projectAccessService.resolveUserId(sessionToken),
                "OWNER",
                "EDITOR"
        );
        try {
            return WorkflowRunResponse.from(runCreationService.start(
                    new WorkflowRunCreationService.StartCommand(
                            request.projectId(),
                            request.workflowVersionId(),
                            objectMapper.writeValueAsString(request.input()),
                            request.idempotencyKey()
                    )
            ));
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid workflow input", exception);
        }
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

    @PostMapping("/{runId}/replay")
    public WorkflowRunResponse replay(
            @PathVariable Long runId,
            @Valid @RequestBody WorkflowReplayRequest request,
            @RequestHeader(value = "X-AutoSpec-Session-Token", required = false) String sessionToken
    ) {
        WorkflowRun source = requireRun(runId);
        requireAccess(source, sessionToken, "OWNER", "EDITOR");
        return WorkflowRunResponse.from(replayService.replay(
                runId,
                new WorkflowReplayService.ReplayCommand(
                        request.mode(),
                        request.selectedWorkflowVersionId(),
                        request.idempotencyKey()
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
