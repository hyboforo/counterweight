import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react";
import { api, ApiFailure } from "../api/client";
import type { CurrentUser } from "../api/types";
import { Button, Field, inputClass } from "../ui/components";

interface AuthValue {
  user: CurrentUser | null;
  loading: boolean;
  signIn(username: string, password: string): Promise<void>;
  signOut(): Promise<void>;
  can(permission: string): boolean;
  /** True while the account is still on the password somebody else typed. */
  mustChangePassword: boolean;
  /** Re-reads who this is, after the password was replaced and re-signed-in. */
  reload(): Promise<void>;
}

const AuthContext = createContext<AuthValue>({
  user: null,
  loading: true,
  async signIn() {},
  async signOut() {},
  can: () => false,
  mustChangePassword: false,
  async reload() {},
});

export const useAuth = () => useContext(AuthContext);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<CurrentUser | null>(null);
  const [loading, setLoading] = useState(true);
  const [refused, setRefused] = useState(false);

  /*
   * The server refusing a request until the password is replaced.
   *
   * Not the same thing as the flag on `/me`, and both are needed: an
   * administrator can reset somebody's password while they are mid-shift, and
   * the access token already in their browser keeps working for the rest of its
   * life carrying a claim that is now a lie. The 403 is the only notice of that.
   */
  useEffect(() => api.onPasswordChangeRequired(() => setRefused(true)), []);

  // A refresh token survives a browser restart, so a till that was closed at
  // lunch comes back signed in rather than making the cashier log in again
  // with a queue forming.
  useEffect(() => {
    let cancelled = false;
    (async () => {
      if (!api.isAuthenticated) {
        setLoading(false);
        return;
      }
      try {
        const me = await api.get<CurrentUser>("/api/auth/me");
        if (!cancelled) setUser(me);
      } catch {
        api.clearTokens();
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  const signIn = useCallback(async (username: string, password: string) => {
    await api.login(username, password);
    // The login response carries roles and permissions but not the user id,
    // and the till needs it. One extra call, once per shift.
    setUser(await api.get<CurrentUser>("/api/auth/me"));
  }, []);

  const signOut = useCallback(async () => {
    await api.logout();
    setUser(null);
    setRefused(false);
  }, []);

  const reload = useCallback(async () => {
    setUser(await api.get<CurrentUser>("/api/auth/me"));
    setRefused(false);
  }, []);

  const can = useCallback(
    (permission: string) => user?.permissions.includes(permission) ?? false,
    [user],
  );

  const value = useMemo(
    () => ({
      user,
      loading,
      signIn,
      signOut,
      can,
      mustChangePassword: refused || (user?.mustChangePassword ?? false),
      reload,
    }),
    [user, loading, signIn, signOut, can, refused, reload],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

/* ── Login ──────────────────────────────────────────────────────────────── */

export function LoginScreen() {
  const { signIn } = useAuth();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await signIn(username, password);
    } catch (err) {
      // The server returns the same message for an unknown username and a wrong
      // password on purpose. Passing it straight through keeps that property.
      setError(
        err instanceof ApiFailure ? err.message : "The server cannot be reached.",
      );
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="min-h-full grid place-items-center p-6 bg-ground">
      <form
        onSubmit={submit}
        className="w-full max-w-sm bg-surface border border-line rounded p-6 grid gap-4"
        style={{ boxShadow: "var(--shadow)" }}
      >
        <div>
          <div className="font-mono text-xs tracking-[.12em] text-inkfaint uppercase">
            Bofma Ventures
          </div>
          <h1 className="m-0 mt-1 text-2xl font-semibold">Sign in to the till</h1>
        </div>

        <Field label="Username">
          <input
            className={inputClass}
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            autoFocus
            autoComplete="username"
            spellCheck={false}
          />
        </Field>

        <Field label="Password">
          <input
            className={inputClass}
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="current-password"
          />
        </Field>

        {error && (
          <div className="text-[13.5px] text-danger bg-dangerwash border-l-[3px] border-l-danger px-3 py-2.5 rounded">
            {error}
          </div>
        )}

        <Button type="submit" variant="primary" disabled={busy || !username || !password}>
          {busy ? "Signing in…" : "Sign in"}
        </Button>
      </form>
    </div>
  );
}
