import type { ExportMetadataResponse } from './projects';

export async function generateCodeSkeleton(projectId: number): Promise<ExportMetadataResponse> {
  return request(`/api/projects/${projectId}/code-skeleton`, { method: 'POST' });
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
  lockVersion: number;
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
  expectedLockVersion: number;
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
  qualityProfile?: 'FAST' | 'BALANCED' | 'DEEP';
  maxTokens?: number;
  maxCost?: number;
  maxModelCalls?: number;
  maxWallTimeMs?: number;
  consumedTokens?: number;
  consumedCost?: number;
  modelCallCount?: number;
  reservedTokens?: number;
  reservedCost?: number;
  reservedModelCalls?: number;
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
  budgetReservationId?: string;
  reservedInputTokens?: number;
  reservedOutputTokens?: number;
  reservedCost?: number;
  reservedModelCalls?: number;
  actualInputTokens?: number;
  actualOutputTokens?: number;
  actualCacheTokens?: number;
  actualCost?: number;
  actualModelCalls?: number;
  actualToolCalls?: number;
  budgetStatus?: string;
  budgetSettledAt?: string;
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
  cacheTokenCount: number;
  estimatedCost: number;
  modelCallCount: number;
  acceptedDuplicateEventCount: number;
  qualityProfile?: string;
  maxTokens?: number;
  maxCost?: number;
  maxModelCalls?: number;
  maxWallTimeMs?: number;
  reservedTokens?: number;
  reservedCost?: number;
  reservedModelCalls?: number;
  remainingTokens?: number;
  remainingCost?: number;
  remainingModelCalls?: number;
  modelUsage: Array<{
    providerKey: string;
    modelName: string;
    invocationCount: number;
    modelCallCount: number;
    inputTokens: number;
    outputTokens: number;
    cacheTokens: number;
    estimatedCost: number;
  }>;
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
  executionPolicy?: {
    qualityProfile: 'FAST' | 'BALANCED' | 'DEEP';
    maxTokens?: number;
    maxCost?: number;
    maxModelCalls?: number;
    maxWallTimeMs?: number;
  };
}

export interface DeliveryReadinessResponse {
  specReady: boolean;
  buildReady: boolean;
  status: 'NOT_STARTED' | 'SPEC_BLOCKED' | 'BUILD_REQUIRED' | 'READY' | string;
  workflowRunId?: number;
  codeGenerationJobId?: number;
  blockers: string[];
}

export interface WorkflowReplayPayload {
  mode: 'ORIGINAL_SNAPSHOT' | 'SELECTED_VERSION';
  selectedWorkflowVersionId?: number;
  idempotencyKey: string;
}

export async function getWorkflowRuns(projectId: number): Promise<WorkflowRunResponse[]> {
  return request(`/api/projects/${projectId}/workflow-runs`);
}

export async function getDeliveryReadiness(
  projectId: number
): Promise<DeliveryReadinessResponse> {
  return request(`/api/projects/${projectId}/delivery-readiness`);
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
  const response = init === undefined ? await fetch(url) : await fetch(url, init);
  if (!response.ok) {
    throw new Error(`Request failed: ${response.status}`);
  }
  return response.json() as Promise<T>;
}

export async function cancelWorkflowRun(
  projectId: number,
  runId: number
): Promise<WorkflowRunResponse> {
  return request(`/api/projects/${projectId}/workflow-runs/${runId}/cancel`, {
    method: 'POST'
  });
}
