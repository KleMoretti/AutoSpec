import { BranchesOutlined, HistoryOutlined, PlayCircleOutlined, StopOutlined } from '@ant-design/icons';
import { Alert, Button, Card, Collapse, Descriptions, Progress, Select, Space, Tag, Timeline, Typography } from 'antd';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type {
  WorkflowNodeRunResponse,
  WorkflowReplayPayload,
  WorkflowRunResponse,
  WorkflowRunStartPayload,
  WorkflowRuntimeMetricsResponse,
  WorkflowVersionResponse
} from '../api/workflow';

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
    if (nodeResult.status === 'rejected') errors.push(`nodes: ${errorMessage(nodeResult.reason)}`);
    if (metricsResult.status === 'rejected') errors.push(`metrics: ${errorMessage(metricsResult.reason)}`);
    setTimeline({
      runId,
      nodes: nodeResult.status === 'fulfilled' ? nodeResult.value : [],
      metrics: metricsResult.status === 'fulfilled' ? metricsResult.value : null,
      loading: false,
      error: errors.length > 0 ? errors.join(' · ') : null
    });
  }, []);

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
          <Typography.Title level={3} id="workflow-replay-title">Generate, review, and replay</Typography.Title>
        </Space>
        <Card size="small" title="Start from a published workflow">
          <Space wrap align="end">
            <label>
              <Typography.Text strong>Published version</Typography.Text>
              <Select
                aria-label="Start workflow version"
                className="replay-select"
                value={startVersionId}
                onChange={setStartVersionId}
                placeholder="Choose version"
                options={publishedVersions.map((version) => ({
                  value: version.id,
                  label: `${version.version} · #${version.id}`
                }))}
              />
            </label>
            <label>
              <Typography.Text strong>Quality profile</Typography.Text>
              <Select
                aria-label="Workflow quality profile"
                className="replay-select"
                value={qualityProfile}
                onChange={setQualityProfile}
                options={[
                  { value: 'FAST', label: 'Fast · 5 min / 50k tokens' },
                  { value: 'BALANCED', label: 'Balanced · 15 min / 150k tokens' },
                  { value: 'DEEP', label: 'Deep · 45 min / 500k tokens' }
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
              Generate specification
            </Button>
          </Space>
          {publishedVersions.length === 0 ? (
            <Alert type="warning" showIcon message="No published autospec-v5 version is available." />
          ) : null}
        </Card>
        {startResult ? (
          <Alert type="success" showIcon message={`Workflow run #${startResult.id} started`} />
        ) : null}
        {runs.length > 0 ? (
          <Card size="small" title="Create an immutable replay">
            <Space wrap align="end">
            <label>
              <Typography.Text strong>Source run</Typography.Text>
              <Select
                aria-label="Source workflow run"
                className="replay-select"
                value={sourceRunId}
                onChange={setSourceRunId}
                options={runs.map((run) => ({
                  value: run.id,
                  label: `#${run.id} · ${run.status}${run.replayOfRunId ? ` · replay of #${run.replayOfRunId}` : ''}`
                }))}
              />
            </label>
            <label>
              <Typography.Text strong>Replay mode</Typography.Text>
              <Select
                aria-label="Replay mode"
                className="replay-select"
                value={mode}
                onChange={setMode}
                options={[
                  { value: 'ORIGINAL_SNAPSHOT', label: 'Original snapshot' },
                  { value: 'SELECTED_VERSION', label: 'Selected version' }
                ]}
              />
            </label>
            {mode === 'SELECTED_VERSION' ? (
              <label>
                <Typography.Text strong>Published version</Typography.Text>
                <Select
                  aria-label="Published workflow version"
                  className="replay-select"
                  value={selectedVersionId}
                  onChange={setSelectedVersionId}
                  placeholder="Choose version"
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
              Start replay
            </Button>
            <Button
              icon={<HistoryOutlined />}
              disabled={!sourceRunId}
              onClick={() => sourceRunId && void loadTimeline(sourceRunId)}
            >
              View timeline
            </Button>
            </Space>
          </Card>
        ) : null}
        {result ? (
          <Alert
            type="success"
            showIcon
            message={`Replay #${result.id} created from run #${result.replayOfRunId}`}
            description={
              <Button type="link" className="inline-link" onClick={() => void loadTimeline(result.id)}>
                Open new run timeline
              </Button>
            }
          />
        ) : null}
        <Collapse
          defaultActiveKey={['runtime-details']}
          items={[{
            key: 'runtime-details',
            label: 'Runtime details and recovery',
            children: (
              <Space direction="vertical" size={16} className="full-width">
                <div className="workflow-run-grid">
                  {runs.slice().reverse().map((run) => (
                    <Card size="small" key={run.id}>
                      <Space direction="vertical" size={8} className="full-width">
                        <Space wrap>
                          <Typography.Text strong>Run #{run.id}</Typography.Text>
                          <Tag color={statusColor(run.status)}>{run.status}</Tag>
                          {run.qualityProfile ? <Tag>{run.qualityProfile}</Tag> : null}
                          {run.replayOfRunId ? <Tag icon={<BranchesOutlined />}>from #{run.replayOfRunId}</Tag> : null}
                        </Space>
                        <Typography.Text className="muted">
                          {run.operation} · version #{run.workflowVersionId ?? 'snapshot'}
                        </Typography.Text>
                        {run.errorMessage ? (
                          <Alert
                            type="error"
                            showIcon
                            message="Run failed"
                            description={run.errorMessage}
                          />
                        ) : null}
                        <Space wrap>
                          <Button type="link" className="inline-link" onClick={() => void loadTimeline(run.id)}>
                            View timeline and usage
                          </Button>
                          {run.status === 'RUNNING' ? (
                            <Button
                              danger
                              icon={<StopOutlined />}
                              loading={cancellingRunId === run.id}
                              onClick={() => void cancelRun(run.id)}
                            >
                              Cancel run
                            </Button>
                          ) : null}
                        </Space>
                      </Space>
                    </Card>
                  ))}
                </div>
                {timeline.runId ? (
                  <Card id="workflow-run-timeline" size="small" title={`Run #${timeline.runId} timeline`} loading={timeline.loading}>
                    {timeline.error ? (
                      <Alert
                        type="error"
                        showIcon
                        message="Runtime details could not be loaded completely"
                        description={timeline.error}
                        action={<Button size="small" onClick={() => void loadTimeline(timeline.runId as number)}>Retry</Button>}
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
                          <Descriptions.Item label="Queue time">{timeline.metrics.queueTimeMs} ms</Descriptions.Item>
                          <Descriptions.Item label="Execution">{timeline.metrics.executionDurationMs} ms</Descriptions.Item>
                          <Descriptions.Item label="Retries / recoveries">{timeline.metrics.retryCount} / {timeline.metrics.recoveryCount}</Descriptions.Item>
                          <Descriptions.Item label="Tokens / cache">{timeline.metrics.tokenCount} / {timeline.metrics.cacheTokenCount}</Descriptions.Item>
                          <Descriptions.Item label="Model calls">{timeline.metrics.modelCallCount}</Descriptions.Item>
                          <Descriptions.Item label="Estimated cost">${Number(timeline.metrics.estimatedCost).toFixed(6)}</Descriptions.Item>
                          <Descriptions.Item label="Duplicate events">{timeline.metrics.acceptedDuplicateEventCount}</Descriptions.Item>
                          <Descriptions.Item label="Profile">{timeline.metrics.qualityProfile ?? '--'}</Descriptions.Item>
                        </Descriptions>
                        {timeline.metrics.maxTokens ? (
                          <div className="budget-progress">
                            <Typography.Text>Token budget</Typography.Text>
                            <Progress
                              percent={Math.min(100, Math.round(timeline.metrics.tokenCount / timeline.metrics.maxTokens * 100))}
                              format={() => `${timeline.metrics?.tokenCount.toLocaleString()} / ${timeline.metrics?.maxTokens?.toLocaleString()}`}
                            />
                          </div>
                        ) : null}
                        {timeline.metrics.modelUsage?.map((usage) => (
                          <Alert
                            key={`${usage.providerKey}-${usage.modelName}`}
                            type="info"
                            showIcon
                            message={`${usage.providerKey} / ${usage.modelName}`}
                            description={`${usage.modelCallCount} calls · ${usage.inputTokens + usage.outputTokens} tokens · $${Number(usage.estimatedCost).toFixed(6)}`}
                          />
                        ))}
                      </>
                    ) : null}
                    {timeline.nodes.length === 0 && !timeline.loading && !timeline.error ? (
                      <Typography.Text className="muted">No node attempts recorded.</Typography.Text>
                    ) : (
                      <Timeline
                        items={timeline.nodes.map((node) => ({
                          color: statusColor(node.status),
                          children: (
                            <Descriptions size="small" column={{ xs: 1, sm: 2, md: 3 }}>
                              <Descriptions.Item label="Node">{node.nodeId}</Descriptions.Item>
                              <Descriptions.Item label="Status"><Tag>{node.status}</Tag></Descriptions.Item>
                              <Descriptions.Item label="Revision / attempt">{node.revision} / {node.attempt}</Descriptions.Item>
                              <Descriptions.Item label="Handler">{node.handlerKey}:{node.handlerVersion}</Descriptions.Item>
                              <Descriptions.Item label="Worker">{node.workerId ?? 'unassigned'}</Descriptions.Item>
                              <Descriptions.Item label="Duration">{formatDuration(node.startedAt, node.finishedAt)}</Descriptions.Item>
                              <Descriptions.Item label="Last heartbeat">{node.heartbeatAt ?? '—'}</Descriptions.Item>
                              {node.errorCode ? <Descriptions.Item label="Error code">{node.errorCode}</Descriptions.Item> : null}
                              {node.errorMessage ? <Descriptions.Item label="Failure" span={3}>{node.errorMessage}</Descriptions.Item> : null}
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

function errorMessage(value: unknown): string {
  return value instanceof Error ? value.message : 'Request failed';
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
