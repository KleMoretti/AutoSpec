package com.autospec.controller;

import com.autospec.dto.CodeGenerationResponse;
import com.autospec.service.CodeSkeletonService;
import com.autospec.service.ProjectAccessService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects")
public class CodeGenerationController {

    private final CodeSkeletonService codeSkeletonService;
    private final ProjectAccessService projectAccessService;

    public CodeGenerationController(CodeSkeletonService codeSkeletonService, ProjectAccessService projectAccessService) {
        this.codeSkeletonService = codeSkeletonService;
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
}
