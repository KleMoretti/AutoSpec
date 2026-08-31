import { BranchesOutlined, HistoryOutlined, PlayCircleOutlined, StopOutlined } from '@ant-design/icons';
import { Alert, Button, Card, Collapse, Descriptions, Progress, Select, Space, Tag, Timeline, Typography } from 'antd';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type {
  WorkflowNodeRunResponse,
  WorkflowReplayPayload,
  WorkflowRunResponse,
  WorkflowRunStartPayload,
  WorkflowRuntimeMetricsResponse,
  WorkflowVersionResponse
} from '../api/workflow';
import { formatDateTime, formatNumber, formatUsd, translateEnum } from '../i18n/formatters';

interface WorkflowReplayPanelProps {
  projectId: number;
  requirement: string;
  runs: WorkflowRunResponse[];
  versions: WorkflowVersionResponse[];
  onStart: (payload: WorkflowRunStartPayload) => Promise<WorkflowRunResponse>;
  onReplay: (runId: number, payload: WorkflowReplayPayload) => Promise<WorkflowRunResponse>;
  onCancel: (runId: number) => Promise<WorkflowRunResponse>;
  onLoadTimeline: (runId: number) => Promise<WorkflowNodeRunResponse[]>;
  onLoadMetrics: (runId: number) => Promise<WorkflowRuntimeMetricsResponse>;
}

type ReplayMode = WorkflowReplayPayload['mode'];

interface TimelineViewState {
  runId: number | null;
  nodes: WorkflowNodeRunResponse[];
  metrics: WorkflowRuntimeMetricsResponse | null;
  loading: boolean;
  error: string | null;
}

