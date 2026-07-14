package com.autospec.controller;

import com.autospec.dto.ApprovalDecisionRequest;
import com.autospec.dto.WorkflowApprovalResponse;
import com.autospec.entity.WorkflowApproval;
import com.autospec.entity.WorkflowNodeRun;
import com.autospec.entity.WorkflowRun;
import com.autospec.mapper.WorkflowNodeRunMapper;
import com.autospec.mapper.WorkflowRunMapper;
import com.autospec.service.ProjectAccessService;
import com.autospec.service.WorkflowApprovalService;
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

    public WorkflowApprovalController(
            WorkflowApprovalService approvalService,
            WorkflowRunMapper runMapper,
            WorkflowNodeRunMapper nodeRunMapper,
            ProjectAccessService projectAccessService
    ) {
        this.approvalService = approvalService;
        this.runMapper = runMapper;
        this.nodeRunMapper = nodeRunMapper;
        this.projectAccessService = projectAccessService;
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

    private WorkflowApprovalResponse response(WorkflowApproval approval) {
        WorkflowNodeRun nodeRun = nodeRunMapper.selectById(approval.getNodeRunId());
        return WorkflowApprovalResponse.from(
                approval,
                nodeRun == null ? null : nodeRun.getNodeId()
        );
    }

    private WorkflowRun requireRun(long runId) {
        WorkflowRun run = runMapper.selectById(runId);
        if (run == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow run not found");
        }
        return run;
    }
}
