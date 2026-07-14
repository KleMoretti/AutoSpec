package com.autospec.controller;

import com.autospec.dto.WorkflowVersionResponse;
import com.autospec.dto.WorkflowDraftRequest;
import com.autospec.dto.WorkflowValidationResponse;
import com.autospec.entity.WorkflowDefinition;
import com.autospec.entity.WorkflowVersion;
import com.autospec.mapper.WorkflowDefinitionMapper;
import com.autospec.mapper.WorkflowVersionMapper;
import com.autospec.service.ProjectAccessService;
import com.autospec.service.WorkflowVersionManagementService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/workflows")
public class WorkflowVersionController {
    private final WorkflowDefinitionMapper definitionMapper;
    private final WorkflowVersionMapper versionMapper;
    private final ProjectAccessService projectAccessService;
    private final WorkflowVersionManagementService managementService;

    public WorkflowVersionController(
            WorkflowDefinitionMapper definitionMapper,
            WorkflowVersionMapper versionMapper,
            ProjectAccessService projectAccessService,
            WorkflowVersionManagementService managementService
    ) {
        this.definitionMapper = definitionMapper;
        this.versionMapper = versionMapper;
        this.projectAccessService = projectAccessService;
        this.managementService = managementService;
    }

    @PostMapping
    public WorkflowVersionResponse createDraft(
            @Valid @RequestBody WorkflowDraftRequest request,
            @RequestHeader(value = "X-AutoSpec-Session-Token", required = false) String sessionToken
    ) {
        projectAccessService.resolveUserId(sessionToken);
        WorkflowVersion version = managementService.createDraft(
                new WorkflowVersionManagementService.CreateDraftCommand(
                        request.workflowKey(),
                        request.name(),
                        request.description(),
                        request.version(),
                        request.specJson()
                )
        );
        WorkflowDefinition definition = definitionMapper.selectById(version.getDefinitionId());
        return WorkflowVersionResponse.from(definition, version);
    }

    @PostMapping("/{versionId}/validate")
    public WorkflowValidationResponse validate(
            @PathVariable Long versionId,
            @RequestHeader(value = "X-AutoSpec-Session-Token", required = false) String sessionToken
    ) {
        projectAccessService.resolveUserId(sessionToken);
        return WorkflowValidationResponse.from(managementService.validate(versionId));
    }

    @PostMapping("/{versionId}/publish")
    public WorkflowVersionResponse publish(
            @PathVariable Long versionId,
            @RequestHeader(value = "X-AutoSpec-Session-Token", required = false) String sessionToken
    ) {
        projectAccessService.resolveUserId(sessionToken);
        WorkflowVersion version = managementService.publish(versionId);
        WorkflowDefinition definition = definitionMapper.selectById(version.getDefinitionId());
        return WorkflowVersionResponse.from(definition, version);
    }

    @GetMapping("/{workflowKey}/versions")
    public List<WorkflowVersionResponse> versions(
            @PathVariable String workflowKey,
            @RequestHeader(value = "X-AutoSpec-Session-Token", required = false) String sessionToken
    ) {
        projectAccessService.resolveUserId(sessionToken);
        WorkflowDefinition definition = definitionMapper.selectOne(
                new LambdaQueryWrapper<WorkflowDefinition>()
                        .eq(WorkflowDefinition::getWorkflowKey, workflowKey)
        );
        if (definition == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow definition not found");
        }
        return versionMapper.selectList(new LambdaQueryWrapper<WorkflowVersion>()
                        .eq(WorkflowVersion::getDefinitionId, definition.getId())
                        .orderByDesc(WorkflowVersion::getPublishedAt)
                        .orderByDesc(WorkflowVersion::getId))
                .stream()
                .map(version -> WorkflowVersionResponse.from(definition, version))
                .toList();
    }
}
