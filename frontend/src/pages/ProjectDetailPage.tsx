import {
  CheckCircleOutlined,
  DownloadOutlined,
  FilePdfOutlined,
  PlayCircleOutlined,
  ReloadOutlined
} from '@ant-design/icons';
import { Button, Result, Space, Spin, Typography, message } from 'antd';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import {
  type AgentEventResponse,
  type ArtifactResponse,
  type ProjectProgressResponse,
  type ProjectResponse,
  type ReviewResponse,
  approveArtifact,
  continueGeneration,
  exportMarkdown,
  exportPdf,
  generatePrd,
  generateProject,
  getArtifacts,
  getEventHistory,
  getProgress,
  getProject,
  getReview,
  retryTask,
  subscribeProjectEvents,
  updateArtifact
} from '../api/projects';
import {
  type ApprovalDecisionPayload,
  type WorkflowApprovalResponse,
  type WorkflowNodeRunResponse,
  type WorkflowReplayPayload,
  type WorkflowRunResponse,
  type WorkflowRunStartPayload,
  type WorkflowSnapshotResponse,
  type WorkflowVersionResponse,
  decideWorkflowApproval,
  getWorkflow,
  getWorkflowApprovals,
  getWorkflowRunNodes,
  getWorkflowRuns,
  getWorkflowVersions,
  replayWorkflowRun,
  startWorkflowRun
} from '../api/v3';
import AgentTimeline from '../components/AgentTimeline';
import ArtifactTabs from '../components/ArtifactTabs';
import CodeExportPanel from '../components/CodeExportPanel';
import ExecutionEventList from '../components/ExecutionEventList';
import PrdEditor from '../components/PrdEditor';
import ReviewIssueTable from '../components/ReviewIssueTable';
import WorkflowGraph from '../components/WorkflowGraph';
import WorkflowApprovalPanel from '../components/WorkflowApprovalPanel';
import WorkflowReplayPanel from '../components/WorkflowReplayPanel';

