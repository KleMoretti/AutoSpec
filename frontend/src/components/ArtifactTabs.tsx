import {
  BranchesOutlined,
  CheckCircleOutlined,
  ClockCircleOutlined,
  HistoryOutlined,
  SafetyCertificateOutlined
} from '@ant-design/icons';
import {
  Alert,
  Button,
  Card,
  Collapse,
  Descriptions,
  Empty,
  List,
  Popconfirm,
  Progress,
  Select,
  Space,
  Table,
  Tag,
  Typography,
  message
} from 'antd';
import { useEffect, useMemo, useState } from 'react';
import {
  getArtifactDiff,
  getArtifactVersions,
  restoreArtifact,
  type ArtifactDiffResponse,
  type ArtifactResponse
} from '../api/projects';
import FrontendSkeletonPreview from './FrontendSkeletonPreview';

interface ArtifactTabsProps {
  projectId: number;
  artifacts: ArtifactResponse[];
  onChanged?: () => Promise<void> | void;
}

const TYPE_LABELS: Record<string, string> = {
  PRD: 'Product requirements',
  ARCHITECTURE_DESIGN: 'Architecture',
  BACKEND_DESIGN: 'API & data',
  FRONTEND_SKELETON: 'Experience design',
  REVIEW_REPORT: 'Review',
  EVALUATION_REPORT: 'Quality evaluation'
};

function ArtifactTabs({ projectId, artifacts, onChanged }: ArtifactTabsProps) {
  const latestByType = useMemo(() => {
    const grouped = new Map<string, ArtifactResponse>();
    for (const artifact of artifacts) {
      const current = grouped.get(artifact.type);
      if (!current || artifact.version > current.version) grouped.set(artifact.type, artifact);
    }
    return [...grouped.values()];
  }, [artifacts]);

  if (latestByType.length === 0) {
    return (
      <section className="panel">
        <Typography.Title level={2}>Deliverables</Typography.Title>
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="No deliverables yet" />
      </section>
    );
  }

  return (
    <section className="panel" aria-labelledby="artifact-title">
      <div className="section-heading">
        <div>
          <Typography.Title level={2} id="artifact-title">Deliverables</Typography.Title>
          <Typography.Text className="muted">
            Browse semantic versions, compare fields, and inspect source provenance.
          </Typography.Text>
        </div>
      </div>
      <div className="artifact-type-grid">
        {latestByType.map((artifact) => (
          <VersionedArtifactView
            key={artifact.type}
            projectId={projectId}
            latest={artifact}
            onChanged={onChanged}
          />
        ))}
      </div>
    </section>
  );
}

function VersionedArtifactView({
  projectId,
  latest,
  onChanged
}: {
  projectId: number;
  latest: ArtifactResponse;
  onChanged?: () => Promise<void> | void;
}) {
  const [versions, setVersions] = useState<ArtifactResponse[]>([latest]);
  const [selectedId, setSelectedId] = useState(latest.id);
  const [compareId, setCompareId] = useState<number | undefined>();
  const [diff, setDiff] = useState<ArtifactDiffResponse | null>(null);
  const [restoring, setRestoring] = useState(false);

  useEffect(() => {
    setSelectedId(latest.id);
    void getArtifactVersions(projectId, latest.id)
      .then((items) => setVersions(items.sort((left, right) => right.version - left.version)))
      .catch(() => setVersions([latest]));
  }, [latest, projectId]);

  const selected = versions.find((version) => version.id === selectedId) ?? latest;
  const latestVersion = versions[0] ?? latest;
  const approved = versions.find((version) => version.status === 'APPROVED');
  const candidate = versions.find((version) => version.status === 'PENDING_REVIEW');

  async function compare(nextId: number | undefined) {
    setCompareId(nextId);
    if (!nextId) {
      setDiff(null);
      return;
    }
    try {
      setDiff(await getArtifactDiff(projectId, selected.id, nextId));
    } catch (error) {
      message.error(error instanceof Error ? error.message : 'Comparison failed');
    }
  }

  async function restore() {
    setRestoring(true);
    try {
      await restoreArtifact(projectId, selected.id, latestVersion.lockVersion);
      message.success(`Version ${selected.version} restored as a new review candidate`);
      await onChanged?.();
    } catch (error) {
      message.error(error instanceof Error ? error.message : 'Restore failed');
    } finally {
      setRestoring(false);
    }
  }

  return (
    <Card
      className="artifact-card"
      title={TYPE_LABELS[latest.type] ?? latest.type}
      extra={<Tag color={statusColor(latest.status)}>{latest.status ?? 'UNKNOWN'}</Tag>}
    >
      <Space direction="vertical" size={16} className="full-width">
        <div className="artifact-version-summary" aria-label="Artifact version summary">
          <span><HistoryOutlined /> Latest v{latestVersion.version}</span>
          <span><CheckCircleOutlined /> Approved {approved ? `v${approved.version}` : 'none'}</span>
          <span><ClockCircleOutlined /> Candidate {candidate ? `v${candidate.version}` : 'none'}</span>
        </div>
        <Space wrap align="end">
          <label>
            <Typography.Text strong>View version</Typography.Text>
            <Select
              aria-label={`View ${latest.type} version`}
              value={selected.id}
              onChange={(value) => {
                setSelectedId(value);
                setCompareId(undefined);
                setDiff(null);
              }}
              options={versions.map((version) => ({
                value: version.id,
                label: `v${version.version} · ${version.status ?? 'UNKNOWN'}`
              }))}
            />
          </label>
          <label>
            <Typography.Text strong>Compare with</Typography.Text>
            <Select
              allowClear
              aria-label={`Compare ${latest.type} version`}
              value={compareId}
              onChange={(value) => void compare(value)}
              placeholder="Choose version"
              options={versions
                .filter((version) => version.id !== selected.id)
                .map((version) => ({ value: version.id, label: `v${version.version}` }))}
            />
          </label>
          {selected.id !== latestVersion.id ? (
            <Popconfirm
              title="Restore this version?"
              description="A new candidate version will be created; current history stays intact."
              okText="Restore"
              onConfirm={() => void restore()}
            >
              <Button icon={<BranchesOutlined />} loading={restoring}>Restore as candidate</Button>
            </Popconfirm>
          ) : null}
        </Space>

        {diff ? (
          <Alert
            type={diff.changed ? 'info' : 'success'}
            showIcon
            message={diff.changed ? `${diff.changedPaths.length} fields changed` : 'No differences'}
            description={diff.changed ? diff.changedPaths.slice(0, 12).join(', ') : undefined}
          />
        ) : null}

        <ArtifactViewer artifact={selected} />
        <Collapse
          ghost
          items={[{
            key: 'provenance',
            label: <Space><SafetyCertificateOutlined /> Provenance & citations</Space>,
            children: <Provenance artifact={selected} />
          }]}
        />
      </Space>
    </Card>
  );
}

