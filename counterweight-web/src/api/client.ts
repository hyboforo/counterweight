import type { ApiError, LoginResponse } from "./types";

/**
 * The one place the till talks to the server.
 *
 * Two things it owns that nothing else should duplicate:
 *
 *  1. **Token refresh, serialised.** A till fires several requests at once —
 *     search while the basket recalculates. If each noticed the expired token
 *     and refreshed independently, the later ones would present a refresh token
 *     the server has already rotated, and the cashier would be thrown to the
 *     login screen mid-sale. One in-flight refresh, everyone waits for it.
 *
 *  2. **A connection state the interface can show.** §14.1 says a till that
 *     loses the LAN goes read-only. That is a state to display calmly, not an
 *     exception to throw at whoever is standing at the counter.
 */

const ACCESS_KEY = "cw.access";
const REFRESH_KEY = "cw.refresh";

export class ApiFailure extends Error {
  readonly code: string;
  readonly status: number;
  readonly details?: Record<string, unknown>;
  readonly traceId?: string;

  constructor(status: number, body: Partial<ApiError>) {
    super(body.message ?? "Something went wrong.");
    this.name = "ApiFailure";
    this.status = status;
    this.code = body.code ?? "UNKNOWN";
    this.details = body.details;
    this.traceId = body.traceId;
  }

  /** Field-level messages from bean validation, for putting beside an input. */
  get fieldErrors(): Record<string, string> {
    const f = this.details?.fields;
    return f && typeof f === "object" ? (f as Record<string, string>) : {};
  }
}

/** Raised when the server cannot be reached at all — a different thing from a 4xx. */
export class OfflineFailure extends Error {
  constructor() {
    super("The server cannot be reached.");
    this.name = "OfflineFailure";
  }
}

/**
 * The filename out of a Content-Disposition header.
 *
 * Prefers RFC 5987 `filename*` when present, because that is the one that
 * survives a non-ASCII character — a report titled with a customer's name will
 * eventually contain one.
 */
function filenameFrom(header: string | null): string | null {
  if (!header) return null;

  const encoded = /filename\*=UTF-8''([^;]+)/i.exec(header)?.[1];
  if (encoded) {
    try {
      return decodeURIComponent(encoded.trim());
    } catch {
      // A malformed encoding is not worth failing a download over.
    }
  }

  const plain = /filename="?([^";]+)"?/i.exec(header)?.[1];
  return plain ? plain.trim() : null;
}

type ConnectionListener = (online: boolean) => void;
type PasswordListener = () => void;

class Client {
  private access: string | null = localStorage.getItem(ACCESS_KEY);
  private refresh: string | null = localStorage.getItem(REFRESH_KEY);
  private refreshing: Promise<boolean> | null = null;
  private online = true;
  private listeners = new Set<ConnectionListener>();
  private passwordListeners = new Set<PasswordListener>();

  get isAuthenticated(): boolean {
    return this.access !== null;
  }

  get isOnline(): boolean {
    return this.online;
  }

  onConnectionChange(fn: ConnectionListener): () => void {
    this.listeners.add(fn);
    return () => this.listeners.delete(fn);
  }

  /**
   * Fires when the server refuses a request until the password is replaced.
   *
   * This is not only the first sign-in — an administrator can reset somebody's
   * password while they are working, and the access token in their hand keeps
   * working for its remaining life with a stale claim. Without this the till
   * would show "set your own password" as a toast on every action and offer
   * nowhere to do it.
   */
  onPasswordChangeRequired(fn: PasswordListener): () => void {
    this.passwordListeners.add(fn);
    return () => this.passwordListeners.delete(fn);
  }

  private setOnline(next: boolean) {
    if (this.online === next) return;
    this.online = next;
    this.listeners.forEach((fn) => fn(next));
  }

  setTokens(access: string, refresh: string) {
    this.access = access;
    this.refresh = refresh;
    localStorage.setItem(ACCESS_KEY, access);
    localStorage.setItem(REFRESH_KEY, refresh);
  }

  clearTokens() {
    this.access = null;
    this.refresh = null;
    localStorage.removeItem(ACCESS_KEY);
    localStorage.removeItem(REFRESH_KEY);
  }

  async login(username: string, password: string): Promise<LoginResponse> {
    /*
     * Whatever is in hand is dropped before asking.
     *
     * Signing in is unauthenticated by definition, and `raw` attaches the
     * access token to everything. Presenting a stale one here can only cause
     * trouble — it did: after replacing a temporary password the old token
     * still claimed the password was temporary, and the server refused the
     * sign-in that was replacing it.
     */
    this.clearTokens();

    const res = await this.raw("/api/auth/login", {
      method: "POST",
      body: JSON.stringify({ username, password }),
    });
    const body = (await res.json()) as LoginResponse;
    if (!res.ok) throw new ApiFailure(res.status, body as unknown as ApiError);
    this.setTokens(body.accessToken, body.refreshToken);
    return body;
  }

  async logout(): Promise<void> {
    if (this.refresh) {
      await this.raw("/api/auth/logout", {
        method: "POST",
        body: JSON.stringify({ refreshToken: this.refresh }),
      }).catch(() => undefined);
    }
    this.clearTokens();
  }

