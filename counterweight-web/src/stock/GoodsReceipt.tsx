import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { api, ApiFailure, money, num, qty as fmtQty } from "../api/client";
import type { GoodsReceiptView, ProductUomView, ProductView } from "../api/types";
import { useScanner } from "../till/useScanner";
import { describe } from "../till/useSale";
import { Button, Callout, Field, Panel, inputClass, useHotkeys, useToast } from "../ui/components";

const DRAFT_KEY = "cw.grn.draft";

interface DraftLine {
  key: string;
  productId: number;
  productUomId: number;
  name: string;
  sku: string;
  uomCode: string;
  /** Base units per one of the chosen unit. A carton of 24 has factor 24. */
  factor: number;
  decimals: number;
  isBatchTracked: boolean;
  qty: string;
  unitCost: string;
  batchCode: string;
  expiresOn: string;
}

interface Draft {
  reference: string;
  lines: DraftLine[];
}

const EMPTY: Draft = { reference: "", lines: [] };

/**
 * Booking goods in.
 *
 * Unlike a stock take, a receipt has no server-side draft: it posts as one
 * transaction, all lines or none, because a lot created without its movement
 * would be stock the ledger cannot explain. That leaves the half-typed delivery
 * living only in this browser, which on a shop PC with the power supply §14
 * assumes is not somewhere to leave fifteen lines of typing — so the draft is
 * mirrored to localStorage on every change and restored on load.
 *
 * The other thing this screen exists to get right is the unit. Cost is quoted
 * per purchase unit and stored per base unit, so a carton of 24 booked at the
 * price of one piece puts the lot cost out by a factor of 24 — silently, and
 * every margin drawn from it afterwards is wrong. Whenever the chosen unit is
 * not the base unit, the derived per-base cost is shown next to the entry,
 * because that is the number a person can sanity-check against what they know
 * a piece costs.
 */
