import { CheckCircleOutlined, QuestionCircleOutlined } from '@ant-design/icons';
import { Alert, Button, Card, Checkbox, Divider, Input, List, Radio, Space, Tag, Typography, message } from 'antd';
import { useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  type ClarificationResponsePayload,
  type WorkflowClarificationResponse,
  getWorkflowClarifications,
  respondToWorkflowClarification
} from '../api/workflow';

interface RequirementClarificationPanelProps {
  runId?: number;
  canEdit?: boolean;
  onChanged?: () => Promise<void>;
  onCancel?: (runId: number) => Promise<unknown>;
}

function RequirementClarificationPanel({
  runId,
  canEdit = true,
  onChanged,
  onCancel
}: RequirementClarificationPanelProps) {
  const { t } = useTranslation();
  const [items, setItems] = useState<WorkflowClarificationResponse[]>([]);
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);

  async function load() {
    if (!runId) {
      setItems([]);
      return;
    }
    setLoading(true);
    try {
      setItems(await getWorkflowClarifications(runId));
      setLoadError(null);
    } catch (error) {
      setLoadError(error instanceof Error ? error.message : t('clarification.loadFailed'));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void load();
  }, [runId]);

  const pending = useMemo(
    () => items.find((item) => item.status === 'PENDING'),
    [items]
  );

  if (!runId || (!pending && items.length === 0 && !loadError)) {
    return null;
  }

  return (
    <section className="panel" aria-labelledby="clarification-title">
      <Space direction="vertical" size={16} className="full-width">
        <Space>
          <QuestionCircleOutlined />
          <Typography.Title level={3} id="clarification-title">
            {t('clarification.title')}
          </Typography.Title>
        </Space>
        {loadError ? <Alert type="error" showIcon message={t('clarification.loadFailed')} description={loadError} /> : null}
        {pending ? (
          <ClarificationCard
            item={pending}
            canEdit={canEdit}
            submitting={loading}
            onSubmit={async (payload) => {
              setLoading(true);
              try {
                await respondToWorkflowClarification(runId, pending.id, payload);
                message.success(t('clarification.submitted'));
                await load();
                await onChanged?.();
              } catch (error) {
                message.error(error instanceof Error ? error.message : t('clarification.submitFailed'));
                throw error;
              } finally {
                setLoading(false);
              }
            }}
            onCancel={onCancel && canEdit ? () => onCancel(runId) : undefined}
          />
        ) : items.length > 0 ? (
          <Alert
            type="success"
            showIcon
            icon={<CheckCircleOutlined />}
            message={t('clarification.resolved')}
            description={t('clarification.resolvedDescription')}
          />
        ) : null}
      </Space>
    </section>
  );
}

interface ClarificationCardProps {
  item: WorkflowClarificationResponse;
  canEdit: boolean;
  submitting: boolean;
  onSubmit: (payload: ClarificationResponsePayload) => Promise<void>;
  onCancel?: () => Promise<unknown>;
}

function ClarificationCard({ item, canEdit, submitting, onSubmit, onCancel }: ClarificationCardProps) {
  const { t } = useTranslation();
  const [answers, setAnswers] = useState<Record<string, string | string[]>>({});
  const [acceptedAssumptions, setAcceptedAssumptions] = useState<string[]>([]);
  const idempotencyKey = useRef(createIdempotencyKey());
  const blockingQuestions = item.request.questions.filter((question) => question.blocking);
  const missingBlocking = blockingQuestions.some((question) => {
    const value = answers[question.question_id];
    return value === undefined || (typeof value === 'string' && !value.trim()) || (Array.isArray(value) && value.length === 0);
  });

  async function submit() {
    if (!canEdit || missingBlocking) return;
    await onSubmit({
      expected_lock_version: item.lockVersion,
      idempotency_key: idempotencyKey.current,
      answers: Object.entries(answers).map(([question_id, value]) => ({ question_id, value })),
      accepted_assumption_ids: acceptedAssumptions,
      conflict_resolutions: []
    });
  }

  return (
    <Card size="small" title={<Space wrap><Tag color="gold">{t('clarification.round', { round: item.round })}</Tag><Typography.Text strong>{item.request.summary}</Typography.Text></Space>}>
      <Space direction="vertical" size={16} className="full-width">
        <Alert
          type="info"
          showIcon
          message={t('clarification.originalRequirement')}
          description={item.request.original_requirement_ref}
        />
        <List
          header={<Typography.Text strong>{t('clarification.questions')}</Typography.Text>}
          dataSource={item.request.questions}
          renderItem={(question) => (
            <List.Item>
              <Space direction="vertical" size={8} className="full-width">
                <Typography.Text strong>
                  {question.question}
                  {question.blocking ? <Tag color="red" className="clarification-required-tag">{t('clarification.required')}</Tag> : null}
                </Typography.Text>
                <Typography.Text type="secondary">{question.reason}</Typography.Text>
                {question.options.length > 0 ? (
                  <Radio.Group
                    disabled={!canEdit}
                    value={answers[question.question_id]}
                    onChange={(event) => setAnswers((current) => ({ ...current, [question.question_id]: event.target.value }))}
                  >
                    <Space direction="vertical">
                      {question.options.map((option) => <Radio key={option} value={option}>{option}</Radio>)}
                    </Space>
                  </Radio.Group>
                ) : (
                  <Input.TextArea
                    disabled={!canEdit}
                    aria-label={question.question}
                    value={answers[question.question_id] ?? ''}
                    autoSize={{ minRows: 2, maxRows: 5 }}
                    onChange={(event) => setAnswers((current) => ({ ...current, [question.question_id]: event.target.value }))}
                  />
                )}
              </Space>
            </List.Item>
          )}
        />
        {item.request.assumptions.length > 0 ? (
          <>
            <Divider orientation="left">{t('clarification.assumptions')}</Divider>
            <Space direction="vertical">
              {item.request.assumptions.map((assumption) => (
                <Checkbox
                  key={assumption.assumption_id}
                  disabled={!canEdit}
                  checked={acceptedAssumptions.includes(assumption.assumption_id)}
                  onChange={(event) => setAcceptedAssumptions((current) => event.target.checked
                    ? [...current, assumption.assumption_id]
                    : current.filter((id) => id !== assumption.assumption_id))}
                >
                  <Typography.Text strong>{assumption.statement}</Typography.Text>
                  <Typography.Text type="secondary"> — {assumption.impact}</Typography.Text>
                </Checkbox>
              ))}
            </Space>
          </>
        ) : null}
        {item.request.context_conflicts.length > 0 ? (
          <Alert
            type="warning"
            showIcon
            message={t('clarification.conflicts')}
            description={item.request.context_conflicts.map((conflict) => `${conflict.fact_key}: ${conflict.reason}`).join(' · ')}
          />
        ) : null}
        {missingBlocking && canEdit ? <Typography.Text type="danger">{t('clarification.missingRequired')}</Typography.Text> : null}
        <Space wrap>
          <Button type="primary" loading={submitting} disabled={!canEdit || missingBlocking} onClick={() => void submit()}>
            {t('clarification.submitAndContinue')}
          </Button>
          {onCancel ? <Button danger disabled={submitting} onClick={() => void onCancel()}>{t('clarification.cancelRun')}</Button> : null}
          {!canEdit ? <Typography.Text type="secondary">{t('clarification.readOnly')}</Typography.Text> : null}
        </Space>
      </Space>
    </Card>
  );
}

function createIdempotencyKey(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) return crypto.randomUUID();
  return `clarification-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

export default RequirementClarificationPanel;
