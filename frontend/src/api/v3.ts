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

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const nextInit = withSessionHeader(init);
  const response = nextInit === undefined ? await fetch(url) : await fetch(url, nextInit);
  if (!response.ok) {
    throw new Error(`Request failed: ${response.status}`);
  }
  return response.json() as Promise<T>;
}

function withSessionHeader(init?: RequestInit): RequestInit | undefined {
  const userId = readSessionUserId();
  if (!userId) {
    return init;
  }
  return {
    ...init,
    headers: {
      ...(init?.headers as Record<string, string> | undefined),
      'X-AutoSpec-User-Id': String(userId)
    }
  };
}

function readSessionUserId(): number | null {
  if (typeof localStorage === 'undefined') {
    return null;
  }
  const raw = localStorage.getItem('autospec.session');
  if (!raw) {
    return null;
  }
  try {
    const parsed = JSON.parse(raw) as { userId?: number };
    return parsed.userId ?? null;
  } catch {
    return null;
  }
}
