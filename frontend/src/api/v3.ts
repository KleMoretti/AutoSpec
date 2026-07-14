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
