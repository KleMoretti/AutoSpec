import { CheckOutlined, CloseOutlined, ToolOutlined } from '@ant-design/icons';
import { Alert, Button, Empty, Form, Input, Modal, Select, Space, Table, Tag, Typography, message } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useMemo, useState } from 'react';
import {
  updateReviewIssue,
  type ArtifactResponse,
  type ReviewIssueResponse,
  type ReviewResponse
} from '../api/projects';

interface ReviewIssueTableProps {
  projectId: number;
  review: ReviewResponse | null;
  artifacts: ArtifactResponse[];
  loadError?: string;
  onChanged?: () => Promise<void> | void;
  onRetry?: () => Promise<void> | void;
}

interface DispositionValues {
  resolution: string;
  resolvedInArtifactId?: number;
}

function ReviewIssueTable({ projectId, review, artifacts, loadError, onChanged, onRetry }: ReviewIssueTableProps) {
  const [selected, setSelected] = useState<ReviewIssueResponse | null>(null);
  const [disposition, setDisposition] = useState<'RESOLVED' | 'IGNORED'>('RESOLVED');
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm<DispositionValues>();
  const artifactOptions = useMemo(
    () => artifacts
      .slice()
      .sort((left, right) => right.version - left.version)
      .map((artifact) => ({
        value: artifact.id,
        label: `${artifact.type} v${artifact.version} · ${artifact.status ?? 'UNKNOWN'}`
      })),
    [artifacts]
  );

  async function startWork(issue: ReviewIssueResponse) {
    try {
      await updateReviewIssue(projectId, issue.id, { status: 'IN_PROGRESS' });
      message.success('Issue assigned and marked in progress');
      await onChanged?.();
    } catch (error) {
      message.error(error instanceof Error ? error.message : 'Issue update failed');
    }
  }

  function openDisposition(issue: ReviewIssueResponse, next: 'RESOLVED' | 'IGNORED') {
    setSelected(issue);
    setDisposition(next);
    form.resetFields();
  }

  async function saveDisposition() {
    if (!selected) return;
    const values = await form.validateFields();
    setSaving(true);
    try {
      await updateReviewIssue(projectId, selected.id, {
        status: disposition,
        resolution: values.resolution,
        resolvedInArtifactId: values.resolvedInArtifactId
      });
      message.success(disposition === 'RESOLVED' ? 'Issue resolved' : 'Issue ignored with rationale');
      setSelected(null);
      await onChanged?.();
    } catch (error) {
      message.error(error instanceof Error ? error.message : 'Issue update failed');
    } finally {
      setSaving(false);
    }
  }

  const columns: ColumnsType<ReviewIssueResponse> = [
    {
      title: 'Severity',
      dataIndex: 'severity',
      key: 'severity',
      width: 105,
      render: (value: string) => <Tag color={tagColor(value)}>{value}</Tag>
    },
    {
      title: 'Finding',
      key: 'finding',
      render: (_, issue) => (
        <div className="review-finding">
          <Space wrap>
            <Typography.Text strong>{issue.issueType}</Typography.Text>
            {issue.requirementId ? <Tag>{issue.requirementId}</Tag> : null}
            {issue.artifactPath ? <Tag>{issue.artifactPath}</Tag> : null}
          </Space>
          <Typography.Paragraph>{issue.description}</Typography.Paragraph>
          <Typography.Text type="secondary">Suggested: {issue.suggestion}</Typography.Text>
        </div>
      )
    },
    {
      title: 'Status',
      dataIndex: 'status',
      key: 'status',
      width: 120,
      render: (value: string) => <Tag>{value}</Tag>
    },
    {
      title: 'Decision',
      key: 'actions',
      width: 250,
      render: (_, issue) => issue.status === 'RESOLVED' || issue.status === 'IGNORED' ? (
        <Typography.Text type="secondary">{issue.resolution}</Typography.Text>
      ) : (
        <Space wrap>
          {issue.status !== 'IN_PROGRESS' ? (
            <Button icon={<ToolOutlined />} onClick={() => void startWork(issue)}>Start work</Button>
          ) : null}
          <Button icon={<CheckOutlined />} onClick={() => openDisposition(issue, 'RESOLVED')}>Resolve</Button>
          <Button danger icon={<CloseOutlined />} onClick={() => openDisposition(issue, 'IGNORED')}>Ignore</Button>
        </Space>
      )
    }
  ];

  return (
    <section className="panel" aria-labelledby="review-issues-title">
      <div className="section-heading">
        <div>
          <Typography.Title level={2} id="review-issues-title">Review findings</Typography.Title>
          <Typography.Text className="muted">
            Every finding needs an explicit owner and disposition before delivery.
          </Typography.Text>
        </div>
        <Typography.Text className="score">{review ? `${review.score}/100` : '--'}</Typography.Text>
      </div>
      {loadError ? (
        <Alert
          type="error"
          showIcon
          message="Review findings could not be loaded"
          description={loadError}
          action={<Button size="small" onClick={() => void onRetry?.()}>Retry</Button>}
        />
      ) : review === null ? (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="Review has not been produced yet" />
      ) : review.issues.length > 0 ? (
        <Table
          rowKey="id"
          columns={columns}
          dataSource={review.issues}
          pagination={false}
          scroll={{ x: 940 }}
        />
      ) : (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="No review findings" />
      )}
      <Modal
        title={disposition === 'RESOLVED' ? 'Resolve finding' : 'Ignore finding'}
        open={selected !== null}
        okText={disposition === 'RESOLVED' ? 'Resolve' : 'Ignore with rationale'}
        okButtonProps={{ danger: disposition === 'IGNORED', loading: saving }}
        onOk={() => void saveDisposition()}
        onCancel={() => setSelected(null)}
        destroyOnHidden
      >
        <Form form={form} layout="vertical" preserve={false}>
          <Form.Item
            name="resolution"
            label={disposition === 'RESOLVED' ? 'Resolution evidence' : 'Rationale'}
            rules={[{ required: true, message: 'Explain this decision' }]}
          >
            <Input.TextArea rows={4} maxLength={2000} showCount />
          </Form.Item>
          <Form.Item name="resolvedInArtifactId" label="Resolved in artifact version (optional)">
            <Select allowClear showSearch optionFilterProp="label" options={artifactOptions} />
          </Form.Item>
        </Form>
      </Modal>
    </section>
  );
}

function tagColor(severity: string) {
  if (severity === 'CRITICAL' || severity === 'HIGH') return 'red';
  if (severity === 'MEDIUM') return 'orange';
  if (severity === 'LOW') return 'blue';
  return 'default';
}

export default ReviewIssueTable;
