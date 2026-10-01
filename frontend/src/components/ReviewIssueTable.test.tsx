import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import ReviewIssueTable from './ReviewIssueTable';

describe('ReviewIssueTable load states', () => {
  it('does not disguise a failed request as an empty review', () => {
    const html = renderToStaticMarkup(
      <ReviewIssueTable
        projectId={7}
        review={null}
        artifacts={[]}
        loadError="Request failed: 503"
      />
    );

    expect(html).toContain('Review findings could not be loaded');
    expect(html).not.toContain('No review findings');
  });

  it('distinguishes not-yet-produced from a genuinely empty review', () => {
    const pending = renderToStaticMarkup(
      <ReviewIssueTable projectId={7} review={null} artifacts={[]} />
    );
    const empty = renderToStaticMarkup(
      <ReviewIssueTable projectId={7} review={{ score: 100, issues: [] }} artifacts={[]} />
    );

    expect(pending).toContain('Review has not been produced yet');
    expect(empty).toContain('No review findings');
  });

  it('renders an unknown score as an empty-state value', () => {
    const html = renderToStaticMarkup(
      <ReviewIssueTable projectId={7} review={{ score: null, issues: [] }} artifacts={[]} />
    );

    expect(html).toContain('>--</span>');
    expect(html).not.toContain('null/100');
  });
});
