package com.autospec.controller;

import com.autospec.dto.ExternalCallLogResponse;
import com.autospec.entity.ExternalCallLog;
import com.autospec.service.ExternalCallLogService;
import com.autospec.service.ProjectAccessService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/projects")
public class ExternalCallLogController {

    private final ExternalCallLogService externalCallLogService;
    private final ProjectAccessService projectAccessService;

    public ExternalCallLogController(
            ExternalCallLogService externalCallLogService,
            ProjectAccessService projectAccessService
    ) {
        this.externalCallLogService = externalCallLogService;
        this.projectAccessService = projectAccessService;
    }

    @GetMapping("/{projectId}/external-calls")
    public List<ExternalCallLogResponse> externalCalls(
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
        return externalCallLogService.lambdaQuery()
                .eq(ExternalCallLog::getProjectId, projectId)
                .orderByAsc(ExternalCallLog::getId)
                .list()
                .stream()
                .map(ExternalCallLogResponse::from)
                .toList();
    }
}
