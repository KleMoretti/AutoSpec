import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  approveArtifact,
  continueGeneration,
  createProject,
  exportPdf,
  exportMarkdown,
  generatePrd,
  generateProject,
  getArtifacts,
  getEventHistory,
  getProgress,
  getReview,
  retryTask,
  updateArtifact
} from './projects';

function jsonResponse(body: unknown) {
  return Promise.resolve({
    ok: true,
    json: () => Promise.resolve(body)
  } as Response);
}

describe('project api client', () => {
  const fetchMock = vi.fn();

  beforeEach(() => {
    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    fetchMock.mockReset();
  });

  it('creates a project with name and requirement', async () => {
    fetchMock.mockReturnValueOnce(jsonResponse({ projectId: 7, status: 'CREATED' }));

    const result = await createProject({ name: 'Campus Marketplace', requirement: 'Build it.' });

    expect(result.projectId).toBe(7);
    expect(fetchMock).toHaveBeenCalledWith('/api/projects', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: 'Campus Marketplace', requirement: 'Build it.' })
    });
  });

  it('calls project generation and reads progress artifacts review and export', async () => {
    fetchMock
      .mockReturnValueOnce(jsonResponse({ projectId: 7, status: 'COMPLETED', percent: 100 }))
      .mockReturnValueOnce(jsonResponse({ projectId: 7, currentAgent: 'COMPLETED', percent: 100, steps: [] }))
      .mockReturnValueOnce(jsonResponse([{ id: 1, type: 'PRD', title: 'PRD', content: '{}', format: 'JSON', version: 1 }]))
      .mockReturnValueOnce(jsonResponse({ score: 100, issues: [] }))
      .mockReturnValueOnce(jsonResponse({ format: 'MARKDOWN', content: '# Campus Marketplace' }));

    await expect(generateProject(7)).resolves.toMatchObject({ status: 'COMPLETED' });
    await expect(getProgress(7)).resolves.toMatchObject({ percent: 100 });
    await expect(getArtifacts(7)).resolves.toHaveLength(1);
    await expect(getReview(7)).resolves.toMatchObject({ score: 100 });
    await expect(exportMarkdown(7)).resolves.toBe('# Campus Marketplace');

    expect(fetchMock).toHaveBeenNthCalledWith(1, '/api/projects/7/generate', { method: 'POST' });
    expect(fetchMock).toHaveBeenNthCalledWith(2, '/api/projects/7/progress');
    expect(fetchMock).toHaveBeenNthCalledWith(3, '/api/projects/7/artifacts');
    expect(fetchMock).toHaveBeenNthCalledWith(4, '/api/projects/7/review');
    expect(fetchMock).toHaveBeenNthCalledWith(5, '/api/projects/7/export?format=MARKDOWN', { method: 'POST' });
  });

  it('supports v2 prd gate approve continue retry events and pdf export', async () => {
    fetchMock
      .mockReturnValueOnce(jsonResponse({ projectId: 7, status: 'PRD_REVIEW', percent: 20 }))
      .mockReturnValueOnce(jsonResponse({ id: 3, status: 'PENDING_REVIEW', version: 2 }))
      .mockReturnValueOnce(jsonResponse({ id: 3, status: 'APPROVED', version: 2 }))
      .mockReturnValueOnce(jsonResponse({ projectId: 7, status: 'COMPLETED', percent: 100 }))
      .mockReturnValueOnce(jsonResponse([{ id: 11, eventType: 'NODE_STARTED', nodeName: 'architect' }]))
      .mockReturnValueOnce(jsonResponse({ taskId: 9, status: 'SUCCEEDED', retryOfTaskId: 8 }))
      .mockReturnValueOnce(
        jsonResponse({
          format: 'PDF',
          content: 'JVBERi0=',
          fileName: 'autospec-project-7.pdf',
          mediaType: 'application/pdf',
          encoding: 'base64'
        })
      );

    await expect(generatePrd(7)).resolves.toMatchObject({ status: 'PRD_REVIEW' });
    await expect(updateArtifact(7, 3, '{"title":"PRD"}')).resolves.toMatchObject({ version: 2 });
    await expect(approveArtifact(7, 3)).resolves.toMatchObject({ status: 'APPROVED' });
    await expect(continueGeneration(7)).resolves.toMatchObject({ status: 'COMPLETED' });
    await expect(getEventHistory(7)).resolves.toHaveLength(1);
    await expect(retryTask(7, 8)).resolves.toMatchObject({ retryOfTaskId: 8 });
    await expect(exportPdf(7)).resolves.toMatchObject({ format: 'PDF' });

    expect(fetchMock).toHaveBeenNthCalledWith(1, '/api/projects/7/generate-prd', { method: 'POST' });
    expect(fetchMock).toHaveBeenNthCalledWith(2, '/api/projects/7/artifacts/3', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ content: '{"title":"PRD"}' })
    });
    expect(fetchMock).toHaveBeenNthCalledWith(3, '/api/projects/7/artifacts/3/approve', { method: 'POST' });
    expect(fetchMock).toHaveBeenNthCalledWith(4, '/api/projects/7/continue', { method: 'POST' });
    expect(fetchMock).toHaveBeenNthCalledWith(5, '/api/projects/7/events/history');
    expect(fetchMock).toHaveBeenNthCalledWith(6, '/api/projects/7/tasks/8/retry', { method: 'POST' });
    expect(fetchMock).toHaveBeenNthCalledWith(7, '/api/projects/7/export?format=PDF', { method: 'POST' });
  });
});
