package com.autospec.controller;

import com.autospec.dto.WorkflowNodeRunResponse;
import com.autospec.dto.WorkflowReplayRequest;
import com.autospec.dto.WorkflowRunResponse;
import com.autospec.entity.WorkflowNodeRun;
import com.autospec.entity.WorkflowRun;
import com.autospec.mapper.WorkflowNodeRunMapper;
import com.autospec.mapper.WorkflowRunMapper;
import com.autospec.service.ProjectAccessService;
import com.autospec.service.WorkflowReplayService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
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

    public WorkflowRuntimeController(
            WorkflowRunMapper runMapper,
            WorkflowNodeRunMapper nodeRunMapper,
            ProjectAccessService projectAccessService,
            WorkflowReplayService replayService
    ) {
        this.runMapper = runMapper;
        this.nodeRunMapper = nodeRunMapper;
        this.projectAccessService = projectAccessService;
        this.replayService = replayService;
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
