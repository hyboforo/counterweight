import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { api, money, num, qty as fmtQty } from "../api/client";
import type {
  CustomerView,
  ProductView,
  SaleLineView,
  UnitPriceView,
} from "../api/types";
import { useAuth } from "../auth/AuthContext";
import { Button, Key, useHotkeys, useToast } from "../ui/components";
import { CustomerPanel, HoldPanel, PayPanel, RecallPanel } from "./panels";
import { usePrintRelay, type AgentState } from "../print/usePrintRelay";
import { LineName } from "./productNames";
import { useScanner } from "./useScanner";
import { describe, unitsFor, useSale } from "./useSale";

export type Mode = "till" | "pay" | "customer" | "hold" | "recall";

interface Hit {
  product: ProductView;
  units: UnitPriceView[];
}

export function TillScreen({
  tillCode,
  onGoToBackOffice,
  onOpenAccount,
}: {
  tillCode: string;
  onGoToBackOffice?: () => void;
  onOpenAccount: () => void;
}) {
  /*
   * Hands queued receipts to the agent on this machine.
   *
   * Only ever has work when somebody pressed "Print receipt" — nothing queues
   * on its own — so on a till with no printer attached this polls, finds an
   * empty queue every time, and reports `missing` to the status bar without
   * anything going wrong.
   */
  const agent = usePrintRelay(tillCode, true);
  const { user, signOut } = useAuth();
  const toast = useToast();
  const sale = useSale();

  const [mode, setMode] = useState<Mode>("till");
  const [query, setQuery] = useState("");
  const [hits, setHits] = useState<Hit[]>([]);
  const [hitIndex, setHitIndex] = useState(0);
  const [pendingQty, setPendingQty] = useState<number | null>(null);
  const [selected, setSelected] = useState(-1);
  /*
   * Whether the selected line's quantity is being typed rather than stepped.
   *
   * `+`/`−` are enough for pieces and useless for a roll of wire: 3.5 metres
   * is not seven presses of anything, and the `3` `*` prefix only applies to
   * the *next* item scanned, which is no help once the line is in the basket.
   */
  const [editingQty, setEditingQty] = useState(false);
  const [customer, setCustomer] = useState<CustomerView | null>(null);
  const [online, setOnline] = useState(api.isOnline);
  const [freshLine, setFreshLine] = useState<number | null>(null);

  const searchRef = useRef<HTMLInputElement>(null);
  const basketRef = useRef<HTMLDivElement>(null);

  const focusSearch = useCallback(() => searchRef.current?.focus(), []);

  useEffect(() => api.onConnectionChange(setOnline), []);

  /* ── Search ──────────────────────────────────────────────────────────── */

  useEffect(() => {
    const term = query.trim();
    if (term.length < 2) {
      setHits([]);
      return;
    }
    // Debounced, because the search runs a trigram scan and a cashier types
    // faster than it is worth asking. Short enough to still feel live.
    const timer = window.setTimeout(async () => {
      try {
        const products = await api.get<ProductView[]>(
          `/api/products/search?q=${encodeURIComponent(term)}&limit=8`,
        );
        const withUnits = await Promise.all(
          products.map(async (product) => ({
            product,
            units: await unitsFor(product.id, customer?.priceListId ?? null).catch(() => []),
          })),
        );
        setHits(withUnits);
        setHitIndex(0);
      } catch {
        setHits([]);
      }
    }, 170);
    return () => window.clearTimeout(timer);
  }, [query, customer]);

  /* ── Adding ──────────────────────────────────────────────────────────── */

  const addFromHit = useCallback(
    async (hit: Hit) => {
      // The sellable unit with the smallest factor is the one a counter means
      // when it does not say — a piece, not the carton it came in.
      const unit = [...hit.units].sort((a, b) => num(a.factor) - num(b.factor))[0];
      if (!unit) {
        toast({ title: `${hit.product.name} has no price yet`, body: "Set one before selling it.", tone: "warn" });
        return;
      }
      try {
        const updated = await sale.addLine({
          productId: hit.product.id,
          productUomId: unit.productUomId,
          qty: pendingQty ?? 1,
        });
        const last = updated.lines[updated.lines.length - 1];
        setFreshLine(last?.id ?? null);
        setSelected(updated.lines.length - 1);
        setQuery("");
        setHits([]);
        setPendingQty(null);
      } catch (err) {
        toast(describe(err));
      }
      focusSearch();
    },
    [sale, pendingQty, toast, focusSearch],
  );

  const onScan = useCallback(
    async (code: string) => {
      /*
       * A scan while a quantity is being typed abandons the typing.
       *
       * The wedge delivers regardless of focus by design, so its digits have
       * already landed in the quantity box; committing them would set the line
       * to a barcode. The scanned item is what the cashier is doing now.
       */
      setEditingQty(false);

      try {
        const found = await api.get<{ productId: number; productUomId: number; name: string }>(
          `/api/products/by-barcode/${encodeURIComponent(code)}`,
        );
        const updated = await sale.addLine({
          productId: found.productId,
          productUomId: found.productUomId,
          qty: pendingQty ?? 1,
        });
        setFreshLine(updated.lines[updated.lines.length - 1]?.id ?? null);
        setSelected(updated.lines.length - 1);
        setPendingQty(null);
        setQuery("");
      } catch {
        // A barcode nobody has registered is ordinary here — most stock has
        // none. Put it in the search box rather than reporting a failure.
        setQuery(code);
        toast({
          title: "That barcode is not on any product",
          body: "Searching for it instead — add the code to the product later.",
          tone: "warn",
        });
      }
      focusSearch();
    },
    [sale, pendingQty, toast, focusSearch],
  );

  useScanner(onScan, mode === "till" && online);

  /* ── Line editing ────────────────────────────────────────────────────── */

  const lines = sale.sale?.lines ?? [];

  const adjustQty = useCallback(
    async (delta: number) => {
      const line = lines[selected];
      if (!line) return;
      const next = Math.round((num(line.qty) + delta) * 10000) / 10000;
      try {
        if (next <= 0) {
          await sale.removeLine(line.id);
          setSelected((s) => Math.min(s, lines.length - 2));
        } else {
          await sale.setQty(line.id, next);
        }
      } catch (err) {
        toast(describe(err));
      }
    },
    [lines, selected, sale, toast],
  );

  /**
   * Sets a line to a quantity that was typed rather than stepped.
   *
   * Zero removes the line, the same as stepping down through it — a cashier
   * correcting "two of those" to none should not have to know which key does
   * that. What is typed goes to the server as typed: `UomConverter` is what
   * knows a piece has no decimals and a kilogram has three, and it answers
   * with a sentence rather than a rounded quantity nobody asked for.
   */
  const setQtyExact = useCallback(
    async (lineId: number, value: string) => {
      setEditingQty(false);
      focusSearch();

      const line = lines.find((l) => l.id === lineId);
      const next = Number.parseFloat(value.replace(",", "."));
      if (!line || !Number.isFinite(next) || next === num(line.qty)) return;

      try {
        if (next <= 0) {
          await sale.removeLine(lineId);
          setSelected((s) => Math.min(s, lines.length - 2));
        } else {
          await sale.setQty(lineId, next);
        }
      } catch (err) {
        toast(describe(err));
      }
    },
    [lines, sale, toast, focusSearch],
  );

  const removeSelected = useCallback(async () => {
    const line = lines[selected];
    if (!line) return;
    try {
      await sale.removeLine(line.id);
      setSelected((s) => Math.min(s, lines.length - 2));
    } catch (err) {
      toast(describe(err));
    }
  }, [lines, selected, sale, toast]);

  /* ── Keyboard ────────────────────────────────────────────────────────── */

  const searching = query.trim().length >= 2 && hits.length > 0;

  useHotkeys(
    (e) => {
      if (mode !== "till") return;
      if (!online && e.key !== "Escape") return;

      /*
       * A key the quantity editor has already dealt with is not a till command.
       *
       * `useHotkeys` listens on the document through a ref it refreshes every
       * render, and React flushes a discrete keydown synchronously — so by the
       * time this listener sees the Enter that *closed* the editor,
       * `editingQty` is already false and the branch below reopens it, seeded
       * with the quantity the commit is still in the middle of replacing.
       */
      if ((e.target as HTMLElement | null)?.dataset?.qtyEditor === "1") return;

      /*
       * While a quantity is being typed, the input owns the keyboard.
       *
       * Not a guard for tidiness: `-` would otherwise step the line down by
       * one instead of typing a minus, `Backspace` would delete the line
       * rather than a digit, and `+` would do both at once. Escape still
       * belongs to the till, so there is always one key that gets out.
       */
      if (editingQty) {
        if (e.key === "Escape") {
          e.preventDefault();
          setEditingQty(false);
          focusSearch();
        }
        return;
      }

      switch (e.key) {
        case "F9":
          e.preventDefault();
          if (lines.length) setMode("pay");
          return;
        case "F4":
          e.preventDefault();
          if (lines.length) setMode("hold");
          return;
        case "F3":
          e.preventDefault();
          setMode("recall");
          return;
        case "F2":
          e.preventDefault();
          setMode("customer");
          return;
        case "Escape":
          e.preventDefault();
          if (query || pendingQty !== null) {
            setQuery("");
            setHits([]);
            setPendingQty(null);
          } else {
            setSelected(-1);
          }
          focusSearch();
          return;
      }

      // Arrows address the results while there is a search, and the basket
      // when there is not. One rule, and no mode to be stranded in.
      if (e.key === "ArrowDown" || e.key === "ArrowUp") {
        e.preventDefault();
        const dir = e.key === "ArrowDown" ? 1 : -1;
        if (searching) {
          setHitIndex((i) => Math.max(0, Math.min(hits.length - 1, i + dir)));
        } else if (lines.length) {
          setSelected((s) =>
            s < 0 ? (dir > 0 ? 0 : lines.length - 1) : Math.max(0, Math.min(lines.length - 1, s + dir)),
          );
        }
        return;
      }

      if (e.key === "Enter" && searching) {
        e.preventDefault();
        const hit = hits[hitIndex];
        if (hit) void addFromHit(hit);
        return;
      }

      // With nothing being searched, Enter opens the selected line's quantity
      // for typing. The same key that commits a search result commits a
      // quantity, and neither is ever available at the same moment.
      if (e.key === "Enter" && !query && selected >= 0 && lines.length) {
        e.preventDefault();
        setEditingQty(true);
        return;
      }

      // Quantity is a prefix, not a mode: type 3 then * and the next item in
      // comes at three. The chip beside the search box is the visible state.
      if (e.key === "*" && /^\d+(\.\d+)?$/.test(query)) {
        e.preventDefault();
        setPendingQty(Number.parseFloat(query));
        setQuery("");
        setHits([]);
        return;
      }

      if (!query) {
        if (e.key === "+" || e.key === "=") {
          e.preventDefault();
          void adjustQty(1);
          return;
        }
        if (e.key === "-") {
          e.preventDefault();
          void adjustQty(-1);
          return;
        }
        if ((e.key === "Delete" || e.key === "Backspace") && selected >= 0) {
          e.preventDefault();
          void removeSelected();
          return;
        }
      }
    },
    [mode, online, query, pendingQty, searching, editingQty, hits, hitIndex, lines, selected, addFromHit, adjustQty, removeSelected, focusSearch],
  );

  useEffect(() => {
    if (mode === "till") focusSearch();
  }, [mode, focusSearch]);

  useEffect(() => {
    if (freshLine === null) return;
    const t = window.setTimeout(() => setFreshLine(null), 600);
    return () => window.clearTimeout(t);
  }, [freshLine]);

  /* ── Totals ──────────────────────────────────────────────────────────── */

  const totals = useMemo(() => {
    const s = sale.sale;
    return {
      subtotal: num(s?.subtotal),
      discount: num(s?.discountTotal),
      tax: num(s?.taxTotal),
      rounding: num(s?.roundingAdjustment),
      grand: num(s?.grandTotal),
    };
  }, [sale.sale]);

  /* ── Render ──────────────────────────────────────────────────────────── */

  return (
    <div className="h-full grid grid-rows-[auto_1fr_auto] bg-ground">
      <StatusBar
        tillCode={tillCode}
        cashier={user?.username ?? ""}
        online={online}
        agent={agent}
        onSignOut={() => void signOut()}
        onGoToBackOffice={onGoToBackOffice}
        onOpenAccount={onOpenAccount}
      />

      <div className="grid grid-cols-1 lg:grid-cols-[minmax(0,1.65fr)_minmax(330px,0.85fr)] min-h-0">
        {/* ── Basket side ── */}
        <div className="grid grid-rows-[auto_1fr] min-h-0 lg:border-r border-line">
          <div className="relative px-4 pt-3.5 pb-3 bg-surface border-b border-line">
            <div className="flex items-center gap-2.5">
              {pendingQty !== null && (
                <span className="tnum font-semibold text-lg bg-accent text-accentink px-3 py-1.5 rounded whitespace-nowrap">
                  {fmtQty(pendingQty)} ×
                </span>
              )}
              <input
                ref={searchRef}
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                disabled={!online}
                autoComplete="off"
                spellCheck={false}
                aria-label="Search products or scan a barcode"
                placeholder={online ? 'Scan, or type a name — cement, weedkiller, 4" pipe…' : "Read-only — no server"}
                className="flex-1 min-w-0 text-[17px] px-3.5 py-2.5 bg-surface2 text-ink border border-line rounded
                           placeholder:text-inkfaint disabled:opacity-60
                           focus:outline-none focus:border-accent focus:ring-[3px] focus:ring-accentwash"
              />
            </div>

            {searching && (
              <div
                role="listbox"
                aria-label="Search results"
                className="absolute left-4 right-4 top-[calc(100%-4px)] z-40 bg-surface border border-line rounded max-h-[340px] overflow-y-auto"
                style={{ boxShadow: "var(--shadow)" }}
              >
                {hits.map((hit, i) => {
                  const unit = [...hit.units].sort((a, b) => num(a.factor) - num(b.factor))[0];
                  return (
                    <div
                      key={hit.product.id}
                      role="option"
                      aria-selected={i === hitIndex}
                      onMouseDown={(e) => {
                        e.preventDefault();
                        void addFromHit(hit);
                      }}
                      className={
                        "grid grid-cols-[1fr_auto] gap-x-3.5 gap-y-1 px-3.5 py-2.5 cursor-pointer border-b border-linesoft last:border-b-0 " +
                        (i === hitIndex ? "bg-accentwash" : "")
                      }
                    >
                      <div className="font-medium">{hit.product.name}</div>
                      <div className="tnum font-medium self-center">
                        {unit?.unitPrice ? money(num(unit.unitPrice)) : "—"}
                        <span className="text-inkfaint font-normal"> / {unit?.uomCode ?? "?"}</span>
                      </div>
                      <div className="col-start-1 flex flex-wrap gap-2.5 text-[12.5px] text-inksoft">
                        <span className="font-mono text-xs text-inkfaint">{hit.product.sku}</span>
                        {hit.product.localName && <span>“{hit.product.localName}”</span>}
                        {hit.product.isBatchTracked && <span className="text-warn">batch tracked</span>}
                      </div>
                    </div>
                  );
                })}
              </div>
            )}
          </div>

          <div ref={basketRef} className="overflow-y-auto py-2 pb-6 min-h-0">
            {lines.length === 0 ? (
              <div className="h-full flex flex-col items-center justify-center gap-2.5 text-inkfaint text-center p-6">
                <div className="text-[17px] text-inksoft">Ready</div>
                <p className="m-0 max-w-[34ch] text-sm">
                  Scan an item, or start typing a name. Most stock here has no barcode, so the
                  search is forgiving — try “cement”, “weedkiller”, or a short code.
                </p>
              </div>
            ) : (
              lines.map((line, i) => (
                <Line
                  key={line.id}
                  line={line}
                  selected={i === selected}
                  editing={i === selected && editingQty}
                  fresh={line.id === freshLine}
                  onSelect={() => {
                    setSelected(i);
                    focusSearch();
                  }}
                  onEdit={() => {
                    setSelected(i);
                    setEditingQty(true);
                  }}
                  onAdjust={(delta) => {
                    setSelected(i);
                    void adjustQty(delta);
                  }}
                  onCommit={(value) => void setQtyExact(line.id, value)}
                  onCancel={() => {
                    setEditingQty(false);
                    focusSearch();
                  }}
                />
              ))
            )}
          </div>
        </div>

        {/* ── Totals side ── */}
        <div className="grid grid-rows-[auto_auto_1fr_auto] bg-surface min-h-0">
          <div className="flex items-center justify-between gap-3 px-4 py-3 border-b border-line">
            <div>
              <div className="text-[11.5px] uppercase tracking-[.07em] text-inkfaint">Customer</div>
              <div className="font-semibold">{customer?.name ?? "Walk-in"}</div>
              {customer?.creditLimit && (
                <div className="tnum text-[12.5px] text-inksoft">
                  Limit {money(num(customer.creditLimit))}
                </div>
              )}
            </div>
            <Button variant="default" className="!px-3 !py-1.5 !text-sm" hint="F2" onClick={() => setMode("customer")}>
              Change
            </Button>
          </div>

          <div className="px-4 pt-3.5 pb-1 grid gap-1.5">
            <Sum label="Subtotal" value={totals.subtotal} />
            {totals.discount > 0 && <Sum label="Discount" value={-totals.discount} />}
            {totals.tax > 0 && <Sum label="Tax" value={totals.tax} />}
            {Math.abs(totals.rounding) > 0.0001 && (
              <Sum label="Cash rounding" value={totals.rounding} tone="warn" />
            )}
          </div>

          <div className="mt-auto px-4 py-4 border-t border-line">
            <div className="text-[11.5px] uppercase tracking-[.07em] text-inkfaint">Total to pay</div>
            <div className="tnum font-semibold leading-[1.05] tracking-[-.02em] flex items-baseline gap-2.5 text-[clamp(34px,4.6vw,52px)]">
              <span className="text-[.42em] text-inksoft font-medium">GHS</span>
              <span>{money(totals.grand)}</span>
            </div>
          </div>

          <div className="px-4 pb-4 grid gap-2">
            <Button
              variant="primary"
              hint="F9"
              disabled={!lines.length || !online || sale.busy}
              onClick={() => setMode("pay")}
            >
              Take payment
            </Button>
            <div className="grid grid-cols-2 gap-2">
              <Button hint="F4" disabled={!lines.length || !online} onClick={() => setMode("hold")}>
                Hold
              </Button>
              <Button hint="F3" disabled={!online} onClick={() => setMode("recall")}>
                Recall
              </Button>
            </div>
          </div>
        </div>
      </div>

      <div className="flex gap-3.5 flex-wrap px-4 py-2 border-t border-line bg-surface text-xs text-inkfaint">
        <span><Key>↑↓</Key> pick a line</span>
        <span><Key>+</Key><Key>−</Key> quantity</span>
        <span><Key>↵</Key> type a quantity</span>
        <span><Key>Del</Key> remove</span>
        <span><Key>3</Key><Key>*</Key> then scan for 3 of it</span>
        <span><Key>Esc</Key> back out</span>
      </div>

      {mode === "pay" && sale.sale && (
        <PayPanel
          sale={sale.sale}
          customer={customer}
          onClose={() => setMode("till")}
          onCompleted={() => {
            sale.clear();
            setCustomer(null);
            setSelected(-1);
            setMode("till");
          }}
        />
      )}
      {mode === "customer" && (
        <CustomerPanel
          onClose={() => setMode("till")}
          onPick={async (picked) => {
            setCustomer(picked);
            try {
              await sale.setCustomer(picked?.id ?? null);
            } catch (err) {
              toast(describe(err));
            }
            setMode("till");
          }}
        />
      )}
      {mode === "hold" && sale.sale && (
        <HoldPanel
          suggested={customer?.name ?? ""}
          onClose={() => setMode("till")}
          onHold={async (label) => {
            try {
              await sale.hold(label);
              setCustomer(null);
              setSelected(-1);
              toast({ title: `Held — ${label}`, body: "Recall it from any till with F3.", tone: "good" });
            } catch (err) {
              toast(describe(err));
            }
            setMode("till");
          }}
        />
      )}
      {mode === "recall" && (
        <RecallPanel
          onClose={() => setMode("till")}
          onRecall={async (heldId) => {
            try {
              const recalled = await sale.recall(heldId);
              setSelected(recalled.lines.length ? 0 : -1);
            } catch (err) {
              toast(describe(err));
            }
            setMode("till");
          }}
        />
      )}
    </div>
  );
}