function ArtifactViewer({ artifact }: { artifact: ArtifactResponse }) {
  const data = parseObject(artifact.content);
  if (!data) return <pre>{artifact.content}</pre>;
  if (artifact.type === 'PRD') return <PrdView data={data} />;
  if (artifact.type === 'ARCHITECTURE_DESIGN') return <ArchitectureView data={data} />;
  if (artifact.type === 'BACKEND_DESIGN') return <BackendView data={data} />;
  if (artifact.type === 'FRONTEND_SKELETON') return <FrontendSkeletonPreview content={artifact.content} />;
  if (artifact.type === 'REVIEW_REPORT' || artifact.type === 'EVALUATION_REPORT') {
    return <QualityView data={data} />;
  }
  return <pre>{JSON.stringify(data, null, 2)}</pre>;
}

function PrdView({ data }: { data: Record<string, unknown> }) {
  return (
    <div className="artifact-semantic-view">
      <Typography.Title level={3}>{text(data.project_name, 'Product requirements')}</Typography.Title>
      <Descriptions size="small" column={{ xs: 1, md: 2 }}>
        <Descriptions.Item label="Target users">{strings(data.target_users).join(', ') || '--'}</Descriptions.Item>
        <Descriptions.Item label="Boundaries">{strings(data.business_boundaries).length}</Descriptions.Item>
      </Descriptions>
      <Typography.Text strong>Core capabilities</Typography.Text>
      <List
        size="small"
        dataSource={objects(data.core_features)}
        renderItem={(feature) => (
          <List.Item extra={<Tag>{text(feature.priority, 'MUST')}</Tag>}>
            <List.Item.Meta title={text(feature.name)} description={text(feature.description)} />
          </List.Item>
        )}
      />
      <Typography.Text strong>User stories & acceptance</Typography.Text>
      <List
        size="small"
        dataSource={objects(data.user_stories)}
        renderItem={(story) => (
          <List.Item>
            <List.Item.Meta
              title={`${text(story.role, 'User')} wants ${text(story.goal)}`}
              description={
                <div>
                  <Typography.Paragraph>{text(story.benefit)}</Typography.Paragraph>
                  {strings(story.acceptance_criteria).map((criterion) => (
                    <Tag key={criterion} className="acceptance-tag">{criterion}</Tag>
                  ))}
                </div>
              }
            />
          </List.Item>
        )}
      />
    </div>
  );
}

function ArchitectureView({ data }: { data: Record<string, unknown> }) {
  return (
    <div className="artifact-semantic-view">
      <Alert type="info" showIcon message="System context" description={text(data.system_context)} />
      <Table
        size="small"
        pagination={false}
        rowKey={(row) => text(row.name)}
        dataSource={objects(data.modules)}
        columns={[
          { title: 'Module', render: (_, row) => text(row.name) },
          { title: 'Responsibility', render: (_, row) => text(row.responsibility) },
          { title: 'Depends on', render: (_, row) => strings(row.depends_on).join(', ') || '--' }
        ]}
      />
    </div>
  );
}

