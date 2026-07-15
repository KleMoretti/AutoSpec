import { BranchesOutlined, HistoryOutlined, PlayCircleOutlined } from '@ant-design/icons';
import { Alert, Button, Card, Descriptions, Select, Space, Tag, Timeline, Typography } from 'antd';
import { useEffect, useMemo, useRef, useState } from 'react';
import type {
  WorkflowNodeRunResponse,
  WorkflowReplayPayload,
  WorkflowRunResponse,
  WorkflowRunStartPayload,
  WorkflowRuntimeMetricsResponse,
  WorkflowVersionResponse
} from '../api/v3';

interface WorkflowReplayPanelProps {
  projectId: number;
  requirement: string;
  runs: WorkflowRunResponse[];
  versions: WorkflowVersionResponse[];
  onStart: (payload: WorkflowRunStartPayload) => Promise<WorkflowRunResponse>;
  onReplay: (runId: number, payload: WorkflowReplayPayload) => Promise<WorkflowRunResponse>;
  onLoadTimeline: (runId: number) => Promise<WorkflowNodeRunResponse[]>;
  onLoadMetrics: (runId: number) => Promise<WorkflowRuntimeMetricsResponse>;
}

type ReplayMode = WorkflowReplayPayload['mode'];

function WorkflowReplayPanel({
  projectId,
  requirement,
  runs,
  versions,
  onStart,
  onReplay,
  onLoadTimeline,
  onLoadMetrics
}: WorkflowReplayPanelProps) {
  const [startVersionId, setStartVersionId] = useState<number | undefined>();
  const [sourceRunId, setSourceRunId] = useState<number | undefined>(runs.at(-1)?.id);
  const [mode, setMode] = useState<ReplayMode>('ORIGINAL_SNAPSHOT');
  const [selectedVersionId, setSelectedVersionId] = useState<number | undefined>();
  const [submitting, setSubmitting] = useState(false);
  const [starting, setStarting] = useState(false);
  const [startResult, setStartResult] = useState<WorkflowRunResponse | null>(null);
  const [result, setResult] = useState<WorkflowRunResponse | null>(null);
  const [timelineRunId, setTimelineRunId] = useState<number | null>(null);
  const [nodes, setNodes] = useState<WorkflowNodeRunResponse[]>([]);
  const [metrics, setMetrics] = useState<WorkflowRuntimeMetricsResponse | null>(null);
  const [timelineLoading, setTimelineLoading] = useState(false);
  const idempotencyKey = useRef(createReplayKey());
  const startIdempotencyKey = useRef(createStartKey());
  const publishedVersions = useMemo(
    () => versions.filter((version) => version.status === 'PUBLISHED'),
    [versions]
  );

  useEffect(() => {
    if (!sourceRunId && runs.length > 0) {
      setSourceRunId(runs.at(-1)?.id);
    }
  }, [runs, sourceRunId]);

  useEffect(() => {
    if (!startVersionId && publishedVersions.length > 0) {
      setStartVersionId(publishedVersions.at(-1)?.id);
    }
  }, [publishedVersions, startVersionId]);

  async function submitStart() {
    if (!startVersionId || !requirement.trim()) {
      return;
    }
    setStarting(true);
    try {
      const run = await onStart(
        buildStartPayload(projectId, startVersionId, requirement, startIdempotencyKey.current)
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

  async function loadTimeline(runId: number) {
    setTimelineRunId(runId);
    setTimelineLoading(true);
    try {
      const [nextNodes, nextMetrics] = await Promise.all([
        onLoadTimeline(runId),
        onLoadMetrics(runId)
      ]);
      setNodes(nextNodes);
      setMetrics(nextMetrics);
    } finally {
      setTimelineLoading(false);
    }
  }

  return (
    <section className="panel" aria-labelledby="workflow-replay-title">
      <Space direction="vertical" size={16} className="full-width">
        <Space>
          <HistoryOutlined />
          <Typography.Title level={3} id="workflow-replay-title">V5 workflow runs</Typography.Title>
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
            <Button
              type="primary"
              icon={<PlayCircleOutlined />}
              loading={starting}
              disabled={!startVersionId || !requirement.trim()}
              onClick={() => void submitStart()}
            >
              Start V5 run
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
        <div className="workflow-run-grid">
          {runs.slice().reverse().map((run) => (
            <Card size="small" key={run.id}>
              <Space direction="vertical" size={8} className="full-width">
                <Space wrap>
                  <Typography.Text strong>Run #{run.id}</Typography.Text>
                  <Tag color={statusColor(run.status)}>{run.status}</Tag>
                  {run.replayOfRunId ? <Tag icon={<BranchesOutlined />}>from #{run.replayOfRunId}</Tag> : null}
                </Space>
                <Typography.Text className="muted">
                  {run.operation} · version #{run.workflowVersionId ?? 'snapshot'}
                </Typography.Text>
                <Button type="link" className="inline-link" onClick={() => void loadTimeline(run.id)}>
                  View timeline
                </Button>
              </Space>
            </Card>
          ))}
        </div>
        {timelineRunId ? (
          <Card id="workflow-run-timeline" size="small" title={`Run #${timelineRunId} timeline`} loading={timelineLoading}>
            {metrics ? (
              <Descriptions size="small" column={{ xs: 1, sm: 2, md: 4 }} className="workflow-metrics">
                <Descriptions.Item label="Queue time">{metrics.queueTimeMs} ms</Descriptions.Item>
                <Descriptions.Item label="Execution">{metrics.executionDurationMs} ms</Descriptions.Item>
                <Descriptions.Item label="Retries / recoveries">{metrics.retryCount} / {metrics.recoveryCount}</Descriptions.Item>
                <Descriptions.Item label="Tokens / cost">{metrics.tokenCount} / {metrics.estimatedCost}</Descriptions.Item>
                <Descriptions.Item label="Duplicate events">{metrics.acceptedDuplicateEventCount}</Descriptions.Item>
              </Descriptions>
            ) : null}
            {nodes.length === 0 && !timelineLoading ? (
              <Typography.Text className="muted">No node attempts recorded.</Typography.Text>
            ) : (
              <Timeline
                items={nodes.map((node) => ({
                  color: statusColor(node.status),
                  children: (
                    <Descriptions size="small" column={{ xs: 1, sm: 2, md: 3 }}>
                      <Descriptions.Item label="Node">{node.nodeId}</Descriptions.Item>
                      <Descriptions.Item label="Status"><Tag>{node.status}</Tag></Descriptions.Item>
                      <Descriptions.Item label="Revision / attempt">{node.revision} / {node.attempt}</Descriptions.Item>
                      <Descriptions.Item label="Handler">{node.handlerKey}:{node.handlerVersion}</Descriptions.Item>
                      <Descriptions.Item label="Worker">{node.workerId ?? 'unassigned'}</Descriptions.Item>
                      <Descriptions.Item label="Duration">{formatDuration(node.startedAt, node.finishedAt)}</Descriptions.Item>
                    </Descriptions>
                  )
                }))}
              />
            )}
          </Card>
        ) : null}
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
  idempotencyKey: string
): WorkflowRunStartPayload {
  return {
    projectId,
    workflowVersionId,
    input: { requirement, retrieved_sources: [] },
    idempotencyKey
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
