import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { api } from "../api/client";
import type { CountSheetRow, StockTakeView } from "../api/types";
import { useScanner } from "../till/useScanner";
import { describe } from "../till/useSale";
import { Button, useHotkeys, useToast } from "../ui/components";

/**
 * Entering a physical count.
 *
 * Two things about this screen are deliberate and load-bearing.
 *
 * **It is blind.** No expected quantity, and nothing derived from one — no
 * "that looks high", no colour coding of plausibility. Any hint at all is an
 * anchor, and an anchored count is not evidence. Typos are caught at the
 * variance review by somebody who is allowed to see the figures, which is what
 * the second step is for.
 *
 * **The row order never changes.** The dominant way this gets used is
 * transcription from the printed sheet — one PC, one printer, and a
 * storekeeper who walked the aisle with a pen. Screen order matches paper
 * order because both come from the same query, and re-sorting as lines are
 * counted (uncounted first, say) would move rows under the transcriber's eyes
 * and lose their place. Predictability beats cleverness here.
 */
export function CountSheet({
  take,
  onChanged,
  onSignedOff,
  onBack,
}: {
  take: StockTakeView;
  onChanged: () => void;
  onSignedOff: () => void;
  onBack: () => void;
}) {
  const toast = useToast();
  const [rows, setRows] = useState<CountSheetRow[] | null>(null);
  const [drafts, setDrafts] = useState<Record<number, string>>({});
  const [saving, setSaving] = useState<Set<number>>(new Set());
  const [busy, setBusy] = useState(false);

  const inputs = useRef<Record<number, HTMLInputElement | null>>({});
  const editable = take.status === "OPEN" || take.status === "COUNTED";

  const load = useCallback(async () => {
    const sheet = await api.get<CountSheetRow[]>(`/api/stock-takes/${take.id}/sheet`);
    setRows(sheet);
    setDrafts(
      Object.fromEntries(
        sheet.map((r) => [r.lineId, r.countedQty === undefined ? "" : String(r.countedQty)]),
      ),
    );
  }, [take.id]);

  useEffect(() => {
    void load().catch((err) => toast(describe(err)));
  }, [load, toast]);

  const counted = useMemo(
    () => (rows ?? []).filter((r) => r.countedQty !== undefined).length,
    [rows],
  );
  const total = rows?.length ?? 0;
  const complete = total > 0 && counted === total;

  /* ── Saving one line ─────────────────────────────────────────────────── */

  const commit = useCallback(
    async (row: CountSheetRow, raw: string) => {
      const text = raw.trim();
      const wanted = text === "" ? undefined : Number.parseFloat(text);

      if (text !== "" && (wanted === undefined || Number.isNaN(wanted) || wanted < 0)) {
        toast({ title: "That is not a quantity", body: "Enter a number, or leave it blank.", tone: "warn" });
        return;
      }
      // The unit's own decimal allowance, not a hardcoded rule: 2.5 kg of nails
      // is a real count and 2.5 padlocks is a typo.
      if (wanted !== undefined && row.decimals === 0 && !Number.isInteger(wanted)) {
        toast({
          title: `${row.uomCode} is counted whole`,
          body: `${text} is not a quantity ${row.name} can come in.`,
          tone: "warn",
        });
        return;
      }
      if (wanted === row.countedQty) return;

      setSaving((s) => new Set(s).add(row.lineId));
      try {
        await api.put(`/api/stock-takes/${take.id}/lines/${row.lineId}`, {
          countedQty: wanted ?? null,
        });
        setRows((prev) =>
          (prev ?? []).map((r) => (r.lineId === row.lineId ? { ...r, countedQty: wanted } : r)),
        );
        onChanged();
      } catch (err) {
        // Put the field back to what the server still believes, rather than
        // leaving a figure on screen that was never recorded.
        setDrafts((d) => ({
          ...d,
          [row.lineId]: row.countedQty === undefined ? "" : String(row.countedQty),
        }));
        toast(describe(err));
      } finally {
        setSaving((s) => {
          const next = new Set(s);
          next.delete(row.lineId);
          return next;
        });
      }
    },
    [take.id, toast, onChanged],
  );

  /* ── Moving about ────────────────────────────────────────────────────── */

  const focusLine = useCallback((lineId: number) => {
    const el = inputs.current[lineId];
    if (!el) return;
    el.scrollIntoView({ block: "center", behavior: "smooth" });
    el.focus();
    el.select();
  }, []);

  /** The endgame affordance: most rows are done and you want the holes. */
  const jumpToGap = useCallback(() => {
    const gap = (rows ?? []).find((r) => r.countedQty === undefined);
    if (gap) focusLine(gap.lineId);
  }, [rows, focusLine]);

  useScanner(
    useCallback(
      async (code: string) => {
        try {
          const hits = await api.get<CountSheetRow[]>(
            `/api/stock-takes/${take.id}/scan/${encodeURIComponent(code)}`,
          );
          const first = hits[0];
          if (!first) return;
          focusLine(first.lineId);
          if (hits.length > 1) {
            // A barcode identifies a product; a batch-tracked product has a
            // line per batch. Landing on the wrong one attaches the count to
            // the wrong expiry date, so say so rather than guess quietly.
            toast({
              title: `${hits.length} batches of ${first.name}`,
              body: "Check the batch code against the pack before entering a figure.",
              tone: "warn",
            });
          }
        } catch (err) {
          toast(describe(err));
        }
      },
      [take.id, focusLine, toast],
    ),
    editable,
  );

  useHotkeys(
    (e) => {
      if (e.key === "Escape") {
        e.preventDefault();
        (document.activeElement as HTMLElement | null)?.blur();
        return;
      }
      if (e.key === "F3") {
        e.preventDefault();
        jumpToGap();
      }
    },
    [jumpToGap],
  );

  /* ── Sign-off and printing ───────────────────────────────────────────── */

  async function signOff() {
    setBusy(true);
    try {
      await api.put(`/api/stock-takes/${take.id}/counted`);
      onSignedOff();
    } catch (err) {
      toast(describe(err));
    } finally {
      setBusy(false);
    }
  }

  async function printSheet() {
    const till = localStorage.getItem("cw.till") ?? "TILL-1";
    try {
      await api.post(`/api/printing/stock-takes/${take.id}/sheet?tillCode=${encodeURIComponent(till)}`);
      toast({ title: "Count sheet sent to the printer", tone: "good" });
    } catch (err) {
      toast(describe(err));
    }
  }

  if (!rows) return <div className="p-6 text-inksoft">Loading the sheet…</div>;

  return (
    <div className="grid grid-rows-[auto_1fr_auto] h-full min-h-0">
      {/* ── Header ── */}
      <div className="px-5 py-4 bg-surface border-b border-line grid gap-3">
        <div className="flex items-baseline gap-3 flex-wrap">
          <Button variant="quiet" onClick={onBack}>← All counts</Button>
          <h1 className="m-0 text-xl font-semibold">{take.reference}</h1>
          <span className="text-inksoft">{take.scopeName}</span>
          <span className="ml-auto flex gap-2">
            <Button variant="quiet" onClick={() => void printSheet()}>Print the sheet</Button>
          </span>
        </div>

        <Progress counted={counted} total={total} />
      </div>

      {/* ── The sheet ── */}
      <div className="overflow-y-auto min-h-0">
        <div className="grid grid-cols-[1fr_auto] gap-x-4 items-center px-5 py-2 text-xs uppercase tracking-[.05em] text-inkfaint border-b border-line sticky top-0 bg-ground z-10">
          <span>Item</span>
          <span className="w-[168px] text-right pr-1">Counted</span>
        </div>

        {rows.map((row) => (
          <Row
            key={row.lineId}
            row={row}
            value={drafts[row.lineId] ?? ""}
            saving={saving.has(row.lineId)}
            editable={editable}
            inputRef={(el) => {
              inputs.current[row.lineId] = el;
            }}
            onChange={(v) => setDrafts((d) => ({ ...d, [row.lineId]: v }))}
            onCommit={() => void commit(row, drafts[row.lineId] ?? "")}
            onEnter={() => {
              void commit(row, drafts[row.lineId] ?? "");
              // Next row, always — sequential and predictable, because the
              // person is reading down a piece of paper.
              const i = rows.findIndex((r) => r.lineId === row.lineId);
              const next = rows[i + 1];
              if (next) focusLine(next.lineId);
              else (document.activeElement as HTMLElement | null)?.blur();
            }}
          />
        ))}
      </div>

      {/* ── Footer ── */}
      <div className="px-5 py-3.5 bg-surface border-t border-line flex items-center gap-3 flex-wrap">
        {!complete && (
          <>
            <span className="text-[13.5px] text-inksoft">
              <strong className="text-ink">{total - counted}</strong> still blank. A blank line means
              nobody has looked yet — if the shelf was empty, enter <strong className="text-ink">0</strong>.
            </span>
            <Button variant="quiet" hint="F3" onClick={jumpToGap}>Go to the next blank</Button>
          </>
        )}
        {complete && take.status === "OPEN" && (
          <span className="text-[13.5px] text-good">Every line has a figure against it.</span>
        )}
        {take.status === "COUNTED" && (
          <span className="text-[13.5px] text-inksoft">
            Signed off. A supervisor posts the variances.
          </span>
        )}
        <span className="ml-auto">
          <Button
            variant="primary"
            disabled={!complete || busy || take.status !== "OPEN"}
            onClick={() => void signOff()}
          >
            {busy ? "Signing off…" : "Sign off as counted"}
          </Button>
        </span>
      </div>
    </div>
  );
}

