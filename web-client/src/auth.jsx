import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { api, setToken, setUnauthorizedHandler } from './api.js';

const AuthContext = createContext(null);

function decode(token) {
  try {
    const payload = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
    return JSON.parse(atob(payload));
  } catch {
    return null;
  }
}

function sessionFromToken(token) {
  const claims = token && decode(token);
  if (!claims || claims.exp * 1000 < Date.now()) return null;
  return { token, user: claims.sub, role: claims.role, patientId: claims.patientId || null };
}

const readStored = () => {
  try { return sessionStorage.getItem('sh-token'); } catch { return null; }
};

export function AuthProvider({ children }) {
  const [session, setSession] = useState(() => {
    const s = sessionFromToken(readStored());
    if (s) setToken(s.token);
    return s;
  });

  const logout = useCallback(() => {
    setToken(null);
    try { sessionStorage.removeItem('sh-token'); } catch { /* ignore */ }
    setSession(null);
  }, []);

  useEffect(() => { setUnauthorizedHandler(logout); }, [logout]);

  const login = useCallback(async (username, password) => {
    const res = await api('/api/auth/login', { method: 'POST', body: { username, password }, auth: false });
    setToken(res.token);
    try { sessionStorage.setItem('sh-token', res.token); } catch { /* ignore */ }
    setSession(sessionFromToken(res.token));
  }, []);

  const value = useMemo(() => ({ session, login, logout }), [session, login, logout]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export const useAuth = () => useContext(AuthContext);
