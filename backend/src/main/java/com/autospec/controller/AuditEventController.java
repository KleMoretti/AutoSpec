package com.autospec.controller;

import com.autospec.dto.AuditEventResponse;
import com.autospec.dto.PaginationRequest;
import com.autospec.service.AuditEventService;
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
public class AuditEventController {

    private final AuditEventService auditEventService;
    private final ProjectAccessService projectAccessService;

    public AuditEventController(
            AuditEventService auditEventService,
            ProjectAccessService projectAccessService
    ) {
        this.auditEventService = auditEventService;
        this.projectAccessService = projectAccessService;
    }

    @GetMapping("/{projectId}/audit-events")
    public List<AuditEventResponse> auditEvents(
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
        return auditEventService.listByProjectId(projectId, pagination.limit(), pagination.offset())
                .stream()
                .map(AuditEventResponse::from)
                .toList();
    }
}