function ProjectDetailPage() {
  const params = useParams();
  const projectId = useMemo(() => Number(params.projectId), [params.projectId]);
  const [project, setProject] = useState<ProjectResponse | null>(null);
  const [progress, setProgress] = useState<ProjectProgressResponse | null>(null);
  const [artifacts, setArtifacts] = useState<ArtifactResponse[]>([]);
  const [review, setReview] = useState<ReviewResponse | null>(null);
  const [events, setEvents] = useState<AgentEventResponse[]>([]);
  const [workflow, setWorkflow] = useState<WorkflowSnapshotResponse | null>(null);
  const [approvals, setApprovals] = useState<WorkflowApprovalResponse[]>([]);
  const [workflowRuns, setWorkflowRuns] = useState<WorkflowRunResponse[]>([]);
  const [workflowVersions, setWorkflowVersions] = useState<WorkflowVersionResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [generating, setGenerating] = useState(false);
  const [continuing, setContinuing] = useState(false);
  const [downloadingMarkdown, setDownloadingMarkdown] = useState(false);
  const [downloadingPdf, setDownloadingPdf] = useState(false);
  const [retryingTaskId, setRetryingTaskId] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const latestPrd = useMemo(() => latestArtifact(artifacts, 'PRD'), [artifacts]);
  const projectStatus = useMemo(() => deriveProjectStatus(progress, artifacts), [artifacts, progress]);
  const failedTask = useMemo(
    () => progress?.steps.find((step) => step.status === 'FAILED' && step.taskId),
    [progress]
  );

  const loadProject = useCallback(async () => {
    if (!Number.isFinite(projectId)) {
      setError('Invalid project id');
      setLoading(false);
      return;
    }
    try {
      const [
        nextProject,
        nextProgress,
        nextArtifacts,
        nextReview,
        nextEvents,
        nextWorkflow,
        nextApprovals,
        nextRuns,
        nextVersions
      ] = await Promise.all([
        getProject(projectId),
        getProgress(projectId),
        getArtifacts(projectId),
        getReview(projectId).catch(() => null),
        getEventHistory(projectId).catch(() => []),
        getWorkflow(projectId).catch(() => null),
        getWorkflowApprovals(projectId).catch(() => []),
        getWorkflowRuns(projectId).catch(() => []),
        getWorkflowVersions('autospec-v5').catch(() => [])
      ]);
      setProject(nextProject);
      setProgress(nextProgress);
      setArtifacts(nextArtifacts);
      setReview(nextReview);
      setEvents(nextEvents);
      setWorkflow(nextWorkflow);
      setApprovals(nextApprovals);
      setWorkflowRuns(nextRuns);
      setWorkflowVersions(nextVersions);
      setError(null);
    } catch (loadError) {
      setError(loadError instanceof Error ? loadError.message : 'Load failed');
    } finally {
      setLoading(false);
    }
  }, [projectId]);

  useEffect(() => {
    void loadProject();
  }, [loadProject]);

  useEffect(() => {
    if (!progress || progress.percent >= 100) {
      return undefined;
    }
    const timer = window.setInterval(() => {
      void loadProject();
    }, 2000);
    return () => window.clearInterval(timer);
  }, [loadProject, progress]);

  useEffect(() => {
    if (projectStatus !== 'GENERATING' || !Number.isFinite(projectId)) {
      return undefined;
    }

    const source = subscribeProjectEvents(
      projectId,
      (event) => {
        setEvents((current) => mergeEvent(current, event));
      },
      () => {
        source.close();
      }
    );

    return () => source.close();
  }, [projectId, projectStatus]);

  async function handleGeneratePrd() {
    setGenerating(true);
    try {
      await generatePrd(projectId);
      await loadProject();
    } catch (generateError) {
      message.error(generateError instanceof Error ? generateError.message : 'PRD generation failed');
    } finally {
      setGenerating(false);
    }
  }

  async function handleRunAgents() {
    setGenerating(true);
    try {
      await generateProject(projectId);
      await loadProject();
    } catch (generateError) {
      message.error(generateError instanceof Error ? generateError.message : 'Generation failed');
    } finally {
      setGenerating(false);
    }
  }

  async function handleSavePrd(content: string) {
    if (!latestPrd) {
      return;
    }
    try {
      await updateArtifact(projectId, latestPrd.id, content);
      message.success('PRD saved');
      await loadProject();
    } catch (saveError) {
      message.error(saveError instanceof Error ? saveError.message : 'PRD save failed');
    }
  }

  async function handleApprovePrd() {
    if (!latestPrd) {
      return;
    }
    try {
      await approveArtifact(projectId, latestPrd.id);
      message.success('PRD approved');
      await loadProject();
    } catch (approveError) {
      message.error(approveError instanceof Error ? approveError.message : 'PRD approval failed');
    }
  }

  async function handleContinueGeneration() {
    setContinuing(true);
    try {
      await continueGeneration(projectId);
      await loadProject();
    } catch (continueError) {
      message.error(continueError instanceof Error ? continueError.message : 'Generation failed');
    } finally {
      setContinuing(false);
    }
  }

  async function handleRetryTask(taskId: number) {
    setRetryingTaskId(taskId);
    try {
      await retryTask(projectId, taskId);
      message.success('Retry started');
      await loadProject();
    } catch (retryError) {
      message.error(retryError instanceof Error ? retryError.message : 'Retry failed');
    } finally {
      setRetryingTaskId(null);
    }
  }

  async function handleApprovalDecision(approvalId: number, payload: ApprovalDecisionPayload) {
    try {
      await decideWorkflowApproval(approvalId, payload);
      message.success('Workflow decision applied');
      await loadProject();
    } catch (decisionError) {
      message.error(decisionError instanceof Error ? decisionError.message : 'Decision failed');
      throw decisionError;
    }
  }

  async function handleReplay(runId: number, payload: WorkflowReplayPayload): Promise<WorkflowRunResponse> {
    try {
      const replay = await replayWorkflowRun(runId, payload);
      message.success(`Replay #${replay.id} started`);
      await loadProject();
      return replay;
    } catch (replayError) {
      message.error(replayError instanceof Error ? replayError.message : 'Replay failed');
      throw replayError;
    }
  }

  async function handleStartWorkflow(payload: WorkflowRunStartPayload): Promise<WorkflowRunResponse> {
    try {
      const run = await startWorkflowRun(payload);
      message.success(`Workflow run #${run.id} started`);
      await loadProject();
      return run;
    } catch (startError) {
      message.error(startError instanceof Error ? startError.message : 'Workflow start failed');
      throw startError;
    }
  }

  async function handleLoadTimeline(runId: number): Promise<WorkflowNodeRunResponse[]> {
    try {
      return await getWorkflowRunNodes(runId);
    } catch (timelineError) {
      message.error(timelineError instanceof Error ? timelineError.message : 'Timeline load failed');
      throw timelineError;
    }
  }

  async function handleExportMarkdown() {
    setDownloadingMarkdown(true);
    try {
      const markdown = await exportMarkdown(projectId);
      downloadBlob(markdown, `autospec-project-${projectId}.md`, 'text/markdown;charset=utf-8');
    } catch (exportError) {
      message.error(exportError instanceof Error ? exportError.message : 'Export failed');
    } finally {
      setDownloadingMarkdown(false);
    }
  }

  async function handleExportPdf() {
    setDownloadingPdf(true);
    try {
      const pdf = await exportPdf(projectId);
      const bytes = Uint8Array.from(atob(pdf.content), (char) => char.charCodeAt(0));
      downloadBlob(bytes, pdf.fileName, pdf.mediaType);
    } catch (exportError) {
      message.error(exportError instanceof Error ? exportError.message : 'PDF export failed');
    } finally {
      setDownloadingPdf(false);
    }
  }

  if (loading) {
    return (
      <main className="workspace center-pane">
        <Spin size="large" />
      </main>
    );
  }

  if (error) {
    return (
      <main className="workspace">
        <Result
          status="warning"
          title={error}
          extra={
            <Button>
              <Link to="/">Back</Link>
            </Button>
          }
        />
      </main>
    );
  }

  return (
    <main className="workspace detail-stack">
      <section className="page-toolbar">
        <div>
          <Typography.Title level={1}>Project #{projectId}</Typography.Title>
          <Typography.Text className="muted">{projectStatus}</Typography.Text>
        </div>
        <Space wrap>
          {projectStatus === 'CREATED' ? (
            <>
              <Button type="primary" icon={<PlayCircleOutlined />} loading={generating} onClick={handleGeneratePrd}>
                Generate PRD
              </Button>
              <Button icon={<ReloadOutlined />} loading={generating} onClick={handleRunAgents}>
                Run agents
              </Button>
            </>
          ) : null}
          {projectStatus === 'PRD_APPROVED' ? (
            <Button type="primary" icon={<CheckCircleOutlined />} loading={continuing} onClick={handleContinueGeneration}>
              Continue generation
            </Button>
          ) : null}
          {projectStatus === 'FAILED' && failedTask?.taskId ? (
            <Button
              type="primary"
              icon={<ReloadOutlined />}
              loading={retryingTaskId === failedTask.taskId}
              onClick={() => handleRetryTask(failedTask.taskId as number)}
            >
              Retry failed task
            </Button>
          ) : null}
          {projectStatus === 'COMPLETED' ? (
            <>
              <Button
                icon={<DownloadOutlined />}
                loading={downloadingMarkdown}
                onClick={handleExportMarkdown}
                disabled={artifacts.length === 0}
              >
                Export Markdown
              </Button>
              <Button
                type="primary"
                icon={<FilePdfOutlined />}
                loading={downloadingPdf}
                onClick={handleExportPdf}
                disabled={artifacts.length === 0}
              >
                Export PDF
              </Button>
            </>
          ) : null}
        </Space>
      </section>
      {projectStatus === 'PRD_REVIEW' && latestPrd ? (
        <PrdEditor artifact={latestPrd} onSave={handleSavePrd} onApprove={handleApprovePrd} />
      ) : null}
      <AgentTimeline progress={progress} onRetryTask={handleRetryTask} retryingTaskId={retryingTaskId} />
      <WorkflowGraph workflow={workflow} />
      <WorkflowApprovalPanel approvals={approvals} artifacts={artifacts} onDecide={handleApprovalDecision} />
      <WorkflowReplayPanel
        projectId={projectId}
        requirement={project?.originalRequirement ?? ''}
        runs={workflowRuns}
        versions={workflowVersions}
        onStart={handleStartWorkflow}
        onReplay={handleReplay}
        onLoadTimeline={handleLoadTimeline}
      />
      {projectStatus === 'GENERATING' || events.length > 0 ? <ExecutionEventList events={events} /> : null}
      {projectStatus === 'COMPLETED' ? <CodeExportPanel projectId={projectId} disabled={artifacts.length === 0} /> : null}
      <ArtifactTabs artifacts={artifacts} />
      <ReviewIssueTable review={review} />
    </main>
  );
}

