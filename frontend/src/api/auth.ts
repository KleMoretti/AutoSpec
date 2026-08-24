export interface SessionUser {
  userId: number;
  username: string;
  displayName: string;
}

export interface LoginResponse extends SessionUser {
  sessionToken?: string;
}

type SessionListener = (session: SessionUser | null) => void;

const sessionListeners = new Set<SessionListener>();

export async function login(username: string, password: string): Promise<LoginResponse> {
  const response = await fetch('/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password })
  });
  if (!response.ok) {
    throw new Error(`Login failed: ${response.status}`);
  }
  return response.json() as Promise<LoginResponse>;
}

export function readSession(): SessionUser | null {
  if (typeof localStorage === 'undefined') {
    return null;
  }
  const raw = localStorage.getItem('autospec.session');
  if (!raw) {
    return null;
  }
  try {
    const parsed = JSON.parse(raw) as SessionUser;
    return {
      userId: parsed.userId,
      username: parsed.username,
      displayName: parsed.displayName
    };
  } catch {
    return null;
  }
}

export function writeSession(session: LoginResponse): void {
  const publicSession: SessionUser = {
    userId: session.userId,
    username: session.username,
    displayName: session.displayName
  };
  localStorage.setItem('autospec.session', JSON.stringify(publicSession));
  notifySessionListeners(publicSession);
}

export function clearSession(): void {
  localStorage.removeItem('autospec.session');
  notifySessionListeners(null);
}

export function subscribeSession(listener: SessionListener): () => void {
  sessionListeners.add(listener);
  return () => sessionListeners.delete(listener);
}

function notifySessionListeners(session: SessionUser | null): void {
  sessionListeners.forEach((listener) => listener(session));
}
