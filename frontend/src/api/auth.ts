export interface LoginResponse {
  userId: number;
  username: string;
  displayName: string;
  sessionToken: string;
}

type SessionListener = (session: LoginResponse | null) => void;

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

export function readSession(): LoginResponse | null {
  if (typeof localStorage === 'undefined') {
    return null;
  }
  const raw = localStorage.getItem('autospec.session');
  if (!raw) {
    return null;
  }
  try {
    return JSON.parse(raw) as LoginResponse;
  } catch {
    return null;
  }
}

export function writeSession(session: LoginResponse): void {
  localStorage.setItem('autospec.session', JSON.stringify(session));
  notifySessionListeners(session);
}

export function clearSession(): void {
  localStorage.removeItem('autospec.session');
  notifySessionListeners(null);
}

export function subscribeSession(listener: SessionListener): () => void {
  sessionListeners.add(listener);
  return () => sessionListeners.delete(listener);
}

function notifySessionListeners(session: LoginResponse | null): void {
  sessionListeners.forEach((listener) => listener(session));
}