function latestArtifact(artifacts: ArtifactResponse[], type: string): ArtifactResponse | undefined {
  return artifacts
    .filter((artifact) => artifact.type === type)
    .sort((left, right) => right.version - left.version || right.id - left.id)[0];
}

function deriveProjectStatus(progress: ProjectProgressResponse | null, artifacts: ArtifactResponse[]): string {
  if (progress?.status) {
    return progress.status;
  }
  if (progress?.steps.some((step) => step.status === 'FAILED')) {
    return 'FAILED';
  }
  if ((progress?.percent ?? 0) >= 100) {
    return 'COMPLETED';
  }
  if ((progress?.percent ?? 0) > 0) {
    return 'GENERATING';
  }

  const prd = latestArtifact(artifacts, 'PRD');
  if (prd?.status === 'APPROVED') {
    return 'PRD_APPROVED';
  }
  if (prd?.status === 'PENDING_REVIEW' || prd) {
    return 'PRD_REVIEW';
  }
  return 'CREATED';
}

function mergeEvent(events: AgentEventResponse[], event: AgentEventResponse): AgentEventResponse[] {
  if (events.some((existing) => existing.id === event.id)) {
    return events;
  }
  return [...events, event];
}

function downloadBlob(content: BlobPart, fileName: string, type: string) {
  const blob = new Blob([content], { type });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = fileName;
  link.click();
  URL.revokeObjectURL(url);
}

export default ProjectDetailPage;
