import { useCallback, useEffect, useMemo, useState } from "react";
import { api, money, num } from "../api/client";
import type {
  BuyerRecordDraft,
  CompletedSaleView,
  CreditDecision,
  CustomerView,
  SaleView,
  TenderDraft,
} from "../api/types";
import {
  Button,
  Callout,
  Field,
  Key,
  Panel,
  inputClass,
  useHotkeys,
  useToast,
} from "../ui/components";
import { useProductName } from "./productNames";
import { tillCode } from "./tillCode";
import { describe } from "./useSale";

/* ── Payment ────────────────────────────────────────────────────────────── */

const METHODS = [
  { key: "C", id: "CASH", label: "Cash" },
  { key: "M", id: "MOBILE_MONEY", label: "Mobile money" },
  { key: "B", id: "BANK_TRANSFER", label: "Bank transfer" },
  { key: "A", id: "ON_ACCOUNT", label: "On account" },
] as const;

/**
 * Split tender is the normal case here, not an exception — part cash, part
 * mobile money, the balance on account. So this is not a method picker: it is a
 * remaining balance that has to reach zero, and it says so at the top.
 */
export function PayPanel({
  sale,
  customer,
  onClose,
  onCompleted,
}: {
  sale: SaleView;
  customer: CustomerView | null;
  onClose: () => void;
  onCompleted: () => void;
}) {
  const toast = useToast();
  const grand = num(sale.grandTotal);

  const [tenders, setTenders] = useState<TenderDraft[]>([]);
  const [amount, setAmount] = useState(money(grand));
  const [momoRef, setMomoRef] = useState("");
  const [busy, setBusy] = useState(false);
  const [approval, setApproval] = useState<{ username: string; pin: string } | null>(null);
  const [needsApproval, setNeedsApproval] = useState<string | null>(null);
  const [done, setDone] = useState<CompletedSaleView | null>(null);
  const [printing, setPrinting] = useState(false);
  const [printed, setPrinted] = useState(false);

  /*
   * The §6.3 register, keyed by sale line.
   *
   * Held here rather than posted as it is typed: a buyer written down for a
   * line that is then removed from the basket would be a record of a sale that
   * never happened, and the register is evidence.
   */
  const [buyerRecords, setBuyerRecords] = useState<Record<number, BuyerRecordDraft>>({});
  const [collectingFor, setCollectingFor] = useState<number | null>(null);

  const restrictedLines = useMemo(
    () => sale.lines.filter((l) => l.requiresBuyerRecord),
    [sale.lines],
  );
  const awaitingBuyer = restrictedLines.filter((l) => !buyerRecords[l.id]);
  const collectingLine = sale.lines.find((l) => l.id === collectingFor);
  const collectingName = useProductName(collectingLine?.productId);

  const paid = useMemo(() => tenders.reduce((a, t) => a + t.amount, 0), [tenders]);
  const remaining = Math.round((grand - paid) * 100) / 100;
  const settled = remaining <= 0.0001;

  const cashTender = tenders.find((t) => t.tendered !== undefined);
  const change = cashTender ? Math.round((cashTender.tendered! - cashTender.amount) * 100) / 100 : 0;

  const allocate = useCallback(
    async (methodId: string) => {
      const value = Number.parseFloat(amount.replace(/,/g, ""));
      if (!Number.isFinite(value) || value <= 0) {
        toast({ title: "Enter an amount first", tone: "warn" });
        return;
      }

      if (methodId === "MOBILE_MONEY" && !momoRef.trim()) {
        toast({
          title: "Key in the reference the customer read out",
          body: "There is no connection to confirm it against — the reference is the record.",
          tone: "warn",
        });
        return;
      }

      if (methodId === "ON_ACCOUNT") {
        if (!customer) {
          toast({ title: "No account to charge", body: "Choose a customer first — F2.", tone: "danger" });
          return;
        }
        // Asked before the sale is committed so the answer arrives while the
        // customer is still at the counter, not as a failure at the end.
        try {
          const decision = await api.get<CreditDecision>(
            `/api/customers/${customer.id}/account/credit-check?amount=${value}`,
          );
          if (decision.outcome === "REFUSED") {
            toast({ title: decision.reason ?? "This cannot go on account", tone: "danger" });
            return;
          }
          if (decision.outcome === "REQUIRES_APPROVAL" && !approval) {
            setNeedsApproval(decision.reason ?? "This is over the customer's credit limit.");
            return;
          }
        } catch (err) {
          toast(describe(err));
          return;
        }
      }

      // Cash over the remaining balance is change, not an error.
      if (methodId === "CASH" && value > remaining) {
        setTenders((t) => [...t, { method: "CASH", amount: remaining, tendered: value }]);
      } else {
        const capped = Math.min(value, remaining);
        setTenders((t) => [
          ...t,
          {
            method: methodId,
            amount: capped,
            momoNetwork: methodId === "MOBILE_MONEY" ? "MTN" : undefined,
            reference: methodId === "MOBILE_MONEY" ? momoRef.trim() : undefined,
          },
        ]);
      }
      setMomoRef("");
      setAmount(money(Math.max(Math.round((remaining - Math.min(value, remaining)) * 100) / 100, 0)));
    },
    [amount, remaining, customer, momoRef, approval, toast],
  );

  const complete = useCallback(async () => {
    // Stopped here rather than sent and refused. The server would say no
    // anyway; asking first is the difference between a form and an error.
    const next = awaitingBuyer[0];
    if (next) {
      setCollectingFor(next.id);
      return;
    }
    setBusy(true);
    try {
      const result = await api.post<CompletedSaleView>(
        `/api/sales/${sale.id}/complete`,
        {
          tenders: tenders.map((t) => ({
            method: t.method,
            amount: t.amount,
            tendered: t.tendered ?? null,
            momoNetwork: t.momoNetwork ?? null,
            reference: t.reference ?? null,
          })),
          tillCode: tillCode(),
          creditApproval: approval,
          buyerRecords: Object.values(buyerRecords),
        },
        // The till mints this when the operator presses Pay. A retry — a double
        // click, or a switch that dropped the response — returns the original
        // sale rather than deducting stock a second time.
        { "Idempotency-Key": crypto.randomUUID() },
      );
      setDone(result);
      if (result.replayed) {
        toast({ title: "Already completed", body: "That payment had gone through — nothing was charged twice.", tone: "warn" });
      }
    } catch (err) {
      toast(describe(err));
    } finally {
      setBusy(false);
    }
  }, [sale.id, tenders, approval, awaitingBuyer, buyerRecords, toast]);

  /*
   * Prints the receipt, because the customer asked for one.
   *
   * Most do not, which is why nothing queues this on its own. A failure here
   * is a toast and nothing more — the sale is recorded either way, and the
   * paper can be printed again from the sale afterwards.
   */
  const printReceipt = useCallback(async () => {
    if (!done) return;
    setPrinting(true);
    try {
      await api.post("/api/printing/receipt", {
        saleId: done.sale.id,
        tillCode: tillCode(),
        isReprint: printed,
      });
      setPrinted(true);
    } catch (err) {
      toast(describe(err));
    } finally {
      setPrinting(false);
    }
  }, [done, printed, toast]);

  useHotkeys(
    (e) => {
      if (done) {
        if (e.key === "Enter") {
          e.preventDefault();
          onCompleted();
          return;
        }
        if (e.key.toLowerCase() === "p") {
          e.preventDefault();
          void printReceipt();
        }
        return;
      }
      if (e.key === "Escape") {
        e.preventDefault();
        onClose();
        return;
      }
      if (e.key === "Enter") {
        e.preventDefault();
        if (settled) void complete();
        else void allocate("CASH");
        return;
      }
      const target = e.target as HTMLElement | null;
      if (target && ["INPUT", "TEXTAREA"].includes(target.tagName)) return;
      const m = METHODS.find((x) => x.key.toLowerCase() === e.key.toLowerCase());
      if (m) {
        e.preventDefault();
        void allocate(m.id);
      }
    },
    [done, printed, printReceipt, settled, allocate, complete, onClose, onCompleted],
  );

  if (done) {
    return (
      <Panel title="Sale complete" hint={done.sale.number ?? ""} onClose={onCompleted}>
        <div className="text-center py-6 px-4 bg-goodwash rounded">
          <div className="text-xs uppercase tracking-[.07em] text-inksoft">
            {change > 0 ? "Change to give" : "Paid in full"}
          </div>
          <div className="tnum font-semibold text-[46px] leading-tight text-good">
            {money(change > 0 ? change : grand)}
          </div>
        </div>
        {printed && (
          <Callout tone="info">
            Receipt queued. If the printer is jammed or out of paper the job waits on the till and
            prints when it is fixed — nothing is lost.
          </Callout>
        )}
        {/*
          Stays available after the first copy. There is no sale-history screen
          to go back to, so once this panel closes the receipt cannot be asked
          for again — a customer who changes their mind has to be served here.
        */}
        <Button variant="quiet" hint="P" disabled={printing} onClick={() => void printReceipt()}>
          {printing ? "Printing…" : printed ? "Print another copy" : "Print receipt"}
        </Button>
        <Button variant="primary" hint="Enter" onClick={onCompleted}>
          Next customer
        </Button>
      </Panel>
    );
  }

  if (collectingFor !== null) {
    return (
      <BuyerRecordPanel
        productName={collectingName}
        remaining={awaitingBuyer.length - 1}
        onClose={() => setCollectingFor(null)}
        onSave={(record) => {
          setBuyerRecords((r) => ({ ...r, [collectingFor]: { ...record, saleLineId: collectingFor } }));
          setCollectingFor(null);
        }}
      />
    );
  }

  if (needsApproval) {
    return (
      <ApprovalPanel
        reason={needsApproval}
        onClose={() => setNeedsApproval(null)}
        onApprove={(creds) => {
          setApproval(creds);
          setNeedsApproval(null);
          toast({ title: "Approved", body: "Allocate the amount again to apply it.", tone: "good" });
        }}
      />
    );
  }

  return (
    <Panel title="Take payment" hint="Esc to go back" onClose={onClose}>
      <div
        className={
          "flex items-baseline justify-between gap-3 px-4 py-3.5 rounded " +
          (settled ? "bg-goodwash" : "bg-accentwash")
        }
      >
        <span className="text-xs uppercase tracking-[.06em] text-inksoft">
          {settled ? "Fully paid" : "Still to pay"}
        </span>
        <span className={"tnum font-semibold text-3xl " + (settled ? "text-good" : "")}>
          {money(Math.max(remaining, 0))}
        </span>
      </div>

      {tenders.length > 0 && (
        <div className="grid gap-1.5">
          {tenders.map((t, i) => (
            <div key={i} className="flex justify-between gap-3 px-3 py-2 bg-surface2 rounded text-sm">
              <span>
                {METHODS.find((m) => m.id === t.method)?.label ?? t.method}
                {t.reference && <span className="text-inksoft text-[12.5px]"> · {t.reference}</span>}
                {t.tendered !== undefined && (
                  <span className="text-inksoft text-[12.5px]"> · {money(t.tendered)} given</span>
                )}
              </span>
              <span className="tnum font-medium">{money(t.amount)}</span>
            </div>
          ))}
        </div>
      )}

      {!settled && (
        <>
          <Field label="Amount">
            <input
              className={inputClass + " tnum text-2xl text-right"}
              inputMode="decimal"
              value={amount}
              autoFocus
              onFocus={(e) => e.target.select()}
              onChange={(e) => setAmount(e.target.value)}
            />
          </Field>

          <Field label="Mobile money reference (if paying by MoMo)">
            <input
              className={inputClass}
              value={momoRef}
              onChange={(e) => setMomoRef(e.target.value)}
              placeholder="What the customer reads out"
            />
          </Field>

          <div className="grid grid-cols-2 gap-2">
            {METHODS.map((m) => {
              const blocked = m.id === "ON_ACCOUNT" && !customer;
              return (
                <button
                  key={m.id}
                  disabled={blocked}
                  onClick={() => void allocate(m.id)}
                  className="flex items-center gap-2.5 px-3 py-2.5 rounded border border-line bg-surface2 text-ink
                             text-left hover:enabled:border-accent disabled:opacity-40"
                >
                  <Key>{m.key}</Key>
                  {m.label}
                </button>
              );
            })}
          </div>

          <p className="m-0 text-[13px] text-inkfaint">
            Paying part now? Type a smaller amount and pick a method — the balance stays here
            until it reaches zero.
          </p>
        </>
      )}

      {settled && (
        <Button variant="primary" hint="Enter" disabled={busy} onClick={() => void complete()}>
          {busy ? "Completing…" : "Complete sale"}
        </Button>
      )}
    </Panel>
  );
}