function WorkflowReplayPanel({
  projectId,
  requirement,
  runs,
  versions,
  onStart,
  onReplay,
  onCancel,
  onLoadTimeline,
  onLoadMetrics
}: WorkflowReplayPanelProps) {
  const { t, i18n } = useTranslation();
  const [startVersionId, setStartVersionId] = useState<number | undefined>();
  const [qualityProfile, setQualityProfile] = useState<'FAST' | 'BALANCED' | 'DEEP'>('BALANCED');
  const [sourceRunId, setSourceRunId] = useState<number | undefined>(runs.at(-1)?.id);
  const [mode, setMode] = useState<ReplayMode>('ORIGINAL_SNAPSHOT');
  const [selectedVersionId, setSelectedVersionId] = useState<number | undefined>();
  const [submitting, setSubmitting] = useState(false);
  const [starting, setStarting] = useState(false);
  const [cancellingRunId, setCancellingRunId] = useState<number | null>(null);
  const [startResult, setStartResult] = useState<WorkflowRunResponse | null>(null);
  const [result, setResult] = useState<WorkflowRunResponse | null>(null);
  const [timeline, setTimeline] = useState<TimelineViewState>({
    runId: null,
    nodes: [],
    metrics: null,
    loading: false,
    error: null
  });
  const timelineRequest = useRef(0);
  const timelineLoaders = useRef({ onLoadTimeline, onLoadMetrics });
  timelineLoaders.current = { onLoadTimeline, onLoadMetrics };
  const idempotencyKey = useRef(createReplayKey());
  const startIdempotencyKey = useRef(createStartKey());
  const publishedVersions = useMemo(
    () => versions.filter((version) => version.status === 'PUBLISHED'),
    [versions]
  );
  const selectedTimelineRun = useMemo(
    () => runs.find((run) => run.id === timeline.runId),
    [runs, timeline.runId]
  );

  useEffect(() => {
    if (!sourceRunId && runs.length > 0) {
      setSourceRunId(runs.at(-1)?.id);
    }
  }, [runs, sourceRunId]);

  useEffect(() => {
    if (!startVersionId && publishedVersions.length > 0) {
      setStartVersionId(publishedVersions[0]?.id);
    }
  }, [publishedVersions, startVersionId]);

  useEffect(() => () => {
    timelineRequest.current += 1;
  }, []);

  async function submitStart() {
    if (!startVersionId || !requirement.trim()) {
      return;
    }
    setStarting(true);
    try {
      const run = await onStart(
        buildStartPayload(
          projectId,
          startVersionId,
          requirement,
          startIdempotencyKey.current,
          qualityProfile
        )
      );
      setStartResult(run);
      startIdempotencyKey.current = createStartKey();
      await loadTimeline(run.id);
    } finally {
      setStarting(false);
    }
  }

  async function submitReplay() {
    if (!sourceRunId || (mode === 'SELECTED_VERSION' && !selectedVersionId)) {
      return;
    }
    setSubmitting(true);
    try {
      const replay = await onReplay(
        sourceRunId,
        buildReplayPayload(mode, selectedVersionId, idempotencyKey.current)
      );
      setResult(replay);
      idempotencyKey.current = createReplayKey();
      await loadTimeline(replay.id);
    } finally {
      setSubmitting(false);
    }
  }

  const loadTimeline = useCallback(async (runId: number) => {
    const requestId = ++timelineRequest.current;
    setTimeline({ runId, nodes: [], metrics: null, loading: true, error: null });
    const [nodeResult, metricsResult] = await Promise.allSettled([
      timelineLoaders.current.onLoadTimeline(runId),
      timelineLoaders.current.onLoadMetrics(runId)
    ]);
    if (requestId !== timelineRequest.current) return;
    const errors: string[] = [];
    if (nodeResult.status === 'rejected') {
      errors.push(t('workflow.runtimeErrorPart', {
        resource: t('workflow.nodesResource'),
        reason: errorMessage(nodeResult.reason, t('common.requestFailed'))
      }));
    }
    if (metricsResult.status === 'rejected') {
      errors.push(t('workflow.runtimeErrorPart', {
        resource: t('workflow.metricsResource'),
        reason: errorMessage(metricsResult.reason, t('common.requestFailed'))
      }));
    }
    setTimeline({
      runId,
      nodes: nodeResult.status === 'fulfilled' ? nodeResult.value : [],
      metrics: metricsResult.status === 'fulfilled' ? metricsResult.value : null,
      loading: false,
      error: errors.length > 0 ? errors.join(' · ') : null
    });
  }, [t]);

  useEffect(() => {
    if (!timeline.runId || !selectedTimelineRun || !isActive(selectedTimelineRun.status)) {
      return undefined;
    }
    const timer = window.setInterval(() => void loadTimeline(timeline.runId as number), 2000);
    return () => window.clearInterval(timer);
  }, [loadTimeline, selectedTimelineRun?.status, timeline.runId]);

  async function cancelRun(runId: number) {
    setCancellingRunId(runId);
    try {
      await onCancel(runId);
      if (timeline.runId === runId) await loadTimeline(runId);
    } finally {
      setCancellingRunId(null);
    }
  }

  return (
    <section className="panel" aria-labelledby="workflow-replay-title">
      <Space direction="vertical" size={16} className="full-width">
        <Space>
          <HistoryOutlined />
          <Typography.Title level={3} id="workflow-replay-title">{t('workflow.title')}</Typography.Title>
        </Space>
        <Card size="small" title={t('workflow.startCardTitle')}>
          <Space wrap align="end">
            <label>
              <Typography.Text strong>{t('workflow.publishedVersion')}</Typography.Text>
              <Select
                aria-label={t('workflow.startVersionLabel')}
                className="replay-select"
                value={startVersionId}
                onChange={setStartVersionId}
                placeholder={t('workflow.chooseVersion')}
                options={publishedVersions.map((version) => ({
                  value: version.id,
                  label: `${version.version} · #${version.id}`
                }))}
              />
            </label>
            <label>
              <Typography.Text strong>{t('workflow.qualityProfile')}</Typography.Text>
              <Select
                aria-label={t('workflow.qualityProfileLabel')}
                className="replay-select"
                value={qualityProfile}
                onChange={setQualityProfile}
                options={[
                  { value: 'FAST', label: t('workflow.fastOption') },
                  { value: 'BALANCED', label: t('workflow.balancedOption') },
                  { value: 'DEEP', label: t('workflow.deepOption') }
                ]}
              />
            </label>
            <Button
              type="primary"
              icon={<PlayCircleOutlined />}
              loading={starting}
              disabled={!startVersionId || !requirement.trim()}
              onClick={() => void submitStart()}
            >
              {t('workflow.generate')}
            </Button>
          </Space>
          {publishedVersions.length === 0 ? (
            <Alert type="warning" showIcon message={t('workflow.noPublishedVersion')} />
          ) : null}
        </Card>
        {startResult ? (
          <Alert type="success" showIcon message={t('workflow.runStarted', { id: startResult.id })} />
        ) : null}
        {runs.length > 0 ? (
          <Card size="small" title={t('workflow.replayCardTitle')}>
            <Space wrap align="end">
            <label>
              <Typography.Text strong>{t('workflow.sourceRun')}</Typography.Text>
              <Select
                aria-label={t('workflow.sourceRunLabel')}
                className="replay-select"
                value={sourceRunId}
                onChange={setSourceRunId}
                options={runs.map((run) => ({
                  value: run.id,
                  label: `#${run.id} · ${translateEnum(t, 'status', run.status)}${run.replayOfRunId ? ` · ${t('workflow.replayOf', { id: run.replayOfRunId })}` : ''}`
                }))}
              />
            </label>
            <label>
              <Typography.Text strong>{t('workflow.replayMode')}</Typography.Text>
              <Select
                aria-label={t('workflow.replayModeLabel')}
                className="replay-select"
                value={mode}
                onChange={setMode}
                options={[
                  { value: 'ORIGINAL_SNAPSHOT', label: translateEnum(t, 'replayMode', 'ORIGINAL_SNAPSHOT') },
                  { value: 'SELECTED_VERSION', label: translateEnum(t, 'replayMode', 'SELECTED_VERSION') }
                ]}
              />
            </label>
            {mode === 'SELECTED_VERSION' ? (
              <label>
                <Typography.Text strong>{t('workflow.publishedVersion')}</Typography.Text>
                <Select
                  aria-label={t('workflow.publishedVersionLabel')}
                  className="replay-select"
                  value={selectedVersionId}
                  onChange={setSelectedVersionId}
                  placeholder={t('workflow.chooseVersion')}
                  options={publishedVersions.map((version) => ({
                    value: version.id,
                    label: `${version.version} · #${version.id}`
                  }))}
                />
              </label>
            ) : null}
            <Button
              type="primary"
              icon={<PlayCircleOutlined />}
              loading={submitting}
              disabled={!sourceRunId || (mode === 'SELECTED_VERSION' && !selectedVersionId)}
              onClick={() => void submitReplay()}
            >
              {t('workflow.startReplay')}
            </Button>
            <Button
              icon={<HistoryOutlined />}
              disabled={!sourceRunId}
              onClick={() => sourceRunId && void loadTimeline(sourceRunId)}
            >
              {t('workflow.viewTimeline')}
            </Button>
            </Space>
          </Card>
        ) : null}
        {result ? (
          <Alert
            type="success"
            showIcon
            message={t('workflow.replayCreated', { id: result.id, sourceId: result.replayOfRunId })}
            description={
              <Button type="link" className="inline-link" onClick={() => void loadTimeline(result.id)}>
                {t('workflow.openNewTimeline')}
              </Button>
            }
          />
        ) : null}
        <Collapse
          defaultActiveKey={['runtime-details']}
          items={[{
            key: 'runtime-details',
            label: t('workflow.runtimeDetails'),
            children: (
              <Space direction="vertical" size={16} className="full-width">
                <div className="workflow-run-grid">
                  {runs.slice().reverse().map((run) => (
                    <Card size="small" key={run.id}>
                      <Space direction="vertical" size={8} className="full-width">
                        <Space wrap>
                          <Typography.Text strong>{t('workflow.run', { id: run.id })}</Typography.Text>
                          <Tag color={statusColor(run.status)}>{translateEnum(t, 'status', run.status)}</Tag>
                          {run.qualityProfile ? <Tag>{translateEnum(t, 'qualityProfile', run.qualityProfile)}</Tag> : null}
                          {run.replayOfRunId ? <Tag icon={<BranchesOutlined />}>{t('workflow.fromRun', { id: run.replayOfRunId })}</Tag> : null}
                        </Space>
                        <Typography.Text className="muted">
                          {t('workflow.operationVersion', {
                            operation: run.operation,
                            version: run.workflowVersionId ?? t('common.snapshot')
                          })}
                        </Typography.Text>
                        {run.errorMessage ? (
                          <Alert
                            type="error"
                            showIcon
                            message={t('workflow.runFailed')}
                            description={run.errorMessage}
                          />
                        ) : null}
                        <Space wrap>
                          <Button type="link" className="inline-link" onClick={() => void loadTimeline(run.id)}>
                            {t('workflow.viewTimelineUsage')}
                          </Button>
                          {run.status === 'RUNNING' ? (
                            <Button
                              danger
                              icon={<StopOutlined />}
                              loading={cancellingRunId === run.id}
                              onClick={() => void cancelRun(run.id)}
                            >
                              {t('workflow.cancelRun')}
                            </Button>
                          ) : null}
                        </Space>
                      </Space>
                    </Card>
                  ))}
                </div>
                {timeline.runId ? (
                  <Card
                    id="workflow-run-timeline"
                    size="small"
                    title={t('workflow.timelineTitle', { id: timeline.runId })}
                    loading={timeline.loading}
                  >
                    {timeline.error ? (
                      <Alert
                        type="error"
                        showIcon
                        message={t('workflow.runtimeLoadFailed')}
                        description={timeline.error}
                        action={<Button size="small" onClick={() => void loadTimeline(timeline.runId as number)}>{t('common.retry')}</Button>}
                      />
                    ) : null}
                    {selectedTimelineRun?.errorMessage ? (
                      <Alert
                        type="error"
                        showIcon
                        message={selectedTimelineRun.responseStatus ?? selectedTimelineRun.status}
                        description={selectedTimelineRun.errorMessage}
                      />
                    ) : null}
                    {timeline.metrics ? (
                      <>
                        <Descriptions size="small" column={{ xs: 1, sm: 2, md: 4 }} className="workflow-metrics">
                          <Descriptions.Item label={t('workflow.metrics.queueTime')}>{formatNumber(timeline.metrics.queueTimeMs, i18n.resolvedLanguage)} ms</Descriptions.Item>
                          <Descriptions.Item label={t('workflow.metrics.execution')}>{formatNumber(timeline.metrics.executionDurationMs, i18n.resolvedLanguage)} ms</Descriptions.Item>
                          <Descriptions.Item label={t('workflow.metrics.retriesRecoveries')}>{formatNumber(timeline.metrics.retryCount, i18n.resolvedLanguage)} / {formatNumber(timeline.metrics.recoveryCount, i18n.resolvedLanguage)}</Descriptions.Item>
                          <Descriptions.Item label={t('workflow.metrics.tokensCache')}>{formatNumber(timeline.metrics.tokenCount, i18n.resolvedLanguage)} / {formatNumber(timeline.metrics.cacheTokenCount, i18n.resolvedLanguage)}</Descriptions.Item>
                          <Descriptions.Item label={t('workflow.metrics.modelCalls')}>{formatNumber(timeline.metrics.modelCallCount, i18n.resolvedLanguage)}</Descriptions.Item>
                          <Descriptions.Item label={t('workflow.metrics.estimatedCost')}>{formatUsd(Number(timeline.metrics.estimatedCost), i18n.resolvedLanguage, 6)}</Descriptions.Item>
                          <Descriptions.Item label={t('workflow.metrics.duplicateEvents')}>{formatNumber(timeline.metrics.acceptedDuplicateEventCount, i18n.resolvedLanguage)}</Descriptions.Item>
                          <Descriptions.Item label={t('workflow.metrics.profile')}>{translateEnum(t, 'qualityProfile', timeline.metrics.qualityProfile)}</Descriptions.Item>
                        </Descriptions>
                        {timeline.metrics.maxTokens ? (
                          <div className="budget-progress">
                            <Typography.Text>{t('workflow.metrics.tokenBudget')}</Typography.Text>
                            <Progress
                              percent={Math.min(100, Math.round(timeline.metrics.tokenCount / timeline.metrics.maxTokens * 100))}
                              format={() => `${formatNumber(timeline.metrics?.tokenCount ?? 0, i18n.resolvedLanguage)} / ${formatNumber(timeline.metrics?.maxTokens ?? 0, i18n.resolvedLanguage)}`}
                            />
                          </div>
                        ) : null}
                        {timeline.metrics.modelUsage?.map((usage) => (
                          <Alert
                            key={`${usage.providerKey}-${usage.modelName}`}
                            type="info"
                            showIcon
                            message={`${usage.providerKey} / ${usage.modelName}`}
                            description={t('workflow.metrics.usage', {
                              calls: formatNumber(usage.modelCallCount, i18n.resolvedLanguage),
                              tokens: formatNumber(usage.inputTokens + usage.outputTokens, i18n.resolvedLanguage),
                              cost: formatUsd(Number(usage.estimatedCost), i18n.resolvedLanguage, 6)
                            })}
                          />
                        ))}
                      </>
                    ) : null}
                    {timeline.nodes.length === 0 && !timeline.loading && !timeline.error ? (
                      <Typography.Text className="muted">{t('workflow.noNodeAttempts')}</Typography.Text>
                    ) : (
                      <Timeline
                        items={timeline.nodes.map((node) => ({
                          color: statusColor(node.status),
                          children: (
                            <Descriptions size="small" column={{ xs: 1, sm: 2, md: 3 }}>
                              <Descriptions.Item label={t('workflow.node.node')}>{node.nodeId}</Descriptions.Item>
                              <Descriptions.Item label={t('workflow.node.status')}><Tag>{translateEnum(t, 'status', node.status)}</Tag></Descriptions.Item>
                              <Descriptions.Item label={t('workflow.node.revisionAttempt')}>{formatNumber(node.revision, i18n.resolvedLanguage)} / {formatNumber(node.attempt, i18n.resolvedLanguage)}</Descriptions.Item>
                              <Descriptions.Item label={t('workflow.node.handler')}>{node.handlerKey}:{node.handlerVersion}</Descriptions.Item>
                              <Descriptions.Item label={t('workflow.node.worker')}>{node.workerId ?? t('common.unassigned')}</Descriptions.Item>
                              <Descriptions.Item label={t('workflow.node.duration')}>{formatDuration(node.startedAt, node.finishedAt)}</Descriptions.Item>
                              <Descriptions.Item label={t('workflow.node.lastHeartbeat')}>{node.heartbeatAt ? formatDateTime(node.heartbeatAt, i18n.resolvedLanguage) : '—'}</Descriptions.Item>
                              {node.errorCode ? <Descriptions.Item label={t('workflow.node.errorCode')}>{node.errorCode}</Descriptions.Item> : null}
                              {node.errorMessage ? <Descriptions.Item label={t('workflow.node.failure')} span={3}>{node.errorMessage}</Descriptions.Item> : null}
                            </Descriptions>
                          )
                        }))}
                      />
                    )}
                  </Card>
                ) : null}
              </Space>
            )
          }]}
        />
      </Space>
    </section>
  );
}

