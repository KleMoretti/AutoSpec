import { CheckCircleOutlined, QuestionCircleOutlined } from '@ant-design/icons';
import { Alert, Button, Card, Checkbox, Divider, Input, List, Radio, Space, Tag, Typography, message } from 'antd';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
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
  runStatus?: string;
  refreshKey?: string;
  onChanged?: () => Promise<void>;
  onCancel?: (runId: number) => Promise<unknown>;
}

function RequirementClarificationPanel({
  runId,
  canEdit = false,
  runStatus,
  refreshKey,
  onChanged,
  onCancel
}: RequirementClarificationPanelProps) {
  const { t } = useTranslation();
  const [items, setItems] = useState<WorkflowClarificationResponse[]>([]);
  const [loading, setLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const loadSequence = useRef(0);
  const loadInFlight = useRef<{ runId: number; promise: Promise<void> } | null>(null);

  const load = useCallback((force = false): Promise<void> => {
    if (!runId) {
      setItems([]);
      return Promise.resolve();
    }
    if (!force && loadInFlight.current?.runId === runId) return loadInFlight.current.promise;
    const sequence = ++loadSequence.current;
    setLoading(true);
    const request = (async () => {
      try {
        const result = await getWorkflowClarifications(runId);
        if (sequence !== loadSequence.current) return;
        setItems(result);
        setLoadError(null);
      } catch (error) {
        if (sequence !== loadSequence.current) return;
        setLoadError(error instanceof Error ? error.message : t('clarification.loadFailed'));
      } finally {
        if (sequence === loadSequence.current) setLoading(false);
      }
    })();
    const tracked = request.finally(() => {
      if (loadInFlight.current?.promise === tracked) loadInFlight.current = null;
    });
    loadInFlight.current = { runId, promise: tracked };
    return tracked;
  }, [runId, t]);

  useEffect(() => {
    setItems([]);
    setLoadError(null);
    return () => { ++loadSequence.current; };
  }, [runId]);

  useEffect(() => { void load(); }, [load, refreshKey]);

  useEffect(() => {
    if (!runId || !['PENDING', 'RUNNING', 'WAITING_APPROVAL'].includes(runStatus ?? '')) return;
    const timer = window.setInterval(() => void load(), 2000);
    return () => window.clearInterval(timer);
  }, [load, runId, runStatus]);

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
        {loadError ? <Alert type="error" showIcon message={t('clarification.loadFailed')} description={loadError}
          action={<Button loading={loading} onClick={() => void load()}>{t('common.retry')}</Button>} /> : null}
        {pending ? (
          <ClarificationCard
            key={`${pending.id}:${pending.requestId}:${pending.lockVersion}`}
            item={pending}
            canEdit={canEdit}
            submitting={submitting}
            onSubmit={async (payload) => {
              setSubmitting(true);
              try {
                await respondToWorkflowClarification(runId, pending.id, payload);
                message.success(t('clarification.submitted'));
                await onChanged?.();
                await load(true);
              } catch (error) {
                message.error(error instanceof Error ? error.message : t('clarification.submitFailed'));
                await load(true);
              } finally {
                setSubmitting(false);
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
  const [conflictResolutions, setConflictResolutions] = useState<Record<string, { resolution: string; value?: string }>>({});
  const idempotencyKey = useRef(createIdempotencyKey());
  const blockingQuestions = item.request.questions.filter((question) => question.blocking);
  const missingBlocking = blockingQuestions.some((question) => {
    const value = answers[question.question_id];
    return value === undefined || (typeof value === 'string' && !value.trim()) || (Array.isArray(value) && value.length === 0);
  }) || item.request.context_conflicts.some((conflict) => {
    const choice = conflictResolutions[conflict.conflict_id];
    return conflict.blocking && conflict.status !== 'RESOLVED'
      && (!choice || (choice.resolution === 'CUSTOM' && !choice.value?.trim()));
  });
  const invalidCustom = Object.values(conflictResolutions).some((choice) => choice.resolution === 'CUSTOM' && !choice.value?.trim());

  async function submit() {
    if (!canEdit || submitting || missingBlocking || invalidCustom) return;
    await onSubmit({
      expected_lock_version: item.lockVersion,
      idempotency_key: idempotencyKey.current,
      answers: Object.entries(answers).map(([question_id, value]) => ({ question_id, value })),
      accepted_assumption_ids: acceptedAssumptions,
      conflict_resolutions: Object.entries(conflictResolutions).map(([conflict_id, choice]) => ({
        conflict_id,
        resolution: choice.resolution,
        ...(choice.resolution === 'CUSTOM' ? { value: choice.value?.trim() }
          : choice.resolution === 'USE_NEW' ? { value: item.request.context_conflicts.find((conflict) => conflict.conflict_id === conflict_id)?.value }
            : {})
      }))
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
                    disabled={!canEdit || submitting}
                    value={answers[question.question_id]}
                    onChange={(event) => setAnswers((current) => ({ ...current, [question.question_id]: event.target.value }))}
                  >
                    <Space direction="vertical">
                      {question.options.map((option) => <Radio key={option} value={option}>{option}</Radio>)}
                    </Space>
                  </Radio.Group>
                ) : (
                  <Input.TextArea
                    disabled={!canEdit || submitting}
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
                  disabled={!canEdit || submitting}
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
            description={<Space direction="vertical" className="full-width">
              {item.request.context_conflicts.map((conflict) => (
                <Space key={conflict.conflict_id} direction="vertical" className="full-width">
                  <Typography.Text strong>{conflict.fact_key}{conflict.blocking ? <Tag color="red">{t('clarification.required')}</Tag> : null}</Typography.Text>
                  <Typography.Text>{conflict.reason}</Typography.Text>
                  <Typography.Text type="secondary">{conflict.source_ref} {JSON.stringify(conflict.value)}</Typography.Text>
                  <Radio.Group
                    aria-label={conflict.fact_key}
                    disabled={!canEdit || submitting || conflict.status === 'RESOLVED'}
                    value={conflictResolutions[conflict.conflict_id]?.resolution}
                    onChange={(event) => setConflictResolutions((current) => ({ ...current, [conflict.conflict_id]: { resolution: event.target.value } }))}
                  >
                    <Radio value="KEEP_CURRENT">{t('clarification.keepCurrent')}</Radio>
                    <Radio value="USE_NEW">{t('clarification.useNew')}</Radio>
                    <Radio value="CUSTOM">{t('clarification.custom')}</Radio>
                  </Radio.Group>
                  {conflictResolutions[conflict.conflict_id]?.resolution === 'CUSTOM' ? (
                    <Input.TextArea
                      aria-label={`${conflict.fact_key} ${t('clarification.custom')}`}
                      disabled={!canEdit || submitting}
                      value={conflictResolutions[conflict.conflict_id]?.value ?? ''}
                      onChange={(event) => setConflictResolutions((current) => ({ ...current, [conflict.conflict_id]: { resolution: 'CUSTOM', value: event.target.value } }))}
                    />
                  ) : null}
                </Space>
              ))}
            </Space>}
          />
        ) : null}
        {missingBlocking && canEdit ? <Typography.Text type="danger">{t('clarification.missingRequired')}</Typography.Text> : null}
        <Space wrap>
          <Button type="primary" loading={submitting} disabled={!canEdit || missingBlocking || invalidCustom} onClick={() => void submit()}>
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