function BackendView({ data }: { data: Record<string, unknown> }) {
  return (
    <div className="artifact-semantic-view">
      <Typography.Text strong>API contract</Typography.Text>
      <Table
        size="small"
        pagination={false}
        scroll={{ x: 680 }}
        rowKey={(row, index) => `${text(row.method)}-${text(row.path)}-${index}`}
        dataSource={objects(data.apis)}
        columns={[
          { title: 'Method', width: 90, render: (_, row) => <Tag>{text(row.method)}</Tag> },
          { title: 'Path', render: (_, row) => <code>{text(row.path)}</code> },
          { title: 'Purpose', render: (_, row) => text(row.description) },
          { title: 'Roles', render: (_, row) => strings(row.required_roles).join(', ') || 'Public' }
        ]}
      />
      <Typography.Text strong>Data model</Typography.Text>
      <List
        grid={{ gutter: 12, xs: 1, md: 2 }}
        dataSource={objects(data.tables)}
        renderItem={(table) => (
          <List.Item>
            <Card size="small" title={text(table.name)}>
              <Typography.Paragraph>{text(table.description)}</Typography.Paragraph>
              {objects(table.fields).map((field) => (
                <Tag key={text(field.name)}>{text(field.name)}: {text(field.type)}</Tag>
              ))}
            </Card>
          </List.Item>
        )}
      />
    </div>
  );
}

function QualityView({ data }: { data: Record<string, unknown> }) {
  const score = Number(data.overall_score ?? data.score ?? 0);
  const issues = objects(data.issues);
  return (
    <div className="quality-view">
      <Progress
        type="dashboard"
        percent={Number.isFinite(score) ? score : 0}
        status={String(data.gate_status ?? data.decision).includes('BLOCK') ? 'exception' : 'normal'}
      />
      <div className="quality-summary">
        <Typography.Title level={3}>{text(data.gate_status ?? data.decision, 'Reviewed')}</Typography.Title>
        <Typography.Text>{issues.length} findings · grade {text(data.final_grade, '--')}</Typography.Text>
      </div>
      <List
        size="small"
        dataSource={issues}
        renderItem={(issue) => (
          <List.Item>
            <List.Item.Meta
              title={<Space><Tag color={statusColor(text(issue.severity))}>{text(issue.severity)}</Tag>{text(issue.issue_type)}</Space>}
              description={text(issue.description)}
            />
          </List.Item>
        )}
      />
    </div>
  );
}

function Provenance({ artifact }: { artifact: ArtifactResponse }) {
  const citations = parseArray(artifact.sourceCitationsJson);
  const provenance = parseObject(artifact.provenanceJson ?? '');
  return (
    <Space direction="vertical" className="full-width">
      <Descriptions size="small" column={{ xs: 1, md: 2 }}>
        <Descriptions.Item label="Content hash"><code>{artifact.contentHash?.slice(0, 16) ?? '--'}</code></Descriptions.Item>
        <Descriptions.Item label="Schema">{artifact.schemaVersion ?? '--'}</Descriptions.Item>
        <Descriptions.Item label="Prompt">{artifact.promptKey ? `${artifact.promptKey}:${artifact.promptVersion}` : '--'}</Descriptions.Item>
        <Descriptions.Item label="Model">{artifact.modelProvider ? `${artifact.modelProvider}/${artifact.modelName}` : 'No model call'}</Descriptions.Item>
        <Descriptions.Item label="Node run">{artifact.workflowNodeRunId ?? '--'}</Descriptions.Item>
        <Descriptions.Item label="Parent artifact">{artifact.parentArtifactId ?? '--'}</Descriptions.Item>
      </Descriptions>
      <Typography.Text strong>Upstream versions</Typography.Text>
      <pre>{JSON.stringify(provenance?.upstream_artifacts ?? [], null, 2)}</pre>
      <Typography.Text strong>Citations ({citations.length})</Typography.Text>
      <pre>{JSON.stringify(citations, null, 2)}</pre>
    </Space>
  );
}

function parseObject(value: string): Record<string, unknown> | null {
  try {
    const parsed = JSON.parse(value) as unknown;
    return parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed)
      ? parsed as Record<string, unknown>
      : null;
  } catch {
    return null;
  }
}

function parseArray(value?: string): unknown[] {
  if (!value) return [];
  try {
    const parsed = JSON.parse(value) as unknown;
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

function objects(value: unknown): Record<string, unknown>[] {
  return Array.isArray(value)
    ? value.filter((item): item is Record<string, unknown> => item !== null && typeof item === 'object')
    : [];
}

function strings(value: unknown): string[] {
  return Array.isArray(value) ? value.map((item) => String(item)) : [];
}

function text(value: unknown, fallback = ''): string {
  return typeof value === 'string' || typeof value === 'number' ? String(value) : fallback;
}

function statusColor(status?: string) {
  if (status === 'APPROVED' || status === 'PASSED' || status === 'LOW') return 'green';
  if (status === 'HIGH' || status === 'CRITICAL' || status === 'BLOCKED') return 'red';
  if (status === 'PENDING_REVIEW' || status === 'MEDIUM') return 'gold';
  return 'blue';
}

export default ArtifactTabs;
