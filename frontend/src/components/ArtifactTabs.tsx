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
import { useTranslation } from 'react-i18next';
import {
  getArtifactDiff,
  getArtifactVersions,
  restoreArtifact,
  type ArtifactDiffResponse,
  type ArtifactResponse
} from '../api/projects';
import { translateEnum } from '../i18n/formatters';
import FrontendSkeletonPreview from './FrontendSkeletonPreview';

interface ArtifactTabsProps {
  projectId: number;
  artifacts: ArtifactResponse[];
  onChanged?: () => Promise<void> | void;
}

function ArtifactTabs({ projectId, artifacts, onChanged }: ArtifactTabsProps) {
  const { t } = useTranslation();
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
        <Typography.Title level={2}>{t('artifacts.title')}</Typography.Title>
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('artifacts.empty')} />
      </section>
    );
  }

  return (
    <section className="panel" aria-labelledby="artifact-title">
      <div className="section-heading">
        <div>
          <Typography.Title level={2} id="artifact-title">{t('artifacts.title')}</Typography.Title>
          <Typography.Text className="muted">
            {t('artifacts.description')}
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
  const { t } = useTranslation();
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
      message.error(error instanceof Error ? error.message : t('artifacts.comparisonFailed'));
    }
  }

  async function restore() {
    setRestoring(true);
    try {
      await restoreArtifact(projectId, selected.id, latestVersion.lockVersion);
      message.success(t('artifacts.restoreSuccess', { version: selected.version }));
      await onChanged?.();
    } catch (error) {
      message.error(error instanceof Error ? error.message : t('artifacts.restoreFailed'));
    } finally {
      setRestoring(false);
    }
  }

  return (
    <Card
      className="artifact-card"
      title={translateEnum(t, 'artifactType', latest.type)}
      extra={<Tag color={statusColor(latest.status)}>{translateEnum(t, 'status', latest.status, t('common.unknown'))}</Tag>}
    >
      <Space direction="vertical" size={16} className="full-width">
        <div className="artifact-version-summary" aria-label={t('artifacts.versionSummaryLabel')}>
          <span><HistoryOutlined /> {t('artifacts.latestVersion', { version: latestVersion.version })}</span>
          <span><CheckCircleOutlined /> {t('artifacts.approvedVersion', { version: approved ? `v${approved.version}` : t('common.none') })}</span>
          <span><ClockCircleOutlined /> {t('artifacts.candidateVersion', { version: candidate ? `v${candidate.version}` : t('common.none') })}</span>
        </div>
        <Space wrap align="end">
          <label>
            <Typography.Text strong>{t('artifacts.viewVersion')}</Typography.Text>
            <Select
              aria-label={t('artifacts.viewVersionLabel', { type: translateEnum(t, 'artifactType', latest.type) })}
              value={selected.id}
              onChange={(value) => {
                setSelectedId(value);
                setCompareId(undefined);
                setDiff(null);
              }}
              options={versions.map((version) => ({
                value: version.id,
                label: `v${version.version} · ${translateEnum(t, 'status', version.status, t('common.unknown'))}`
              }))}
            />
          </label>
          <label>
            <Typography.Text strong>{t('artifacts.compareWith')}</Typography.Text>
            <Select
              allowClear
              aria-label={t('artifacts.compareVersionLabel', { type: translateEnum(t, 'artifactType', latest.type) })}
              value={compareId}
              onChange={(value) => void compare(value)}
              placeholder={t('artifacts.chooseVersion')}
              options={versions
                .filter((version) => version.id !== selected.id)
                .map((version) => ({ value: version.id, label: `v${version.version}` }))}
            />
          </label>
          {selected.id !== latestVersion.id ? (
            <Popconfirm
              title={t('artifacts.restoreTitle')}
              description={t('artifacts.restoreDescription')}
              okText={t('artifacts.restore')}
              onConfirm={() => void restore()}
            >
              <Button icon={<BranchesOutlined />} loading={restoring}>{t('artifacts.restoreAsCandidate')}</Button>
            </Popconfirm>
          ) : null}
        </Space>

        {diff ? (
          <Alert
            type={diff.changed ? 'info' : 'success'}
            showIcon
            message={diff.changed
              ? t('artifacts.fieldsChanged', { count: diff.changedPaths.length })
              : t('artifacts.noDifferences')}
            description={diff.changed ? diff.changedPaths.slice(0, 12).join(', ') : undefined}
          />
        ) : null}

        <ArtifactViewer artifact={selected} />
        <Collapse
          ghost
          items={[{
            key: 'provenance',
            label: <Space><SafetyCertificateOutlined /> {t('artifacts.provenanceAndCitations')}</Space>,
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
  const { t } = useTranslation();
  return (
    <div className="artifact-semantic-view">
      <Typography.Title level={3}>{text(data.project_name, t('artifacts.productRequirements'))}</Typography.Title>
      <Descriptions size="small" column={{ xs: 1, md: 2 }}>
        <Descriptions.Item label={t('artifacts.targetUsers')}>{strings(data.target_users).join(', ') || '--'}</Descriptions.Item>
        <Descriptions.Item label={t('artifacts.boundaries')}>{strings(data.business_boundaries).length}</Descriptions.Item>
      </Descriptions>
      <Typography.Text strong>{t('artifacts.coreCapabilities')}</Typography.Text>
      <List
        size="small"
        dataSource={objects(data.core_features)}
        renderItem={(feature) => (
          <List.Item extra={<Tag>{text(feature.priority, 'MUST')}</Tag>}>
            <List.Item.Meta title={text(feature.name)} description={text(feature.description)} />
          </List.Item>
        )}
      />
      <Typography.Text strong>{t('artifacts.userStories')}</Typography.Text>
      <List
        size="small"
        dataSource={objects(data.user_stories)}
        renderItem={(story) => (
          <List.Item>
            <List.Item.Meta
              title={t('artifacts.userWants', {
                role: text(story.role, t('artifacts.user')),
                goal: text(story.goal)
              })}
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
  const { t } = useTranslation();
  return (
    <div className="artifact-semantic-view">
      <Alert type="info" showIcon message={t('artifacts.systemContext')} description={text(data.system_context)} />
      <Table
        size="small"
        pagination={false}
        rowKey={(row) => text(row.name)}
        dataSource={objects(data.modules)}
        columns={[
          { title: t('artifacts.module'), render: (_, row) => text(row.name) },
          { title: t('artifacts.responsibility'), render: (_, row) => text(row.responsibility) },
          { title: t('artifacts.dependsOn'), render: (_, row) => strings(row.depends_on).join(', ') || '--' }
        ]}
      />
    </div>
  );
}

function BackendView({ data }: { data: Record<string, unknown> }) {
  const { t } = useTranslation();
  return (
    <div className="artifact-semantic-view">
      <Typography.Text strong>{t('artifacts.apiContract')}</Typography.Text>
      <Table
        size="small"
        pagination={false}
        scroll={{ x: 680 }}
        rowKey={(row, index) => `${text(row.method)}-${text(row.path)}-${index}`}
        dataSource={objects(data.apis)}
        columns={[
          { title: t('artifacts.method'), width: 90, render: (_, row) => <Tag>{text(row.method)}</Tag> },
          { title: t('artifacts.path'), render: (_, row) => <code>{text(row.path)}</code> },
          { title: t('artifacts.purpose'), render: (_, row) => text(row.description) },
          { title: t('artifacts.roles'), render: (_, row) => strings(row.required_roles).join(', ') || t('common.public') }
        ]}
      />
      <Typography.Text strong>{t('artifacts.dataModel')}</Typography.Text>
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
  const { t } = useTranslation();
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
        <Typography.Title level={3}>
          {translateEnum(t, 'status', text(data.gate_status ?? data.decision), t('artifacts.reviewed'))}
        </Typography.Title>
        <Typography.Text>
          {t('artifacts.findingsGrade', { count: issues.length, grade: text(data.final_grade, '--') })}
        </Typography.Text>
      </div>
      <List
        size="small"
        dataSource={issues}
        renderItem={(issue) => (
          <List.Item>
            <List.Item.Meta
              title={<Space><Tag color={statusColor(text(issue.severity))}>{translateEnum(t, 'severity', text(issue.severity))}</Tag>{text(issue.issue_type)}</Space>}
              description={text(issue.description)}
            />
          </List.Item>
        )}
      />
    </div>
  );
}

function Provenance({ artifact }: { artifact: ArtifactResponse }) {
  const { t } = useTranslation();
  const citations = parseArray(artifact.sourceCitationsJson);
  const provenance = parseObject(artifact.provenanceJson ?? '');
  return (
    <Space direction="vertical" className="full-width">
      <Descriptions size="small" column={{ xs: 1, md: 2 }}>
        <Descriptions.Item label={t('artifacts.contentHash')}><code>{artifact.contentHash?.slice(0, 16) ?? '--'}</code></Descriptions.Item>
        <Descriptions.Item label={t('artifacts.schema')}>{artifact.schemaVersion ?? '--'}</Descriptions.Item>
        <Descriptions.Item label={t('artifacts.prompt')}>{artifact.promptKey ? `${artifact.promptKey}:${artifact.promptVersion}` : '--'}</Descriptions.Item>
        <Descriptions.Item label={t('artifacts.model')}>{artifact.modelProvider ? `${artifact.modelProvider}/${artifact.modelName}` : t('artifacts.noModelCall')}</Descriptions.Item>
        <Descriptions.Item label={t('artifacts.nodeRun')}>{artifact.workflowNodeRunId ?? '--'}</Descriptions.Item>
        <Descriptions.Item label={t('artifacts.parentArtifact')}>{artifact.parentArtifactId ?? '--'}</Descriptions.Item>
      </Descriptions>
      <Typography.Text strong>{t('artifacts.upstreamVersions')}</Typography.Text>
      <pre>{JSON.stringify(provenance?.upstream_artifacts ?? [], null, 2)}</pre>
      <Typography.Text strong>{t('artifacts.citations', { count: citations.length })}</Typography.Text>
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
