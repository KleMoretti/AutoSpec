import { ReloadOutlined } from '@ant-design/icons';
import { Alert, Button, Card, Col, Collapse, Empty, Row, Space, Statistic, Tag, Typography } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  getEvaluationComparison,
  type EvaluationComparisonResponse,
  type EvaluationMetricResponse,
  type EvaluationRunResponse
} from '../api/evaluation';

function metric(run: EvaluationRunResponse, name: string): EvaluationMetricResponse | undefined {
  return run.metrics.find((candidate) => candidate.name === name);
}

function metricValue(run: EvaluationRunResponse, name: string): number | null {
  const value = metric(run, name);
  return value?.status === 'MEASURED' && value.value != null ? value.value : null;
}

function displayMetric(run: EvaluationRunResponse, name: string, unit: string, unknown: string): string {
  const value = metricValue(run, name);
  if (value == null) return unknown;
  if (unit === 'ratio') return `${(value * 100).toFixed(1)}%`;
  if (unit === 'currency') return value.toFixed(4);
  return `${value.toFixed(1)} ${unit}`;
}

function interval(run: EvaluationRunResponse, unknown: string): string {
  const value = metric(run, 'gate_pass_rate');
  if (value?.status !== 'MEASURED' || value.intervalLow == null || value.intervalHigh == null) return unknown;
  return `${(value.intervalLow * 100).toFixed(1)}%–${(value.intervalHigh * 100).toFixed(1)}%`;
}

function sampleCount(run: EvaluationRunResponse): number | null {
  return run.status === 'NOT_EXECUTED' ? null : run.caseResults.length;
}

function EvaluationRunCard({ run, unknown }: { run: EvaluationRunResponse; unknown: string }) {
  const { t } = useTranslation();
  const passRate = metricValue(run, 'gate_pass_rate');
  const sample = sampleCount(run);
  const statusColor = run.status === 'SUCCEEDED' ? 'green' : run.status === 'NOT_EXECUTED' ? 'gold' : 'red';

  return (
    <Card size="small" title={<Space><Tag>{run.group}</Tag><span>{run.groupName}</span></Space>}>
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <Space wrap>
          <Tag color={statusColor}>{run.status}</Tag>
          <Tag>{run.executionMode}</Tag>
          <Tag>{run.datasetSplit ?? unknown}</Tag>
          <Typography.Text type="secondary">{run.runId}</Typography.Text>
        </Space>
        <Row gutter={[12, 12]}>
          <Col xs={12} md={6}><Statistic title={t('evaluation.sampleCount')} value={sample == null ? unknown : sample} /></Col>
          <Col xs={12} md={6}><Statistic title={t('evaluation.passRate')} value={displayMetric(run, 'gate_pass_rate', 'ratio', unknown)} /></Col>
          <Col xs={12} md={6}><Statistic title={t('evaluation.passInterval')} value={interval(run, unknown)} /></Col>
          <Col xs={12} md={6}><Statistic title={t('evaluation.p95Latency')} value={displayMetric(run, 'p95_latency_ms', 'ms', unknown)} /></Col>
          <Col xs={12} md={6}><Statistic title={t('evaluation.tokens')} value={displayMetric(run, 'tokens_per_run', 'tokens', unknown)} /></Col>
          <Col xs={12} md={6}><Statistic title={t('evaluation.cost')} value={displayMetric(run, 'cost_per_run', 'currency', unknown)} /></Col>
          <Col xs={12} md={6}><Statistic title={t('evaluation.mustCoverage')} value={displayMetric(run, 'must_trace_coverage', 'ratio', unknown)} /></Col>
          <Col xs={12} md={6}><Statistic title={t('evaluation.blockingIssues')} value={displayMetric(run, 'blocking_issue_median', 'count', unknown)} /></Col>
        </Row>
        {passRate == null ? (
          <Alert type="warning" showIcon message={run.notExecutedReason ?? t('evaluation.notEvaluated')} />
        ) : null}
        {run.caseResults.length > 0 ? (
          <Collapse items={[{
            key: 'cases',
            label: t('evaluation.caseDetails', { count: run.caseResults.length }),
            children: (
              <Space direction="vertical" style={{ width: '100%' }}>
                {run.caseResults.map((caseResult) => (
                  <Card key={`${caseResult.caseId}-${caseResult.repetition}`} size="small">
                    <Space wrap>
                      <Typography.Text strong>{caseResult.caseId}</Typography.Text>
                      <Tag>{t('evaluation.repetition', { value: caseResult.repetition })}</Tag>
                      <Tag color={caseResult.gatePass === true ? 'green' : caseResult.gatePass === false ? 'red' : 'gold'}>
                        {caseResult.gatePass == null ? unknown : caseResult.gatePass ? t('evaluation.passed') : t('evaluation.failed')}
                      </Tag>
                      {caseResult.workflowRunId ? <Typography.Text type="secondary">run {caseResult.workflowRunId}</Typography.Text> : null}
                      {caseResult.traceId ? <Typography.Text type="secondary">trace {caseResult.traceId}</Typography.Text> : null}
                    </Space>
                  </Card>
                ))}
              </Space>
            )
          }]} />
        ) : null}
      </Space>
    </Card>
  );
}

