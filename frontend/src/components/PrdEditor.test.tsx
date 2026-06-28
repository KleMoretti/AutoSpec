import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import PrdEditor, { isValidJson } from './PrdEditor';
import type { ArtifactResponse } from '../api/projects';

const artifact: ArtifactResponse = {
  id: 3,
  type: 'PRD',
  title: 'PRD',
  content: '{"title":"Campus Marketplace"}',
  format: 'JSON',
  version: 1,
  status: 'PENDING_REVIEW',
  sourceAgent: 'ProductManagerAgent_v2',
  updatedAt: '2026-06-28T10:00:00'
};

describe('PrdEditor', () => {
  it('validates JSON content', () => {
    expect(isValidJson('{"title":"PRD"}')).toBe(true);
    expect(isValidJson('{')).toBe(false);
  });

  it('disables approve when the initial PRD JSON is invalid', () => {
    const html = renderToStaticMarkup(
      <PrdEditor
        artifact={{ ...artifact, content: '{' }}
        onSave={vi.fn()}
        onApprove={vi.fn()}
      />
    );

    expect(html).toContain('Invalid JSON');
    expect(html).toContain('disabled=""');
  });
});
