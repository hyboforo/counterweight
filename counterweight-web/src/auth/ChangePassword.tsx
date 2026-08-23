import { useState } from "react";
import { api, ApiFailure, OfflineFailure } from "../api/client";
import { useAuth } from "./AuthContext";
import { Button, Field, inputClass } from "../ui/components";

/**
 * Replacing a temporary password.
 *
 * The server's `TemporaryPasswordFilter` answers 403 to everything except this,
 * `/me` and sign-out, which makes this screen the only thing a new account can
 * do — and the reason a password typed on paper by somebody else, and read by
 * whoever walked past the counter, is a one-time value rather than a credential.
 *
 * Two things worth knowing about the flow:
 *
 *  1. **Changing a password kills every session for the account, this one
 *     included.** That is the point of it when the old one was seen. So the
 *     tokens in hand are dead the moment the server answers, and the screen
 *     signs back in with the new password rather than leaving the person
 *     staring at a till that 401s on the first scan.
 *  2. **The rules are not restated here.** Length, the blocklist and "not your
 *     own name" all live in `PasswordPolicy`, and a copy on this side would
 *     drift from it — refusing a password the server would take, or promising
 *     one it will not. The server's message is written to be read out loud.
 *     What this screen does enforce is the one thing it owns: the two boxes
 *     matching, which the server cannot check.
 */
export function ChangePasswordScreen({
  username,
  onChanged,
  onCancel,
}: {
  username: string;
  onChanged: () => void;
  /** Absent when the change is compulsory — there is nowhere to go back to. */
  onCancel?: () => void;
}) {
  const { signOut } = useAuth();
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [again, setAgain] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const mismatch = again !== "" && next !== again;
  const ready = current !== "" && next !== "" && next === again;

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!ready) return;
    setBusy(true);
    setError(null);
    try {
      await api.post("/api/auth/change-password", {
        currentPassword: current,
        newPassword: next,
      });
    } catch (err) {
      setError(readable(err));
      setBusy(false);
      return;
    }

    /*
     * The password is changed from here on, and the tokens in hand died with
     * it. Signing straight back in is what makes this one screen rather than
     * two — but if that second call fails, retrying the first would only be
     * refused, so the honest fallback is the sign-in screen and the password
     * they just chose.
     */
    try {
      await api.login(username, next);
      onChanged();
    } catch {
      await signOut();
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
            {username}
          </div>
          <h1 className="m-0 mt-1 text-2xl font-semibold">
            {onCancel ? "Change your password" : "Set your own password"}
          </h1>
          {!onCancel && (
            <p className="m-0 mt-2 text-[13.5px] text-inksoft leading-relaxed">
              The password you were given is temporary and other people have seen
              it. Nothing else works until it is replaced.
            </p>
          )}
        </div>

        <Field label={onCancel ? "Current password" : "The password you were given"}>
          <input
            className={inputClass}
            type="password"
            value={current}
            autoFocus
            autoComplete="current-password"
            onChange={(e) => setCurrent(e.target.value)}
          />
        </Field>

        <Field label="New password">
          <input
            className={inputClass}
            type="password"
            value={next}
            autoComplete="new-password"
            onChange={(e) => setNext(e.target.value)}
          />
        </Field>

        <Field label="Type it again" error={mismatch ? "The two do not match." : undefined}>
          <input
            className={inputClass}
            type="password"
            value={again}
            autoComplete="new-password"
            onChange={(e) => setAgain(e.target.value)}
          />
        </Field>

        {error && (
          <div className="text-[13.5px] text-danger bg-dangerwash border-l-[3px] border-l-danger px-3 py-2.5 rounded">
            {error}
          </div>
        )}

        <div className="grid gap-2">
          <Button type="submit" variant="primary" disabled={busy || !ready}>
            {busy ? "Setting it…" : "Set my password"}
          </Button>
          {onCancel && (
            <Button type="button" variant="quiet" onClick={onCancel}>
              Not now
            </Button>
          )}
        </div>
      </form>
    </div>
  );
}

const readable = (err: unknown): string =>
  err instanceof OfflineFailure
    ? "The server cannot be reached."
    : err instanceof ApiFailure
      ? err.message
      : String(err);
