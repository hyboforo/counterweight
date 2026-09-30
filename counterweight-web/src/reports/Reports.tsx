import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { api } from "../api/client";
import type { RefreshState, ReportCell, ReportTable } from "../api/types";
import { useAuth } from "../auth/AuthContext";
import { describe } from "../till/useSale";
import { Button, inputClass, useToast } from "../ui/components";
import { FAMILIES, visibleTo, type ReportDef } from "./catalogue";

/**
 * Reports.
 *
 * One screen for all of them, because the server reduces every report to the
 * same grid and builds the export from that same definition. Seventeen bespoke
 * screens would be eighteen chances for what is on the screen to stop matching
 * what comes out of the export, which is the thing §12 is careful about.
 *
 * The rail lists only what this account may run — see `catalogue.ts` for why
 * that matters more than it looks.
 */
export function Reports() {
  const { can } = useAuth();
  const toast = useToast();

  const available = useMemo(() => visibleTo(can), [can]);
  // Offering a download the server will refuse teaches the office that exports
  // are broken. Every seeded role that can view a report can also export one,
  // so this only shows up on a role somebody builds by hand — which is exactly
  // when a silent 403 is hardest to explain.
  const mayExport = can("REPORT_EXPORT");
  const [selected, setSelected] = useState<ReportDef | null>(available[0] ?? null);

  const [range, setRange] = useState(defaultRange);
  const [limit, setLimit] = useState("50");
  const [batch, setBatch] = useState("");
  const [onlyInStock, setOnlyInStock] = useState(true);

  const [table, setTable] = useState<ReportTable | null>(null);
  const [busy, setBusy] = useState(false);
  const [ran, setRan] = useState<Params | null>(null);

  const params: Params = { ...range, limit, batch, onlyInStock };
  // Read through a ref so `run` does not change identity on every keystroke —
  // otherwise the effect below re-runs the report as the date is being typed.
  const latest = useRef(params);
  latest.current = params;

  const run = useCallback(
    async (report: ReportDef) => {
      const p = latest.current;
      if (!ready(report, p)) {
        setTable(null);
        return;
      }
      setBusy(true);
      try {
        setTable(await api.get<ReportTable>(`/api/reports/${report.id}?${queryFor(report, p)}`));
        setRan(p);
      } catch (err) {
        setTable(null);
        toast(describe(err));
      } finally {
        setBusy(false);
      }
    },
    [toast],
  );

  // Picking a report runs it. Changing a parameter does not — a report screen
  // that refetches while somebody is halfway through typing a date is one that
  // answers questions nobody asked.
  useEffect(() => {
    setTable(null);
    if (selected) void run(selected);
  }, [selected, run]);

  /**
   * The export sends the report id and its parameters, never the rows.
   *
   * The server re-runs the report to build the file, which is what keeps the
   * permission checks in front of it — a client-supplied table would let anyone
   * download any figures they could compose. It demands `REPORT_EXPORT` on top
   * of the report's own gate.
   *
   * The parameters go with it, `onlyInStock` included: exporting a different
   * set of rows from the ones on the screen is the drift one report definition
   * exists to prevent.
   */
  async function exportAs(format: "csv" | "xlsx") {
    if (!selected) return;
    const q = queryFor(selected, latest.current);
    q.set("report", selected.id);
    try {
      await api.download(
        `/api/reports/export/${format}?${q}`,
        `${selected.id.replace("/", "-")}.${format}`,
      );
    } catch (err) {
      toast(describe(err));
    }
  }

  if (available.length === 0) {
    return (
      <div className="h-full grid place-items-center p-10 text-center">
        <div className="grid gap-2 max-w-[42ch]">
          <div className="text-lg font-semibold">No reports for this account</div>
          <p className="m-0 text-inksoft text-sm">
            Reports need <code className="font-mono text-[12.5px]">REPORT_VIEW</code>; the control
            registers need <code className="font-mono text-[12.5px]">AUDIT_VIEW</code>. Whoever
            administers accounts can grant them.
          </p>
        </div>
      </div>
    );
  }

  return (
    <div className="h-full grid grid-cols-[minmax(200px,240px)_1fr] min-h-0">
      <nav
        className="border-r border-line bg-surface overflow-y-auto min-h-0 py-2"
        aria-label="Reports"
      >
        {FAMILIES.map((family) => {
          const inFamily = available.filter((r) => r.family === family);
          if (inFamily.length === 0) return null;
          return (
            <div key={family} className="mb-1">
              <h2 className="m-0 px-4 py-1.5 text-[11px] uppercase tracking-[.07em] text-inkfaint">
                {family}
              </h2>
              {inFamily.map((r) => (
                <button
                  key={r.id}
                  onClick={() => setSelected(r)}
                  aria-current={selected?.id === r.id ? "true" : undefined}
                  className={
                    "w-full text-left px-4 py-2 text-[13.5px] border-l-2 transition-colors " +
                    (selected?.id === r.id
                      ? "border-l-accent bg-accentwash text-accent font-medium"
                      : "border-l-transparent text-inksoft hover:text-ink hover:bg-surface2")
                  }
                >
                  {r.name}
                </button>
              ))}
            </div>
          );
        })}
      </nav>

      <div className="grid grid-rows-[auto_1fr] min-h-0">
        {selected && (
          <div className="px-5 py-4 bg-surface border-b border-line grid gap-3">
            <div className="flex items-baseline gap-3 flex-wrap">
              <h1 className="m-0 text-xl font-semibold">{table?.title ?? selected.name}</h1>
              {table && (
                <span className="text-[13px] text-inkfaint tnum">
                  {table.rows.length.toLocaleString()}{" "}
                  {table.rows.length === 1 ? "row" : "rows"}
                </span>
              )}
              {mayExport && (
                <span className="ml-auto flex gap-2">
                  <Button variant="quiet" disabled={!table} onClick={() => void exportAs("csv")}>
                    Export CSV
                  </Button>
                  <Button variant="quiet" disabled={!table} onClick={() => void exportAs("xlsx")}>
                    Export Excel
                  </Button>
                </span>
              )}
            </div>

            {selected.note && (
              <p className="m-0 text-[13px] text-inksoft max-w-[76ch]">{selected.note}</p>
            )}

            <form
              className="flex gap-3 flex-wrap items-end"
              onSubmit={(e) => {
                e.preventDefault();
                void run(selected);
              }}
            >
              {selected.params.includes("dates") && (
                <>
                  <Compact label="From">
                    <input
                      type="date"
                      className={inputClass + " tnum py-1.5"}
                      value={range.from}
                      max={range.to}
                      onChange={(e) => setRange((r) => ({ ...r, from: e.target.value }))}
                    />
                  </Compact>
                  <Compact label="To">
                    <input
                      type="date"
                      className={inputClass + " tnum py-1.5"}
                      value={range.to}
                      min={range.from}
                      onChange={(e) => setRange((r) => ({ ...r, to: e.target.value }))}
                    />
                  </Compact>
                  <Presets onPick={setRange} />
                </>
              )}

              {selected.params.includes("limit") && (
                <Compact label="Top">
                  <input
                    className={inputClass + " tnum py-1.5 w-[84px] text-right"}
                    inputMode="numeric"
                    value={limit}
                    onChange={(e) => setLimit(e.target.value)}
                  />
                </Compact>
              )}

              {selected.params.includes("batch") && (
                <Compact label="Batch code">
                  <input
                    className={inputClass + " py-1.5 font-mono w-[220px]"}
                    value={batch}
                    autoFocus
                    placeholder="as printed on the drum"
                    onChange={(e) => setBatch(e.target.value)}
                  />
                </Compact>
              )}

              {selected.params.includes("onlyInStock") && (
                <label className="flex items-center gap-2 text-[13px] cursor-pointer pb-1.5">
                  <input
                    type="checkbox"
                    checked={onlyInStock}
                    onChange={(e) => setOnlyInStock(e.target.checked)}
                  />
                  <span>On hand only</span>
                </label>
              )}

              <Button
                type="submit"
                variant="quiet"
                className="py-1.5"
                disabled={busy || !ready(selected, params)}
              >
                {busy ? "Running…" : "Run"}
              </Button>
            </form>

            {selected.stale && <Staleness />}
          </div>
        )}

        <div className="overflow-auto min-h-0 bg-ground">
          {busy && !table && <div className="p-6 text-inksoft">Running the report…</div>}

          {!busy && !table && selected && !ready(selected, params) && (
            <div className="p-6 text-inksoft">
              {selected.params.includes("batch")
                ? "Type a batch code and run it."
                : "Choose a period and run it."}
            </div>
          )}

          {table && <Grid table={table} ran={ran} />}
        </div>
      </div>
    </div>
  );
}

