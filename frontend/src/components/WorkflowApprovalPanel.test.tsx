import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import type { WorkflowApprovalResponse } from '../api/v3';
import WorkflowApprovalPanel, { buildApprovalDecisionPayload } from './WorkflowApprovalPanel';

const pendingApproval: WorkflowApprovalResponse = {
  id: 41,
  workflowRunId: 8,
  nodeRunId: 13,
  nodeId: 'review_prd',
  mode: 'AFTER_NODE',
  allowedActions: ['APPROVE', 'EDIT_AND_APPROVE', 'REJECT'],
  status: 'PENDING',
  candidateArtifactId: 7
};

describe('WorkflowApprovalPanel', () => {
  it('renders only the actions allowed by the workflow snapshot', () => {
    const html = renderToStaticMarkup(
      <WorkflowApprovalPanel approvals={[pendingApproval]} artifacts={[]} onDecide={vi.fn()} />
    );

    expect(html).toContain('Approve');
    expect(html).toContain('Edit and approve');
    expect(html).toContain('Reject');
    expect(html).not.toContain('Rollback to node');
    expect(html).not.toContain('Cancel workflow');
  });

  it('builds an edit decision with content and idempotency key', () => {
    expect(buildApprovalDecisionPayload('EDIT_AND_APPROVE', ' fixes ', '{"title":"v2"}', '', 'key-1')).toEqual({
      decision: 'EDIT_AND_APPROVE',
      reason: 'fixes',
      editedContent: '{"title":"v2"}',
      rollbackNodeId: undefined,
      idempotencyKey: 'key-1'
    });
  });

  it('does not render decision controls after completion', () => {
    const html = renderToStaticMarkup(
      <WorkflowApprovalPanel
        approvals={[{ ...pendingApproval, status: 'DECIDED', decision: 'APPROVE' }]}
        artifacts={[]}
        onDecide={vi.fn()}
      />
    );

    expect(html).toContain('Decision completed: APPROVE');
    expect(html).not.toContain('Submit decision');
    expect(html).not.toContain('Edit and approve');
  });
});
