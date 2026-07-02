package com.autospec.controller;

import com.autospec.dto.AgentEventResponse;
import com.autospec.dto.ApproveArtifactResponse;
import com.autospec.dto.ArtifactResponse;
import com.autospec.dto.CreateProjectRequest;
import com.autospec.dto.CreateProjectResponse;
import com.autospec.dto.GenerateProjectResponse;
import com.autospec.dto.ProjectProgressResponse;
import com.autospec.dto.RetryTaskResponse;
import com.autospec.dto.ReviewIssueResponse;
import com.autospec.dto.ReviewResponse;
import com.autospec.dto.UpdateArtifactRequest;
import com.autospec.entity.AgentEvent;
import com.autospec.entity.AgentTask;
import com.autospec.entity.Artifact;
import com.autospec.entity.Project;
import com.autospec.service.AgentEventService;
import com.autospec.service.AgentEventStreamService;
import com.autospec.service.AgentOrchestrationService;
import com.autospec.service.ArtifactVersionService;
import com.autospec.service.ArtifactService;
import com.autospec.service.ProjectAccessService;
import com.autospec.service.ProjectService;
import com.autospec.service.ReviewIssueService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;
    private final AgentOrchestrationService agentOrchestrationService;
    private final ArtifactService artifactService;
    private final ArtifactVersionService artifactVersionService;
    private final ReviewIssueService reviewIssueService;
    private final AgentEventService agentEventService;
    private final AgentEventStreamService agentEventStreamService;
    private final ProjectAccessService projectAccessService;

    public ProjectController(
            ProjectService projectService,
            AgentOrchestrationService agentOrchestrationService,
            ArtifactService artifactService,
            ArtifactVersionService artifactVersionService,
            ReviewIssueService reviewIssueService,
            AgentEventService agentEventService,
            AgentEventStreamService agentEventStreamService,
            ProjectAccessService projectAccessService
    ) {
        this.projectService = projectService;
        this.agentOrchestrationService = agentOrchestrationService;
        this.artifactService = artifactService;
        this.artifactVersionService = artifactVersionService;
        this.reviewIssueService = reviewIssueService;
        this.agentEventService = agentEventService;
        this.agentEventStreamService = agentEventStreamService;
        this.projectAccessService = projectAccessService;
    }

    @PostMapping
    public CreateProjectResponse createProject(
            @Valid @RequestBody CreateProjectRequest request,
            @RequestHeader(value = "X-AutoSpec-Session-Token", required = false) String sessionToken
    ) {
        Long userId = projectAccessService.resolveUserId(sessionToken);
        Project project = projectService.createProject(request.name(), request.requirement());
        project.setUserId(userId);
        projectService.updateById(project);
        projectAccessService.addOwner(project.getId(), userId);
        return new CreateProjectResponse(project.getId(), project.getStatus());
    }

    @PostMapping("/{projectId}/generate")
    public GenerateProjectResponse generate(
            @PathVariable Long projectId,
            @RequestHeader(value = "X-AutoSpec-Session-Token", required = false) String sessionToken
    ) {
        requireEditor(projectId, sessionToken);
        ProjectProgressResponse progress = agentOrchestrationService.generate(projectId);
        return new GenerateProjectResponse(projectId, progress.status(), progress.percent());
    }

    @PostMapping("/{projectId}/generate-prd")
    public GenerateProjectResponse generatePrd(
            @PathVariable Long projectId,
            @RequestHeader(value = "X-AutoSpec-Session-Token", required = false) String sessionToken
    ) {
        requireEditor(projectId, sessionToken);
        ProjectProgressResponse progress = agentOrchestrationService.generatePrd(projectId);
        return new GenerateProjectResponse(projectId, progress.status(), progress.percent());
    }

    @PostMapping("/{projectId}/generate-v4")
    public GenerateProjectResponse generateV4(
            @PathVariable Long projectId,
            @RequestHeader(value = "X-AutoSpec-Session-Token", required = false) String sessionToken
    ) {
        requireEditor(projectId, sessionToken);
        ProjectProgressResponse progress = agentOrchestrationService.generateV4(projectId);
        return new GenerateProjectResponse(projectId, progress.status(), progress.percent());
    }

    @PostMapping("/{projectId}/continue")
    public GenerateProjectResponse continueGeneration(
            @PathVariable Long projectId,
            @RequestHeader(value = "X-AutoSpec-Session-Token", required = false) String sessionToken
    ) {
        requireEditor(projectId, sessionToken);
        ProjectProgressResponse progress = agentOrchestrationService.continueAfterApprovedPrd(projectId);
        return new GenerateProjectResponse(projectId, progress.status(), progress.percent());
    }

    @GetMapping("/{projectId}/progress")
    public ProjectProgressResponse progress(
            @PathVariable Long projectId,
            @RequestHeader(value = "X-AutoSpec-Session-Token", required = false) String sessionToken
    ) {
        requireViewer(projectId, sessionToken);
        return agentOrchestrationService.progress(projectId);
    }

    @GetMapping("/{projectId}/artifacts")
    public List<ArtifactResponse> artifacts(
            @PathVariable Long projectId,
            @RequestHeader(value = "X-AutoSpec-Session-Token", required = false) String sessionToken
    ) {
        requireViewer(projectId, sessionToken);
        return artifactService.lambdaQuery()
                .eq(Artifact::getProjectId, projectId)
                .orderByAsc(Artifact::getId)
                .list()
                .stream()
                .map(ArtifactResponse::from)
                .toList();
    }

    @PutMapping("/{projectId}/artifacts/{artifactId}")
    public ArtifactResponse updateArtifact(
            @PathVariable Long projectId,
            @PathVariable Long artifactId,
            @RequestHeader(value = "X-AutoSpec-Session-Token", required = false) String sessionToken,
            @Valid @RequestBody UpdateArtifactRequest request
    ) {
        requireEditor(projectId, sessionToken);
        return ArtifactResponse.from(artifactVersionService.updateDraft(projectId, artifactId, request.content()));
    }

    @PostMapping("/{projectId}/artifacts/{artifactId}/approve")
    public ApproveArtifactResponse approveArtifact(
            @PathVariable Long projectId,
            @PathVariable Long artifactId,
            @RequestHeader(value = "X-AutoSpec-Session-Token", required = false) String sessionToken
    ) {
        requireEditor(projectId, sessionToken);
        Artifact approved = artifactVersionService.approve(projectId, artifactId);
        return new ApproveArtifactResponse(approved.getId(), approved.getStatus(), approved.getVersion());
    }

    @GetMapping("/{projectId}/review")
    public ReviewResponse review(
            @PathVariable Long projectId,
            @RequestHeader(value = "X-AutoSpec-Session-Token", required = false) String sessionToken
    ) {
        requireViewer(projectId, sessionToken);
        List<ReviewIssueResponse> issues = reviewIssueService.lambdaQuery()
                .eq(com.autospec.entity.ReviewIssue::getProjectId, projectId)
                .orderByAsc(com.autospec.entity.ReviewIssue::getId)
                .list()
                .stream()
                .map(issue -> new ReviewIssueResponse(
                        issue.getSeverity(),
                        issue.getIssueType(),
                        issue.getDescription(),
                        issue.getSuggestion(),
                        issue.getStatus()
                ))
                .toList();
        return new ReviewResponse(agentOrchestrationService.reviewScore(projectId), issues);
    }

    @GetMapping("/{projectId}/events/history")
    public List<AgentEventResponse> eventHistory(
            @PathVariable Long projectId,
            @RequestHeader(value = "X-AutoSpec-Session-Token", required = false) String sessionToken
    ) {
        requireViewer(projectId, sessionToken);
        return agentEventService.lambdaQuery()
                .eq(AgentEvent::getProjectId, projectId)
                .orderByAsc(AgentEvent::getId)
                .list()
                .stream()
                .map(AgentEventResponse::from)
                .toList();
    }

    @GetMapping(value = "/{projectId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamEvents(
            @PathVariable Long projectId,
            @RequestHeader(value = "X-AutoSpec-Session-Token", required = false) String sessionHeader,
            @RequestParam(value = "sessionToken", required = false) String sessionParam
    ) {
        requireViewer(projectId, sessionHeader == null ? sessionParam : sessionHeader);
        return agentEventStreamService.subscribe(projectId);
    }

    @PostMapping("/{projectId}/tasks/{taskId}/retry")
    public RetryTaskResponse retryTask(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @RequestHeader(value = "X-AutoSpec-Session-Token", required = false) String sessionToken
    ) {
        requireEditor(projectId, sessionToken);
        AgentTask retry = agentOrchestrationService.retryTask(projectId, taskId);
        return RetryTaskResponse.from(retry);
    }

    private void requireViewer(Long projectId, String sessionToken) {
        projectAccessService.requireProjectRole(
                projectId,
                projectAccessService.resolveUserId(sessionToken),
                "OWNER",
                "EDITOR",
                "VIEWER"
        );
    }

    private void requireEditor(Long projectId, String sessionToken) {
        projectAccessService.requireProjectRole(
                projectId,
                projectAccessService.resolveUserId(sessionToken),
                "OWNER",
                "EDITOR"
        );
    }
}
