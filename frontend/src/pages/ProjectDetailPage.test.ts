import { describe, expect, it } from 'vitest';
import { isDeliveryReady } from './ProjectDetailPage';

describe('ProjectDetailPage delivery truth', () => {
  it('requires the server-authoritative spec and build gates', () => {
    expect(isDeliveryReady(null)).toBe(false);
    expect(isDeliveryReady({
      specReady: true,
      buildReady: false,
      status: 'BUILD_REQUIRED',
      blockers: ['Generate and verify a delivery bundle']
    })).toBe(false);
    expect(isDeliveryReady({
      specReady: true,
      buildReady: true,
      status: 'READY',
      blockers: []
    })).toBe(true);
  });
});