export function EvaluationComparisonView({ comparison }: { comparison: EvaluationComparisonResponse }) {
  const { t } = useTranslation();
  const measuredRuns = useMemo(
    () => comparison.matrix.runs.filter((run) => run.status === 'SUCCEEDED' && run.gateStatus !== 'NOT_EVALUATED').length,
    [comparison]
  );

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Space wrap>
        <Tag color={comparison.status === 'MEASURED' ? 'green' : 'gold'}>{comparison.status}</Tag>
        <Tag>{comparison.source}</Tag>
        <Typography.Text>{comparison.matrix.datasetVersion}</Typography.Text>
        <Typography.Text type="secondary">{comparison.matrix.datasetSplit ?? t('evaluation.splitUnknown')}</Typography.Text>
        <Typography.Text type="secondary">{t('evaluation.measuredGroups', { count: measuredRuns })}</Typography.Text>
      </Space>
      {comparison.status === 'NOT_EVALUATED' ? (
        <Alert
          type="warning"
          showIcon
          message={t('evaluation.notEvaluated')}
          description={comparison.notEvaluatedReason ?? comparison.decision.reasons.join(' · ')}
        />
      ) : (
        <Alert
          type={comparison.decision.decision === 'PROMOTE' ? 'success' : 'warning'}
          showIcon
          message={comparison.decision.decision}
          description={comparison.decision.reasons.join(' · ')}
        />
      )}
      {comparison.matrix.runs.length === 0 ? (
        <Empty description={t('evaluation.noGroups')} />
      ) : (
        <Row gutter={[16, 16]}>
          {comparison.matrix.runs.map((run) => (
            <Col xs={24} md={12} xl={6} key={run.runId}>
              <EvaluationRunCard run={run} unknown={t('evaluation.unknown')} />
            </Col>
          ))}
        </Row>
      )}
    </Space>
  );
}

function EvaluationComparisonPanel() {
  const { t } = useTranslation();
  const [comparison, setComparison] = useState<EvaluationComparisonResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [hidden, setHidden] = useState(false);

  async function load() {
    setLoading(true);
    try {
      setComparison(await getEvaluationComparison());
      setError(null);
      setHidden(false);
    } catch (loadError) {
      const message = loadError instanceof Error ? loadError.message : t('evaluation.loadFailed');
      if (message.includes('403')) {
        setHidden(true);
      } else {
        setError(message);
      }
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void load();
  }, []);

  if (hidden) return null;

  return (
    <section className="panel" aria-labelledby="evaluation-comparison-title">
      <div className="section-heading">
        <div>
          <Typography.Title level={2} id="evaluation-comparison-title">{t('evaluation.title')}</Typography.Title>
          <Typography.Text className="muted">{t('evaluation.description')}</Typography.Text>
        </div>
        <Button icon={<ReloadOutlined />} onClick={() => void load()} loading={loading}>{t('common.retry')}</Button>
      </div>
      {error ? <Alert type="error" showIcon message={t('evaluation.loadFailed')} description={error} /> : null}
      {loading && !comparison ? <Typography.Paragraph>{t('evaluation.loading')}</Typography.Paragraph> : null}
      {!loading && !error && comparison ? <EvaluationComparisonView comparison={comparison} /> : null}
    </section>
  );
}

export default EvaluationComparisonPanel;
