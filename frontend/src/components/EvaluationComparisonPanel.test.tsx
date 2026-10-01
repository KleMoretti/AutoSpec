import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { EvaluationComparisonResponse, EvaluationRunResponse } from '../api/evaluation';
import { EvaluationComparisonView } from './EvaluationComparisonPanel';

function run(group: 'A' | 'B' | 'C' | 'D', status: string, measured: boolean): EvaluationRunResponse {
  return {
    runId: `${group}-run`,
    datasetVersion: 'eval-v1',
    datasetHash: null,
    datasetSplit: measured ? 'holdout' : null,
    environmentHash: null,
    pricingSnapshot: {},
    group,
    groupName: `group-${group}`,
    executionMode: measured ? 'FIXTURE_BASELINE' : 'LIVE_CONTROL_PLANE',
    workflowKey: 'autospec-v5',
    workflowVersion: 'v1',
    codeVersion: 'test',
    bundleHash: null,
    promptSchemaVersions: {},
    modelVersion: null,
    retrieverVersion: null,
    toolPolicyVersion: null,
    budgetVersion: 'budget-v1',
    randomSeed: null,
    status,
    gateStatus: measured ? 'PASSED' : 'NOT_EVALUATED',
    decision: measured ? 'PROMOTE' : 'NOT_EVALUATED',
    notExecutedReason: measured ? null : 'No authorized comparison has been published.',
    caseResults: [],
    metrics: measured ? [
      { name: 'gate_pass_rate', status: 'MEASURED', value: 1, unit: 'ratio', source: 'test', intervalLow: 1, intervalHigh: 1 },
      { name: 'p95_latency_ms', status: 'MEASURED', value: 120, unit: 'ms', source: 'test' },
      { name: 'tokens_per_run', status: 'MEASURED', value: 10, unit: 'tokens', source: 'test' },
      { name: 'cost_per_run', status: 'MEASURED', value: 0.1, unit: 'currency', source: 'test' },
      { name: 'must_trace_coverage', status: 'MEASURED', value: 1, unit: 'ratio', source: 'test' },
      { name: 'blocking_issue_median', status: 'MEASURED', value: 0, unit: 'count', source: 'test' }
    ] : [
      { name: 'gate_pass_rate', status: 'NOT_EXECUTED', value: null, unit: 'ratio', source: 'test' },
      { name: 'cost_per_run', status: 'UNAVAILABLE', value: null, unit: 'currency', source: 'test' }
    ]
  };
}

function comparison(runs: EvaluationRunResponse[], status: 'MEASURED' | 'NOT_EVALUATED'): EvaluationComparisonResponse {
  return {
    status,
    source: status === 'MEASURED' ? 'RESULT_DIRECTORY' : 'NONE',
    matrix: {
      matrixId: 'matrix-1',
      datasetVersion: 'eval-v1',
      datasetHash: null,
      datasetSplit: status === 'MEASURED' ? 'holdout' : null,
      generatedAtEpochMs: 1,
      runs
    },
    decision: {
      decision: status === 'MEASURED' ? 'PROMOTE' : 'NOT_EVALUATED',
      gateStatus: status === 'MEASURED' ? 'PASSED' : 'NOT_EVALUATED',
      reasons: [status === 'MEASURED' ? 'Measured test evidence' : 'No authorized comparison has been published.'],
      baselineRunId: 'A-run',
      candidateRunId: 'D-run',
      statisticsVersion: null,
      pairedStatistics: {}
    },
    notEvaluatedReason: status === 'MEASURED' ? null : 'No authorized comparison has been published.'
  };
}

describe('EvaluationComparisonView', () => {
  it('renders a complete measured comparison and final decision', () => {
    const html = renderToStaticMarkup(
      <EvaluationComparisonView comparison={comparison(
        (['A', 'B', 'C', 'D'] as const).map((group) => run(group, 'SUCCEEDED', true)),
        'MEASURED'
      )} />
    );

    expect(html).toContain('PROMOTE');
    expect(html).toContain('group-A');
    expect(html).toContain('100.0%');
  });

  it('keeps missing groups and unknown costs out of measured-looking values', () => {
    const html = renderToStaticMarkup(
      <EvaluationComparisonView comparison={comparison([run('A', 'NOT_EXECUTED', false)], 'NOT_EVALUATED')} />
    );

    expect(html).toContain('NOT_EVALUATED');
    expect(html).toContain('Unknown');
    expect(html).not.toContain('PROMOTE');
    expect(html).not.toContain('0.0000');
  });
});
