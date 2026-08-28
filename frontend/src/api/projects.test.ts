import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  approveArtifact,
  createProject,
  exportMarkdown,
  exportPdf,
  getArtifacts,
  getReview,
  updateArtifact
} from './projects';
import { generateCodeSkeleton } from './workflow';

function jsonResponse(body: unknown) {
  return Promise.resolve({
    ok: true,
    json: () => Promise.resolve(body)
  } as Response);
}

describe('current project api client', () => {
  const fetchMock = vi.fn();

  beforeEach(() => {
    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    fetchMock.mockReset();
  });

  it('creates a project without exposing the session token', async () => {
    fetchMock.mockReturnValueOnce(jsonResponse({ projectId: 7, status: 'CREATED' }));

    const result = await createProject({
      name: 'Campus Marketplace',
      requirement: 'Build it.'
    });

    expect(result.projectId).toBe(7);
    expect(fetchMock).toHaveBeenCalledWith('/api/projects', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: 'Campus Marketplace', requirement: 'Build it.' })
    });
  });

  it('reads current artifacts and review, then exports deliverables', async () => {
    fetchMock
      .mockReturnValueOnce(jsonResponse([
        { id: 1, type: 'PRD', title: 'PRD', content: '{}', format: 'JSON', version: 1 }
      ]))
      .mockReturnValueOnce(jsonResponse({ score: 100, issues: [] }))
      .mockReturnValueOnce(jsonResponse({ format: 'MARKDOWN', content: '# Result' }))
      .mockReturnValueOnce(jsonResponse({
        format: 'PDF',
        content: 'JVBERi0=',
        fileName: 'autospec-project-7.pdf',
        mediaType: 'application/pdf',
        encoding: 'base64'
      }));

    await expect(getArtifacts(7)).resolves.toHaveLength(1);
    await expect(getReview(7)).resolves.toMatchObject({ score: 100 });
    await expect(exportMarkdown(7)).resolves.toBe('# Result');
    await expect(exportPdf(7)).resolves.toMatchObject({ format: 'PDF' });

    expect(fetchMock).toHaveBeenNthCalledWith(1, '/api/projects/7/artifacts');
    expect(fetchMock).toHaveBeenNthCalledWith(2, '/api/projects/7/review');
    expect(fetchMock).toHaveBeenNthCalledWith(
      3,
      '/api/projects/7/export?format=MARKDOWN',
      { method: 'POST' }
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      4,
      '/api/projects/7/export?format=PDF',
      { method: 'POST' }
    );
  });

  it('updates and approves an artifact and generates the code bundle', async () => {
    fetchMock
      .mockReturnValueOnce(jsonResponse({ id: 3, status: 'PENDING_REVIEW', version: 2 }))
      .mockReturnValueOnce(jsonResponse({ id: 3, status: 'APPROVED', version: 2 }))
      .mockReturnValueOnce(jsonResponse({
        format: 'ZIP',
        content: 'UEs=',
        fileName: 'autospec-project-7-skeleton.zip',
        mediaType: 'application/zip',
        encoding: 'base64'
      }));

    await updateArtifact(7, 3, '{"title":"PRD"}', 0);
    await approveArtifact(7, 3);
    await expect(generateCodeSkeleton(7)).resolves.toMatchObject({ format: 'ZIP' });

    expect(fetchMock).toHaveBeenNthCalledWith(1, '/api/projects/7/artifacts/3', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ content: '{"title":"PRD"}', expectedLockVersion: 0 })
    });
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      '/api/projects/7/artifacts/3/approve',
      { method: 'POST' }
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      3,
      '/api/projects/7/code-skeleton',
      { method: 'POST' }
    );
  });
});
