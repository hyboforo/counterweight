import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { ApiFailure, api, money, num } from "../api/client";
import type { CollectedTender, CollectionView, PendingCollectionView } from "../api/types";
import { describe } from "../till/useSale";
import { Button, Callout, Field, inputClass, useToast } from "../ui/components";

const LABELS: Record<CollectedTender, string> = {
  CASH: "Cash",
  MOBILE_MONEY: "Mobile money",
  BANK_TRANSFER: "Bank transfer",
  CHEQUE: "Cheques",
  CARD: "Card",
};

/** Two places at most, no thousands separators: what the server will accept as money. */
const AMOUNT = /^\d{1,12}(\.\d{1,2})?$/;

/** `datetime-local` speaks the browser's local time without a zone; the API speaks instants. */
function toLocalInput(iso: string): string {
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

const when = (iso: string) =>
  new Date(iso).toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" });

/**
 * Collecting the takings.
 *
 * The owner takes away what has come in since the last collection and writes
 * down what they counted, tender by tender, against what the sales say. A
 * shortage in cash is not made good by a surplus in mobile money, which is why
 * there is a box per tender rather than one for the total.
 *
 * The figures are read at a moment, and that moment is the collection's time
 * unless the owner changes it. A sale rung up while they are counting then
 * lands in the next collection instead of turning up here as a shortage
 * nobody saw — and if the figures move anyway, the server refuses the record
 * and says so rather than storing a difference that was never on screen.
 *
 * `SALES_COLLECT` only, which V17 grants to the owner and nobody else.
 */
export function Collections() {
  const toast = useToast();
  const [history, setHistory] = useState<CollectionView[] | null>(null);
  const [pending, setPending] = useState<PendingCollectionView | null>(null);
  const [pendingError, setPendingError] = useState<string | null>(null);
  /** What the owner typed into the time box; empty means "when the figures were read". */
  const [chosenAt, setChosenAt] = useState("");
  const [counted, setCounted] = useState<Partial<Record<CollectedTender, string>>>({});
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  // Changing the time re-reads the figures; only the latest answer may land.
  const readNo = useRef(0);

  const readPending = useCallback(async (at: string) => {
    const mine = ++readNo.current;
    const q = at ? `?until=${encodeURIComponent(new Date(at).toISOString())}` : "";
    try {
      const next = await api.get<PendingCollectionView>(`/api/collections/pending${q}`);
      if (mine !== readNo.current) return;
      setPending(next);
      setPendingError(null);
    } catch (err) {
      if (mine !== readNo.current) return;
      setPending(null);
      setPendingError(err instanceof ApiFailure ? err.message : describe(err).title);
    }
  }, []);

  const readHistory = useCallback(async () => {
    setHistory(await api.get<CollectionView[]>("/api/collections?limit=30"));
  }, []);

  useEffect(() => {
    void readPending("");
    void readHistory().catch((err) => toast(describe(err)));
  }, [readPending, readHistory, toast]);

  /*
   * Cash always has a box — it is what gets carried away. Anything else only
   * when the sales say some came in: the till takes no cheques or cards, and
   * five boxes of which three can only ever say 0.00 is how the one that
   * matters gets skipped.
   */
  const rows = useMemo(
    () => (pending?.tenders ?? []).filter((t) => t.method === "CASH" || num(t.expected) !== 0),
    [pending],
  );

  const figures = rows.map((t) => {
    // Left blank where the sales say nothing came in, the box means nothing
    // was counted — which is also what it would say if filled in.
    const raw = (counted[t.method] ?? "").trim();
    const typed = raw === "" && num(t.expected) === 0 ? "0" : raw;
    const valid = AMOUNT.test(typed);
    return {
      ...t,
      typed,
      valid,
      difference: valid ? Math.round((num(typed) - num(t.expected)) * 100) / 100 : null,
    };
  });
  const allCounted = figures.length > 0 && figures.every((f) => f.valid);
  const differs = figures.some((f) => f.difference !== null && f.difference !== 0);
  const collectedTotal = figures.reduce((sum, f) => sum + (f.valid ? num(f.typed) : 0), 0);
  const nothingAtAll =
    allCounted && collectedTotal === 0 && figures.every((f) => num(f.expected) === 0);
  const canRecord = pending !== null && allCounted && !nothingAtAll && (!differs || note.trim() !== "") && !busy;

  async function record() {
    if (!pending) return;
    setBusy(true);
    try {
      const saved = await api.post<CollectionView>("/api/collections", {
        collectedAt: pending.until,
        // The expected figure goes back exactly as it arrived, so the server
        // can tell whether the sales moved while this screen was open.
        tenders: figures.map((f) => ({ method: f.method, expected: f.expected, collected: f.typed })),
        note: note.trim() || null,
      });
      toast({
        title: `${saved.number} recorded`,
        body: `${money(num(saved.collectedTotal))} collected.`,
        tone: "good",
      });
      setCounted({});
      setNote("");
      setChosenAt("");
      await Promise.all([readPending(""), readHistory()]);
    } catch (err) {
      toast(describe(err));
    } finally {
      setBusy(false);
    }
  }

  const since = pending?.previousNumber
    ? `since ${pending.previousNumber}, ${when(pending.periodFrom!)}`
    : "since the shop started using Counterweight";

  return (
    <div className="grid grid-rows-[auto_1fr] h-full min-h-0">
      <div className="px-5 py-4 bg-surface border-b border-line grid gap-3">
        <h1 className="m-0 text-xl font-semibold">Collections</h1>
        <div className="flex gap-6 flex-wrap items-end">
          <div className="grid gap-0.5">
            <span className="text-xs uppercase tracking-[.05em] text-inkfaint">Waiting to be collected</span>
            <span className="tnum text-2xl font-semibold">
              {pending ? money(num(pending.expectedTotal)) : "—"}
            </span>
          </div>
          {pending && <span className="text-inksoft text-[13.5px] pb-1">{since}</span>}
        </div>
      </div>

      <div className="overflow-y-auto min-h-0">
        <Section title="Record a collection">
          <div className="px-5 py-4 grid gap-4 max-w-3xl">
            <div className="flex gap-4 flex-wrap items-end">
              <Field label="Collected at">
                <input
                  type="datetime-local"
                  className={inputClass + " tnum w-auto"}
                  value={chosenAt || (pending ? toLocalInput(pending.until) : "")}
                  min={pending?.periodFrom ? toLocalInput(pending.periodFrom) : undefined}
                  max={toLocalInput(new Date().toISOString())}
                  onChange={(e) => {
                    // Clearing the box goes back to now.
                    setChosenAt(e.target.value);
                    void readPending(e.target.value);
                  }}
                />
              </Field>
              <Button
                variant="quiet"
                className="mb-2"
                onClick={() => {
                  setChosenAt("");
                  void readPending("");
                }}
              >
                Now — re-read the sales
              </Button>
            </div>
            <p className="m-0 -mt-2 text-[12.5px] text-inksoft max-w-[70ch]">
              Sales after this time go into the next collection. Set it earlier if you took the money
              before writing it down.
            </p>

            {pendingError && <Callout tone="warn">{pendingError}</Callout>}

            {pending && (
              <div className="grid gap-0 border border-line rounded bg-surface">
                <div className="grid grid-cols-[1fr_9rem_9rem_8rem] gap-x-4 px-4 py-2 text-xs uppercase tracking-[.05em] text-inkfaint border-b border-linesoft">
                  <span>Tender</span>
                  <span className="text-right">The sales say</span>
                  <span className="text-right">You counted</span>
                  <span className="text-right">Difference</span>
                </div>
                {figures.map((f) => (
                  <div
                    key={f.method}
                    className="grid grid-cols-[1fr_9rem_9rem_8rem] gap-x-4 px-4 py-2 items-center border-b border-linesoft last:border-b-0"
                  >
                    <span className="font-medium">{LABELS[f.method]}</span>
                    <span className="tnum text-right">{money(num(f.expected))}</span>
                    <input
                      className={inputClass + " tnum text-right py-1.5"}
                      inputMode="decimal"
                      placeholder="0.00"
                      aria-label={`${LABELS[f.method]} counted`}
                      value={counted[f.method] ?? ""}
                      onChange={(e) => setCounted((c) => ({ ...c, [f.method]: e.target.value }))}
                    />
                    <span
                      className={
                        "tnum text-right " +
                        (f.difference === null
                          ? "text-inkfaint"
                          : f.difference < 0
                            ? "text-danger font-semibold"
                            : f.difference > 0
                              ? "text-warn font-semibold"
                              : "text-good")
                      }
                    >
                      {f.difference === null
                        ? counted[f.method]?.trim()
                          ? "not an amount"
                          : "—"
                        : f.difference === 0
                          ? "exact"
                          : `${f.difference > 0 ? "+" : "−"}${money(Math.abs(f.difference))}`}
                    </span>
                  </div>
                ))}
              </div>
            )}

            {pending && (
              <Field label={differs ? "Why the count differs (required)" : "Note"}>
                <textarea
                  className={inputClass + " min-h-[4.5rem]"}
                  maxLength={500}
                  value={note}
                  placeholder={differs ? "Say where the difference went, or came from." : "Optional"}
                  onChange={(e) => setNote(e.target.value)}
                />
              </Field>
            )}

            {nothingAtAll && (
              <Callout tone="info">Nothing has come in {since}, so there is nothing to collect.</Callout>
            )}

            <div>
              <Button variant="primary" disabled={!canRecord} onClick={() => void record()}>
                {busy ? "Recording…" : `Record collection of ${money(collectedTotal)}`}
              </Button>
            </div>
          </div>
        </Section>

        <Section title="Earlier collections">
          {!history && <p className="m-0 px-5 py-3 text-inksoft text-sm">Loading…</p>}
          {history?.length === 0 && (
            <p className="m-0 px-5 py-3 text-inksoft text-sm">No collections yet. The first one covers every sale so far.</p>
          )}
          {history?.map((c) => <CollectionRow key={c.id} collection={c} />)}
        </Section>
      </div>
    </div>
  );
}

function CollectionRow({ collection: c }: { collection: CollectionView }) {
  const difference = num(c.difference);
  const writtenDownLater = new Date(c.recordedAt).getTime() - new Date(c.collectedAt).getTime() >= 60_000;
  return (
    <div className="grid grid-cols-[1fr_auto] gap-x-5 px-5 py-3 border-b border-linesoft">
      <div className="min-w-0 grid gap-0.5">
        <div className="flex gap-3 items-baseline flex-wrap">
          <span className="font-mono text-[13px] font-semibold">{c.number}</span>
          <span className="tnum">{when(c.collectedAt)}</span>
          <span className="text-inksoft text-[13px]">by {c.collectedByName}</span>
        </div>
        <div className="text-[12.5px] text-inksoft">
          {c.periodFrom ? `Covers from ${when(c.periodFrom)}` : "Covers everything before it"}
          {writtenDownLater && ` · written down ${when(c.recordedAt)}`}
        </div>
        <div className="flex gap-x-4 flex-wrap text-[12.5px]">
          {c.tenders.map((t) => (
            <span key={t.method} className={num(t.difference) < 0 ? "text-danger" : "text-inksoft"}>
              {LABELS[t.method]} <span className="tnum">{money(num(t.collected))}</span>
              {num(t.difference) !== 0 && (
                <span className="text-inkfaint"> of {money(num(t.expected))}</span>
              )}
            </span>
          ))}
        </div>
        {c.note && <div className="text-[13px] italic text-inksoft">“{c.note}”</div>}
      </div>
      <div className="text-right">
        <div className="tnum text-lg font-semibold">{money(num(c.collectedTotal))}</div>
        <div
          className={
            "tnum text-[12.5px] " +
            (difference < 0 ? "text-danger font-semibold" : difference > 0 ? "text-warn" : "text-good")
          }
        >
          {difference === 0 ? "exact" : `${difference > 0 ? "+" : "−"}${money(Math.abs(difference))}`}
        </div>
      </div>
    </div>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section>
      <h2 className="m-0 px-5 py-2.5 text-xs uppercase tracking-[.06em] text-inkfaint bg-ground sticky top-0 z-10">
        {title}
      </h2>
      <div className="bg-surface">{children}</div>
    </section>
  );
}
