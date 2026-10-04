package com.autospec.service;

import com.autospec.entity.WorkflowApproval;
import com.autospec.entity.WorkflowClarification;
import com.autospec.workflow.runtime.WorkflowApprovalCoordinator;

import java.util.List;

public interface WorkflowApprovalService extends WorkflowApprovalCoordinator {
    WorkflowApproval getById(long approvalId);

    List<WorkflowApproval> listByProjectId(long projectId);

    WorkflowApproval decide(long approvalId, int expectedLockVersion, ApprovalDecision decision);

    List<WorkflowClarification> listClarifications(long workflowRunId);

    WorkflowClarification getClarification(long workflowRunId, long clarificationId);

    WorkflowClarification respondToClarification(
            long workflowRunId,
            long clarificationId,
            int expectedLockVersion,
            ClarificationResponseDecision response
    );

    record ApprovalDecision(
            String action,
            String reason,
            String editedContent,
            String rollbackNodeId,
            String idempotencyKey,
            long userId
    ) {
    }

    record ClarificationResponseDecision(
            String idempotencyKey,
            String answersJson,
            String acceptedAssumptionIdsJson,
            String conflictResolutionsJson,
            long userId
    ) {
    }
}
