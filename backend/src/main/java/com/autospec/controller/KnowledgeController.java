package com.autospec.controller;

import com.autospec.dto.KnowledgeSourceResponse;
import com.autospec.dto.KnowledgeUploadRequest;
import com.autospec.dto.KnowledgeUploadResponse;
import com.autospec.service.KnowledgeIndexService;
import com.autospec.service.KnowledgeUploadService;
import com.autospec.service.ProjectAccessService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/projects")
public class KnowledgeController {

    private final KnowledgeIndexService knowledgeIndexService;
    private final ProjectAccessService projectAccessService;
    private final KnowledgeUploadService knowledgeUploadService;

    public KnowledgeController(KnowledgeIndexService knowledgeIndexService, ProjectAccessService projectAccessService,
                               KnowledgeUploadService knowledgeUploadService) {
        this.knowledgeIndexService = knowledgeIndexService;
        this.projectAccessService = projectAccessService;
        this.knowledgeUploadService = knowledgeUploadService;
    }

    @GetMapping("/{projectId}/knowledge/sources")
    public List<KnowledgeSourceResponse> sources(
            @PathVariable Long projectId,
            @RequestHeader(value = "X-AutoSpec-Session-Token", required = false) String sessionToken,
            @RequestParam(required = false) String corpusType
    ) {
        projectAccessService.requireProjectRole(
                projectId,
                projectAccessService.resolveUserId(sessionToken),
                "OWNER",
                "EDITOR",
                "VIEWER"
        );
        return knowledgeIndexService.sources(projectId, corpusType);
    }

    @PostMapping("/{projectId}/knowledge/uploads")
    public KnowledgeUploadResponse upload(
            @PathVariable Long projectId,
            @RequestHeader(value = "X-AutoSpec-Session-Token", required = false) String sessionToken,
            @Valid @RequestBody KnowledgeUploadRequest request
    ) {
        Long userId = projectAccessService.resolveUserId(sessionToken);
        projectAccessService.requireProjectRole(projectId, userId, "OWNER", "EDITOR");
        return knowledgeUploadService.upload(projectId, userId, request);
    }
}
