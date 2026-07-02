package com.autospec.controller;

import com.autospec.dto.ExportResponse;
import com.autospec.service.MarkdownExportService;
import com.autospec.service.PdfExportService;
import com.autospec.service.ProjectAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/projects")
public class ExportController {

    private final MarkdownExportService markdownExportService;
    private final PdfExportService pdfExportService;
    private final ProjectAccessService projectAccessService;

    public ExportController(
            MarkdownExportService markdownExportService,
            PdfExportService pdfExportService,
            ProjectAccessService projectAccessService
    ) {
        this.markdownExportService = markdownExportService;
        this.pdfExportService = pdfExportService;
        this.projectAccessService = projectAccessService;
    }

    @PostMapping("/{projectId}/export")
    public ExportResponse export(
            @PathVariable Long projectId,
            @RequestParam(defaultValue = "MARKDOWN") String format,
            @RequestHeader(value = "X-AutoSpec-Session-Token", required = false) String sessionToken
    ) {
        projectAccessService.requireProjectRole(
                projectId,
                projectAccessService.resolveUserId(sessionToken),
                "OWNER",
                "EDITOR",
                "VIEWER"
        );
        if ("MARKDOWN".equalsIgnoreCase(format)) {
            return new ExportResponse(
                    "MARKDOWN",
                    markdownExportService.exportProject(projectId),
                    "autospec-project-" + projectId + ".md",
                    "text/markdown;charset=utf-8",
                    "text"
            );
        }
        if ("PDF".equalsIgnoreCase(format)) {
            return new ExportResponse(
                    "PDF",
                    java.util.Base64.getEncoder().encodeToString(pdfExportService.exportProject(projectId)),
                    "autospec-project-" + projectId + ".pdf",
                    "application/pdf",
                    "base64"
            );
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only MARKDOWN and PDF export are supported");
    }
}
