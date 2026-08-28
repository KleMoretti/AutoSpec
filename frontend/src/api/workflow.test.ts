import { afterEach, describe, expect, it, vi } from 'vitest';
import { cancelWorkflowRun, getDeliveryReadiness } from './workflow';

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('workflow delivery and recovery APIs', () => {
  it('loads the server-authoritative delivery gate', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({
      specReady: true,
      buildReady: false,
      status: 'BUILD_REQUIRED',
      workflowRunId: 41,
      blockers: ['Generate and verify a delivery bundle']
    }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(getDeliveryReadiness(7)).resolves.toMatchObject({
      specReady: true,
      buildReady: false,
      status: 'BUILD_REQUIRED'
    });
    expect(fetchMock).toHaveBeenCalledWith('/api/projects/7/delivery-readiness');
  });

  it('cancels a running workflow through its project-scoped endpoint', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({
      id: 19,
      projectId: 7,
      operation: 'GENERATE_V5',
      idempotencyKey: 'run-19',
      status: 'CANCELLED'
    }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(cancelWorkflowRun(7, 19)).resolves.toMatchObject({ status: 'CANCELLED' });
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/projects/7/workflow-runs/19/cancel',
      { method: 'POST' }
    );
  });
});

function jsonResponse(body: unknown): Response {
  return {
    ok: true,
    status: 200,
    json: async () => body
  } as Response;
}