export function GoodsReceipt() {
  const toast = useToast();
  const [draft, setDraft] = useState<Draft>(() => {
    try {
      const saved = localStorage.getItem(DRAFT_KEY);
      return saved ? (JSON.parse(saved) as Draft) : EMPTY;
    } catch {
      return EMPTY;
    }
  });
  const [query, setQuery] = useState("");
  const [hits, setHits] = useState<ProductView[]>([]);
  const [hitIndex, setHitIndex] = useState(0);
  const [picking, setPicking] = useState<ProductView | null>(null);
  const [editing, setEditing] = useState<DraftLine | null>(null);
  const [busy, setBusy] = useState(false);
  const [posted, setPosted] = useState<{ result: GoodsReceiptView; draft: Draft } | null>(null);

  const searchRef = useRef<HTMLInputElement>(null);

  /*
   * The effect owns the stored draft outright, including deleting it once
   * there is nothing left to keep. Clearing the key anywhere else does not
   * work: setting state schedules this, and it would write the empty draft
   * straight back over the deletion.
   */
  useEffect(() => {
    if (draft.lines.length === 0 && draft.reference.trim() === "") {
      localStorage.removeItem(DRAFT_KEY);
      return;
    }
    localStorage.setItem(DRAFT_KEY, JSON.stringify(draft));
  }, [draft]);

  /* ── Finding a product ───────────────────────────────────────────────── */

  useEffect(() => {
    const term = query.trim();
    if (term.length < 2) {
      setHits([]);
      return;
    }
    const timer = window.setTimeout(async () => {
      try {
        setHits(
          await api.get<ProductView[]>(`/api/products/search?q=${encodeURIComponent(term)}&limit=8`),
        );
        setHitIndex(0);
      } catch {
        setHits([]);
      }
    }, 170);
    return () => window.clearTimeout(timer);
  }, [query]);

  useScanner(
    useCallback(
      async (code: string) => {
        try {
          const found = await api.get<{ productId: number }>(
            `/api/products/by-barcode/${encodeURIComponent(code)}`,
          );
          setPicking(await api.get<ProductView>(`/api/products/${found.productId}`));
        } catch {
          // Most stock here has no barcode; fall back to what was typed.
          setQuery(code);
        }
      },
      [],
    ),
    !picking && !editing && !posted,
  );

  useHotkeys(
    (e) => {
      if (picking || editing || posted) return;
      if (e.key === "ArrowDown" || e.key === "ArrowUp") {
        if (hits.length === 0) return;
        e.preventDefault();
        const dir = e.key === "ArrowDown" ? 1 : -1;
        setHitIndex((i) => Math.max(0, Math.min(hits.length - 1, i + dir)));
        return;
      }
      if (e.key === "Enter" && hits.length > 0) {
        e.preventDefault();
        const hit = hits[hitIndex];
        if (hit) setPicking(hit);
      }
    },
    [picking, editing, posted, hits, hitIndex],
  );

  /* ── The draft ───────────────────────────────────────────────────────── */

  const addLine = (line: DraftLine) => {
    setDraft((d) => ({ ...d, lines: [...d.lines, line] }));
    setQuery("");
    setHits([]);
    searchRef.current?.focus();
  };

  const replaceLine = (line: DraftLine) =>
    setDraft((d) => ({ ...d, lines: d.lines.map((l) => (l.key === line.key ? line : l)) }));

  const removeLine = (key: string) =>
    setDraft((d) => ({ ...d, lines: d.lines.filter((l) => l.key !== key) }));

  const total = useMemo(
    () => draft.lines.reduce((sum, l) => sum + num(l.qty) * num(l.unitCost), 0),
    [draft.lines],
  );

  /* ── Posting ─────────────────────────────────────────────────────────── */

  async function post() {
    setBusy(true);
    try {
      const result = await api.post<GoodsReceiptView>("/api/inventory/receipts", {
        reference: draft.reference.trim() || null,
        lines: draft.lines.map((l) => ({
          productId: l.productId,
          productUomId: l.productUomId,
          qty: Number(l.qty),
          unitCost: Number(l.unitCost),
          batchCode: l.batchCode.trim() || null,
          expiresOn: l.expiresOn || null,
        })),
      });
      setPosted({ result, draft });
      setDraft(EMPTY);
    } catch (err) {
      // Nothing was written — the receipt is one transaction — so the draft
      // stays exactly as it was and the storekeeper fixes the offending line
      // rather than retyping the delivery. Which line, though, is the whole
      // question on a fifteen-line delivery: the server answers it as
      // `lines[7].unitCost`, and an index is no use to somebody looking at a
      // list of product names.
      toast(describeReceiptFailure(err, draft.lines));
    } finally {
      setBusy(false);
    }
  }

  if (posted) {
    return (
      <Posted
        result={posted.result}
        draft={posted.draft}
        onAnother={() => setPosted(null)}
      />
    );
  }

  return (
    <div className="grid grid-rows-[auto_1fr_auto] h-full min-h-0">
      {/* ── Search ── */}
      <div className="px-5 py-4 bg-surface border-b border-line grid gap-3 relative">
        <div className="flex items-baseline gap-3 flex-wrap">
          <h1 className="m-0 text-xl font-semibold">Goods received</h1>
          <span className="text-inksoft text-[13.5px]">
            Book in what arrived, against the delivery note.
          </span>
        </div>

        <input
          ref={searchRef}
          value={query}
          autoFocus
          autoComplete="off"
          spellCheck={false}
          aria-label="Search for the product that arrived, or scan it"
          placeholder="Scan, or type a name — cement, weedkiller, elbow…"
          onChange={(e) => setQuery(e.target.value)}
          className="text-[17px] px-3.5 py-2.5 bg-surface2 text-ink border border-line rounded
                     placeholder:text-inkfaint focus:outline-none focus:border-accent focus:ring-[3px] focus:ring-accentwash"
        />

        {hits.length > 0 && (
          <div
            role="listbox"
            aria-label="Search results"
            className="absolute left-5 right-5 top-[calc(100%-8px)] z-40 bg-surface border border-line rounded max-h-[320px] overflow-y-auto"
            style={{ boxShadow: "var(--shadow)" }}
          >
            {hits.map((hit, i) => (
              <div
                key={hit.id}
                role="option"
                aria-selected={i === hitIndex}
                onMouseDown={(e) => {
                  e.preventDefault();
                  setPicking(hit);
                }}
                className={
                  "grid gap-y-1 px-3.5 py-2.5 cursor-pointer border-b border-linesoft last:border-b-0 " +
                  (i === hitIndex ? "bg-accentwash" : "")
                }
              >
                <div className="font-medium">{hit.name}</div>
                <div className="flex flex-wrap gap-2.5 text-[12.5px] text-inksoft">
                  <span className="font-mono text-xs text-inkfaint">{hit.sku}</span>
                  {hit.localName && <span>“{hit.localName}”</span>}
                  {hit.isBatchTracked && <span className="text-warn">batch tracked</span>}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* ── Lines ── */}
      <div className="overflow-y-auto min-h-0">
        {draft.lines.length === 0 ? (
          <div className="h-full grid place-items-center text-center p-8">
            <div className="grid gap-2 max-w-[40ch]">
              <div className="text-[17px] text-inksoft">Nothing booked in yet</div>
              <p className="m-0 text-sm text-inkfaint">
                Find the product that arrived and enter what the delivery note says. Nothing reaches
                the ledger until you post the whole receipt.
              </p>
            </div>
          </div>
        ) : (
          draft.lines.map((line) => (
            <LineRow
              key={line.key}
              line={line}
              onEdit={() => setEditing(line)}
              onRemove={() => removeLine(line.key)}
            />
          ))
        )}
      </div>

      {/* ── Footer ── */}
      <div className="px-5 py-3.5 bg-surface border-t border-line grid gap-3">
        <div className="flex items-end gap-4 flex-wrap">
          <div className="grid gap-1.5 min-w-[220px]">
            <span className="text-xs uppercase tracking-[.05em] text-inkfaint">
              Delivery note number
            </span>
            <input
              className={inputClass}
              value={draft.reference}
              placeholder="From the supplier's paper"
              onChange={(e) => setDraft((d) => ({ ...d, reference: e.target.value }))}
            />
          </div>

          <div className="grid gap-0.5">
            <span className="text-xs uppercase tracking-[.05em] text-inkfaint">Lines</span>
            <span className="tnum text-2xl font-semibold">{draft.lines.length}</span>
          </div>

          <div className="grid gap-0.5">
            <span className="text-xs uppercase tracking-[.05em] text-inkfaint">
              Value of this delivery
            </span>
            <span className="tnum text-2xl font-semibold">{money(total)}</span>
          </div>

          <span className="ml-auto">
            <Button
              variant="primary"
              disabled={busy || draft.lines.length === 0}
              onClick={() => void post()}
            >
              {busy ? "Posting…" : `Post ${draft.lines.length} line${draft.lines.length === 1 ? "" : "s"}`}
            </Button>
          </span>
        </div>

        <p className="m-0 text-[12.5px] text-inkfaint max-w-[70ch]">
          Check the value against the supplier's invoice before posting. It is the sum of what you
          typed, and the last chance to catch a carton entered at the price of a piece.
        </p>
      </div>

      {picking && (
        <LineEditor
          product={picking}
          onClose={() => setPicking(null)}
          onSave={(line) => {
            addLine(line);
            setPicking(null);
          }}
        />
      )}

      {editing && (
        <LineEditor
          existing={editing}
          onClose={() => setEditing(null)}
          onSave={(line) => {
            replaceLine(line);
            setEditing(null);
          }}
        />
      )}
    </div>
  );
}

/* ── Errors ─────────────────────────────────────────────────────────────── */

/** How the server names a field inside the posted array. */
const FIELD_PATH = /^lines\[(\d+)\]\.(\w+)$/;

const FIELD_WORDS: Record<string, string> = {
  qty: "the quantity",
  unitCost: "the cost",
  batchCode: "the batch number",
  expiresOn: "the expiry date",
  productId: "the product",
  productUomId: "the unit",
};

/**
 * Turns `lines[7].unitCost: has too many digits` into something about a
 * padlock.
 *
 * Bean validation reports the position in the array it was handed, which is
 * exactly the draft's own order — so the index maps straight back to a line the
 * storekeeper can see and point at. Without this the message is "some details
 * need correcting" over a delivery of fifteen lines, which is barely better
 * than silence.
 */
function describeReceiptFailure(err: unknown, lines: DraftLine[]) {
  if (!(err instanceof ApiFailure)) return describe(err);

  const named = Object.entries(err.fieldErrors)
    .map(([path, problem]) => {
      const match = FIELD_PATH.exec(path);
      if (!match) return null;
      const line = lines[Number(match[1])];
      if (!line) return null;
      return `${line.name}: ${FIELD_WORDS[match[2] ?? ""] ?? match[2]} ${problem}`;
    })
    .filter((s): s is string => s !== null);

  if (named.length === 0) return describe(err);
  return {
    title: named.length === 1 ? "One line needs correcting" : `${named.length} lines need correcting`,
    body: named.join(" · "),
    tone: "warn" as const,
  };
}

/* ── One line ───────────────────────────────────────────────────────────── */

function LineRow({
  line,
  onEdit,
  onRemove,
}: {
  line: DraftLine;
  onEdit: () => void;
  onRemove: () => void;
}) {
  const perBase = line.factor === 1 ? null : num(line.unitCost) / line.factor;

  return (
    <div className="grid grid-cols-[1fr_auto_auto] gap-x-4 items-center px-5 py-3 border-b border-linesoft bg-surface">
      <div className="min-w-0">
        <div className="font-medium truncate">{line.name}</div>
        <div className="flex flex-wrap gap-x-3 gap-y-0.5 text-[12.5px] text-inksoft">
          <span className="font-mono text-xs text-inkfaint">{line.sku}</span>
          {line.batchCode && <span className="font-mono text-xs">{line.batchCode}</span>}
          {line.expiresOn && <span>exp {line.expiresOn}</span>}
          {perBase !== null && (
            <span className="text-inkfaint">
              = {money(perBase)} per base unit
            </span>
          )}
        </div>
      </div>

      <div className="text-right">
        <div className="tnum font-semibold">
          {fmtQty(num(line.qty))}
          <span className="text-[12.5px] font-normal text-inkfaint"> {line.uomCode}</span>
        </div>
        <div className="tnum text-[12.5px] text-inksoft">
          {money(num(line.unitCost))} each · {money(num(line.qty) * num(line.unitCost))}
        </div>
      </div>

      <div className="flex gap-2">
        <Button variant="quiet" onClick={onEdit}>Edit</Button>
        <Button variant="quiet" onClick={onRemove}>Remove</Button>
      </div>
    </div>
  );
}

/* ── The line editor ────────────────────────────────────────────────────── */

function LineEditor({
  product,
  existing,
  onClose,
  onSave,
}: {
  product?: ProductView;
  existing?: DraftLine;
  onClose: () => void;
  onSave: (line: DraftLine) => void;
}) {
  const toast = useToast();
  const [units, setUnits] = useState<ProductUomView[] | null>(null);
  const [unitId, setUnitId] = useState<number | null>(existing?.productUomId ?? null);
  const [qty, setQty] = useState(existing?.qty ?? "");
  const [cost, setCost] = useState(existing?.unitCost ?? "");
  const [batch, setBatch] = useState(existing?.batchCode ?? "");
  const [expires, setExpires] = useState(existing?.expiresOn ?? "");

  const productId = product?.id ?? existing!.productId;
  const name = product?.name ?? existing!.name;
  const sku = product?.sku ?? existing!.sku;
  const batchTracked = product?.isBatchTracked ?? existing!.isBatchTracked;

  useEffect(() => {
    api
      .get<ProductUomView[]>(`/api/products/${productId}/units`)
      .then((all) => {
        // Only what the shop actually buys in. A unit marked unpurchasable is
        // a selling convenience, and receiving against it is a mistake.
        const buyable = all.filter((u) => u.purchasable);
        setUnits(buyable);
        if (unitId === null) {
          setUnitId((buyable.find((u) => !u.isBase) ?? buyable[0])?.id ?? null);
        }
      })
      .catch((err) => toast(describe(err)));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [productId]);

  const unit = units?.find((u) => u.id === unitId) ?? null;
  const perBase = unit && unit.factor !== 1 && cost !== "" ? num(cost) / unit.factor : null;

  const problems: string[] = [];
  if (qty.trim() === "" || !(num(qty) > 0)) problems.push("Enter how many arrived.");
  if (unit && unit.decimals === 0 && qty !== "" && !Number.isInteger(num(qty))) {
    problems.push(`${unit.uomName} comes in whole units.`);
  }
  if (cost.trim() === "" || num(cost) < 0) problems.push("Enter the cost from the delivery note.");
  if (batchTracked && !batch.trim()) problems.push("Read the batch number off the pack.");
  if (batchTracked && !expires) problems.push("Read the expiry date off the pack.");
  if (batchTracked && expires && new Date(expires) <= new Date()) {
    problems.push("That expiry is not in the future — check the pack, or quarantine the delivery.");
  }

  function save() {
    if (!unit || problems.length > 0) return;
    onSave({
      key: existing?.key ?? `${productId}-${unit.id}-${Date.now()}`,
      productId,
      productUomId: unit.id,
      name,
      sku,
      uomCode: unit.uomCode,
      factor: unit.factor,
      decimals: unit.decimals,
      isBatchTracked: batchTracked,
      qty: qty.trim(),
      unitCost: cost.trim(),
      batchCode: batch.trim(),
      expiresOn: expires,
    });
  }

  return (
    <Panel title={name} hint="Esc to cancel" onClose={onClose}>
      <div className="font-mono text-xs text-inkfaint -mt-2">{sku}</div>

      {units === null && <p className="m-0 text-inksoft">Loading units…</p>}

      {units !== null && units.length === 0 && (
        <Callout tone="danger">
          Nothing on this product is marked as purchasable, so there is no unit to receive it in.
          Fix the product before booking the delivery in.
        </Callout>
      )}

      {units !== null && units.length > 0 && (
        <>
          {units.length > 1 && (
            <Field label="Which unit did it arrive in?">
              <select
                className={inputClass}
                value={unitId ?? ""}
                onChange={(e) => setUnitId(Number(e.target.value))}
              >
                {units.map((u) => (
                  <option key={u.id} value={u.id}>
                    {u.uomName} ({u.uomCode})
                    {u.factor === 1 ? "" : ` — ${fmtQty(u.factor)} base units each`}
                  </option>
                ))}
              </select>
            </Field>
          )}

          <div className="grid grid-cols-2 gap-4">
            <Field label={`How many ${unit?.uomCode ?? ""}`}>
              <input
                className={inputClass + " tnum text-right text-lg"}
                inputMode="decimal"
                value={qty}
                autoFocus
                placeholder="0"
                onChange={(e) => setQty(e.target.value)}
              />
            </Field>

            <Field label={`Cost per ${unit?.uomCode ?? "unit"} (GHS)`}>
              <input
                className={inputClass + " tnum text-right text-lg"}
                inputMode="decimal"
                value={cost}
                placeholder="0.00"
                onChange={(e) => setCost(e.target.value)}
              />
            </Field>
          </div>

          {/*
           * The check that catches the expensive mistake. Cost is entered per
           * purchase unit and stored per base unit, so a carton booked at the
           * price of a piece is wrong by the factor — visible here, invisible
           * afterwards.
           */}
          {perBase !== null && (
            <Callout tone="info">
              That works out at <strong>{money(perBase)}</strong> per base unit. If that is not
              roughly what one costs, the unit or the price is wrong.
            </Callout>
          )}

          {batchTracked && (
            <>
              <Callout tone="warn">
                This is batch tracked. The batch number and expiry come off the pack, not from
                memory — they are what FEFO picks on and what a recall is traced through.
              </Callout>
              <div className="grid grid-cols-2 gap-4">
                <Field label="Batch number">
                  <input
                    className={inputClass}
                    value={batch}
                    spellCheck={false}
                    onChange={(e) => setBatch(e.target.value)}
                  />
                </Field>
                <Field label="Expires on">
                  <input
                    className={inputClass}
                    type="date"
                    value={expires}
                    onChange={(e) => setExpires(e.target.value)}
                  />
                </Field>
              </div>
            </>
          )}

          {qty !== "" && cost !== "" && problems.length === 0 && (
            <div className="flex justify-between items-baseline px-3.5 py-2.5 bg-surface2 rounded">
              <span className="text-[13.5px] text-inksoft">Line total</span>
              <span className="tnum text-lg font-semibold">{money(num(qty) * num(cost))}</span>
            </div>
          )}

          {problems.length > 0 && (qty !== "" || cost !== "" || batch !== "" || expires !== "") && (
            <ul className="m-0 pl-5 text-[13px] text-danger grid gap-1">
              {problems.map((p) => (
                <li key={p}>{p}</li>
              ))}
            </ul>
          )}

          <Button variant="primary" disabled={problems.length > 0} onClick={save}>
            {existing ? "Save the line" : "Add to the receipt"}
          </Button>
        </>
      )}
    </Panel>
  );
}

/* ── After posting ──────────────────────────────────────────────────────── */

function Posted({
  result,
  draft,
  onAnother,
}: {
  result: GoodsReceiptView;
  draft: Draft;
  onAnother: () => void;
}) {
  const toast = useToast();
  const [printed, setPrinted] = useState(false);

  const total = draft.lines.reduce((sum, l) => sum + num(l.qty) * num(l.unitCost), 0);

  /*
   * Prints by id, not by sending the lines back.
   *
   * The server renders the slip from `goods_receipt_line`, so the paper says
   * what was recorded rather than what this browser still happens to hold —
   * which matters because the slip is the evidence behind every lot cost, and
   * a document assembled from a request body proves nothing.
   */
  async function print() {
    const till = localStorage.getItem("cw.till") ?? "TILL-1";
    try {
      await api.post(
        `/api/printing/goods-receipts/${result.id}?tillCode=${encodeURIComponent(till)}`,
      );
      setPrinted(true);
      toast({ title: `${result.number} sent to the printer`, tone: "good" });
    } catch (err) {
      toast(describe(err));
    }
  }

  return (
    <div className="h-full grid place-items-center p-6 bg-ground overflow-y-auto">
      <div
        className="w-full max-w-lg bg-surface border border-line rounded p-6 grid gap-4"
        style={{ boxShadow: "var(--shadow)" }}
      >
        <div>
          <div className="font-mono text-xs tracking-[.12em] text-inkfaint uppercase">Received</div>
          {/* Ours, not theirs. The delivery note number is the supplier's and
              sits below — this is the number the shop files under. */}
          <h1 className="m-0 mt-1 text-2xl font-semibold">{result.number}</h1>
        </div>

        <div className="grid gap-1.5">
          <Summary
            label="Delivery note"
            value={result.supplierReference ?? "none given"}
          />
          <Summary label="Lines booked in" value={String(draft.lines.length)} />
          <Summary label="Value of the delivery" value={money(total)} />
        </div>

        <Callout tone="info">
          The stock is on the shelf as far as the ledger is concerned. Print the slip, sign it, and
          file it with the delivery note — it is the paper behind every lot cost from here on.
        </Callout>

        <Button variant="primary" onClick={() => void print()}>
          {printed ? "Print another copy" : "Print the goods receipt"}
        </Button>
        <Button variant="quiet" onClick={onAnother}>Book in another delivery</Button>
      </div>
    </div>
  );
}

function Summary({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex justify-between gap-3 text-[13.5px] text-inksoft">
      <span>{label}</span>
      <span className="tnum">{value}</span>
    </div>
  );
}
