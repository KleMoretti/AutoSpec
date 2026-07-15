import { readSession } from './auth';

export interface CreateProjectPayload {
  name: string;
  requirement: string;
}

export interface CreateProjectResponse {
  projectId: number;
  status: string;
}

export interface ProjectResponse {
  projectId: number;
  name: string;
  originalRequirement: string;
  status: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface GenerateProjectResponse {
  projectId: number;
  status: string;
  percent: number;
}

export interface AgentStepStatus {
  taskId?: number;
  agentName: string;
  status: string;
  nodeName?: string;
  durationMs?: number;
  errorMessage?: string;
}

export interface ProjectProgressResponse {
  projectId: number;
  status?: string;
  currentAgent: string;
  percent: number;
  steps: AgentStepStatus[];
}

export type ArtifactStatus = 'GENERATED' | 'PENDING_REVIEW' | 'APPROVED' | 'SUPERSEDED' | string;

export interface ArtifactResponse {
  id: number;
  type: string;
  title: string;
  content: string;
  format: string;
  version: number;
  status?: ArtifactStatus;
  sourceAgent?: string;
  parentArtifactId?: number;
  approvedAt?: string;
  updatedAt?: string;
}

export interface ReviewIssueResponse {
  severity: string;
  issueType: string;
  description: string;
  suggestion: string;
  status: string;
}

export interface ReviewResponse {
  score: number;
  issues: ReviewIssueResponse[];
}

interface ExportResponse {
  format: string;
  content: string;
  fileName?: string;
  mediaType?: string;
  encoding?: string;
}

export interface ApproveArtifactResponse {
  id: number;
  status: ArtifactStatus;
  version: number;
}

export interface RetryTaskResponse {
  taskId: number;
  status: string;
  retryOfTaskId: number;
}

export interface AgentEventResponse {
  id: number;
  projectId?: number;
  taskId?: number;
  eventType: string;
  nodeName: string;
  message?: string;
  payload?: string;
  createdAt?: string;
}

export interface ExportMetadataResponse {
  format: string;
  content: string;
  fileName: string;
  mediaType: string;
  encoding?: string;
}

export async function createProject(payload: CreateProjectPayload): Promise<CreateProjectResponse> {
  return request('/api/projects', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  });
}

export async function getProject(projectId: number): Promise<ProjectResponse> {
  return request(`/api/projects/${projectId}`);
}

export async function generateProject(projectId: number): Promise<GenerateProjectResponse> {
  return request(`/api/projects/${projectId}/generate`, { method: 'POST' });
}

export async function generateProjectV4(projectId: number): Promise<GenerateProjectResponse> {
  return request(`/api/projects/${projectId}/generate-v4`, { method: 'POST' });
}

export async function generatePrd(projectId: number): Promise<GenerateProjectResponse> {
  return request(`/api/projects/${projectId}/generate-prd`, { method: 'POST' });
}

export async function updateArtifact(
  projectId: number,
  artifactId: number,
  content: string
): Promise<ArtifactResponse> {
  return request(`/api/projects/${projectId}/artifacts/${artifactId}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ content })
  });
}

export async function approveArtifact(
  projectId: number,
  artifactId: number
): Promise<ApproveArtifactResponse> {
  return request(`/api/projects/${projectId}/artifacts/${artifactId}/approve`, { method: 'POST' });
}

export async function continueGeneration(projectId: number): Promise<GenerateProjectResponse> {
  return request(`/api/projects/${projectId}/continue`, { method: 'POST' });
}

export async function getProgress(projectId: number): Promise<ProjectProgressResponse> {
  return request(`/api/projects/${projectId}/progress`);
}

export async function getArtifacts(projectId: number): Promise<ArtifactResponse[]> {
  return request(`/api/projects/${projectId}/artifacts`);
}

export async function getReview(projectId: number): Promise<ReviewResponse> {
  return request(`/api/projects/${projectId}/review`);
}

export async function getEventHistory(projectId: number): Promise<AgentEventResponse[]> {
  return request(`/api/projects/${projectId}/events/history`);
}

export function subscribeProjectEvents(
  projectId: number,
  onEvent: (event: AgentEventResponse) => void,
  onError?: (event: Event) => void
): EventSource {
  const token = readSessionToken();
  const query = token ? `?sessionToken=${encodeURIComponent(token)}` : '';
  const source = new EventSource(`/api/projects/${projectId}/events${query}`);
  source.onmessage = (message) => onEvent(JSON.parse(message.data) as AgentEventResponse);
  if (onError) {
    source.onerror = onError;
  }
  return source;
}

export async function retryTask(projectId: number, taskId: number): Promise<RetryTaskResponse> {
  return request(`/api/projects/${projectId}/tasks/${taskId}/retry`, { method: 'POST' });
}

export async function exportMarkdown(projectId: number): Promise<string> {
  const response = await request<ExportResponse>(`/api/projects/${projectId}/export?format=MARKDOWN`, {
    method: 'POST'
  });
  return response.content;
}

export async function exportPdf(projectId: number): Promise<ExportMetadataResponse> {
  return request(`/api/projects/${projectId}/export?format=PDF`, { method: 'POST' });
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
  return readSession()?.sessionToken ?? null;
}