/* ── The grid ───────────────────────────────────────────────────────────── */

const RENDER_CAP = 500;

function Grid({ table, ran }: { table: ReportTable; ran: Params | null }) {
  /**
   * Alignment and decimals come from the column type the server declares, so a
   * figure is written here exactly as the export writes it.
   *
   * Falling back to inspecting the values keeps a hand-built table renderable,
   * but it is only a fallback: it cannot tell money whose figures are all whole
   * from a column of counts, which is the case that produced `261` on screen
   * against `261.00` in the file.
   */
  const shape = useMemo(() => {
    return table.headers.map((header, i) => {
      const percent = header.trim().endsWith("%");
      const declared = table.types?.[i];

      if (declared) {
        return {
          numeric: declared === "MONEY" || declared === "INTEGER",
          fractional: declared === "MONEY",
          percent,
        };
      }

      const present = table.rows
        .map((row) => row[i])
        .filter((v) => v !== null && v !== undefined);
      const numeric = present.length > 0 && present.every((v) => typeof v === "number");
      return {
        numeric,
        fractional: numeric && present.some((v) => !Number.isInteger(v as number)),
        percent,
      };
    });
  }, [table]);

  if (table.rows.length === 0) {
    return (
      <div className="h-full grid place-items-center text-center p-10">
        <div className="grid gap-2 max-w-[40ch]">
          <div className="text-lg font-semibold">Nothing to report</div>
          <p className="m-0 text-inksoft text-sm">
            {ran && ran.from !== ran.to
              ? `No activity between ${ran.from} and ${ran.to}.`
              : "No activity in that period."}
          </p>
        </div>
      </div>
    );
  }

  const shown = table.rows.slice(0, RENDER_CAP);
  // A row longer than the header list would be a server bug, but reading past
  // the end of `shape` should not be how it surfaces.
  const shapeAt = (i: number): ColumnShape => shape[i] ?? PLAIN;

  return (
    <>
      <table className="w-full border-collapse text-[13px]">
        <thead>
          <tr>
            {table.headers.map((h, i) => (
              <th
                key={h + i}
                scope="col"
                className={
                  "sticky top-0 z-10 bg-surface2 border-b border-line px-3 py-2 " +
                  "text-[11px] uppercase tracking-[.05em] text-inkfaint font-medium whitespace-nowrap " +
                  (shapeAt(i).numeric ? "text-right" : "text-left")
                }
              >
                {h}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {shown.map((row, r) => (
            <tr key={r} className="bg-surface even:bg-surface2/40 hover:bg-accentwash/40">
              {row.map((cell, c) => (
                <td
                  key={c}
                  className={
                    "border-b border-linesoft px-3 py-1.5 align-baseline " +
                    (shapeAt(c).numeric ? "tnum text-right whitespace-nowrap " : "") +
                    (cell === null || cell === undefined ? "text-inkfaint" : "")
                  }
                >
                  {format(cell, shapeAt(c))}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>

      {table.rows.length > RENDER_CAP && (
        <div className="px-5 py-3 text-[13px] text-inksoft bg-surface border-t border-line">
          Showing the first {RENDER_CAP.toLocaleString()} of{" "}
          {table.rows.length.toLocaleString()} rows. Export the report for all of them — the file is
          built from the full set, not from this screen.
        </div>
      )}
    </>
  );
}

interface ColumnShape {
  numeric: boolean;
  fractional: boolean;
  percent: boolean;
}

const PLAIN: ColumnShape = { numeric: false, fractional: false, percent: false };

const ISO_INSTANT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/;

function format(cell: ReportCell, shape: ColumnShape): string {
  if (cell === null || cell === undefined) return "—";
  if (typeof cell === "boolean") return cell ? "yes" : "no";

  if (typeof cell === "number") {
    // Same number of decimals the export writes, so the two never disagree by
    // a digit. A percent column is still money underneath; it only gains a
    // sign, and dropping to one decimal here would reintroduce the drift.
    const digits = shape.fractional ? 2 : 0;
    const text = cell.toLocaleString("en-GH", {
      minimumFractionDigits: digits,
      maximumFractionDigits: digits,
    });
    return shape.percent ? `${text}%` : text;
  }

  // Instants come back in UTC; the shop reads them in its own time.
  if (ISO_INSTANT.test(cell)) {
    const when = new Date(cell);
    if (!Number.isNaN(when.getTime())) {
      return when.toLocaleString("en-GH", {
        year: "numeric",
        month: "short",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit",
      });
    }
  }

  return cell;
}

/* ── Staleness ──────────────────────────────────────────────────────────── */

/**
 * How old the inventory views are.
 *
 * Stock valuation and expiry ageing read materialised views rebuilt at 02:00,
 * because aggregating them belongs off the till. That is a good trade only if
 * the screen says so — an owner reading a valuation has to know whether it
 * includes this morning, and guessing is how a number gets quoted to a bank.
 */
function Staleness() {
  const toast = useToast();
  const [at, setAt] = useState<string | null | undefined>(undefined);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    const state = await api.get<RefreshState>("/api/reports/inventory/refreshed-at");
    setAt(state.refreshedAt);
  }, []);

  useEffect(() => {
    void load().catch(() => setAt(null));
  }, [load]);

  async function refresh() {
    setBusy(true);
    try {
      const state = await api.post<RefreshState>("/api/reports/inventory/refresh");
      setAt(state.refreshedAt);
      toast({ title: "Rebuilt the inventory views", tone: "good" });
    } catch (err) {
      toast(describe(err));
    } finally {
      setBusy(false);
    }
  }

  if (at === undefined) return null;

  const when = at ? new Date(at) : null;
  const hours = when ? (Date.now() - when.getTime()) / 3_600_000 : Infinity;

  return (
    <div className="flex items-center gap-3 flex-wrap text-[13px]">
      <span className={hours > 26 ? "text-warn" : "text-inksoft"}>
        {when
          ? `As of ${when.toLocaleString("en-GH", {
              month: "short",
              day: "2-digit",
              hour: "2-digit",
              minute: "2-digit",
            })}, not as of now.`
          : "These views have never been built."}
      </span>
      <Button variant="quiet" disabled={busy} onClick={() => void refresh()}>
        {busy ? "Rebuilding…" : "Rebuild now"}
      </Button>
    </div>
  );
}

/* ── Parameters ─────────────────────────────────────────────────────────── */

interface Params {
  from: string;
  to: string;
  limit: string;
  batch: string;
  onlyInStock: boolean;
}

const iso = (d: Date): string =>
  `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;

/** This month so far — the period somebody opening reports usually means. */
function defaultRange(): { from: string; to: string } {
  const now = new Date();
  return { from: iso(new Date(now.getFullYear(), now.getMonth(), 1)), to: iso(now) };
}

function ready(r: ReportDef, p: Params): boolean {
  if (r.params.includes("batch") && p.batch.trim() === "") return false;
  if (r.params.includes("dates") && (!p.from || !p.to || p.from > p.to)) return false;
  return true;
}

/** Only the parameters this report actually takes — the server rejects strays. */
function queryFor(r: ReportDef, p: Params): URLSearchParams {
  const q = new URLSearchParams();
  if (r.params.includes("dates")) {
    q.set("from", p.from);
    q.set("to", p.to);
  }
  if (r.params.includes("limit")) q.set("limit", p.limit.trim() === "" ? "50" : p.limit.trim());
  if (r.params.includes("batch")) q.set("batch", p.batch.trim());
  if (r.params.includes("onlyInStock")) q.set("onlyInStock", String(p.onlyInStock));
  return q;
}

function Presets({ onPick }: { onPick: (r: { from: string; to: string }) => void }) {
  const now = new Date();
  const day = (back: number) => {
    const d = new Date(now);
    d.setDate(d.getDate() - back);
    return d;
  };

  const options: Array<[string, () => { from: string; to: string }]> = [
    ["Today", () => ({ from: iso(now), to: iso(now) })],
    ["7 days", () => ({ from: iso(day(6)), to: iso(now) })],
    ["30 days", () => ({ from: iso(day(29)), to: iso(now) })],
    ["This month", defaultRange],
    [
      "Last month",
      () => ({
        from: iso(new Date(now.getFullYear(), now.getMonth() - 1, 1)),
        to: iso(new Date(now.getFullYear(), now.getMonth(), 0)),
      }),
    ],
  ];

  return (
    <div className="flex gap-1 pb-0.5">
      {options.map(([label, make]) => (
        <button
          key={label}
          type="button"
          onClick={() => onPick(make())}
          className="px-2 py-1 text-[12px] rounded border border-line text-inksoft hover:border-accent hover:text-accent transition-colors"
        >
          {label}
        </button>
      ))}
    </div>
  );
}

/**
 * A denser `Field`.
 *
 * The shared one is sized for a panel with a cashier reading it at arm's
 * length; a report toolbar puts five controls on one line and is read sitting
 * down.
 */
function Compact({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="grid gap-1">
      <span className="text-[11px] uppercase tracking-[.05em] text-inkfaint">{label}</span>
      {children}
    </div>
  );
}
