import type { ExportMetadataResponse } from './projects';

export async function generateCodeSkeleton(projectId: number): Promise<ExportMetadataResponse> {
  return request(`/api/projects/${projectId}/code-skeleton`, { method: 'POST' });
}

export interface WorkflowNodeResponse {
  id: string;
  label?: string;
  artifactType?: string;
}

export interface WorkflowEdgeResponse {
  from: string;
  to: string;
}

export interface WorkflowSnapshotResponse {
  workflowKey: string;
  version: string;
  nodes: WorkflowNodeResponse[];
  edges: WorkflowEdgeResponse[];
}

export async function getWorkflow(projectId: number): Promise<WorkflowSnapshotResponse> {
  return request(`/api/projects/${projectId}/workflow`);
}

export type WorkflowApprovalDecision =
  | 'APPROVE'
  | 'REJECT'
  | 'EDIT_AND_APPROVE'
  | 'ROLLBACK_TO_NODE'
  | 'CANCEL_WORKFLOW';

export interface WorkflowApprovalResponse {
  id: number;
  workflowRunId: number;
  nodeRunId: number;
  nodeId: string;
  mode: 'BEFORE_NODE' | 'AFTER_NODE' | string;
  allowedActions: WorkflowApprovalDecision[];
  status: 'PENDING' | 'DECIDED' | string;
  decision?: WorkflowApprovalDecision;
  candidateArtifactId?: number;
  revisedArtifactId?: number;
  decisionReason?: string;
  decidedAt?: string;
  createdAt?: string;
}

export interface ApprovalDecisionPayload {
  decision: WorkflowApprovalDecision;
  reason?: string;
  editedContent?: string;
  rollbackNodeId?: string;
  idempotencyKey: string;
}

export async function getWorkflowApprovals(projectId: number): Promise<WorkflowApprovalResponse[]> {
  return request(`/api/projects/${projectId}/workflow-approvals`);
}

export async function decideWorkflowApproval(
  approvalId: number,
  payload: ApprovalDecisionPayload
): Promise<WorkflowApprovalResponse> {
  return request(`/api/workflow-approvals/${approvalId}/decide`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  });
}

export interface WorkflowRunResponse {
  id: number;
  projectId: number;
  operation: string;
  idempotencyKey: string;
  correlationId?: string;
  workflowVersionId?: number;
  replayOfRunId?: number;
  reviewRound?: number;
  maxReviewRounds?: number;
  status: string;
  responseStatus?: string;
  responsePercent?: number;
  errorMessage?: string;
  startedAt?: string;
  completedAt?: string;
}

export interface WorkflowNodeRunResponse {
  id: number;
  workflowRunId: number;
  nodeId: string;
  revision: number;
  attempt: number;
  executionId: string;
  status: string;
  handlerKey: string;
  handlerVersion: string;
  timeoutMs?: number;
  durationMs?: number;
  inputJson?: string;
  outputJson?: string;
  errorCode?: string;
  errorMessage?: string;
  queuedAt?: string;
  startedAt?: string;
  heartbeatAt?: string;
  finishedAt?: string;
  workerId?: string;
}

export interface WorkflowRuntimeMetricsResponse {
  workflowRunId: number;
  nodeAttemptCount: number;
  queueTimeMs: number;
  executionDurationMs: number;
  retryCount: number;
  recoveryCount: number;
  tokenCount: number;
  estimatedCost: number;
  acceptedDuplicateEventCount: number;
}

export interface WorkflowVersionResponse {
  id: number;
  definitionId: number;
  workflowKey: string;
  version: string;
  contentHash: string;
  specJson?: string;
  status: string;
  publishedAt?: string;
  createdAt?: string;
}

export interface WorkflowRunStartPayload {
  projectId: number;
  workflowVersionId: number;
  input: Record<string, unknown>;
  idempotencyKey: string;
}

export interface WorkflowReplayPayload {
  mode: 'ORIGINAL_SNAPSHOT' | 'SELECTED_VERSION';
  selectedWorkflowVersionId?: number;
  idempotencyKey: string;
}

export async function getWorkflowRuns(projectId: number): Promise<WorkflowRunResponse[]> {
  return request(`/api/projects/${projectId}/workflow-runs`);
}

export async function startWorkflowRun(
  payload: WorkflowRunStartPayload
): Promise<WorkflowRunResponse> {
  return request('/api/workflow-runs', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  });
}

export async function getWorkflowRunNodes(runId: number): Promise<WorkflowNodeRunResponse[]> {
  return request(`/api/workflow-runs/${runId}/nodes`);
}

export async function getWorkflowRunMetrics(
  runId: number
): Promise<WorkflowRuntimeMetricsResponse> {
  return request(`/api/workflow-runs/${runId}/metrics`);
}

export async function getWorkflowVersions(workflowKey: string): Promise<WorkflowVersionResponse[]> {
  return request(`/api/workflows/${encodeURIComponent(workflowKey)}/versions`);
}

export async function replayWorkflowRun(
  runId: number,
  payload: WorkflowReplayPayload
): Promise<WorkflowRunResponse> {
  return request(`/api/workflow-runs/${runId}/replay`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  });
}

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const nextInit = withSessionHeader(init);
  const response = nextInit === undefined ? await fetch(url) : await fetch(url, nextInit);
  if (!response.ok) {
    throw new Error(`Request failed: ${response.status}`);
  }
  return response.json() as Promise<T>;
}

function withSessionHeader(init?: RequestInit): RequestInit | undefined {
  const sessionToken = readSessionToken();
  if (!sessionToken) {
    return init;
  }
  return {
    ...init,
    headers: {
      ...(init?.headers as Record<string, string> | undefined),
      'X-AutoSpec-Session-Token': sessionToken
    }
  };
}

function readSessionToken(): string | null {
  if (typeof localStorage === 'undefined') {
    return null;
  }
  const raw = localStorage.getItem('autospec.session');
  if (!raw) {
    return null;
  }
  try {
    const parsed = JSON.parse(raw) as { sessionToken?: string };
    return parsed.sessionToken ?? null;
  } catch {
    return null;
  }
}