/* ── Pieces ─────────────────────────────────────────────────────────────── */

function StatusBar({
  tillCode,
  cashier,
  online,
  agent,
  onSignOut,
  onGoToBackOffice,
  onOpenAccount,
}: {
  tillCode: string;
  cashier: string;
  online: boolean;
  agent: AgentState;
  onSignOut: () => void;
  onGoToBackOffice?: () => void;
  onOpenAccount: () => void;
}) {
  return (
    <div className="flex items-center gap-5 flex-wrap px-4 h-10 bg-surface border-b border-line text-[12.5px] text-inksoft">
      <span className="font-mono font-semibold tracking-[.04em] text-ink">{tillCode}</span>
      <span>
        <strong className="text-ink font-semibold">{cashier}</strong>
      </span>
      <span className="flex items-center gap-1.5">
        <span
          className="w-[7px] h-[7px] rounded-full inline-block"
          style={{ background: online ? "var(--good)" : "var(--danger)" }}
        />
        {online ? "Server connected" : "Read-only — no server"}
      </span>
      {agent === "missing" && (
        <span
          className="text-inkfaint"
          title="Receipts can still be printed later from the sale once a printer is attached."
        >
          No printer on this till
        </span>
      )}
      <span className="ml-auto flex gap-2">
        {onGoToBackOffice && (
          <Button variant="quiet" onClick={onGoToBackOffice}>Back office</Button>
        )}
        <Button variant="quiet" onClick={onOpenAccount}>My account</Button>
        <Button variant="quiet" onClick={onSignOut}>Sign out</Button>
      </span>
    </div>
  );
}

