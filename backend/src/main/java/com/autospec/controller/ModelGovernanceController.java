package com.autospec.controller;

import com.autospec.dto.CursorPageResponse;
import com.autospec.dto.CursorPaginationRequest;
import com.autospec.dto.ModelInvocationResponse;
import com.autospec.dto.PaginationRequest;
import com.autospec.entity.ModelInvocation;
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
            @RequestParam(required = false) Long workflowNodeRunId,
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
        List<ModelInvocation> invocations = workflowNodeRunId == null
                ? modelInvocationService.listByProjectId(
                projectId, pagination.limit(), pagination.offset())
                : modelInvocationService.listByProjectAndNodeRunId(
                projectId, workflowNodeRunId, pagination.limit(), pagination.offset());
        return invocations
                .stream()
                .map(ModelInvocationResponse::from)
                .toList();
    }

    @GetMapping("/{projectId}/model-invocations/page")
    public CursorPageResponse<ModelInvocationResponse> invocationPage(
            @PathVariable Long projectId,
            @RequestHeader(value = "X-AutoSpec-Session-Token", required = false) String sessionToken,
            @RequestParam(required = false) Long workflowNodeRunId,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor
    ) {
        CursorPaginationRequest pagination = CursorPaginationRequest.of(limit, cursor);
        projectAccessService.requireProjectRole(
                projectId,
                projectAccessService.resolveUserId(sessionToken),
                "OWNER",
                "EDITOR",
                "VIEWER"
        );
        List<ModelInvocation> fetched = workflowNodeRunId == null
                ? modelInvocationService.listByProjectIdAfterId(
                projectId, pagination.afterId(), pagination.fetchLimit())
                : modelInvocationService.listByProjectAndNodeRunIdAfterId(
                projectId, workflowNodeRunId, pagination.afterId(), pagination.fetchLimit());
        boolean hasMore = fetched.size() > pagination.limit();
        List<ModelInvocation> page = hasMore
                ? fetched.subList(0, pagination.limit())
                : fetched;
        String nextCursor = hasMore
                ? CursorPaginationRequest.encode(page.get(page.size() - 1).getId())
                : null;
        return new CursorPageResponse<>(
                page.stream().map(ModelInvocationResponse::from).toList(),
                nextCursor,
                hasMore
        );
    }
}
