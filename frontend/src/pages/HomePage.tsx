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
import { useTranslation } from 'react-i18next';
import { Link, useNavigate } from 'react-router-dom';
import {
  createProject,
  getProjectDashboard,
  type ProjectDashboardItemResponse
} from '../api/projects';
import { formatUsd, translateEnum } from '../i18n/formatters';

const { TextArea, Search } = Input;

interface FormValues {
  name: string;
  requirement: string;
}

function HomePage() {
  const { t, i18n } = useTranslation();
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
      setLoadError(error instanceof Error ? error.message : t('home.list.loadFailed'));
    } finally {
      setLoading(false);
    }
  }

  async function handleFinish(values: FormValues) {
    setSubmitting(true);
    try {
      const project = await createProject(values);
      message.success(t('home.create.success'));
      navigate(`/projects/${project.projectId}`);
    } catch (error) {
      message.error(error instanceof Error ? error.message : t('home.create.failed'));
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
          {t('home.backToProjects')}
        </Button>
        <section className="panel input-panel create-project-panel">
          <Typography.Title level={1}>{t('home.create.title')}</Typography.Title>
          <Typography.Paragraph className="muted readable-copy">
            {t('home.create.description')}
          </Typography.Paragraph>
          <Form layout="vertical" onFinish={handleFinish} requiredMark="optional">
            <Form.Item
              name="name"
              label={t('home.create.name')}
              rules={[{ required: true, message: t('home.create.nameRequired') }]}
            >
              <Input size="large" autoComplete="off" />
            </Form.Item>
            <Form.Item
              name="requirement"
              label={t('home.create.requirement')}
              extra={t('home.create.requirementHelp')}
              rules={[{ required: true, message: t('home.create.requirementRequired') }]}
            >
              <TextArea rows={10} showCount maxLength={2000} />
            </Form.Item>
            <Button type="primary" htmlType="submit" size="large" loading={submitting}>
              {t('home.create.submit')}
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
          <Typography.Text className="eyebrow">{t('home.hero.eyebrow')}</Typography.Text>
          <Typography.Title level={1}>{t('home.hero.title')}</Typography.Title>
          <Typography.Paragraph className="muted readable-copy">
            {t('home.hero.description')}
          </Typography.Paragraph>
        </div>
        <Button type="primary" size="large" icon={<PlusOutlined />} onClick={() => setCreating(true)}>
          {t('home.hero.newProject')}
        </Button>
      </section>

      <Row gutter={[16, 16]} aria-label={t('home.overviewLabel')}>
        <Col xs={12} md={6}>
          <Card className="metric-card">
            <Statistic title={t('home.metrics.projects')} value={projects.length} prefix={<ProjectOutlined />} />
          </Card>
        </Col>
        <Col xs={12} md={6}>
          <Card className="metric-card">
            <Statistic title={t('home.metrics.running')} value={runningCount} prefix={<ClockCircleOutlined />} />
          </Card>
        </Col>
        <Col xs={12} md={6}>
          <Card className="metric-card">
            <Statistic title={t('home.metrics.awaitingApproval')} value={pendingCount} prefix={<CheckCircleOutlined />} />
          </Card>
        </Col>
        <Col xs={12} md={6}>
          <Card className="metric-card metric-card-warning">
            <Statistic title={t('home.metrics.qualityBlockers')} value={blockerCount} prefix={<WarningOutlined />} />
          </Card>
        </Col>
      </Row>

      <section className="panel project-list-panel" aria-labelledby="recent-projects-title">
        <div className="section-heading project-list-heading">
          <div>
            <Typography.Title level={2} id="recent-projects-title">{t('home.list.title')}</Typography.Title>
            <Typography.Text className="muted">{t('home.list.description')}</Typography.Text>
          </div>
          <Space wrap className="project-filters">
            <Search
              aria-label={t('home.list.searchLabel')}
              allowClear
              placeholder={t('home.list.searchPlaceholder')}
              onChange={(event) => setQuery(event.target.value)}
            />
            <Select
              aria-label={t('home.list.statusFilterLabel')}
              value={status}
              onChange={setStatus}
              options={[
                { value: 'ALL', label: translateEnum(t, 'status', 'ALL') },
                { value: 'RUNNING', label: translateEnum(t, 'status', 'RUNNING') },
                { value: 'WAITING_APPROVAL', label: translateEnum(t, 'status', 'WAITING_APPROVAL') },
                { value: 'COMPLETED', label: translateEnum(t, 'status', 'COMPLETED') },
                { value: 'FAILED', label: translateEnum(t, 'status', 'FAILED') }
              ]}
            />
          </Space>
        </div>

        {loadError ? (
          <Alert
            type="error"
            showIcon
            message={t('home.list.loadTitle')}
            description={loadError}
            action={<Button onClick={() => void loadProjects()}>{t('common.retry')}</Button>}
          />
        ) : null}
        {loading ? <Skeleton active paragraph={{ rows: 6 }} /> : null}
        {!loading && !loadError && filtered.length === 0 ? (
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description={projects.length === 0 ? t('home.list.noProjects') : t('home.list.noMatches')}
          >
            {projects.length === 0 ? (
              <Button type="primary" onClick={() => setCreating(true)}>{t('home.list.createFirst')}</Button>
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
                    {translateEnum(t, 'status', project.latestWorkflowStatus ?? project.projectStatus)}
                  </Tag>}
                >
                  <Typography.Paragraph ellipsis={{ rows: 3 }} className="project-requirement">
                    {project.requirementSummary}
                  </Typography.Paragraph>
                  <div className="project-card-metrics">
                    <span><strong>{project.pendingApprovalCount}</strong> {t('home.list.approvalLabel', { count: project.pendingApprovalCount })}</span>
                    <span><strong>{project.blockingReviewIssueCount}</strong> {t('home.list.blockerLabel', { count: project.blockingReviewIssueCount })}</span>
                    <span><strong>{project.qualityScore ?? '--'}</strong> {t('home.list.quality')}</span>
                    <span><strong>{formatUsd(Number(project.estimatedCost ?? 0), i18n.resolvedLanguage)}</strong> {t('home.list.cost')}</span>
                  </div>
                  <Button block><Link to={`/projects/${project.projectId}`}>{t('home.list.openProject')}</Link></Button>
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
