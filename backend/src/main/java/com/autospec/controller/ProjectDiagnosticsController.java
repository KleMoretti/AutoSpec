package com.autospec.controller;

import com.autospec.dto.ProjectDiagnosticsResponse;
import com.autospec.service.ProjectAccessService;
import com.autospec.service.ProjectDiagnosticsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects")
public class ProjectDiagnosticsController {

    private final ProjectAccessService projectAccessService;
    private final ProjectDiagnosticsService projectDiagnosticsService;

    public ProjectDiagnosticsController(
            ProjectAccessService projectAccessService,
            ProjectDiagnosticsService projectDiagnosticsService
    ) {
        this.projectAccessService = projectAccessService;
        this.projectDiagnosticsService = projectDiagnosticsService;
    }

    @GetMapping("/{projectId}/diagnostics")
    public ProjectDiagnosticsResponse diagnostics(
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
        return projectDiagnosticsService.summarize(projectId);
    }
}
