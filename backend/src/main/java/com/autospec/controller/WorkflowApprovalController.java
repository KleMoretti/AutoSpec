package com.autospec.controller;

import com.autospec.dto.ApprovalDecisionRequest;
import com.autospec.dto.ClarificationResponseRequest;
import com.autospec.dto.WorkflowApprovalResponse;
import com.autospec.dto.WorkflowClarificationResponse;
import com.autospec.entity.WorkflowApproval;
import com.autospec.entity.WorkflowClarification;
import com.autospec.entity.WorkflowNodeRun;
import com.autospec.entity.WorkflowRun;
import com.autospec.mapper.WorkflowNodeRunMapper;
import com.autospec.mapper.WorkflowRunMapper;
import com.autospec.service.ProjectAccessService;
import com.autospec.service.WorkflowApprovalService;
import com.autospec.workflow.runtime.DagCompiler;
import com.autospec.workflow.runtime.WorkflowSnapshotParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api")
public class WorkflowApprovalController {
    private final WorkflowApprovalService approvalService;
    private final WorkflowRunMapper runMapper;
    private final WorkflowNodeRunMapper nodeRunMapper;
    private final ProjectAccessService projectAccessService;
    private final WorkflowSnapshotParser snapshotParser;
    private final DagCompiler dagCompiler;
    private final ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Autowired
    public WorkflowApprovalController(
            WorkflowApprovalService approvalService,
            WorkflowRunMapper runMapper,
            WorkflowNodeRunMapper nodeRunMapper,
            ProjectAccessService projectAccessService,
            WorkflowSnapshotParser snapshotParser,
            DagCompiler dagCompiler,
            ObjectMapper objectMapper
    ) {
        this.approvalService = approvalService;
        this.runMapper = runMapper;
        this.nodeRunMapper = nodeRunMapper;
        this.projectAccessService = projectAccessService;
        this.snapshotParser = snapshotParser;
        this.dagCompiler = dagCompiler;
        this.objectMapper = objectMapper;
    }

    public WorkflowApprovalController(
            WorkflowApprovalService approvalService,
            WorkflowRunMapper runMapper,
            WorkflowNodeRunMapper nodeRunMapper,
            ProjectAccessService projectAccessService,
            WorkflowSnapshotParser snapshotParser,
            DagCompiler dagCompiler
    ) {
        this(
                approvalService,
                runMapper,
                nodeRunMapper,
                projectAccessService,
                snapshotParser,
                dagCompiler,
                new ObjectMapper()
        );
    }

    @GetMapping("/projects/{projectId}/workflow-approvals")
    public List<WorkflowApprovalResponse> list(
            @PathVariable Long projectId,
            @RequestHeader(value = "X-AutoSpec-Session-Token", required = false)
            String sessionToken
    ) {
        projectAccessService.requireProjectRole(
                projectId,
                projectAccessService.resolveUserId(sessionToken),
                "OWNER",
                "EDITOR",
                "VIEWER"
        );
        return approvalService.listByProjectId(projectId).stream()
                .map(this::response)
                .toList();
    }

    @PostMapping("/workflow-approvals/{approvalId}/decide")
    public WorkflowApprovalResponse decide(
            @PathVariable Long approvalId,
            @Valid @RequestBody ApprovalDecisionRequest request,
            @RequestHeader(value = "X-AutoSpec-Session-Token", required = false)
            String sessionToken
    ) {
        WorkflowApproval approval = approvalService.getById(approvalId);
        WorkflowRun run = requireRun(approval.getWorkflowRunId());
        long userId = projectAccessService.resolveUserId(sessionToken);
        projectAccessService.requireProjectRole(run.getProjectId(), userId, "OWNER", "EDITOR");
        WorkflowApproval decided = approvalService.decide(
                approvalId,
                request.expectedLockVersion(),
                new WorkflowApprovalService.ApprovalDecision(
                        request.decision(),
                        request.reason(),
                        request.editedContent(),
                        request.rollbackNodeId(),
                        request.idempotencyKey(),
                        userId
                )
        );
        return response(decided);
    }

    @GetMapping("/workflow-runs/{runId}/clarifications")
    public List<WorkflowClarificationResponse> clarifications(
            @PathVariable Long runId,
            @RequestHeader(value = "X-AutoSpec-Session-Token", required = false)
            String sessionToken
    ) {
        WorkflowRun run = requireRun(runId);
        projectAccessService.requireProjectRole(
                run.getProjectId(),
                projectAccessService.resolveUserId(sessionToken),
                "OWNER",
                "EDITOR",
                "VIEWER"
        );
        return approvalService.listClarifications(runId).stream()
                .map(this::clarificationResponse)
                .toList();
    }

    @PostMapping("/workflow-runs/{runId}/clarifications/{clarificationId}/respond")
    public WorkflowClarificationResponse respond(
            @PathVariable Long runId,
            @PathVariable Long clarificationId,
            @Valid @RequestBody ClarificationResponseRequest request,
            @RequestHeader(value = "X-AutoSpec-Session-Token", required = false)
            String sessionToken
    ) {
        WorkflowRun run = requireRun(runId);
        long userId = projectAccessService.resolveUserId(sessionToken);
        projectAccessService.requireProjectRole(run.getProjectId(), userId, "OWNER", "EDITOR");
        WorkflowApprovalService.ClarificationResponseDecision decision =
                new WorkflowApprovalService.ClarificationResponseDecision(
                        request.idempotencyKey(),
                        json(request.answers()),
                        request.acceptedAssumptionIds() == null
                                ? "[]"
                                : objectMapper.valueToTree(request.acceptedAssumptionIds()).toString(),
                        json(request.conflictResolutions()),
                        userId
                );
        WorkflowClarification result = approvalService.respondToClarification(
                runId,
                clarificationId,
                request.expectedLockVersion(),
                decision
        );
        return clarificationResponse(result);
    }

    private WorkflowApprovalResponse response(WorkflowApproval approval) {
        WorkflowNodeRun nodeRun = nodeRunMapper.selectById(approval.getNodeRunId());
        WorkflowRun run = requireRun(approval.getWorkflowRunId());
        String nodeId = nodeRun == null ? null : nodeRun.getNodeId();
        List<String> allowedActions = nodeId == null
                ? List.of()
                : dagCompiler.compile(snapshotParser.parse(run.getWorkflowSnapshotJson()))
                        .nodes()
                        .get(nodeId)
                        .approval()
                        .allowedActions();
        return WorkflowApprovalResponse.from(
                approval,
                nodeId,
                allowedActions
        );
    }

    private WorkflowClarificationResponse clarificationResponse(WorkflowClarification clarification) {
        return WorkflowClarificationResponse.from(
                clarification,
                readJson(clarification.getRequestJson()),
                readJson(clarification.getResponseJson())
        );
    }

    private String json(JsonNode value) {
        return value == null || value.isNull() ? "[]" : value.toString();
    }

    private JsonNode readJson(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Stored clarification JSON is invalid",
                    exception
            );
        }
    }

    private WorkflowRun requireRun(long runId) {
        WorkflowRun run = runMapper.selectById(runId);
        if (run == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow run not found");
        }
        return run;
    }
}
