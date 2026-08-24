import { DownloadOutlined, FilePdfOutlined, ReloadOutlined } from '@ant-design/icons';
import { Alert, Button, Descriptions, Result, Space, Spin, Steps, Tag, Typography, message } from 'antd';
import { useMemo, useState } from 'react';
import { useParams } from 'react-router-dom';
import {
  exportMarkdown,
  exportPdf
} from '../api/projects';
import {
  type ApprovalDecisionPayload,
  type DeliveryReadinessResponse,
  type WorkflowNodeRunResponse,
  type WorkflowReplayPayload,
  type WorkflowRunResponse,
  type WorkflowRunStartPayload,
  type WorkflowRuntimeMetricsResponse,
  cancelWorkflowRun,
  decideWorkflowApproval,
  getWorkflowRunMetrics,
  getWorkflowRunNodes,
  replayWorkflowRun,
  startWorkflowRun
} from '../api/v3';
import ArtifactTabs from '../components/ArtifactTabs';
import CodeExportPanel from '../components/CodeExportPanel';
import ReviewIssueTable from '../components/ReviewIssueTable';
import WorkflowApprovalPanel from '../components/WorkflowApprovalPanel';
import WorkflowReplayPanel from '../components/WorkflowReplayPanel';
import { useProjectDetailData } from '../hooks/useProjectDetailData';

