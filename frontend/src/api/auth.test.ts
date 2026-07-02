import { afterEach, describe, expect, it, vi } from 'vitest';
import { clearSession, subscribeSession, writeSession } from './auth';

describe('auth session store', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('notifies subscribers when the current session changes', () => {
    const storage = new Map<string, string>();
    vi.stubGlobal('localStorage', {
      getItem: (key: string) => storage.get(key) ?? null,
      setItem: (key: string, value: string) => storage.set(key, value),
      removeItem: (key: string) => storage.delete(key)
    });
    const listener = vi.fn();

    const unsubscribe = subscribeSession(listener);
    writeSession({
      userId: 3,
      username: 'owner',
      displayName: 'Owner',
      sessionToken: 'session-abc'
    });
    clearSession();
    unsubscribe();

    expect(listener).toHaveBeenNthCalledWith(1, {
      userId: 3,
      username: 'owner',
      displayName: 'Owner',
      sessionToken: 'session-abc'
    });
    expect(listener).toHaveBeenNthCalledWith(2, null);
  });
});
