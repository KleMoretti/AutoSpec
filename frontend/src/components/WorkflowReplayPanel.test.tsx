import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import type { WorkflowRunResponse, WorkflowVersionResponse } from '../api/v3';
import WorkflowReplayPanel, { buildReplayPayload, buildStartPayload, formatDuration } from './WorkflowReplayPanel';

const run: WorkflowRunResponse = {
  id: 12,
  projectId: 3,
  operation: 'GENERATE_V5',
  idempotencyKey: 'source-key',
  status: 'COMPLETED'
};

const version: WorkflowVersionResponse = {
  id: 7,
  definitionId: 2,
  workflowKey: 'autospec-v5',
  version: 'v5.1',
  contentHash: 'hash',
  status: 'PUBLISHED'
};

describe('WorkflowReplayPanel', () => {
  it('shows source runs, replay modes, and timeline entry points', () => {
    const html = renderToStaticMarkup(
      <WorkflowReplayPanel
        projectId={3}
        requirement="Build AutoSpec"
        runs={[run]}
        versions={[version]}
        onStart={vi.fn()}
        onReplay={vi.fn()}
        onLoadTimeline={vi.fn()}
        onLoadMetrics={vi.fn()}
      />
    );

    expect(html).toContain('Source run');
    expect(html).toContain('Original snapshot');
    expect(html).toContain('Replay mode');
    expect(html).toContain('View timeline');
    expect(html).toContain('Start V5 run');
  });

  it('renders the first-run entry point without existing history', () => {
    const html = renderToStaticMarkup(
      <WorkflowReplayPanel
        projectId={3}
        requirement="Build AutoSpec"
        runs={[]}
        versions={[version]}
        onStart={vi.fn()}
        onReplay={vi.fn()}
        onLoadTimeline={vi.fn()}
        onLoadMetrics={vi.fn()}
      />
    );

    expect(html).toContain('Start from a published workflow');
    expect(html).toContain('Start V5 run');
    expect(html).not.toContain('Create an immutable replay');
  });

  it('builds a frozen root input for a new V5 run', () => {
    expect(buildStartPayload(3, 7, 'Build AutoSpec', 'start-key')).toEqual({
      projectId: 3,
      workflowVersionId: 7,
      input: { requirement: 'Build AutoSpec', retrieved_sources: [] },
      idempotencyKey: 'start-key'
    });
  });

  it('builds selected-version payloads with a stable idempotency key', () => {
    expect(buildReplayPayload('SELECTED_VERSION', 7, 'replay-key')).toEqual({
      mode: 'SELECTED_VERSION',
      selectedWorkflowVersionId: 7,
      idempotencyKey: 'replay-key'
    });
    expect(buildReplayPayload('ORIGINAL_SNAPSHOT', 7, 'original-key')).toEqual({
      mode: 'ORIGINAL_SNAPSHOT',
      selectedWorkflowVersionId: undefined,
      idempotencyKey: 'original-key'
    });
  });

  it('formats node execution duration without guessing incomplete attempts', () => {
    expect(formatDuration('2026-07-14T10:00:00.000Z', '2026-07-14T10:00:01.250Z')).toBe('1250 ms');
    expect(formatDuration('2026-07-14T10:00:00.000Z')).toBe('—');
  });
});
