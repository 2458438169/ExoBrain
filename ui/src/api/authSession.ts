let csrfToken: string | null = null;
let revision = 0;
const expiredListeners = new Set<() => void>();

export function authSnapshot() {
  return { csrfToken, revision };
}

export function setAuthToken(token: string | null) {
  csrfToken = token;
  revision += 1;
  return revision;
}

export function expireAuthSession(requestRevision: number) {
  if (requestRevision !== revision) return;
  setAuthToken(null);
  expiredListeners.forEach((listener) => listener());
}

export function onAuthExpired(listener: () => void) {
  expiredListeners.add(listener);
  return () => { expiredListeners.delete(listener); };
}
