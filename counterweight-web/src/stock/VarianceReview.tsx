import { useCallback, useEffect, useMemo, useState } from "react";
import { api, money, qty as fmtQty } from "../api/client";
import type { StockTakePosting, StockTakeView, VarianceRow } from "../api/types";
import { describe } from "../till/useSale";
import { Button, Callout, useToast } from "../ui/components";

/**
 * The sign-off: what was counted against what the books said.
 *
 * This is the other half of the blind count, and the only screen in the flow
 * that may show an expected figure. It sits behind STOCK_ADJUST rather than
 * STOCK_COUNT — the storekeeper who counted the shelf cannot reach it, because
 * a count that discovers and disposes of its own shrinkage is not a control.
 * The server enforces that; hiding the route is only so nobody is offered a
 * button that will 403.
 *
 * Lines arrive ordered by the cash value of the discrepancy. A missing bag of
 * cement and a missing wheelbarrow are not the same problem, and the one worth
 * chasing belongs at the top.
 */
export function VarianceReview({
  take,
  onPosted,
  onBack,
}: {
  take: StockTakeView;
  onPosted: () => void;
  onBack: () => void;
}) {
  const toast = useToast();
  const [rows, setRows] = useState<VarianceRow[] | null>(null);
  const [showAll, setShowAll] = useState(false);
  const [busy, setBusy] = useState(false);
  const [posting, setPosting] = useState<StockTakePosting | null>(null);

  const load = useCallback(async () => {
    setRows(
      await api.get<VarianceRow[]>(
        `/api/stock-takes/${take.id}/variances?onlyVariances=${!showAll}`,
      ),
    );
  }, [take.id, showAll]);

  useEffect(() => {
    void load().catch((err) => toast(describe(err)));
  }, [load, toast]);

  const totals = useMemo(() => {
    let missing = 0;
    let found = 0;
    for (const r of rows ?? []) {
      const v = r.variance ?? 0;
      const value = Math.abs(v) * r.unitCost;
      if (v < 0) missing += value;
      else if (v > 0) found += value;
    }
    return { missing, found, net: found - missing };
  }, [rows]);

  /**
   * Lines the server will refuse: more is missing than is left on the shelf,
   * which means the goods sold after being counted. Surfaced here so it reads
   * as something to recount rather than as a failure at the moment of posting.
   */
  const impossible = useMemo(
    () => (rows ?? []).filter((r) => (r.variance ?? 0) < 0 && Math.abs(r.variance ?? 0) > r.onHandNow),
    [rows],
  );

  async function post() {
    setBusy(true);
    try {
      setPosting(await api.post<StockTakePosting>(`/api/stock-takes/${take.id}/post`, {}));
      onPosted();
    } catch (err) {
      toast(describe(err));
    } finally {
      setBusy(false);
    }
  }

  async function printSlip() {
    const till = localStorage.getItem("cw.till") ?? "TILL-1";
    try {
      await api.post(
        `/api/printing/stock-takes/${take.id}/variances?tillCode=${encodeURIComponent(till)}`,
      );
      toast({ title: "Variance slip sent to the printer", body: "Two copies — one to sign, one to file.", tone: "good" });
    } catch (err) {
      toast(describe(err));
    }
  }

  if (posting) {
    return (
      <Posted posting={posting} onPrint={() => void printSlip()} onBack={onBack} />
    );
  }

  if (!rows) return <div className="p-6 text-inksoft">Loading the variances…</div>;

  return (
    <div className="grid grid-rows-[auto_1fr_auto] h-full min-h-0">
      <div className="px-5 py-4 bg-surface border-b border-line grid gap-3">
        <div className="flex items-baseline gap-3 flex-wrap">
          <Button variant="quiet" onClick={onBack}>← All counts</Button>
          <h1 className="m-0 text-xl font-semibold">{take.reference}</h1>
          <span className="text-inksoft">{take.scopeName}</span>
          <span className="ml-auto">
            <Button variant="quiet" onClick={() => setShowAll((s) => !s)}>
              {showAll ? "Only the discrepancies" : "Show every line"}
            </Button>
          </span>
        </div>

        <div className="flex gap-6 flex-wrap">
          <Figure label="Missing, at cost" value={totals.missing} tone={totals.missing > 0 ? "danger" : undefined} />
          <Figure label="Found, at cost" value={totals.found} tone={totals.found > 0 ? "good" : undefined} />
          <Figure label="Net" value={totals.net} tone={totals.net < 0 ? "danger" : totals.net > 0 ? "good" : undefined} signed />
        </div>
      </div>

      <div className="overflow-y-auto min-h-0">
        {impossible.length > 0 && (
          <div className="p-5 pb-0">
            <Callout tone="danger">
              {impossible.length === 1 ? "One line is" : `${impossible.length} lines are`} short by more
              than is still on the shelf, which means the goods sold after being counted. Those lines
              need recounting — posting will be refused until they are.
            </Callout>
          </div>
        )}

        {rows.length === 0 && (
          <div className="h-full grid place-items-center text-center p-10">
            <div className="grid gap-2">
              <div className="text-lg text-good font-semibold">No discrepancies</div>
              <p className="m-0 text-inksoft max-w-[38ch]">
                The count agreed with the books on every line. Posting records that it happened and
                moves no stock.
              </p>
            </div>
          </div>
        )}

        {rows.map((row) => (
          <VarianceLine key={row.lineId} row={row} />
        ))}
      </div>

      <div className="px-5 py-3.5 bg-surface border-t border-line flex items-center gap-3 flex-wrap">
        <span className="text-[13.5px] text-inksoft max-w-[52ch]">
          Posting writes each discrepancy into the stock ledger as its own movement. It cannot be
          undone — a mistake is corrected by a further adjustment, which is itself on the record.
        </span>
        <span className="ml-auto">
          <Button
            variant="primary"
            disabled={busy || impossible.length > 0 || take.status !== "COUNTED"}
            onClick={() => void post()}
          >
            {busy ? "Posting…" : "Post the variances"}
          </Button>
        </span>
      </div>
    </div>
  );
}

