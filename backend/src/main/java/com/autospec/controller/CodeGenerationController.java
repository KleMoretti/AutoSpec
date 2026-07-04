package com.autospec.controller;

import com.autospec.dto.CodeGenerationJobResponse;
import com.autospec.dto.CodeGenerationResponse;
import com.autospec.service.CodeGenerationJobService;
import com.autospec.service.CodeSkeletonService;
import com.autospec.service.ProjectAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

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
            @RequestHeader(value = "X-AutoSpec-Session-Token", required = false) String sessionToken,
            @RequestParam(defaultValue = "50") Integer limit,
            @RequestParam(defaultValue = "0") Integer offset
    ) {
        if (limit == null || limit < 1 || limit > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be between 1 and 100");
        }
        if (offset == null || offset < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "offset must be greater than or equal to 0");
        }
        projectAccessService.requireProjectRole(
                projectId,
                projectAccessService.resolveUserId(sessionToken),
                "OWNER",
                "EDITOR",
                "VIEWER"
        );
        return codeGenerationJobService.listByProjectId(projectId, limit, offset)
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
