package com.autospec.controller;

import com.autospec.dto.ProjectMemoryFactResponse;
import com.autospec.service.ProjectAccessService;
import com.autospec.service.ProjectMemoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/projects/{projectId}/memory")
public class ProjectMemoryController {
    private final ProjectMemoryService memoryService;
    private final ProjectAccessService projectAccessService;

    public ProjectMemoryController(
            ProjectMemoryService memoryService,
            ProjectAccessService projectAccessService
    ) {
        this.memoryService = memoryService;
        this.projectAccessService = projectAccessService;
    }

    @GetMapping("/facts")
    public List<ProjectMemoryFactResponse> facts(
            @PathVariable Long projectId,
            @RequestHeader(value = "X-AutoSpec-Session-Token", required = false) String sessionToken,
            @RequestParam(required = false) Set<String> factType
    ) {
        projectAccessService.requireProjectRole(
                projectId,
                projectAccessService.resolveUserId(sessionToken),
                "OWNER", "EDITOR", "VIEWER"
        );
        Set<String> selected = factType == null || factType.isEmpty()
                ? ProjectMemoryService.FACT_TYPES
                : factType;
        if (!ProjectMemoryService.FACT_TYPES.containsAll(selected)) {
            throw new IllegalArgumentException("Unknown project memory fact type");
        }
        return memoryService.recall(projectId, selected);
    }
}