  get<T>(path: string): Promise<T> {
    return this.request<T>(path, { method: "GET" });
  }

  post<T>(path: string, body?: unknown, headers?: Record<string, string>): Promise<T> {
    return this.request<T>(path, {
      method: "POST",
      body: body === undefined ? undefined : JSON.stringify(body),
      headers,
    });
  }

  put<T>(path: string, body?: unknown): Promise<T> {
    return this.request<T>(path, {
      method: "PUT",
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  }

  del<T>(path: string): Promise<T> {
    return this.request<T>(path, { method: "DELETE" });
  }

  /**
   * A GET that yields a file rather than JSON, saved under the name the server
   * chose.
   *
   * It lives here rather than in the reports screen because everything above
   * about tokens applies to a download too: an export started just after the
   * access token expired must refresh and retry, not silently save a JSON error
   * body with an .xlsx extension. Going through `fetch` — instead of pointing a
   * link or a new window at the URL — is what makes that possible at all, since
   * neither carries the Authorization header.
   */
  async download(path: string, fallbackName: string): Promise<void> {
    let res = await this.raw(path, { method: "GET" });

    if (res.status === 401 && this.refresh) {
      if (!(await this.refreshOnce())) {
        this.clearTokens();
        throw new ApiFailure(401, { code: "UNAUTHENTICATED", message: "Sign in to continue." });
      }
      res = await this.raw(path, { method: "GET" });
    }

    if (!res.ok) {
      // The error path is still JSON, so the caller gets the real message
      // rather than a downloaded file containing it.
      const text = await res.text();
      const body = text ? (JSON.parse(text) as Partial<ApiError>) : {};
      throw new ApiFailure(res.status, body);
    }

    const blob = await res.blob();
    const url = URL.createObjectURL(blob);
    try {
      const a = document.createElement("a");
      a.href = url;
      a.download = filenameFrom(res.headers.get("Content-Disposition")) ?? fallbackName;
      document.body.appendChild(a);
      a.click();
      a.remove();
    } finally {
      // Revoking immediately cancels the save in some browsers; one tick is
      // enough for the click to have been handled.
      window.setTimeout(() => URL.revokeObjectURL(url), 0);
    }
  }

  private async request<T>(
    path: string,
    init: RequestInit & { headers?: Record<string, string> },
    retried = false,
  ): Promise<T> {
    const res = await this.raw(path, init);

    if (res.status === 401 && !retried && this.refresh) {
      const ok = await this.refreshOnce();
      if (ok) return this.request<T>(path, init, true);
      this.clearTokens();
    }

    if (res.status === 204) return undefined as T;

    const text = await res.text();
    const body = text ? (JSON.parse(text) as unknown) : undefined;

    if (!res.ok) {
      const failure = new ApiFailure(res.status, (body ?? {}) as Partial<ApiError>);
      if (failure.code === "PASSWORD_CHANGE_REQUIRED") {
        this.passwordListeners.forEach((fn) => fn());
      }
      throw failure;
    }
    return body as T;
  }

  private async raw(path: string, init: RequestInit & { headers?: Record<string, string> }) {
    // Narrowed explicitly: RequestInit["headers"] is a union that includes
    // Headers and string[][], and spreading either produces something that is
    // not a Record<string, string>.
    const supplied: Record<string, string> = init.headers ?? {};
    const headers: Record<string, string> = {
      "Content-Type": "application/json",
      ...supplied,
    };
    if (this.access) headers.Authorization = `Bearer ${this.access}`;

    try {
      const res = await fetch(path, { ...init, headers });
      this.setOnline(true);
      return res;
    } catch {
      // A network failure is not an API error. The till shows it as a state.
      this.setOnline(false);
      throw new OfflineFailure();
    }
  }

  /** At most one refresh in flight; concurrent callers await the same promise. */
  private refreshOnce(): Promise<boolean> {
    if (this.refreshing) return this.refreshing;

    this.refreshing = (async () => {
      try {
        const res = await this.raw("/api/auth/refresh", {
          method: "POST",
          body: JSON.stringify({ refreshToken: this.refresh }),
        });
        if (!res.ok) return false;
        const body = (await res.json()) as LoginResponse;
        this.setTokens(body.accessToken, body.refreshToken);
        return true;
      } catch {
        return false;
      } finally {
        this.refreshing = null;
      }
    })();

    return this.refreshing;
  }
}

export const api = new Client();

/* ── Money ──────────────────────────────────────────────────────────────── */

/**
 * The server sends money as strings, deliberately — a NUMERIC(14,2) through a
 * JSON double is how a total ends up a pesewa out. Parsing happens at the edge
 * and the till works in numbers from there, rounding only for display.
 */
export const num = (s: string | null | undefined): number =>
  s === null || s === undefined ? 0 : Number.parseFloat(s);

export const money = (n: number): string =>
  n.toLocaleString("en-GH", { minimumFractionDigits: 2, maximumFractionDigits: 2 });

/** Quantities are not money: 3 stays "3", 3.5 metres stays "3.5". */
export const qty = (n: number): string =>
  Number.isInteger(n) ? String(n) : String(Number(n.toFixed(4)));
