import { describe, expect, it } from 'vitest';
import type { ArtifactResponse, ProjectResponse, ReviewResponse } from '../api/projects';
import type {
  DeliveryReadinessResponse,
  WorkflowApprovalResponse,
  WorkflowRunResponse,
  WorkflowVersionResponse
} from '../api/v3';
import {
  type ProjectDetailLoadResults,
  applyProjectDetailLoad,
  createProjectDetailDataState
} from './useProjectDetailData';

const oldProject: ProjectResponse = {
  projectId: 7,
  name: 'Old project',
  originalRequirement: 'Keep visible data during transient failures.',
  status: 'GENERATING'
};
const oldArtifact: ArtifactResponse = {
  id: 11,
  type: 'PRD',
  title: 'Existing PRD',
  content: '{}',
  format: 'JSON',
  version: 1,
  lockVersion: 0
};
const oldReview: ReviewResponse = { score: 88, issues: [] };
const oldRun: WorkflowRunResponse = {
  id: 21,
  projectId: 7,
  operation: 'GENERATE_V5',
  idempotencyKey: 'run-21',
  status: 'RUNNING'
};

describe('project detail load state', () => {
  it('updates successful resources while retaining failed auxiliary data', () => {
    const previous = {
      ...createProjectDetailDataState(),
      project: oldProject,
      artifacts: [oldArtifact],
      review: oldReview,
      workflowRuns: [oldRun],
      deliveryReadiness: ready()
    };
    const next = applyProjectDetailLoad(previous, results({
      project: fulfilled({ ...oldProject, name: 'Fresh project' }),
      review: rejected(new Error('review unavailable')),
      runs: rejected(new Error('runs unavailable')),
      readiness: rejected(new Error('gate unavailable'))
    }));

    expect(next.project?.name).toBe('Fresh project');
    expect(next.review).toBe(oldReview);
    expect(next.workflowRuns).toEqual([oldRun]);
    expect(next.deliveryReadiness).toBeNull();
    expect(next.resourceErrors).toEqual({
      review: 'review unavailable',
      runs: 'runs unavailable',
      readiness: 'gate unavailable'
    });
    expect(next.error).toBeNull();
    expect(next.loading).toBe(false);
  });

  it('retains core data and reports core refresh failures separately', () => {
    const previous = { ...createProjectDetailDataState(), project: oldProject, artifacts: [oldArtifact] };
    const next = applyProjectDetailLoad(previous, results({
      project: rejected(new Error('project unavailable')),
      artifacts: rejected('artifact unavailable')
    }));

    expect(next.project).toBe(oldProject);
    expect(next.artifacts).toEqual([oldArtifact]);
    expect(next.error).toBe('Could not refresh project: project unavailable; artifacts: Request failed');
    expect(next.resourceErrors).toEqual({});
  });
});

function results(overrides: Partial<ProjectDetailLoadResults>): ProjectDetailLoadResults {
  return {
    project: fulfilled(oldProject),
    artifacts: fulfilled([oldArtifact]),
    review: fulfilled(oldReview),
    approvals: fulfilled<WorkflowApprovalResponse[]>([]),
    runs: fulfilled([oldRun]),
    versions: fulfilled<WorkflowVersionResponse[]>([]),
    readiness: fulfilled(ready()),
    ...overrides
  };
}

function ready(): DeliveryReadinessResponse {
  return { specReady: true, buildReady: true, status: 'READY', blockers: [] };
}

function fulfilled<T>(value: T): PromiseFulfilledResult<T> {
  return { status: 'fulfilled', value };
}

function rejected(reason: unknown): PromiseRejectedResult {
  return { status: 'rejected', reason };
}