export function buildReplayPayload(
  mode: ReplayMode,
  selectedVersionId: number | undefined,
  idempotencyKey: string
): WorkflowReplayPayload {
  return {
    mode,
    selectedWorkflowVersionId: mode === 'SELECTED_VERSION' ? selectedVersionId : undefined,
    idempotencyKey
  };
}

export function buildStartPayload(
  projectId: number,
  workflowVersionId: number,
  requirement: string,
  idempotencyKey: string,
  qualityProfile: 'FAST' | 'BALANCED' | 'DEEP' = 'BALANCED'
): WorkflowRunStartPayload {
  return {
    projectId,
    workflowVersionId,
    input: { requirement },
    idempotencyKey,
    executionPolicy: { qualityProfile }
  };
}

export function formatDuration(startedAt?: string, finishedAt?: string): string {
  if (!startedAt || !finishedAt) {
    return '—';
  }
  const milliseconds = new Date(finishedAt).getTime() - new Date(startedAt).getTime();
  return milliseconds >= 0 ? `${milliseconds} ms` : '—';
}

function statusColor(status: string): string {
  if (status === 'SUCCEEDED' || status === 'COMPLETED') return 'green';
  if (status === 'FAILED' || status === 'CANCELLED') return 'red';
  if (status === 'RUNNING' || status === 'QUEUED') return 'blue';
  return 'gray';
}

function isActive(status: string): boolean {
  return status === 'RUNNING' || status === 'PENDING' || status === 'QUEUED';
}

function errorMessage(value: unknown, fallback: string): string {
  return value instanceof Error ? value.message : fallback;
}

function createReplayKey(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID();
  }
  return `replay-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function createStartKey(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID();
  }
  return `start-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

export default WorkflowReplayPanel;
