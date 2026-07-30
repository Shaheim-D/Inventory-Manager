import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { api, primeCsrf } from '../api/client';
import type { CurrentUser } from '../api/types';

/**
 * The only client-side global state in the app: who is signed in and what they
 * may do. Everything else is server state and belongs to React Query.
 *
 * `has()` reads the permission set the server resolved. The UI never derives
 * permissions itself, and never branches on a role name.
 */
interface AuthValue {
  user: CurrentUser | null;
  loading: boolean;
  has: (permission: string) => boolean;
  hasAny: (...permissions: string[]) => boolean;
  signIn: (username: string, password: string) => Promise<void>;
  signOut: () => Promise<void>;
  refresh: () => Promise<void>;
}

const AuthContext = createContext<AuthValue | undefined>(undefined);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<CurrentUser | null>(null);
  const [loading, setLoading] = useState(true);

  const refresh = useCallback(async () => {
    try {
      setUser(await api.get<CurrentUser>('/api/auth/me'));
    } catch {
      setUser(null);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void (async () => {
      await primeCsrf();
      await refresh();
    })();
  }, [refresh]);

  const signIn = useCallback(async (username: string, password: string) => {
    await primeCsrf();
    const signedIn = await api.post<CurrentUser>('/api/auth/login', { username, password });
    setUser(signedIn);
  }, []);

  const signOut = useCallback(async () => {
    await api.post('/api/auth/logout');
    setUser(null);
    // The session cookie is gone; a full reload also clears every cached query.
    window.location.assign('/login');
  }, []);

  const value = useMemo<AuthValue>(() => {
    const permissions = new Set(user?.permissions ?? []);
    return {
      user,
      loading,
      has: (permission) => permissions.has(permission),
      hasAny: (...wanted) => wanted.some((permission) => permissions.has(permission)),
      signIn,
      signOut,
      refresh,
    };
  }, [user, loading, signIn, signOut, refresh]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthValue {
  const context = useContext(AuthContext);
  if (!context) throw new Error('useAuth must be used inside AuthProvider');
  return context;
}