function Sum({ label, value, tone }: { label: string; value: number; tone?: "warn" }) {
  return (
    <div className={"flex justify-between gap-3 text-[13.5px] " + (tone === "warn" ? "text-warn" : "text-inksoft")}>
      <span>{label}</span>
      <span className="tnum">
        {value > 0 && tone === "warn" ? "+" : ""}
        {money(value)}
      </span>
    </div>
  );
}

function Line({
  line,
  selected,
  editing,
  fresh,
  onSelect,
  onEdit,
  onAdjust,
  onCommit,
  onCancel,
}: {
  line: SaleLineView;
  selected: boolean;
  editing: boolean;
  fresh: boolean;
  onSelect: () => void;
  onEdit: () => void;
  onAdjust: (delta: number) => void;
  onCommit: (value: string) => void;
  onCancel: () => void;
}) {
  const [draft, setDraft] = useState("");

  /*
   * One edit settles once, by whichever key or click ends it.
   *
   * Committing on Enter hands focus back to the search box, which blurs this
   * input and would commit a second time; Escape would do worse, cancelling
   * and then saving through the blur it caused. Typing `0` made that visible —
   * the line went, and the duplicate arrived at a line the server had already
   * removed.
   */
  const settled = useRef(false);

  // Seeded when the editor opens, not on every render: the line's quantity
  // changes under it the moment the server answers, and re-seeding then would
  // overwrite what is being typed.
  useEffect(() => {
    if (!editing) return;
    settled.current = false;
    setDraft(fmtQty(num(line.qty)));
  }, [editing]);

  const commit = () => {
    if (settled.current) return;
    settled.current = true;
    onCommit(draft);
  };

  const cancel = () => {
    if (settled.current) return;
    settled.current = true;
    onCancel();
  };

  return (
    <div
      aria-selected={selected}
      onMouseDown={(e) => {
        e.preventDefault();
        onSelect();
      }}
      className={
        "grid grid-cols-[54px_1fr_auto_auto] gap-x-3 gap-y-0.5 px-4 py-2.5 border-b border-linesoft items-baseline cursor-pointer " +
        (selected ? "bg-accentwash " : "") +
        (fresh ? "just-added" : "")
      }
    >
      {editing ? (
        <input
          className="tnum font-semibold text-base text-right w-full px-1 py-0.5 rounded bg-surface2 border border-accent focus:outline-none"
          value={draft}
          autoFocus
          inputMode="decimal"
          aria-label="Quantity"
          data-qty-editor="1"
          onMouseDown={(e) => e.stopPropagation()}
          onChange={(e) => setDraft(e.target.value)}
          onBlur={commit}
          onKeyDown={(e) => {
            if (e.key === "Enter") {
              e.preventDefault();
              commit();
            }
            if (e.key === "Escape") {
              e.preventDefault();
              cancel();
            }
          }}
        />
      ) : (
        <div
          className="tnum font-semibold text-base text-right"
          onMouseDown={(e) => {
            e.preventDefault();
            e.stopPropagation();
            onEdit();
          }}
        >
          {fmtQty(num(line.qty))}
        </div>
      )}
      <div className="font-medium">
        <LineName productId={line.productId} />
      </div>
      <div className="tnum text-[13px] text-inksoft text-right">{money(num(line.unitPrice))}</div>
      <div className="tnum font-semibold text-base text-right min-w-[82px]">{money(num(line.lineTotal))}</div>
      {num(line.discountAmount) > 0 && (
        <div className="col-start-2 col-end-[-1] text-[12.5px] text-warn">
          Discount −{money(num(line.discountAmount))}
        </div>
      )}
      {/*
        Shown as the item is scanned, not when Pay is pressed. The buyer goes in
        the register with the customer standing there, and a cashier who learns
        at the drawer that they need an ID has already let them walk to the door.
      */}
      {line.requiresBuyerRecord && (
        <div className="col-start-2 col-end-[-1] text-[12.5px] text-warn">
          Restricted — buyer's details needed
        </div>
      )}

      {/*
        Stepping without the keyboard.
        The footer says `+` and `−` do this, and on a till driven by a mouse
        that is a hint about a keyboard nobody is using. They appear on the
        selected line only, so twelve rows do not become twenty-four buttons to
        mis-click near a payment button.
      */}
      {selected && !editing && (
        <div className="col-start-1 col-end-[-1] flex items-center gap-1.5 pt-1.5">
          <Step label="one fewer" onClick={() => onAdjust(-1)}>
            −
          </Step>
          <Step label="one more" onClick={() => onAdjust(1)}>
            +
          </Step>
          <span className="text-[12px] text-inkfaint pl-1">
            or click the quantity to type it
          </span>
        </div>
      )}
    </div>
  );
}

/** A stepper on the selected basket line. Deliberately small and out of the way. */
function Step({
  children,
  label,
  onClick,
}: {
  children: ReactNode;
  label: string;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      aria-label={label}
      onMouseDown={(e) => {
        e.preventDefault();
        e.stopPropagation();
      }}
      onClick={onClick}
      className="w-7 h-7 grid place-items-center rounded border border-line bg-surface text-ink text-[15px] leading-none hover:border-accent hover:text-accent"
    >
      {children}
    </button>
  );
}
