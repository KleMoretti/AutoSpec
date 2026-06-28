import { CheckCircleOutlined, ClockCircleOutlined, LoadingOutlined, ReloadOutlined } from '@ant-design/icons';
import { Button, Progress, Space, Steps, Typography } from 'antd';
import type { ProjectProgressResponse } from '../api/projects';

const AGENT_LABELS: Record<string, string> = {
  ProductManagerAgent_v1: 'Product Manager',
  ProductManagerAgent_v2: 'Product Manager',
  BackendEngineerAgent_v1: 'Backend Engineer',
  BackendEngineerAgent_v2: 'Backend Engineer',
  ArchitectAgent_v1: 'Architect',
  FrontendEngineerAgent_v1: 'Frontend Engineer',
  ReviewerAgent_v1: 'Reviewer',
  ReviewerAgent_v2: 'Reviewer',
  product_manager: 'Product Manager',
  backend_engineer: 'Backend Engineer',
  architect: 'Architect',
  frontend_engineer: 'Frontend Engineer',
  reviewer: 'Reviewer'
};

const DEFAULT_STEP_KEYS = [
  'ProductManagerAgent_v1',
  'BackendEngineerAgent_v1',
  'ReviewerAgent_v1',
  'architect',
  'frontend_engineer'
];

interface AgentTimelineProps {
  progress: ProjectProgressResponse | null;
  onRetryTask?: (taskId: number) => void;
  retryingTaskId?: number | null;
}

function AgentTimeline({ progress, onRetryTask, retryingTaskId }: AgentTimelineProps) {
  const percent = progress?.percent ?? 0;
  const steps = progress?.steps ?? [];
  const stepKeys = Array.from(
    new Set([...DEFAULT_STEP_KEYS, ...steps.map((step) => step.nodeName ?? step.agentName).filter(Boolean)])
  );

  return (
    <section className="panel">
      <div className="section-heading">
        <Typography.Title level={2}>Agent progress</Typography.Title>
        <Typography.Text className="muted">{progress?.currentAgent ?? 'PENDING'}</Typography.Text>
      </div>
      <Space direction="vertical" size={20} className="full-width">
        <Progress percent={percent} status={percent >= 100 ? 'success' : 'active'} />
        <Steps
          responsive
          items={stepKeys.map((agentName) => {
            const step = steps.find((item) => item.agentName === agentName || item.nodeName === agentName);
            const status = step?.status ?? 'PENDING';
            return {
              title: AGENT_LABELS[agentName] ?? agentName,
              description: (
                <Space direction="vertical" size={4}>
                  <Typography.Text type={status === 'FAILED' ? 'danger' : undefined}>{status}</Typography.Text>
                  {step?.durationMs ? <Typography.Text className="muted">{step.durationMs} ms</Typography.Text> : null}
                  {step?.errorMessage ? <Typography.Text type="danger">{step.errorMessage}</Typography.Text> : null}
                  {status === 'FAILED' && step?.taskId && onRetryTask ? (
                    <Button
                      size="small"
                      icon={<ReloadOutlined />}
                      loading={retryingTaskId === step.taskId}
                      onClick={() => onRetryTask(step.taskId as number)}
                    >
                      Retry
                    </Button>
                  ) : null}
                </Space>
              ),
              status: toStepStatus(status),
              icon: toIcon(status)
            };
          })}
        />
      </Space>
    </section>
  );
}

function toStepStatus(status: string): 'wait' | 'process' | 'finish' | 'error' {
  if (status === 'SUCCEEDED') {
    return 'finish';
  }
  if (status === 'FAILED') {
    return 'error';
  }
  if (status === 'RUNNING') {
    return 'process';
  }
  return 'wait';
}

function toIcon(status: string) {
  if (status === 'SUCCEEDED') {
    return <CheckCircleOutlined />;
  }
  if (status === 'RUNNING') {
    return <LoadingOutlined />;
  }
  return <ClockCircleOutlined />;
}

export default AgentTimeline;
