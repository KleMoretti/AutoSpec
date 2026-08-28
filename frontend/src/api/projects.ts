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

export interface ProjectDashboardItemResponse {
  projectId: number;
  name: string;
  requirementSummary: string;
  projectStatus: string;
  latestWorkflowRunId?: number;
  latestWorkflowStatus?: string;
  pendingApprovalCount: number;
  openReviewIssueCount: number;
  blockingReviewIssueCount: number;
  estimatedCost: number;
  qualityScore?: number;
  updatedAt?: string;
}

export type ArtifactStatus = 'GENERATED' | 'PENDING_REVIEW' | 'APPROVED' | 'SUPERSEDED' | string;

export interface ArtifactResponse {
  id: number;
  type: string;
  title: string;
  content: string;
  format: string;
  version: number;
  lockVersion: number;
  status?: ArtifactStatus;
  sourceAgent?: string;
  parentArtifactId?: number;
  workflowNodeRunId?: number;
  contentHash?: string;
  schemaVersion?: string;
  promptKey?: string;
  promptVersion?: string;
  modelProvider?: string;
  modelName?: string;
  sourceCitationsJson?: string;
  provenanceJson?: string;
  approvedAt?: string;
  updatedAt?: string;
}

export interface ReviewIssueResponse {
  id: number;
  issueKey?: string;
  severity: string;
  issueType: string;
  artifactType?: string;
  artifactPath?: string;
  requirementId?: string;
  description: string;
  suggestion: string;
  evidence?: string;
  status: string;
  ownerUserId?: number;
  resolution?: string;
  resolvedInArtifactId?: number;
}

export interface ArtifactDiffResponse {
  baseArtifactId: number;
  targetArtifactId: number;
  changed: boolean;
  changedPaths: string[];
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

export async function getProjects(): Promise<ProjectResponse[]> {
  return request('/api/projects');
}

export async function getProjectDashboard(): Promise<ProjectDashboardItemResponse[]> {
  return request('/api/projects/dashboard');
}

export async function updateArtifact(
  projectId: number,
  artifactId: number,
  content: string,
  expectedLockVersion: number
): Promise<ArtifactResponse> {
  return request(`/api/projects/${projectId}/artifacts/${artifactId}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ content, expectedLockVersion })
  });
}

export async function approveArtifact(
  projectId: number,
  artifactId: number
): Promise<ApproveArtifactResponse> {
  return request(`/api/projects/${projectId}/artifacts/${artifactId}/approve`, { method: 'POST' });
}

export async function getArtifacts(projectId: number): Promise<ArtifactResponse[]> {
  return request(`/api/projects/${projectId}/artifacts`);
}

export async function getArtifactVersions(
  projectId: number,
  artifactId: number
): Promise<ArtifactResponse[]> {
  return request(`/api/projects/${projectId}/artifacts/${artifactId}/versions`);
}

export async function getArtifactDiff(
  projectId: number,
  artifactId: number,
  againstArtifactId: number
): Promise<ArtifactDiffResponse> {
  return request(
    `/api/projects/${projectId}/artifacts/${artifactId}/diff?againstArtifactId=${againstArtifactId}`
  );
}

export async function restoreArtifact(
  projectId: number,
  artifactId: number,
  expectedLatestLockVersion: number
): Promise<ArtifactResponse> {
  return request(`/api/projects/${projectId}/artifacts/${artifactId}/restore`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ expectedLatestLockVersion })
  });
}

export async function getReview(projectId: number): Promise<ReviewResponse> {
  return request(`/api/projects/${projectId}/review`);
}

export async function updateReviewIssue(
  projectId: number,
  issueId: number,
  payload: {
    status: 'OPEN' | 'IN_PROGRESS' | 'RESOLVED' | 'IGNORED';
    resolution?: string;
    resolvedInArtifactId?: number;
  }
): Promise<ReviewIssueResponse> {
  return request(`/api/projects/${projectId}/review/issues/${issueId}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  });
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
  const response = init === undefined ? await fetch(url) : await fetch(url, init);
  if (!response.ok) {
    throw new Error(`Request failed: ${response.status}`);
  }
  return response.json() as Promise<T>;
}
