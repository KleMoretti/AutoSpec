import { CheckCircleOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import { Alert, Button, Card, Input, Popconfirm, Space, Tag, Typography } from 'antd';
import { useMemo, useRef, useState } from 'react';
import type { ArtifactResponse } from '../api/projects';
import type {
  ApprovalDecisionPayload,
  WorkflowApprovalDecision,
  WorkflowApprovalResponse
} from '../api/v3';

interface WorkflowApprovalPanelProps {
  approvals: WorkflowApprovalResponse[];
  artifacts: ArtifactResponse[];
  onDecide: (approvalId: number, payload: ApprovalDecisionPayload) => Promise<void>;
}

interface ApprovalCardProps {
  approval: WorkflowApprovalResponse;
  candidateContent: string;
  onDecide: WorkflowApprovalPanelProps['onDecide'];
}

const actionLabels: Record<WorkflowApprovalDecision, string> = {
  APPROVE: 'Approve',
  REJECT: 'Reject',
  EDIT_AND_APPROVE: 'Edit and approve',
  ROLLBACK_TO_NODE: 'Rollback to node',
  CANCEL_WORKFLOW: 'Cancel workflow'
};

function WorkflowApprovalPanel({ approvals, artifacts, onDecide }: WorkflowApprovalPanelProps) {
  const artifactContents = useMemo(
    () => new Map(artifacts.map((artifact) => [artifact.id, artifact.content])),
    [artifacts]
  );

  if (approvals.length === 0) {
    return null;
  }

  return (
    <section className="panel" aria-labelledby="workflow-approvals-title">
      <Space direction="vertical" size={16} className="full-width">
        <Space>
          <SafetyCertificateOutlined />
          <Typography.Title level={3} id="workflow-approvals-title">
            Workflow approvals
          </Typography.Title>
        </Space>
        {approvals.map((approval) => (
          <ApprovalCard
            key={approval.id}
            approval={approval}
            candidateContent={artifactContents.get(approval.candidateArtifactId ?? -1) ?? ''}
            onDecide={onDecide}
          />
        ))}
      </Space>
    </section>
  );
}

function ApprovalCard({ approval, candidateContent, onDecide }: ApprovalCardProps) {
  const [selectedAction, setSelectedAction] = useState<WorkflowApprovalDecision | null>(null);
  const [reason, setReason] = useState('');
  const [editedContent, setEditedContent] = useState(candidateContent);
  const [rollbackNodeId, setRollbackNodeId] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const idempotencyKey = useRef(createIdempotencyKey());
  const completed = approval.status !== 'PENDING';

  async function submit() {
    if (!selectedAction || completed) {
      return;
    }
    setSubmitting(true);
    try {
      await onDecide(
        approval.id,
        buildApprovalDecisionPayload(
          selectedAction,
          reason,
          editedContent,
          rollbackNodeId,
          idempotencyKey.current
        )
      );
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Card
      size="small"
      title={
        <Space wrap>
          <Typography.Text strong>{approval.nodeId}</Typography.Text>
          <Tag>{approval.mode}</Tag>
          <Tag color={completed ? 'green' : 'gold'}>{approval.status}</Tag>
        </Space>
      }
    >
      {completed ? (
        <Alert
          type="success"
          showIcon
          icon={<CheckCircleOutlined />}
          message={`Decision completed: ${approval.decision ?? 'UNKNOWN'}`}
          description={approval.decisionReason}
        />
      ) : (
        <Space direction="vertical" size={12} className="full-width">
          <Typography.Text className="muted">
            Choose one of the actions permitted by this workflow version.
          </Typography.Text>
          <Space wrap aria-label={`Allowed actions for ${approval.nodeId}`}>
            {approval.allowedActions.map((action) => (
              <Button
                key={action}
                danger={action === 'REJECT' || action === 'CANCEL_WORKFLOW'}
                type={selectedAction === action ? 'primary' : 'default'}
                disabled={submitting}
                onClick={() => setSelectedAction(action)}
              >
                {actionLabels[action]}
              </Button>
            ))}
          </Space>
          {selectedAction === 'EDIT_AND_APPROVE' ? (
            <Input.TextArea
              aria-label="Edited artifact JSON"
              className="json-editor"
              value={editedContent}
              autoSize={{ minRows: 8, maxRows: 18 }}
              onChange={(event) => setEditedContent(event.target.value)}
            />
          ) : null}
          {selectedAction === 'ROLLBACK_TO_NODE' ? (
            <Input
              aria-label="Rollback node id"
              placeholder="Target node id"
              value={rollbackNodeId}
              onChange={(event) => setRollbackNodeId(event.target.value)}
            />
          ) : null}
          {selectedAction ? (
            <Input.TextArea
              aria-label="Decision reason"
              placeholder="Reason (optional)"
              value={reason}
              autoSize={{ minRows: 2, maxRows: 5 }}
              onChange={(event) => setReason(event.target.value)}
            />
          ) : null}
          {selectedAction === 'CANCEL_WORKFLOW' ? (
            <Popconfirm
              title="Cancel this workflow run?"
              description="Queued and pending nodes will no longer be scheduled."
              okText="Cancel workflow"
              okButtonProps={{ danger: true }}
              onConfirm={() => void submit()}
            >
              <Button danger loading={submitting}>Confirm cancellation</Button>
            </Popconfirm>
          ) : selectedAction ? (
            <Button
              type="primary"
              loading={submitting}
              disabled={selectedAction === 'ROLLBACK_TO_NODE' && !rollbackNodeId.trim()}
              onClick={() => void submit()}
            >
              Submit decision
            </Button>
          ) : null}
        </Space>
      )}
    </Card>
  );
}

export function buildApprovalDecisionPayload(
  decision: WorkflowApprovalDecision,
  reason: string,
  editedContent: string,
  rollbackNodeId: string,
  idempotencyKey: string
): ApprovalDecisionPayload {
  return {
    decision,
    reason: reason.trim() || undefined,
    editedContent: decision === 'EDIT_AND_APPROVE' ? editedContent : undefined,
    rollbackNodeId: decision === 'ROLLBACK_TO_NODE' ? rollbackNodeId.trim() : undefined,
    idempotencyKey
  };
}

function createIdempotencyKey(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID();
  }
  return `approval-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

export default WorkflowApprovalPanel;