/* ── Supervisor approval ────────────────────────────────────────────────── */

/**
 * A PIN entered in front of a queue.
 *
 * Masked, and it authorises exactly the action named above it — the server
 * re-evaluates under the approver's own roles rather than trusting a flag, so
 * a supervisor who is not entitled to approve this cannot.
 */
function ApprovalPanel({
  reason,
  onClose,
  onApprove,
}: {
  reason: string;
  onClose: () => void;
  onApprove: (creds: { username: string; pin: string }) => void;
}) {
  const [username, setUsername] = useState("");
  const [pin, setPin] = useState("");

  return (
    <Panel title="A supervisor needs to approve this" hint="Esc to cancel" onClose={onClose}>
      <Callout tone="warn">{reason}</Callout>
      <Field label="Supervisor username">
        <input className={inputClass} value={username} autoFocus onChange={(e) => setUsername(e.target.value)} />
      </Field>
      <Field label="Till PIN">
        <input
          className={inputClass + " tnum text-xl"}
          type="password"
          inputMode="numeric"
          value={pin}
          onChange={(e) => setPin(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter" && username && pin) onApprove({ username, pin });
          }}
        />
      </Field>
      <Button variant="primary" disabled={!username || !pin} onClick={() => onApprove({ username, pin })}>
        Approve
      </Button>
    </Panel>
  );
}

