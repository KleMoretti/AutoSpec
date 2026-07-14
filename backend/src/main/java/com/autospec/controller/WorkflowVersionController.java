package com.autospec.controller;

import com.autospec.dto.WorkflowVersionResponse;
import com.autospec.entity.WorkflowDefinition;
import com.autospec.entity.WorkflowVersion;
import com.autospec.mapper.WorkflowDefinitionMapper;
import com.autospec.mapper.WorkflowVersionMapper;
import com.autospec.service.ProjectAccessService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/workflows")
public class WorkflowVersionController {
    private final WorkflowDefinitionMapper definitionMapper;
    private final WorkflowVersionMapper versionMapper;
    private final ProjectAccessService projectAccessService;

    public WorkflowVersionController(
            WorkflowDefinitionMapper definitionMapper,
            WorkflowVersionMapper versionMapper,
            ProjectAccessService projectAccessService
    ) {
        this.definitionMapper = definitionMapper;
        this.versionMapper = versionMapper;
        this.projectAccessService = projectAccessService;
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
