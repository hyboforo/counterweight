import { useCallback, useEffect, useState } from "react";
import { api, ApiFailure } from "../api/client";
import type { BackupRunView, BackupStatus, ConfigView } from "../api/types";
import { useAuth } from "../auth/AuthContext";
import { describe } from "../till/useSale";
import { Button, Callout, Field, Panel, inputClass, useToast } from "../ui/components";

/**
 * What the shop decides.
 *
 * The other half of the split V9 drew: `application.yml` holds what the machine
 * needs to start — the datasource, the signing key, the lockout policy — and
 * this holds what the owner decides. Rounding, paper width, expiry horizons,
 * reorder terms, the backup window. Waiting for a release to change any of
 * those would be absurd; being able to change a signing key from a screen would
 * mean it was not a signing key.
 *
 * Nothing here creates a key. Every row corresponds to code that reads it, and
 * a settings screen that accepts free-form keys accumulates
 * `currency.rounding.incremnt` sitting next to the real one, with the shop
 * convinced it changed something.
 */
export function Settings() {
  const { can } = useAuth();
  const mayConfigure = can("CONFIG_MANAGE");
  const mayBackup = can("BACKUP_MANAGE");

  return (
    <div className="h-full overflow-y-auto min-h-0">
      <div className="max-w-3xl mx-auto p-5 grid gap-8">
        {mayConfigure && <ShopSettings />}
        {/* Backup status is readable by anyone signed in; running one is not. */}
        <Backups mayRun={mayBackup} />
      </div>
    </div>
  );
}

/* ── The settings themselves ────────────────────────────────────────────── */

/**
 * Group titles for the key prefixes, and a fallback that matters.
 *
 * A future migration will add a key with a prefix nothing here knows, and the
 * right behaviour then is to show it under its own prefix rather than to hide
 * it. A setting the shop cannot find is the same as a setting that does not
 * work — which is the failure `PlatformTest.noSettingLies` guards from the
 * other side.
 */
const GROUPS: Record<string, string> = {
  currency: "Money",
  receipt: "Receipts",
  agent: "The printer agent",
  expiry: "Expiry warnings",
  reorder: "Reordering",
  dead: "Dead stock",
  backup: "Backups",
};

function ShopSettings() {
  const toast = useToast();
  const [rows, setRows] = useState<ConfigView[] | null>(null);

  const load = useCallback(async () => {
    setRows(await api.get<ConfigView[]>("/api/config"));
  }, []);

  useEffect(() => {
    void load().catch((err) => toast(describe(err)));
  }, [load, toast]);

  if (!rows) return <div className="text-inksoft">Loading the settings…</div>;

  const groups = new Map<string, ConfigView[]>();
  for (const row of rows) {
    const prefix = row.key.split(".")[0] ?? row.key;
    const title = GROUPS[prefix] ?? prefix;
    groups.set(title, [...(groups.get(title) ?? []), row]);
  }

  return (
    <section className="grid gap-5">
      <div>
        <h1 className="m-0 text-xl font-semibold">Settings</h1>
        <p className="m-0 mt-1 text-[13.5px] text-inksoft leading-relaxed max-w-[62ch]">
          What the shop decides. Everything the machine needs to start — the
          database, the signing key, how long a lockout lasts — lives in the
          server's own configuration and is deliberately not editable here.
        </p>
      </div>

      {[...groups].map(([title, settings]) => (
        <div key={title} className="grid gap-2">
          <h2 className="m-0 text-[11px] uppercase tracking-[.07em] text-inkfaint font-semibold">
            {title}
          </h2>
          <div className="bg-surface border border-line rounded">
            {settings.map((setting) => (
              <SettingRow
                key={setting.key}
                setting={setting}
                onSaved={(saved) =>
                  setRows((all) =>
                    (all ?? []).map((r) => (r.key === saved.key ? saved : r)),
                  )
                }
              />
            ))}
          </div>
        </div>
      ))}

      <div className="flex items-center gap-3 flex-wrap">
        <Button
          variant="quiet"
          onClick={() =>
            void api
              .post("/api/config/reload")
              .then(() => load())
              .then(() => toast({ title: "Re-read from the database", tone: "info" }))
              .catch((err) => toast(describe(err)))
          }
        >
          Re-read from the database
        </Button>
        <span className="text-[12.5px] text-inkfaint max-w-[46ch]">
          Only needed if a value was changed in the database by hand — the server
          caches these because they are read on every sale.
        </span>
      </div>
    </section>
  );
}

