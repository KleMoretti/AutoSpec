package com.autospec.controller;

import com.autospec.dto.CodeGenerationJobResponse;
import com.autospec.dto.CodeGenerationResponse;
import com.autospec.dto.PaginationRequest;
import com.autospec.entity.CodeGenerationJob;
import com.autospec.service.AuditEventService;
import com.autospec.service.CodeGenerationJobService;
import com.autospec.service.CodeSkeletonService;
import com.autospec.service.ProjectAccessService;
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
public class CodeGenerationController {

    private final CodeSkeletonService codeSkeletonService;
    private final CodeGenerationJobService codeGenerationJobService;
    private final ProjectAccessService projectAccessService;
    private final AuditEventService auditEventService;

    public CodeGenerationController(
            CodeSkeletonService codeSkeletonService,
            CodeGenerationJobService codeGenerationJobService,
            ProjectAccessService projectAccessService,
            AuditEventService auditEventService
    ) {
        this.codeSkeletonService = codeSkeletonService;
        this.codeGenerationJobService = codeGenerationJobService;
        this.projectAccessService = projectAccessService;
        this.auditEventService = auditEventService;
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
        PaginationRequest pagination = PaginationRequest.of(limit, offset);
        projectAccessService.requireProjectRole(
                projectId,
                projectAccessService.resolveUserId(sessionToken),
                "OWNER",
                "EDITOR",
                "VIEWER"
        );
        return codeGenerationJobService.listByProjectId(projectId, pagination.limit(), pagination.offset())
                .stream()
                .map(CodeGenerationJobResponse::from)
                .toList();
    }

    @PostMapping("/{projectId}/code-generation-jobs/{jobId}/cancel")
    @Transactional
    public CodeGenerationJobResponse cancel(
            @PathVariable Long projectId,
            @PathVariable Long jobId,
            @RequestHeader(value = "X-AutoSpec-Session-Token", required = false) String sessionToken
    ) {
        Long userId = projectAccessService.resolveUserId(sessionToken);
        projectAccessService.requireProjectRole(
                projectId,
                userId,
                "OWNER",
                "EDITOR"
        );
        CodeGenerationJob job = codeGenerationJobService.cancelRunningJob(projectId, jobId);
        auditEventService.record(
                projectId,
                userId,
                "CODE_GENERATION_JOB_CANCELLED",
                "CODE_GENERATION_JOB",
                job.getId(),
                "Code generation job cancelled by user request",
                "{\"status\":\"CANCELLED\"}"
        );
        return CodeGenerationJobResponse.from(job);
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
