package com.autospec.controller;

import com.autospec.dto.WorkflowSnapshotResponse;
import com.autospec.dto.WorkflowRunResponse;
import com.autospec.service.ProjectAccessService;
import com.autospec.service.WorkflowRunService;
import com.autospec.service.WorkflowSnapshotService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/projects")
public class WorkflowController {

    private final WorkflowSnapshotService workflowSnapshotService;
    private final WorkflowRunService workflowRunService;
    private final ProjectAccessService projectAccessService;

    public WorkflowController(
            WorkflowSnapshotService workflowSnapshotService,
            WorkflowRunService workflowRunService,
            ProjectAccessService projectAccessService
    ) {
        this.workflowSnapshotService = workflowSnapshotService;
        this.workflowRunService = workflowRunService;
        this.projectAccessService = projectAccessService;
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
            @RequestHeader(value = "X-AutoSpec-Session-Token", required = false) String sessionToken
    ) {
        projectAccessService.requireProjectRole(
                projectId,
                projectAccessService.resolveUserId(sessionToken),
                "OWNER",
                "EDITOR",
                "VIEWER"
        );
        return workflowRunService.listByProjectId(projectId)
                .stream()
                .map(WorkflowRunResponse::from)
                .toList();
    }
}
