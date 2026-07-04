package com.autospec.controller;

import com.autospec.dto.ModelInvocationResponse;
import com.autospec.service.ModelInvocationService;
import com.autospec.service.ProjectAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

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
        return modelInvocationService.listByProjectId(projectId, limit, offset)
                .stream()
                .map(ModelInvocationResponse::from)
                .toList();
    }
}
