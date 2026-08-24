import {
  ArrowLeftOutlined,
  CheckCircleOutlined,
  ClockCircleOutlined,
  PlusOutlined,
  ProjectOutlined,
  WarningOutlined
} from '@ant-design/icons';
import {
  Alert,
  Button,
  Card,
  Col,
  Empty,
  Form,
  Input,
  List,
  Row,
  Select,
  Skeleton,
  Space,
  Statistic,
  Tag,
  Typography,
  message
} from 'antd';
import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import {
  createProject,
  getProjectDashboard,
  type ProjectDashboardItemResponse
} from '../api/projects';

const { TextArea, Search } = Input;

interface FormValues {
  name: string;
  requirement: string;
}

function HomePage() {
  const navigate = useNavigate();
  const [projects, setProjects] = useState<ProjectDashboardItemResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [creating, setCreating] = useState(false);
  const [query, setQuery] = useState('');
  const [status, setStatus] = useState('ALL');

  useEffect(() => {
    void loadProjects();
  }, []);

  const filtered = useMemo(() => {
    const normalized = query.trim().toLowerCase();
    return projects.filter((project) => {
      const workflowStatus = project.latestWorkflowStatus ?? project.projectStatus;
      const matchesStatus = status === 'ALL' || workflowStatus === status;
      const matchesQuery = !normalized
        || project.name.toLowerCase().includes(normalized)
        || project.requirementSummary.toLowerCase().includes(normalized);
      return matchesStatus && matchesQuery;
    });
  }, [projects, query, status]);

  async function loadProjects() {
    setLoading(true);
    try {
      setProjects(await getProjectDashboard());
      setLoadError(null);
    } catch (error) {
      setLoadError(error instanceof Error ? error.message : 'Unable to load projects');
    } finally {
      setLoading(false);
    }
  }

  async function handleFinish(values: FormValues) {
    setSubmitting(true);
    try {
      const project = await createProject(values);
      message.success('Project created');
      navigate(`/projects/${project.projectId}`);
    } catch (error) {
      message.error(error instanceof Error ? error.message : 'Project creation failed');
    } finally {
      setSubmitting(false);
    }
  }

  if (creating) {
    return (
      <main className="workspace" id="main-content">
        <Button
          type="text"
          icon={<ArrowLeftOutlined />}
          onClick={() => setCreating(false)}
          className="back-action"
        >
          Back to projects
        </Button>
        <section className="panel input-panel create-project-panel">
          <Typography.Title level={1}>Create a project</Typography.Title>
          <Typography.Paragraph className="muted readable-copy">
            Describe the outcome you need. AutoSpec will turn it into a versioned specification,
            review it, and keep every approval and revision traceable.
          </Typography.Paragraph>
          <Form layout="vertical" onFinish={handleFinish} requiredMark="optional">
            <Form.Item
              name="name"
              label="Project name"
              rules={[{ required: true, message: 'Project name is required' }]}
            >
              <Input size="large" autoComplete="off" />
            </Form.Item>
            <Form.Item
              name="requirement"
              label="Requirement"
              extra="Include users, desired outcome, must-have scope, constraints, and success criteria when known."
              rules={[{ required: true, message: 'Requirement is required' }]}
            >
              <TextArea rows={10} showCount maxLength={2000} />
            </Form.Item>
            <Button type="primary" htmlType="submit" size="large" loading={submitting}>
              Create project
            </Button>
          </Form>
        </section>
      </main>
    );
  }

  const runningCount = projects.filter((project) =>
    ['RUNNING', 'PENDING'].includes(project.latestWorkflowStatus ?? '')
  ).length;
  const pendingCount = projects.reduce((sum, project) => sum + project.pendingApprovalCount, 0);
  const blockerCount = projects.reduce((sum, project) => sum + project.blockingReviewIssueCount, 0);

  return (
    <main className="workspace dashboard-stack" id="main-content">
      <section className="dashboard-hero">
        <div>
          <Typography.Text className="eyebrow">Requirements-to-contract workspace</Typography.Text>
          <Typography.Title level={1}>Projects</Typography.Title>
          <Typography.Paragraph className="muted readable-copy">
            Track work waiting for approval, quality blockers, and approved deliverables from one place.
          </Typography.Paragraph>
        </div>
        <Button type="primary" size="large" icon={<PlusOutlined />} onClick={() => setCreating(true)}>
          New project
        </Button>
      </section>

      <Row gutter={[16, 16]} aria-label="Project overview">
        <Col xs={12} md={6}>
          <Card className="metric-card">
            <Statistic title="Projects" value={projects.length} prefix={<ProjectOutlined />} />
          </Card>
        </Col>
        <Col xs={12} md={6}>
          <Card className="metric-card">
            <Statistic title="Running" value={runningCount} prefix={<ClockCircleOutlined />} />
          </Card>
        </Col>
        <Col xs={12} md={6}>
          <Card className="metric-card">
            <Statistic title="Awaiting approval" value={pendingCount} prefix={<CheckCircleOutlined />} />
          </Card>
        </Col>
        <Col xs={12} md={6}>
          <Card className="metric-card metric-card-warning">
            <Statistic title="Quality blockers" value={blockerCount} prefix={<WarningOutlined />} />
          </Card>
        </Col>
      </Row>

      <section className="panel project-list-panel" aria-labelledby="recent-projects-title">
        <div className="section-heading project-list-heading">
          <div>
            <Typography.Title level={2} id="recent-projects-title">Recent projects</Typography.Title>
            <Typography.Text className="muted">Open a project to continue from its current stage.</Typography.Text>
          </div>
          <Space wrap className="project-filters">
            <Search
              aria-label="Search projects"
              allowClear
              placeholder="Search projects"
              onChange={(event) => setQuery(event.target.value)}
            />
            <Select
              aria-label="Filter by workflow status"
              value={status}
              onChange={setStatus}
              options={[
                { value: 'ALL', label: 'All statuses' },
                { value: 'RUNNING', label: 'Running' },
                { value: 'WAITING_APPROVAL', label: 'Waiting approval' },
                { value: 'COMPLETED', label: 'Completed' },
                { value: 'FAILED', label: 'Failed' }
              ]}
            />
          </Space>
        </div>

        {loadError ? (
          <Alert
            type="error"
            showIcon
            message="Projects could not be loaded"
            description={loadError}
            action={<Button onClick={() => void loadProjects()}>Retry</Button>}
          />
        ) : null}
        {loading ? <Skeleton active paragraph={{ rows: 6 }} /> : null}
        {!loading && !loadError && filtered.length === 0 ? (
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description={projects.length === 0 ? 'No projects yet' : 'No projects match these filters'}
          >
            {projects.length === 0 ? (
              <Button type="primary" onClick={() => setCreating(true)}>Create the first project</Button>
            ) : null}
          </Empty>
        ) : null}
        {!loading && !loadError && filtered.length > 0 ? (
          <List
            className="project-grid"
            grid={{ gutter: 16, xs: 1, sm: 1, md: 2, lg: 2, xl: 3 }}
            dataSource={filtered}
            renderItem={(project) => (
              <List.Item>
                <Card
                  className="project-card"
                  title={<Link to={`/projects/${project.projectId}`}>{project.name}</Link>}
                  extra={<Tag color={statusColor(project.latestWorkflowStatus ?? project.projectStatus)}>
                    {project.latestWorkflowStatus ?? project.projectStatus}
                  </Tag>}
                >
                  <Typography.Paragraph ellipsis={{ rows: 3 }} className="project-requirement">
                    {project.requirementSummary}
                  </Typography.Paragraph>
                  <div className="project-card-metrics">
                    <span><strong>{project.pendingApprovalCount}</strong> approvals</span>
                    <span><strong>{project.blockingReviewIssueCount}</strong> blockers</span>
                    <span><strong>{project.qualityScore ?? '--'}</strong> quality</span>
                    <span><strong>${Number(project.estimatedCost ?? 0).toFixed(4)}</strong> cost</span>
                  </div>
                  <Button block><Link to={`/projects/${project.projectId}`}>Open project</Link></Button>
                </Card>
              </List.Item>
            )}
          />
        ) : null}
      </section>
    </main>
  );
}

function statusColor(status: string) {
  if (status === 'COMPLETED' || status === 'SUCCEEDED') return 'green';
  if (status === 'FAILED' || status === 'CANCELLED') return 'red';
  if (status === 'RUNNING' || status === 'QUEUED') return 'blue';
  if (status.includes('APPROVAL')) return 'gold';
  return 'default';
}

export default HomePage;
