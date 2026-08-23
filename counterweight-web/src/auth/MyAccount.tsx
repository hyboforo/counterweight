import { useState } from "react";
import { api, ApiFailure } from "../api/client";
import { APPROVAL_PERMISSIONS } from "./approval";
import { useAuth } from "./AuthContext";
import { describe } from "../till/useSale";
import { Button, Callout, Field, Panel, inputClass, useToast } from "../ui/components";

/**
 * Your own account: the password, and the PIN you approve with.
 *
 * Both are yours alone. An administrator can take either away — reset the
 * password, clear the PIN — and can choose neither, because an override is
 * recorded against the person whose PIN was typed, and a PIN somebody else
 * picked would put their name on an authorisation they never gave.
 */
export function MyAccount({
  onClose,
  onChangePassword,
}: {
  onClose: () => void;
  onChangePassword: () => void;
}) {
  const { user, can } = useAuth();
  const mayApprove = APPROVAL_PERMISSIONS.some((p) => can(p));

  return (
    <Panel title="My account" hint={user?.username} onClose={onClose}>
      <div className="grid gap-1 text-[13.5px]">
        <div className="grid grid-cols-[110px_1fr] gap-3">
          <span className="text-inkfaint">Signed in as</span>
          <span className="font-medium">{user?.username}</span>
        </div>
        <div className="grid grid-cols-[110px_1fr] gap-3">
          <span className="text-inkfaint">Roles</span>
          <span>{user?.roles.map((r) => r.replace(/_/g, " ").toLowerCase()).join(", ")}</span>
        </div>
      </div>

      <div className="flex gap-2 items-center flex-wrap pt-1">
        <Button onClick={onChangePassword}>Change my password</Button>
        <span className="text-[12.5px] text-inksoft">
          It signs every device out, this one included.
        </span>
      </div>

      {mayApprove && <TillPin />}
    </Panel>
  );
}

/**
 * The till PIN.
 *
 * Short on purpose. The alternative at a counter is the supervisor typing a
 * full password in front of a queue, which in practice means the supervisor
 * tells the cashier the password once and never comes over again — a shared
 * password is worse than a four-digit secret that authorises one action and
 * issues no session.
 */
function TillPin() {
  const toast = useToast();
  const { user, reload } = useAuth();
  const [password, setPassword] = useState("");
  const [pin, setPin] = useState("");
  const [again, setAgain] = useState("");
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);

  const has = user?.hasOverridePin ?? false;
  const mismatch = again !== "" && pin !== again;
  const ready = password !== "" && pin !== "" && pin === again;

  async function save() {
    setBusy(true);
    setErrors({});
    try {
      await api.post("/api/auth/override-pin", { currentPassword: password, pin });
      setPassword("");
      setPin("");
      setAgain("");
      // /me reports whether a PIN exists, so the panel stops offering to set a
      // first one the moment there is one.
      await reload();
      toast({ title: "Your till PIN is set", tone: "good" });
    } catch (err) {
      if (err instanceof ApiFailure) setErrors(err.fieldErrors);
      toast(describe(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="grid gap-3 pt-3 border-t border-linesoft">
      <div>
        <h3 className="m-0 text-[13.5px] font-semibold">
          {has ? "Change my till PIN" : "Set my till PIN"}
        </h3>
        <p className="m-0 mt-1 text-[12.5px] text-inksoft leading-relaxed max-w-[52ch]">
          What you type at somebody else's till to approve a discount, a price
          change or a sale over a customer's credit limit. It authorises that one
          action, under your own roles, and is recorded against your name.
        </p>
      </div>

      {!has && (
        <Callout>
          You have no till PIN. Until you set one, every approval asked of you is
          refused — in the same words as a wrong PIN, so nobody at the counter
          will be able to tell why.
        </Callout>
      )}

      <div className="grid gap-3 sm:grid-cols-2">
        <Field label="Your password" error={errors.currentPassword}>
          <input
            className={inputClass}
            type="password"
            value={password}
            autoComplete="current-password"
            onChange={(e) => setPassword(e.target.value)}
          />
        </Field>
        <div />
        <Field label={has ? "New PIN" : "PIN"} error={errors.pin}>
          <input
            className={inputClass + " tnum tracking-[.3em]"}
            type="password"
            inputMode="numeric"
            value={pin}
            autoComplete="off"
            onChange={(e) => setPin(e.target.value)}
          />
        </Field>
        <Field label="Type it again" error={mismatch ? "The two do not match." : undefined}>
          <input
            className={inputClass + " tnum tracking-[.3em]"}
            type="password"
            inputMode="numeric"
            value={again}
            autoComplete="off"
            onChange={(e) => setAgain(e.target.value)}
          />
        </Field>
      </div>

      <div className="flex gap-2 items-center">
        <Button variant="primary" disabled={busy || !ready} onClick={() => void save()}>
          {busy ? "Setting it…" : has ? "Replace my PIN" : "Set my PIN"}
        </Button>
      </div>
    </div>
  );
}
