package com.autospec.controller;

import com.autospec.dto.CodeGenerationJobResponse;
import com.autospec.dto.CodeGenerationResponse;
import com.autospec.entity.CodeGenerationJob;
import com.autospec.service.CodeGenerationJobService;
import com.autospec.service.CodeSkeletonService;
import com.autospec.service.ProjectAccessService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/projects")
public class CodeGenerationController {

    private final CodeSkeletonService codeSkeletonService;
    private final CodeGenerationJobService codeGenerationJobService;
    private final ProjectAccessService projectAccessService;

    public CodeGenerationController(
            CodeSkeletonService codeSkeletonService,
            CodeGenerationJobService codeGenerationJobService,
            ProjectAccessService projectAccessService
    ) {
        this.codeSkeletonService = codeSkeletonService;
        this.codeGenerationJobService = codeGenerationJobService;
        this.projectAccessService = projectAccessService;
    }

    @PostMapping("/{projectId}/code-skeleton")
    public CodeGenerationResponse generate(
            @PathVariable Long projectId,
            @RequestHeader(value = "X-AutoSpec-Session-Token", required = false) String sessionToken
    ) {
        projectAccessService.requireProjectRole(
                projectId,
                projectAccessService.resolveUserId(sessionToken),
                "OWNER",
                "EDITOR"
        );
        return codeSkeletonService.generate(projectId);
    }

    @GetMapping("/{projectId}/code-generation-jobs")
    public List<CodeGenerationJobResponse> jobs(
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
        return codeGenerationJobService.lambdaQuery()
                .eq(CodeGenerationJob::getProjectId, projectId)
                .orderByAsc(CodeGenerationJob::getId)
                .list()
                .stream()
                .map(CodeGenerationJobResponse::from)
                .toList();
    }

    @PostMapping("/{projectId}/code-generation-jobs/{jobId}/cancel")
    public CodeGenerationJobResponse cancel(
            @PathVariable Long projectId,
            @PathVariable Long jobId,
            @RequestHeader(value = "X-AutoSpec-Session-Token", required = false) String sessionToken
    ) {
        projectAccessService.requireProjectRole(
                projectId,
                projectAccessService.resolveUserId(sessionToken),
                "OWNER",
                "EDITOR"
        );
        return CodeGenerationJobResponse.from(codeGenerationJobService.cancelRunningJob(projectId, jobId));
    }

    @PostMapping("/{projectId}/code-generation-jobs/{jobId}/retry")
    public CodeGenerationResponse retry(
            @PathVariable Long projectId,
            @PathVariable Long jobId,
            @RequestHeader(value = "X-AutoSpec-Session-Token", required = false) String sessionToken
    ) {
        projectAccessService.requireProjectRole(
                projectId,
                projectAccessService.resolveUserId(sessionToken),
                "OWNER",
                "EDITOR"
        );
        return codeSkeletonService.retry(projectId, jobId);
    }
}