/* ── Buyer register ─────────────────────────────────────────────────────── */

const ID_TYPES = ["Ghana Card", "Voter ID", "Passport", "Driver's licence", "Other"] as const;

/**
 * The §6.3 buyer register, filled in at the counter.
 *
 * Restricted agro-chemicals cannot be sold without the buyer written down, and
 * the server refuses the sale outright — so this is not a nicety, it is the
 * only way that line gets paid for. It appears when Pay is pressed rather than
 * on the way in, because a basket is edited and a buyer written down for a line
 * that is then removed is a record of a sale that never happened.
 *
 * One line at a time. A basket with two restricted items is two buyers as often
 * as it is one, and a single form covering both invites the second to be a copy
 * of the first.
 *
 * Only the name is obliged, matching the server. Everything else is what an
 * inspector expects and what a recall needs — but a counter that refuses to
 * proceed over a missing ID number teaches the cashier to type anything at all
 * into the box, and an invented ID is worse than an empty one.
 */
function BuyerRecordPanel({
  productName,
  remaining,
  onClose,
  onSave,
}: {
  productName: string;
  remaining: number;
  onClose: () => void;
  onSave: (record: Omit<BuyerRecordDraft, "saleLineId">) => void;
}) {
  const [buyerName, setBuyerName] = useState("");
  const [buyerPhone, setBuyerPhone] = useState("");
  const [buyerIdType, setBuyerIdType] = useState<string>(ID_TYPES[0]);
  const [buyerIdNumber, setBuyerIdNumber] = useState("");
  const [intendedUse, setIntendedUse] = useState("");

  const save = () => {
    if (!buyerName.trim()) return;
    onSave({
      buyerName: buyerName.trim(),
      buyerPhone: buyerPhone.trim(),
      buyerIdType: buyerIdNumber.trim() ? buyerIdType : "",
      buyerIdNumber: buyerIdNumber.trim(),
      intendedUse: intendedUse.trim(),
    });
  };

  return (
    <Panel title="Who is buying this?" hint="Esc to cancel" onClose={onClose}>
      <Callout tone="warn">
        {productName} is restricted. The buyer has to go in the register before it can be sold
        {remaining > 0 ? ` — ${remaining} more after this one.` : "."}
      </Callout>
      <Field label="Buyer's name">
        <input
          className={inputClass}
          value={buyerName}
          autoFocus
          onChange={(e) => setBuyerName(e.target.value)}
        />
      </Field>
      <Field label="Phone">
        <input
          className={inputClass + " tnum"}
          inputMode="tel"
          value={buyerPhone}
          onChange={(e) => setBuyerPhone(e.target.value)}
        />
      </Field>
      <div className="grid grid-cols-[1fr_1.3fr] gap-3">
        <Field label="ID type">
          <select
            className={inputClass}
            value={buyerIdType}
            onChange={(e) => setBuyerIdType(e.target.value)}
          >
            {ID_TYPES.map((t) => (
              <option key={t} value={t}>
                {t}
              </option>
            ))}
          </select>
        </Field>
        <Field label="ID number">
          <input
            className={inputClass}
            value={buyerIdNumber}
            onChange={(e) => setBuyerIdNumber(e.target.value)}
          />
        </Field>
      </div>
      <Field label="What is it for">
        <input
          className={inputClass}
          value={intendedUse}
          placeholder="maize farm, 2 acres"
          onChange={(e) => setIntendedUse(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter") save();
          }}
        />
      </Field>
      <Button variant="primary" disabled={!buyerName.trim()} onClick={save}>
        Record and continue
      </Button>
    </Panel>
  );
}