function ProjectDetailPage() {
  const params = useParams();
  const projectId = useMemo(() => Number(params.projectId), [params.projectId]);
  const {
    project,
    artifacts,
    review,
    approvals,
    workflowRuns,
    workflowVersions,
    deliveryReadiness,
    latestRun,
    loading,
    error,
    resourceErrors,
    reload: loadProject
  } = useProjectDetailData(projectId);
  const [downloadingMarkdown, setDownloadingMarkdown] = useState(false);
  const [downloadingPdf, setDownloadingPdf] = useState(false);
  const [selectedStage, setSelectedStage] = useState<number | null>(null);
  const workflowStatus = latestRun?.status ?? project?.status ?? 'CREATED';
  const specificationReady = deliveryReadiness?.specReady === true;
  const deliverable = isDeliveryReady(deliveryReadiness);
  const openReviewIssues = review?.issues.filter((issue) =>
    issue.status === 'OPEN' || issue.status === 'IN_PROGRESS'
  ) ?? [];
  const inferredStage = useMemo(() => {
    if (specificationReady || deliverable) return 3;
    if (approvals.some((approval) => approval.status === 'PENDING') || openReviewIssues.length > 0) return 2;
    if (latestRun) return 1;
    return 0;
  }, [approvals, deliverable, latestRun, openReviewIssues.length, specificationReady]);
  const activeStage = selectedStage ?? inferredStage;

  async function handleApprovalDecision(approvalId: number, payload: ApprovalDecisionPayload) {
    try {
      await decideWorkflowApproval(approvalId, payload);
      message.success('Workflow decision applied');
      await loadProject();
    } catch (decisionError) {
      await loadProject().catch(() => undefined);
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

  async function handleCancelRun(runId: number): Promise<WorkflowRunResponse> {
    try {
      const cancelled = await cancelWorkflowRun(projectId, runId);
      message.success(`Workflow run #${runId} cancelled`);
      await loadProject();
      return cancelled;
    } catch (cancelError) {
      message.error(cancelError instanceof Error ? cancelError.message : 'Cancellation failed');
      throw cancelError;
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
    return getWorkflowRunNodes(runId);
  }

  async function handleLoadMetrics(runId: number): Promise<WorkflowRuntimeMetricsResponse> {
    return getWorkflowRunMetrics(runId);
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

  if (loading && !project) {
    return (
      <main className="workspace center-pane" id="main-content">
        <Spin size="large" />
      </main>
    );
  }

  if (error && !project) {
    return (
      <main className="workspace" id="main-content">
        <Result
          status="warning"
          title={error}
          extra={(
            <Space>
              <Button icon={<ReloadOutlined />} onClick={() => void loadProject()}>Retry</Button>
              <Button href="/">Back</Button>
            </Space>
          )}
        />
      </main>
    );
  }

  return (
    <main className="workspace detail-stack" id="main-content">
      {error ? (
        <Alert
          type="error"
          showIcon
          message="Some core project data could not be refreshed"
          description={error}
          action={<Button size="small" onClick={() => void loadProject()}>Retry</Button>}
        />
      ) : null}
      {Object.keys(resourceErrors).length > 0 ? (
        <Alert
          type="warning"
          showIcon
          message="Some project data is temporarily unavailable"
          description={Object.entries(resourceErrors)
            .map(([name, reason]) => `${name}: ${reason}`)
            .join(' · ')}
          action={<Button size="small" onClick={() => void loadProject()}>Retry all</Button>}
        />
      ) : null}
      <section className="page-toolbar">
        <div>
          <Typography.Title level={1}>{project?.name ?? `Project #${projectId}`}</Typography.Title>
          <Space wrap>
            <Tag color={workflowStatus === 'COMPLETED' ? 'green' : 'blue'}>{workflowStatus}</Tag>
            {latestRun?.qualityProfile ? <Tag>{latestRun.qualityProfile}</Tag> : null}
            <Typography.Text className="muted">V5 canonical workflow</Typography.Text>
          </Space>
        </div>
        <Space wrap>
          <Button
            icon={<DownloadOutlined />}
            loading={downloadingMarkdown}
            onClick={handleExportMarkdown}
            disabled={!deliverable}
          >
            Export Markdown
          </Button>
          <Button
            type="primary"
            icon={<FilePdfOutlined />}
            loading={downloadingPdf}
            onClick={handleExportPdf}
            disabled={!deliverable}
          >
            Export PDF
          </Button>
        </Space>
      </section>

      <section className="panel stage-navigation" aria-label="Project stages">
        <Steps
          current={activeStage}
          onChange={setSelectedStage}
          responsive
          items={[
            { title: 'Intake', description: 'Requirement baseline' },
            { title: 'Generate', description: 'Agents and approvals' },
            { title: 'Review & fix', description: 'Quality decisions' },
            { title: 'Deliver', description: 'Approved outputs' }
          ]}
        />
      </section>

      {activeStage === 0 ? (
        <section className="panel intake-stage" aria-labelledby="intake-title">
          <Typography.Title level={2} id="intake-title">Requirement baseline</Typography.Title>
          <Alert
            type="info"
            showIcon
            message="This requirement is frozen into every workflow run"
            description="Use a new run or immutable replay for changes; previous outputs stay available."
          />
          <Typography.Paragraph className="requirement-baseline">
            {project?.originalRequirement}
          </Typography.Paragraph>
          <Descriptions size="small" column={{ xs: 1, md: 3 }}>
            <Descriptions.Item label="Project status">{project?.status}</Descriptions.Item>
            <Descriptions.Item label="Latest workflow">{latestRun ? `#${latestRun.id}` : 'Not started'}</Descriptions.Item>
            <Descriptions.Item label="Quality profile">{latestRun?.qualityProfile ?? 'Choose at generation'}</Descriptions.Item>
          </Descriptions>
          <Button type="primary" onClick={() => setSelectedStage(1)}>Continue to generation</Button>
        </section>
      ) : null}

      {activeStage === 1 ? (
        <WorkflowReplayPanel
          projectId={projectId}
          requirement={project?.originalRequirement ?? ''}
          runs={workflowRuns}
          versions={workflowVersions}
          onStart={handleStartWorkflow}
          onReplay={handleReplay}
          onCancel={handleCancelRun}
          onLoadTimeline={handleLoadTimeline}
          onLoadMetrics={handleLoadMetrics}
        />
      ) : null}

      {activeStage === 2 ? (
        <>
          <WorkflowApprovalPanel approvals={approvals} artifacts={artifacts} onDecide={handleApprovalDecision} />
          <ReviewIssueTable
            projectId={projectId}
            review={review}
            loadError={resourceErrors.review}
            artifacts={artifacts}
            onChanged={loadProject}
            onRetry={loadProject}
          />
        </>
      ) : null}

      {activeStage === 3 ? (
        <>
          {!deliverable ? (
            <Alert
              type={specificationReady ? 'info' : 'warning'}
              showIcon
              message={specificationReady ? 'Build verification is required' : 'Specification gate is blocked'}
              description={deliveryReadiness?.blockers.join(' · ')
                ?? resourceErrors.readiness
                ?? 'Complete the workflow, approvals, and blocking review findings.'}
            />
          ) : null}
          {specificationReady ? (
            <CodeExportPanel
              projectId={projectId}
              disabled={false}
              onGenerated={loadProject}
            />
          ) : null}
          <ArtifactTabs projectId={projectId} artifacts={artifacts} onChanged={loadProject} />
        </>
      ) : null}
    </main>
  );
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

export function isDeliveryReady(readiness: DeliveryReadinessResponse | null): boolean {
  return readiness?.specReady === true
    && readiness.buildReady === true
    && readiness.status === 'READY';
}

export default ProjectDetailPage;
