package com.autospec.controller;

import com.autospec.dto.KnowledgeSourceResponse;
import com.autospec.service.KnowledgeIndexService;
import com.autospec.service.ProjectAccessService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/projects")
public class KnowledgeController {

    private final KnowledgeIndexService knowledgeIndexService;
    private final ProjectAccessService projectAccessService;

    public KnowledgeController(KnowledgeIndexService knowledgeIndexService, ProjectAccessService projectAccessService) {
        this.knowledgeIndexService = knowledgeIndexService;
        this.projectAccessService = projectAccessService;
    }

    @GetMapping("/{projectId}/knowledge/sources")
    public List<KnowledgeSourceResponse> sources(
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
        return knowledgeIndexService.sources(projectId);
    }
}