/* ── Customer ───────────────────────────────────────────────────────────── */

export function CustomerPanel({
  onClose,
  onPick,
}: {
  onClose: () => void;
  onPick: (c: CustomerView | null) => void;
}) {
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<CustomerView[]>([]);

  useEffect(() => {
    if (query.trim().length < 2) {
      setResults([]);
      return;
    }
    const t = window.setTimeout(async () => {
      try {
        setResults(
          await api.get<CustomerView[]>(`/api/customers/search?q=${encodeURIComponent(query.trim())}&limit=8`),
        );
      } catch {
        setResults([]);
      }
    }, 170);
    return () => window.clearTimeout(t);
  }, [query]);

  useHotkeys(
    (e) => {
      if (e.key === "Escape") {
        e.preventDefault();
        onClose();
      }
    },
    [onClose],
  );

  return (
    <Panel title="Who is buying?" hint="Esc to cancel" onClose={onClose}>
      <Field label="Search by name, code or phone">
        <input
          className={inputClass}
          value={query}
          autoFocus
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Kwame, 0244…"
        />
      </Field>

      <div className="grid gap-1.5">
        {results.map((c) => (
          <button
            key={c.id}
            onClick={() => onPick(c)}
            className="flex items-center justify-between gap-3 px-3 py-2.5 rounded border border-line
                       bg-surface2 text-ink text-left hover:border-accent"
          >
            <span>
              <span className="block font-medium">{c.name}</span>
              <span className="block text-[12.5px] text-inksoft font-mono">{c.code}</span>
            </span>
            <span className="tnum text-[12.5px] text-inksoft">
              {c.creditLimit ? `limit ${money(num(c.creditLimit))}` : "cash only"}
            </span>
          </button>
        ))}
      </div>

      <Button onClick={() => onPick(null)}>Walk-in — no account</Button>
    </Panel>
  );
}

