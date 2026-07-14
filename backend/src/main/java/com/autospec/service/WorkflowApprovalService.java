package com.autospec.service;

import com.autospec.entity.WorkflowApproval;
import com.autospec.workflow.runtime.WorkflowApprovalCoordinator;

import java.util.List;

public interface WorkflowApprovalService extends WorkflowApprovalCoordinator {
    WorkflowApproval getById(long approvalId);

    List<WorkflowApproval> listByProjectId(long projectId);

    WorkflowApproval decide(long approvalId, ApprovalDecision decision);

    record ApprovalDecision(
            String action,
            String reason,
            String editedContent,
            String rollbackNodeId,
            String idempotencyKey,
            long userId
    ) {
    }
}
