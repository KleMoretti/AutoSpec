package com.autospec.controller;

import com.autospec.dto.ModelInvocationResponse;
import com.autospec.entity.ModelInvocation;
import com.autospec.service.ModelInvocationService;
import com.autospec.service.ProjectAccessService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/projects")
public class ModelGovernanceController {

    private final ModelInvocationService modelInvocationService;
    private final ProjectAccessService projectAccessService;

    public ModelGovernanceController(
            ModelInvocationService modelInvocationService,
            ProjectAccessService projectAccessService
    ) {
        this.modelInvocationService = modelInvocationService;
        this.projectAccessService = projectAccessService;
    }

    @GetMapping("/{projectId}/model-invocations")
    public List<ModelInvocationResponse> invocations(
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
        return modelInvocationService.lambdaQuery()
                .eq(ModelInvocation::getProjectId, projectId)
                .orderByAsc(ModelInvocation::getId)
                .list()
                .stream()
                .map(ModelInvocationResponse::from)
                .toList();
    }
}