/* ── Hold and recall ────────────────────────────────────────────────────── */

export function HoldPanel({
  suggested,
  onClose,
  onHold,
}: {
  suggested: string;
  onClose: () => void;
  onHold: (label: string) => void;
}) {
  const [label, setLabel] = useState(suggested);

  useHotkeys(
    (e) => {
      if (e.key === "Escape") {
        e.preventDefault();
        onClose();
      }
    },
    [onClose],
  );

  return (
    <Panel title="Park this sale" hint="Enter to hold · Esc to cancel" onClose={onClose}>
      <Callout tone="info">
        The basket is held on the server, not in this browser. Any till can recall it, and
        refreshing this page will not lose it.
      </Callout>
      <Field label="Label it so you can find it">
        <input
          className={inputClass}
          value={label}
          autoFocus
          onChange={(e) => setLabel(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter") onHold(label || "Held sale");
          }}
          placeholder="Kwame — gone for MoMo"
        />
      </Field>
      <Button variant="primary" hint="Enter" onClick={() => onHold(label || "Held sale")}>
        Hold this sale
      </Button>
    </Panel>
  );
}

export function RecallPanel({
  onClose,
  onRecall,
}: {
  onClose: () => void;
  onRecall: (saleId: number) => void;
}) {
  const [held, setHeld] = useState<SaleView[] | null>(null);

  useEffect(() => {
    api
      .get<SaleView[]>("/api/sales/held")
      .then(setHeld)
      .catch(() => setHeld([]));
  }, []);

  useHotkeys(
    (e) => {
      if (e.key === "Escape") {
        e.preventDefault();
        onClose();
      }
    },
    [onClose],
  );

  return (
    <Panel title="Held sales" hint="Esc to cancel" onClose={onClose}>
      {held === null && <p className="m-0 text-inksoft">Looking…</p>}
      {held?.length === 0 && (
        <p className="m-0 text-inksoft">Nothing is on hold. Park a sale with F4 when a customer goes for money.</p>
      )}
      <div className="grid gap-1.5">
        {held?.map((s) => (
          <button
            key={s.id}
            onClick={() => onRecall(s.id)}
            className="flex items-center justify-between gap-3 px-3 py-2.5 rounded border border-line
                       bg-surface2 text-ink text-left hover:border-accent"
          >
            <span>
              <span className="block font-medium">{s.heldLabel ?? `Sale ${s.id}`}</span>
              <span className="block text-[12.5px] text-inksoft">{s.lines.length} lines</span>
            </span>
            <span className="tnum font-semibold">{money(num(s.grandTotal))}</span>
          </button>
        ))}
      </div>
    </Panel>
  );
}
