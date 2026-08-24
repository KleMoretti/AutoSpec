import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  type ArtifactResponse,
  type ProjectResponse,
  type ReviewResponse,
  getArtifacts,
  getProject,
  getReview
} from '../api/projects';
import {
  type DeliveryReadinessResponse,
  type WorkflowApprovalResponse,
  type WorkflowRunResponse,
  type WorkflowVersionResponse,
  getDeliveryReadiness,
  getWorkflowApprovals,
  getWorkflowRuns,
  getWorkflowVersions
} from '../api/v3';

export type AuxiliaryResource = 'review' | 'approvals' | 'runs' | 'versions' | 'readiness';

export interface ProjectDetailDataState {
  project: ProjectResponse | null;
  artifacts: ArtifactResponse[];
  review: ReviewResponse | null;
  approvals: WorkflowApprovalResponse[];
  workflowRuns: WorkflowRunResponse[];
  workflowVersions: WorkflowVersionResponse[];
  deliveryReadiness: DeliveryReadinessResponse | null;
  loading: boolean;
  error: string | null;
  resourceErrors: Partial<Record<AuxiliaryResource, string>>;
}

export interface ProjectDetailLoadResults {
  project: PromiseSettledResult<ProjectResponse>;
  artifacts: PromiseSettledResult<ArtifactResponse[]>;
  review: PromiseSettledResult<ReviewResponse>;
  approvals: PromiseSettledResult<WorkflowApprovalResponse[]>;
  runs: PromiseSettledResult<WorkflowRunResponse[]>;
  versions: PromiseSettledResult<WorkflowVersionResponse[]>;
  readiness: PromiseSettledResult<DeliveryReadinessResponse>;
}

export function createProjectDetailDataState(): ProjectDetailDataState {
  return {
    project: null,
    artifacts: [],
    review: null,
    approvals: [],
    workflowRuns: [],
    workflowVersions: [],
    deliveryReadiness: null,
    loading: true,
    error: null,
    resourceErrors: {}
  };
}

export function applyProjectDetailLoad(
  previous: ProjectDetailDataState,
  results: ProjectDetailLoadResults
): ProjectDetailDataState {
  const next = { ...previous, loading: false };
  const coreErrors: string[] = [];
  const resourceErrors: Partial<Record<AuxiliaryResource, string>> = {};

  if (results.project.status === 'fulfilled') next.project = results.project.value;
  else coreErrors.push(`project: ${errorMessage(results.project.reason)}`);
  if (results.artifacts.status === 'fulfilled') next.artifacts = results.artifacts.value;
  else coreErrors.push(`artifacts: ${errorMessage(results.artifacts.reason)}`);

  if (results.review.status === 'fulfilled') next.review = results.review.value;
  else resourceErrors.review = errorMessage(results.review.reason);
  if (results.approvals.status === 'fulfilled') next.approvals = results.approvals.value;
  else resourceErrors.approvals = errorMessage(results.approvals.reason);
  if (results.runs.status === 'fulfilled') next.workflowRuns = results.runs.value;
  else resourceErrors.runs = errorMessage(results.runs.reason);
  if (results.versions.status === 'fulfilled') next.workflowVersions = results.versions.value;
  else resourceErrors.versions = errorMessage(results.versions.reason);
  if (results.readiness.status === 'fulfilled') next.deliveryReadiness = results.readiness.value;
  else {
    next.deliveryReadiness = null;
    resourceErrors.readiness = errorMessage(results.readiness.reason);
  }

  next.resourceErrors = resourceErrors;
  next.error = coreErrors.length > 0 ? `Could not refresh ${coreErrors.join('; ')}` : null;
  return next;
}

async function loadProjectDetailResources(projectId: number): Promise<ProjectDetailLoadResults> {
  const [project, artifacts, review, approvals, runs, versions, readiness] = await Promise.allSettled([
    getProject(projectId),
    getArtifacts(projectId),
    getReview(projectId),
    getWorkflowApprovals(projectId),
    getWorkflowRuns(projectId),
    getWorkflowVersions('autospec-v5'),
    getDeliveryReadiness(projectId)
  ]);
  return { project, artifacts, review, approvals, runs, versions, readiness };
}

export function useProjectDetailData(projectId: number) {
  const [state, setState] = useState(createProjectDetailDataState);
  const loadInFlight = useRef<{ projectId: number; promise: Promise<void> } | null>(null);
  const activeProjectId = useRef(projectId);
  activeProjectId.current = projectId;

  const latestRun = useMemo(
    () => state.workflowRuns.slice().sort((left, right) => right.id - left.id)[0],
    [state.workflowRuns]
  );

  const reload = useCallback((): Promise<void> => {
    if (!Number.isFinite(projectId)) {
      setState((current) => ({ ...current, error: 'Invalid project id', loading: false }));
      return Promise.resolve();
    }
    if (loadInFlight.current?.projectId === projectId) {
      return loadInFlight.current.promise;
    }

    const requestedProjectId = projectId;
    const request = loadProjectDetailResources(projectId).then((results) => {
      if (activeProjectId.current === requestedProjectId) {
        setState((current) => applyProjectDetailLoad(current, results));
      }
    });
    const tracked = request.finally(() => {
      if (loadInFlight.current?.promise === tracked) loadInFlight.current = null;
    });
    loadInFlight.current = { projectId, promise: tracked };
    return tracked;
  }, [projectId]);

  useEffect(() => {
    void reload();
  }, [reload]);

  useEffect(() => {
    if (!latestRun || !['RUNNING', 'PENDING'].includes(latestRun.status)) return undefined;
    const timer = window.setInterval(() => void reload(), 2000);
    return () => window.clearInterval(timer);
  }, [latestRun, reload]);

  return { ...state, latestRun, reload };
}

function errorMessage(value: unknown): string {
  return value instanceof Error ? value.message : 'Request failed';
}