/* ── Pieces ─────────────────────────────────────────────────────────────── */

function Progress({ counted, total }: { counted: number; total: number }) {
  const pct = total === 0 ? 0 : Math.round((counted / total) * 100);
  return (
    <div className="grid gap-1.5">
      <div className="flex justify-between text-[13px] text-inksoft">
        <span>
          <strong className="text-ink tnum">{counted}</strong> of{" "}
          <span className="tnum">{total}</span> lines counted
        </span>
        <span className="tnum text-inkfaint">{pct}%</span>
      </div>
      <div className="h-1.5 rounded-full bg-surface2 overflow-hidden" role="progressbar"
           aria-valuenow={counted} aria-valuemin={0} aria-valuemax={total}>
        <div className="h-full bg-accent transition-[width] duration-200" style={{ width: `${pct}%` }} />
      </div>
    </div>
  );
}

function Row({
  row,
  value,
  saving,
  editable,
  inputRef,
  onChange,
  onCommit,
  onEnter,
}: {
  row: CountSheetRow;
  value: string;
  saving: boolean;
  editable: boolean;
  inputRef: (el: HTMLInputElement | null) => void;
  onChange: (v: string) => void;
  onCommit: () => void;
  onEnter: () => void;
}) {
  const blank = row.countedQty === undefined;

  return (
    <div className="grid grid-cols-[1fr_auto] gap-x-4 items-center px-5 py-2.5 border-b border-linesoft bg-surface">
      <div className="min-w-0">
        <div className="font-medium truncate">{row.name}</div>
        <div className="flex flex-wrap gap-x-3 gap-y-0.5 text-[12.5px] text-inksoft">
          <span className="font-mono text-xs text-inkfaint">{row.sku}</span>
          <span className="font-mono text-xs">{row.lotCode}</span>
          {row.expiresOn && <span>exp {row.expiresOn}</span>}
          {row.localName && <span className="text-inkfaint">“{row.localName}”</span>}
          {row.movedSince > 0 && (
            <span className="text-warn" title="Stock moved after counting began, so this figure is approximate">
              moved since the count opened
            </span>
          )}
        </div>
      </div>

      <div className="flex items-center gap-2 w-[168px] justify-end">
        {saving && <span className="text-xs text-inkfaint">saving…</span>}
        <input
          ref={inputRef}
          value={value}
          disabled={!editable}
          inputMode="decimal"
          aria-label={`Counted quantity for ${row.name}, batch ${row.lotCode}`}
          placeholder="—"
          onChange={(e) => onChange(e.target.value)}
          onBlur={onCommit}
          onKeyDown={(e) => {
            if (e.key === "Enter") {
              e.preventDefault();
              onEnter();
            }
          }}
          className={
            "tnum text-right text-[17px] w-[92px] px-2.5 py-2 rounded border bg-surface2 text-ink " +
            "focus:outline-none focus:border-accent focus:ring-[3px] focus:ring-accentwash " +
            "disabled:opacity-60 placeholder:text-inkfaint " +
            // A blank line is not a zero, and the two must not look alike.
            (blank ? "border-dashed border-line" : "border-line")
          }
        />
        <span className="text-[12.5px] text-inkfaint w-[34px]">{row.uomCode}</span>
      </div>
    </div>
  );
}
