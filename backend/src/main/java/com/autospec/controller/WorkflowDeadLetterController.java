package com.autospec.controller;

import com.autospec.dto.PaginationRequest;
import com.autospec.dto.WorkflowDeadLetterResponse;
import com.autospec.entity.WorkflowOutbox;
import com.autospec.entity.WorkflowRun;
import com.autospec.mapper.WorkflowRunMapper;
import com.autospec.service.AuditEventService;
import com.autospec.service.ProjectAccessService;
import com.autospec.service.WorkflowDeadLetterService;
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

import java.util.List;

@RestController
@RequestMapping("/api/workflow-runs/{runId}/dead-letters")
public class WorkflowDeadLetterController {
    private final WorkflowDeadLetterService deadLetterService;
    private final WorkflowRunMapper runMapper;
    private final ProjectAccessService projectAccessService;
    private final AuditEventService auditEventService;
    private final ObjectMapper objectMapper;

    public WorkflowDeadLetterController(
            WorkflowDeadLetterService deadLetterService,
            WorkflowRunMapper runMapper,
            ProjectAccessService projectAccessService,
            AuditEventService auditEventService,
            ObjectMapper objectMapper
    ) {
        this.deadLetterService = deadLetterService;
        this.runMapper = runMapper;
        this.projectAccessService = projectAccessService;
        this.auditEventService = auditEventService;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public List<WorkflowDeadLetterResponse> list(
            @PathVariable Long runId,
            @RequestHeader(value = "X-AutoSpec-Session-Token", required = false) String sessionToken,
            @RequestParam(defaultValue = "DEAD_LETTER") String status,
            @RequestParam(defaultValue = "50") Integer limit,
            @RequestParam(defaultValue = "0") Integer offset
    ) {
        WorkflowRun run = requireRun(runId);
        requireAccess(run, sessionToken, "OWNER", "EDITOR", "VIEWER");
        PaginationRequest pagination = PaginationRequest.of(limit, offset);
        return deadLetterService.listByWorkflowRunId(runId, status, pagination).stream()
                .map(outbox -> WorkflowDeadLetterResponse.from(runId, outbox, objectMapper))
                .toList();
    }

    @PostMapping("/{outboxId}/replay")
    @Transactional
    public WorkflowDeadLetterResponse replay(
            @PathVariable Long runId,
            @PathVariable Long outboxId,
            @RequestHeader(value = "X-AutoSpec-Session-Token", required = false) String sessionToken
    ) {
        WorkflowRun run = requireRun(runId);
        long userId = requireAccess(run, sessionToken, "OWNER", "EDITOR");
        WorkflowOutbox outbox = deadLetterService.replay(runId, outboxId);
        auditEventService.record(
                run.getProjectId(),
                userId,
                run.getCorrelationId(),
                "WORKFLOW_DEAD_LETTER_REPLAYED",
                "WORKFLOW_OUTBOX",
                outbox.getId(),
                "Dead-letter command approved for replay",
                "{\"workflowRunId\":" + runId + ",\"status\":\"PENDING\"}"
        );
        return WorkflowDeadLetterResponse.from(runId, outbox, objectMapper);
    }

    @PostMapping("/{outboxId}/close")
    @Transactional
    public WorkflowDeadLetterResponse close(
            @PathVariable Long runId,
            @PathVariable Long outboxId,
            @RequestHeader(value = "X-AutoSpec-Session-Token", required = false) String sessionToken
    ) {
        WorkflowRun run = requireRun(runId);
        long userId = requireAccess(run, sessionToken, "OWNER", "EDITOR");
        WorkflowOutbox outbox = deadLetterService.close(runId, outboxId);
        auditEventService.record(
                run.getProjectId(),
                userId,
                run.getCorrelationId(),
                "WORKFLOW_DEAD_LETTER_CLOSED",
                "WORKFLOW_OUTBOX",
                outbox.getId(),
                "Dead-letter command closed by user",
                "{\"workflowRunId\":" + runId + ",\"status\":\"CLOSED\"}"
        );
        return WorkflowDeadLetterResponse.from(runId, outbox, objectMapper);
    }

    private long requireAccess(WorkflowRun run, String sessionToken, String... roles) {
        long userId = projectAccessService.resolveUserId(sessionToken);
        projectAccessService.requireProjectRole(run.getProjectId(), userId, roles);
        return userId;
    }

    private WorkflowRun requireRun(long runId) {
        WorkflowRun run = runMapper.selectById(runId);
        if (run == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow run not found");
        }
        return run;
    }
}
