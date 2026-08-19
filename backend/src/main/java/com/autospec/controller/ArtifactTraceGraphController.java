package com.autospec.controller;

import com.autospec.dto.ArtifactTraceGraphResponse;
import com.autospec.service.ArtifactTraceGraphService;
import com.autospec.service.ProjectAccessService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects")
public class ArtifactTraceGraphController {
    private final ArtifactTraceGraphService traceGraphService;
    private final ProjectAccessService projectAccessService;

    public ArtifactTraceGraphController(
            ArtifactTraceGraphService traceGraphService,
            ProjectAccessService projectAccessService
    ) {
        this.traceGraphService = traceGraphService;
        this.projectAccessService = projectAccessService;
    }

    @GetMapping("/{projectId}/trace-graph")
    public ArtifactTraceGraphResponse graph(
            @PathVariable Long projectId,
            @RequestParam(required = false) Long workflowRunId,
            @RequestHeader(value = "X-AutoSpec-Session-Token", required = false) String sessionToken
    ) {
        projectAccessService.requireProjectRole(
                projectId,
                projectAccessService.resolveUserId(sessionToken),
                "OWNER",
                "EDITOR",
                "VIEWER"
        );
        return traceGraphService.graph(projectId, workflowRunId);
    }
}