/* ── Pieces ─────────────────────────────────────────────────────────────── */

function Figure({
  label,
  value,
  tone,
  signed,
}: {
  label: string;
  value: number;
  tone?: "good" | "danger";
  signed?: boolean;
}) {
  const colour = tone === "danger" ? "text-danger" : tone === "good" ? "text-good" : "text-ink";
  return (
    <div className="grid gap-0.5">
      <span className="text-xs uppercase tracking-[.05em] text-inkfaint">{label}</span>
      <span className={`tnum text-2xl font-semibold ${colour}`}>
        {signed && value > 0 ? "+" : signed && value < 0 ? "−" : ""}
        {money(Math.abs(value))}
      </span>
    </div>
  );
}

function VarianceLine({ row }: { row: VarianceRow }) {
  const variance = row.variance ?? 0;
  const short = variance < 0;
  const value = Math.abs(variance) * row.unitCost;
  const beyondStock = short && Math.abs(variance) > row.onHandNow;

  return (
    <div className="grid grid-cols-[1fr_auto] gap-x-5 gap-y-1 px-5 py-3 border-b border-linesoft bg-surface items-baseline">
      <div className="min-w-0">
        <div className="font-medium truncate">{row.name}</div>
        <div className="flex flex-wrap gap-x-3 gap-y-0.5 text-[12.5px] text-inksoft">
          <span className="font-mono text-xs text-inkfaint">{row.sku}</span>
          <span className="font-mono text-xs">{row.lotCode}</span>
          <span className="tnum">
            expected {fmtQty(row.expectedQty)} · counted{" "}
            {row.countedQty === undefined ? "—" : fmtQty(row.countedQty)}
          </span>
          {row.movedSince > 0 && (
            <span className="text-warn">traded through the count — approximate</span>
          )}
          {beyondStock && (
            <span className="text-danger">only {fmtQty(row.onHandNow)} left — recount</span>
          )}
        </div>
        {row.note && <div className="text-[12.5px] text-inksoft italic mt-0.5">“{row.note}”</div>}
      </div>

      <div className="text-right">
        <div className={"tnum text-lg font-semibold " + (variance === 0 ? "text-inksoft" : short ? "text-danger" : "text-good")}>
          {variance > 0 ? "+" : ""}
          {fmtQty(variance)}
          <span className="text-[12.5px] font-normal text-inkfaint"> {row.uomCode}</span>
        </div>
        {variance !== 0 && (
          <div className="tnum text-[12.5px] text-inksoft">{money(value)}</div>
        )}
      </div>
    </div>
  );
}

function Posted({
  posting,
  onPrint,
  onBack,
}: {
  posting: StockTakePosting;
  onPrint: () => void;
  onBack: () => void;
}) {
  const loss = posting.netValue < 0;
  return (
    <div className="h-full grid place-items-center p-6 bg-ground">
      <div
        className="w-full max-w-lg bg-surface border border-line rounded p-6 grid gap-4"
        style={{ boxShadow: "var(--shadow)" }}
      >
        <div>
          <div className="font-mono text-xs tracking-[.12em] text-inkfaint uppercase">Posted</div>
          <h1 className="m-0 mt-1 text-2xl font-semibold">{posting.reference}</h1>
        </div>

        <div className="grid gap-1.5">
          <Row label="Lines posted" value={String(posting.linesPosted)} />
          <Row label="Lines that agreed" value={String(posting.linesUnchanged)} />
          <Row label="Written off" value={`${fmtQty(posting.unitsWrittenOff)} units`} />
          <Row label="Found" value={`${fmtQty(posting.unitsFound)} units`} />
        </div>

        <div className={"text-center py-5 px-4 rounded " + (loss ? "bg-dangerwash" : "bg-goodwash")}>
          <div className="text-xs uppercase tracking-[.07em] text-inksoft">Net, at cost</div>
          <div className={"tnum font-semibold text-[40px] leading-tight " + (loss ? "text-danger" : "text-good")}>
            {posting.netValue > 0 ? "+" : posting.netValue < 0 ? "−" : ""}
            {money(Math.abs(posting.netValue))}
          </div>
        </div>

        <Callout tone="info">
          Sign the printed slip and file it. The count is the record; the paper is a copy of it.
        </Callout>

        <Button variant="primary" onClick={onPrint}>Print the variance slip</Button>
        <Button variant="quiet" onClick={onBack}>Back to all counts</Button>
      </div>
    </div>
  );
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex justify-between gap-3 text-[13.5px] text-inksoft">
      <span>{label}</span>
      <span className="tnum">{value}</span>
    </div>
  );
}
