export interface EvaluationMetricResponse {
  name: string;
  status: string;
  value: number | null;
  unit: string;
  source: string;
  note?: string | null;
  intervalLow?: number | null;
  intervalHigh?: number | null;
  sampleCount?: number | null;
  statisticVersion?: string | null;
}

export interface EvaluationCaseResponse {
  caseId: string;
  repetition: number;
  workflowRunId?: string | null;
  traceId?: string | null;
  bundleHash?: string | null;
  rubricRef?: string | null;
  status: string;
  gatePass?: boolean | null;
  mustTraceCoverage?: number | null;
  blockingIssueCount?: number | null;
  unauthorizedToolRequests?: number | null;
  invalidToolArguments?: number | null;
  toolCallCount?: number | null;
  unauthorizedToolExecutions?: number | null;
  schemaInvalidCount?: number | null;
  durationMs?: number | null;
  steps?: number | null;
  replans?: number | null;
  pathOscillations?: number | null;
  p95LatencyMs?: number | null;
  tokens?: number | null;
  cost?: number | null;
  failureCodes: string[];
}

export interface EvaluationRunResponse {
  runId: string;
  datasetVersion: string;
  datasetHash?: string | null;
  datasetSplit?: string | null;
  environmentHash?: string | null;
  pricingSnapshot: Record<string, unknown>;
  group: 'A' | 'B' | 'C' | 'D' | string;
  groupName: string;
  executionMode: 'LIVE_CONTROL_PLANE' | 'FIXTURE_BASELINE' | string;
  workflowKey: string;
  workflowVersion: string;
  codeVersion: string;
  bundleHash?: string | null;
  promptSchemaVersions: Record<string, string>;
  modelVersion?: string | null;
  retrieverVersion?: string | null;
  toolPolicyVersion?: string | null;
  budgetVersion: string;
  randomSeed?: number | null;
  status: string;
  gateStatus: string;
  decision: string;
  notExecutedReason?: string | null;
  caseResults: EvaluationCaseResponse[];
  metrics: EvaluationMetricResponse[];
}

export interface EvaluationComparisonResponse {
  status: 'MEASURED' | 'NOT_EVALUATED' | string;
  source: 'RESULT_DIRECTORY' | 'NONE' | string;
  matrix: {
    matrixId: string;
    datasetVersion: string;
    datasetHash?: string | null;
    datasetSplit?: string | null;
    generatedAtEpochMs: number;
    runs: EvaluationRunResponse[];
  };
  decision: {
    decision: string;
    gateStatus: string;
    reasons: string[];
    baselineRunId: string;
    candidateRunId: string;
    statisticsVersion?: string | null;
    pairedStatistics: Record<string, unknown>;
  };
  notEvaluatedReason?: string | null;
}

export async function getEvaluationComparison(): Promise<EvaluationComparisonResponse> {
  const response = await fetch('/api/evaluations/ablation');
  if (!response.ok) {
    throw new Error(`Request failed: ${response.status}`);
  }
  return response.json() as Promise<EvaluationComparisonResponse>;
}
