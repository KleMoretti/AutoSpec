package com.autospec.controller;

import com.autospec.dto.ModelInvocationResponse;
import com.autospec.dto.PaginationRequest;
import com.autospec.service.ModelInvocationService;
import com.autospec.service.ProjectAccessService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
        return modelInvocationService.listByProjectId(projectId, pagination.limit(), pagination.offset())
                .stream()
                .map(ModelInvocationResponse::from)
                .toList();
    }
}