function SettingRow({
  setting,
  onSaved,
}: {
  setting: ConfigView;
  onSaved: (saved: ConfigView) => void;
}) {
  const toast = useToast();
  const [value, setValue] = useState(setting.value);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    setValue(setting.value);
    setError(null);
  }, [setting.value]);

  const changed = value.trim() !== setting.value;

  async function save(next: string) {
    setBusy(true);
    setError(null);
    try {
      onSaved(await api.put<ConfigView>(`/api/config/${setting.key}`, { value: next }));
      toast({ title: "Saved", body: setting.key, tone: "good" });
    } catch (err) {
      // The server's own validation message names the type it expected, which
      // is more use than "invalid".
      setError(err instanceof ApiFailure ? err.message : String(err));
      toast(describe(err));
      setValue(setting.value);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="grid gap-2 px-4 py-3.5 border-b border-linesoft last:border-b-0">
      <div className="grid sm:grid-cols-[1fr_auto] gap-3 items-start">
        <div className="min-w-0">
          <div className="font-mono text-[12px] text-inkfaint">{setting.key}</div>
          {setting.description && (
            <p className="m-0 mt-0.5 text-[13px] text-inksoft leading-relaxed max-w-[52ch]">
              {setting.description}
            </p>
          )}
        </div>

        <div className="flex items-center gap-2 justify-end">
          {setting.valueType === "BOOL" ? (
            <label className="flex items-center gap-2 text-[13.5px] cursor-pointer">
              <input
                type="checkbox"
                checked={value.toLowerCase() === "true"}
                disabled={busy}
                onChange={(e) => {
                  const next = e.target.checked ? "true" : "false";
                  setValue(next);
                  void save(next);
                }}
              />
              <span>{value.toLowerCase() === "true" ? "On" : "Off"}</span>
            </label>
          ) : (
            <>
              <input
                className={
                  inputClass +
                  " py-1.5 " +
                  (setting.valueType === "NUMBER"
                    ? "tnum text-right max-w-[130px]"
                    : "font-mono text-[13px] max-w-[230px]")
                }
                value={value}
                inputMode={setting.valueType === "NUMBER" ? "decimal" : undefined}
                disabled={busy}
                onChange={(e) => setValue(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === "Enter" && changed) void save(value.trim());
                  if (e.key === "Escape") setValue(setting.value);
                }}
              />
              <Button
                variant="quiet"
                disabled={!changed || busy}
                onClick={() => void save(value.trim())}
              >
                {busy ? "…" : "Save"}
              </Button>
            </>
          )}
        </div>
      </div>

      {error && <div className="text-[13px] text-danger">{error}</div>}
    </div>
  );
}

/* ── Backups ────────────────────────────────────────────────────────────── */

/**
 * Whether the shop's records would survive this machine.
 *
 * §14 calls losing it the end of the business's records, which is why the
 * status is readable by whoever is signed in rather than gated: "are we
 * protected" should not need a permission. Running and recording backups does.
 *
 * The screen leads with the verified date, not the last successful dump,
 * because that is what "protected" means here — a dump nobody has ever restored
 * proves the job runs, not that the file can be read back.
 */
