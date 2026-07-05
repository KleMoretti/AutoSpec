package com.autospec.controller;

import com.autospec.dto.ExportFileDetailResponse;
import com.autospec.dto.ExportFileResponse;
import com.autospec.dto.ExportResponse;
import com.autospec.dto.PaginationRequest;
import com.autospec.entity.ExportFile;
import com.autospec.service.AuditEventService;
import com.autospec.service.ExportFileService;
import com.autospec.service.MarkdownExportService;
import com.autospec.service.PdfExportService;
import com.autospec.service.ProjectAccessService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/projects")
public class ExportController {

    private final MarkdownExportService markdownExportService;
    private final PdfExportService pdfExportService;
    private final ExportFileService exportFileService;
    private final AuditEventService auditEventService;
    private final ProjectAccessService projectAccessService;
    private final ObjectMapper objectMapper;

    public ExportController(
            MarkdownExportService markdownExportService,
            PdfExportService pdfExportService,
            ExportFileService exportFileService,
            AuditEventService auditEventService,
            ProjectAccessService projectAccessService,
            ObjectMapper objectMapper
    ) {
        this.markdownExportService = markdownExportService;
        this.pdfExportService = pdfExportService;
        this.exportFileService = exportFileService;
        this.auditEventService = auditEventService;
        this.projectAccessService = projectAccessService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/{projectId}/export")
    @Transactional
    public ExportResponse export(
            @PathVariable Long projectId,
            @RequestParam(defaultValue = "MARKDOWN") String format,
            @RequestHeader(value = "X-AutoSpec-Session-Token", required = false) String sessionToken
    ) {
        Long actorUserId = projectAccessService.resolveUserId(sessionToken);
        projectAccessService.requireProjectRole(
                projectId,
                actorUserId,
                "OWNER",
                "EDITOR"
        );
        if ("MARKDOWN".equalsIgnoreCase(format)) {
            return persistExport(projectId, actorUserId, new ExportResponse(
                    "MARKDOWN",
                    markdownExportService.exportProject(projectId),
                    "autospec-project-" + projectId + ".md",
                    "text/markdown;charset=utf-8",
                    "text"
            ));
        }
        if ("PDF".equalsIgnoreCase(format)) {
            return persistExport(projectId, actorUserId, new ExportResponse(
                    "PDF",
                    java.util.Base64.getEncoder().encodeToString(pdfExportService.exportProject(projectId)),
                    "autospec-project-" + projectId + ".pdf",
                    "application/pdf",
                    "base64"
            ));
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only MARKDOWN and PDF export are supported");
    }

    @GetMapping("/{projectId}/exports")
    public List<ExportFileResponse> exports(
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
        return exportFileService.listByProjectId(projectId, pagination.limit(), pagination.offset())
                .stream()
                .map(ExportFileResponse::from)
                .toList();
    }

    @GetMapping("/{projectId}/exports/{exportFileId}")
    public ExportFileDetailResponse exportFile(
            @PathVariable Long projectId,
            @PathVariable Long exportFileId,
            @RequestHeader(value = "X-AutoSpec-Session-Token", required = false) String sessionToken
    ) {
        projectAccessService.requireProjectRole(
                projectId,
                projectAccessService.resolveUserId(sessionToken),
                "OWNER",
                "EDITOR",
                "VIEWER"
        );
        ExportFile file = exportFileService.getByProjectIdAndId(projectId, exportFileId);
        if (file == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Export file not found");
        }
        return ExportFileDetailResponse.from(file);
    }

    private ExportResponse persistExport(Long projectId, Long actorUserId, ExportResponse response) {
        ExportFile file = new ExportFile();
        file.setProjectId(projectId);
        file.setFileName(response.fileName());
        file.setMediaType(response.mediaType());
        file.setEncoding(response.encoding());
        file.setContent(response.content());
        exportFileService.save(file);
        auditEventService.record(
                projectId,
                actorUserId,
                "PROJECT_EXPORTED",
                "EXPORT_FILE",
                file.getId(),
                "Project exported as " + response.format(),
                exportMetadata(response)
        );
        return response;
    }

    private String exportMetadata(ExportResponse response) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("format", response.format());
        metadata.put("fileName", response.fileName());
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize export metadata", ex);
        }
    }
}
