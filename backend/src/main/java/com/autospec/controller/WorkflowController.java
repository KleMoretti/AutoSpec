package com.autospec.controller;

import com.autospec.dto.CursorPageResponse;
import com.autospec.dto.CursorPaginationRequest;
import com.autospec.dto.PaginationRequest;
import com.autospec.dto.WorkflowRunResponse;
import com.autospec.dto.WorkflowSnapshotResponse;
import com.autospec.entity.WorkflowRun;
import com.autospec.service.AuditEventService;
import com.autospec.service.ProjectAccessService;
import com.autospec.service.WorkflowRunService;
import com.autospec.service.WorkflowSnapshotService;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/projects")
public class WorkflowController {

    private final WorkflowSnapshotService workflowSnapshotService;
    private final WorkflowRunService workflowRunService;
    private final ProjectAccessService projectAccessService;
    private final AuditEventService auditEventService;

    public WorkflowController(
            WorkflowSnapshotService workflowSnapshotService,
            WorkflowRunService workflowRunService,
            ProjectAccessService projectAccessService,
            AuditEventService auditEventService
    ) {
        this.workflowSnapshotService = workflowSnapshotService;
        this.workflowRunService = workflowRunService;
        this.projectAccessService = projectAccessService;
        this.auditEventService = auditEventService;
    }

    @GetMapping("/{projectId}/workflow")
    public WorkflowSnapshotResponse workflow(
            @PathVariable Long projectId,
            @RequestHeader(value = "X-AutoSpec-Session-Token", required = false) String sessionToken
    ) {
        projectAccessService.requireProjectRole(
                projectId,
                projectAccessService.resolveUserId(sessionToken),
                "OWNER",
                "EDITOR",
                "VIEWER"
        );
        return workflowSnapshotService.latestResponse(projectId);
    }

    @GetMapping("/{projectId}/workflow-runs")
    public List<WorkflowRunResponse> workflowRuns(
            @PathVariable Long projectId,
            @RequestHeader(value = "X-AutoSpec-Session-Token", required = false) String sessionToken,
            @RequestParam(defaultValue = "50") Integer limit,
            @RequestParam(defaultValue = "0") Integer offset
    ) {
        PaginationRequest pagination = PaginationRequest.of(limit, offset);
        projectAccessService.requireProjectRole(
                projectId,
                projectAccessService.resolveUserId(sessionToken),
                "OWNER",
                "EDITOR",
                "VIEWER"
        );
        return workflowRunService.listByProjectId(projectId, pagination.limit(), pagination.offset())
                .stream()
                .map(WorkflowRunResponse::from)
                .toList();
    }

    @GetMapping("/{projectId}/workflow-runs/page")
    public CursorPageResponse<WorkflowRunResponse> workflowRunPage(
            @PathVariable Long projectId,
            @RequestHeader(value = "X-AutoSpec-Session-Token", required = false) String sessionToken,
            @RequestParam(defaultValue = "50") Integer limit,
            @RequestParam(required = false) String cursor
    ) {
        CursorPaginationRequest pagination = CursorPaginationRequest.of(limit, cursor);
        projectAccessService.requireProjectRole(
                projectId,
                projectAccessService.resolveUserId(sessionToken),
                "OWNER",
                "EDITOR",
                "VIEWER"
        );
        List<WorkflowRun> fetched = workflowRunService.listByProjectIdAfterId(
                projectId,
                pagination.afterId(),
                pagination.fetchLimit()
        );
        boolean hasMore = fetched.size() > pagination.limit();
        List<WorkflowRun> page = hasMore
                ? fetched.subList(0, pagination.limit())
                : fetched;
        String nextCursor = hasMore
                ? CursorPaginationRequest.encode(page.get(page.size() - 1).getId())
                : null;
        return new CursorPageResponse<>(
                page.stream().map(WorkflowRunResponse::from).toList(),
                nextCursor,
                hasMore
        );
    }

    @PostMapping("/{projectId}/workflow-runs/{runId}/cancel")
    @Transactional
    public WorkflowRunResponse cancelWorkflowRun(
            @PathVariable Long projectId,
            @PathVariable Long runId,
            @RequestHeader(value = "X-AutoSpec-Session-Token", required = false) String sessionToken
    ) {
        Long userId = projectAccessService.resolveUserId(sessionToken);
        projectAccessService.requireProjectRole(
                projectId,
                userId,
                "OWNER",
                "EDITOR"
        );
        WorkflowRun run = workflowRunService.cancelRunningRun(projectId, runId);
        auditEventService.record(
                projectId,
                userId,
                run.getCorrelationId(),
                "WORKFLOW_RUN_CANCELLED",
                "WORKFLOW_RUN",
                run.getId(),
                "Workflow run cancelled by user request",
                "{\"status\":\"CANCELLED\"}"
        );
        return WorkflowRunResponse.from(run);
    }
}