function Backups({ mayRun }: { mayRun: boolean }) {
  const toast = useToast();
  const [status, setStatus] = useState<BackupStatus | null>(null);
  const [history, setHistory] = useState<BackupRunView[]>([]);
  const [busy, setBusy] = useState<string | null>(null);
  const [recording, setRecording] = useState(false);

  const load = useCallback(async () => {
    const [s, h] = await Promise.all([
      api.get<BackupStatus>("/api/backups/status"),
      api.get<BackupRunView[]>("/api/backups"),
    ]);
    setStatus(s);
    setHistory(h);
  }, []);

  useEffect(() => {
    void load().catch((err) => toast(describe(err)));
  }, [load, toast]);

  async function run(what: string, path: string, nothingHappened: string) {
    setBusy(what);
    try {
      const result = await api.post<BackupRunView | null>(path);
      // Both endpoints answer with no body when there was nothing to do —
      // backups switched off, or no dump to restore yet. Silence there would
      // read as a button that does nothing.
      if (!result) toast({ title: nothingHappened, tone: "warn" });
      else if (result.status === "FAILED") {
        toast({ title: "It failed", body: result.error ?? undefined, tone: "danger" });
      } else toast({ title: "Done", body: result.destination, tone: "good" });
      await load();
    } catch (err) {
      toast(describe(err));
    } finally {
      setBusy(null);
    }
  }

  if (!status) return <div className="text-inksoft">Reading the backup register…</div>;

  return (
    <section className="grid gap-4">
      <div>
        <h2 className="m-0 text-xl font-semibold">Backups</h2>
        <p className="m-0 mt-1 text-[13.5px] text-inksoft leading-relaxed max-w-[62ch]">
          Losing this machine without one of these is losing the shop's records.
          A backup counts as protection only once it has been restored and
          checked.
        </p>
      </div>

      <div
        className={
          "rounded border px-4 py-4 grid gap-2 " +
          (status.stale ? "bg-dangerwash border-danger/30" : "bg-surface border-line")
        }
      >
        <div className={"text-lg font-semibold " + (status.stale ? "text-danger" : "text-good")}>
          {status.stale ? "Not protected" : "Protected"}
        </div>
        <div className="text-[13.5px] text-inksoft grid gap-0.5">
          <span>
            Last verified:{" "}
            {status.lastVerifiedAt ? (
              <strong className="text-ink">
                {new Date(status.lastVerifiedAt).toLocaleString()}
              </strong>
            ) : (
              <strong className="text-ink">never — no backup has been restored yet</strong>
            )}
            {/*
              * `!= null`, not `!== null`. The API omits nulls rather than
              * sending them, so an absent figure is `undefined` and the strict
              * test passes — which printed a bare "( h ago)" next to "never".
              */}
            {status.hoursSinceVerified != null && (
              <span className="text-inkfaint"> ({status.hoursSinceVerified}h ago)</span>
            )}
          </span>
          <span>
            Last dump written:{" "}
            {status.lastDumpAt ? new Date(status.lastDumpAt).toLocaleString() : "never"}
          </span>
          <span className="text-inkfaint">
            A critical alert fires after {status.staleAfterHours} hours without a
            verified backup.
          </span>
        </div>
        {status.lastError && (
          <Callout tone="danger">Last failure: {status.lastError}</Callout>
        )}
      </div>

      {mayRun && (
        <div className="flex gap-2 flex-wrap">
          <Button
            variant="quiet"
            disabled={busy !== null}
            onClick={() =>
              void run("dump", "/api/backups/run", "Backups are switched off in the settings.")
            }
          >
            {busy === "dump" ? "Dumping…" : "Back up now"}
          </Button>
          <Button
            variant="quiet"
            disabled={busy !== null}
            onClick={() =>
              void run("verify", "/api/backups/verify", "There is no dump to restore yet.")
            }
          >
            {busy === "verify" ? "Restoring into a scratch copy…" : "Run the restore drill"}
          </Button>
          <Button variant="quiet" disabled={busy !== null} onClick={() => setRecording(true)}>
            Record one taken elsewhere
          </Button>
        </div>
      )}

      {mayRun && (
        <p className="m-0 text-[12.5px] text-inksoft leading-relaxed max-w-[62ch]">
          The drill restores the latest dump into a scratch database and drops it
          again. It never touches the live one.
        </p>
      )}

      {history.length > 0 && (
        <div className="bg-surface border border-line rounded overflow-x-auto">
          <table className="w-full text-[13px] border-collapse">
            <thead>
              <tr className="text-left text-inkfaint text-[11px] uppercase tracking-[.05em]">
                <th className="font-medium px-4 py-2">Started</th>
                <th className="font-medium px-4 py-2">Kind</th>
                <th className="font-medium px-4 py-2">Where</th>
                <th className="font-medium px-4 py-2">Result</th>
              </tr>
            </thead>
            <tbody>
              {history.map((run) => (
                <tr key={run.id} className="border-t border-linesoft align-top">
                  <td className="px-4 py-2 whitespace-nowrap">
                    {new Date(run.startedAt).toLocaleString()}
                  </td>
                  <td className="px-4 py-2 text-inksoft">{run.kind.replace(/_/g, " ").toLowerCase()}</td>
                  <td className="px-4 py-2 font-mono text-[12px] text-inksoft break-all">
                    {run.destination}
                  </td>
                  <td className="px-4 py-2">
                    <RunResult run={run} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {recording && (
        <RecordBackup
          onClose={() => setRecording(false)}
          onRecorded={() => {
            setRecording(false);
            void load().catch((err) => toast(describe(err)));
          }}
        />
      )}
    </section>
  );
}

function RunResult({ run }: { run: BackupRunView }) {
  if (run.status === "FAILED") {
    return (
      <span className="text-danger">
        failed{run.error && <span className="text-inksoft"> — {run.error}</span>}
      </span>
    );
  }
  if (run.verifiedAt) {
    return <span className="text-good">verified {new Date(run.verifiedAt).toLocaleDateString()}</span>;
  }
  if (run.verifyError) {
    return <span className="text-danger">restore drill failed — {run.verifyError}</span>;
  }
  return (
    <span className="text-warn">
      written, never restored
      {run.sizeBytes != null && (
        <span className="text-inkfaint"> ({Math.round(run.sizeBytes / 1024 / 1024)} MB)</span>
      )}
    </span>
  );
}

/**
 * A backup this application did not perform.
 *
 * WAL archiving is PostgreSQL's own machinery and the off-site copy involves
 * somebody carrying a disk home. Recording them is what makes the register
 * describe the shop's real protection rather than only the part that happens
 * inside this process.
 */
function RecordBackup({
  onClose,
  onRecorded,
}: {
  onClose: () => void;
  onRecorded: () => void;
}) {
  const toast = useToast();
  const [kind, setKind] = useState("OFFSITE");
  const [destination, setDestination] = useState("");
  const [verified, setVerified] = useState(false);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);

  async function record() {
    setBusy(true);
    setErrors({});
    try {
      await api.post("/api/backups/record", {
        kind,
        destination: destination.trim(),
        verified,
      });
      onRecorded();
    } catch (err) {
      if (err instanceof ApiFailure) setErrors(err.fieldErrors);
      toast(describe(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Panel title="Record a backup taken elsewhere" onClose={onClose}>
      <Field label="What kind">
        <select className={inputClass} value={kind} onChange={(e) => setKind(e.target.value)}>
          <option value="OFFSITE">Off-site copy — a disk carried away</option>
          <option value="WAL_ARCHIVE">WAL archive — PostgreSQL's own</option>
          <option value="DUMP">A dump taken by hand</option>
        </select>
      </Field>

      <Field label="Where it went" error={errors.destination}>
        <input
          className={inputClass}
          value={destination}
          autoFocus
          placeholder="external disk, taken home Friday"
          onChange={(e) => setDestination(e.target.value)}
        />
      </Field>

      <label className="flex items-start gap-2.5 text-[13.5px] cursor-pointer">
        <input
          type="checkbox"
          className="mt-1"
          checked={verified}
          onChange={(e) => setVerified(e.target.checked)}
        />
        <span>
          It was restored and checked
          <span className="block text-[12.5px] text-inkfaint leading-relaxed">
            Only tick this if somebody actually restored it. Ticking it here is
            what tells the shop it is protected, and the alert believes you.
          </span>
        </span>
      </label>

      <div className="flex gap-2">
        <Button variant="primary" disabled={busy || destination.trim() === ""} onClick={() => void record()}>
          {busy ? "Recording…" : "Record it"}
        </Button>
        <Button variant="quiet" onClick={onClose}>
          Cancel
        </Button>
      </div>
    </Panel>
  );
}
