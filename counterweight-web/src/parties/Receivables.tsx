import { useCallback, useEffect, useMemo, useState } from "react";
import { api, money, num } from "../api/client";
import type { DebtorView, OpenInvoiceView, Statement } from "../api/types";
import { describe } from "../till/useSale";
import { Button, Callout, Field, Panel, inputClass, useToast } from "../ui/components";

/**
 * Money owed to the shop.
 *
 * Opens on the book rather than on a search box. Chasing debt is not a lookup:
 * requiring a name first would mean only ever seeing what is owed by the people
 * somebody already suspects, and the account quietly going bad is the one
 * nobody thinks to type in.
 *
 * Ordered by overdue amount before total balance, because those are different
 * problems. A large account inside its terms is the business working; a small
 * one sixty days late is not.
 */
export function Receivables() {
  const toast = useToast();
  const [debtors, setDebtors] = useState<DebtorView[] | null>(null);
  const [selected, setSelected] = useState<DebtorView | null>(null);

  const load = useCallback(async () => {
    setDebtors(await api.get<DebtorView[]>("/api/receivables/debtors"));
  }, []);

  useEffect(() => {
    void load().catch((err) => toast(describe(err)));
  }, [load, toast]);

  const totals = useMemo(() => {
    let owed = 0;
    let overdue = 0;
    let inCredit = 0;
    for (const d of debtors ?? []) {
      const balance = num(d.balance);
      if (balance > 0) owed += balance;
      else inCredit += -balance;
      overdue += num(d.overdueAmount);
    }
    return { owed, overdue, inCredit };
  }, [debtors]);

  if (selected) {
    return (
      <CustomerAccount
        debtor={selected}
        onBack={() => {
          setSelected(null);
          void load();
        }}
      />
    );
  }

  if (!debtors) return <div className="p-6 text-inksoft">Loading the book…</div>;

  return (
    <div className="grid grid-rows-[auto_1fr] h-full min-h-0">
      <div className="px-5 py-4 bg-surface border-b border-line grid gap-3">
        <h1 className="m-0 text-xl font-semibold">Money owed</h1>
        <div className="flex gap-6 flex-wrap">
          <Figure label="Owed to the shop" value={totals.owed} />
          <Figure label="Of that, overdue" value={totals.overdue} tone={totals.overdue > 0 ? "danger" : undefined} />
          {totals.inCredit > 0 && (
            <Figure label="Paid in advance" value={totals.inCredit} tone="good" />
          )}
        </div>
      </div>

      <div className="overflow-y-auto min-h-0">
        {debtors.length === 0 && (
          <div className="h-full grid place-items-center text-center p-10">
            <div className="grid gap-2 max-w-[38ch]">
              <div className="text-lg text-good font-semibold">Nobody owes anything</div>
              <p className="m-0 text-inksoft text-sm">
                Every account is settled. Sales taken on account will appear here.
              </p>
            </div>
          </div>
        )}

        {debtors.map((d) => (
          <DebtorRow key={d.customerId} debtor={d} onOpen={() => setSelected(d)} />
        ))}
      </div>
    </div>
  );
}

function DebtorRow({ debtor, onOpen }: { debtor: DebtorView; onOpen: () => void }) {
  const balance = num(debtor.balance);
  const overdue = num(debtor.overdueAmount);
  const inCredit = balance < 0;

  return (
    <button
      onClick={onOpen}
      className="w-full text-left grid grid-cols-[1fr_auto] gap-x-5 items-center px-5 py-3 border-b border-linesoft bg-surface hover:bg-surface2 transition-colors"
    >
      <div className="min-w-0">
        <div className="font-medium truncate">{debtor.name}</div>
        <div className="flex flex-wrap gap-x-3 gap-y-0.5 text-[12.5px] text-inksoft">
          <span className="font-mono text-xs text-inkfaint">{debtor.code}</span>
          {/* The phone number is the point of this row: the next action is a call. */}
          {debtor.phone && <span className="tnum">{debtor.phone}</span>}
          {debtor.creditLimit === null && (
            <span className="text-inkfaint">cash only — no account</span>
          )}
          {overdue > 0 && (
            <span className="text-danger font-medium">
              {money(overdue)} overdue, oldest by {debtor.oldestOverdueDays} days
            </span>
          )}
        </div>
      </div>

      <div className="text-right">
        <div className={"tnum text-lg font-semibold " + (inCredit ? "text-good" : overdue > 0 ? "text-danger" : "")}>
          {money(Math.abs(balance))}
        </div>
        <div className="text-[12px] text-inkfaint">{inCredit ? "in credit" : "owed"}</div>
      </div>
    </button>
  );
}

/* ── One account ────────────────────────────────────────────────────────── */

function CustomerAccount({ debtor, onBack }: { debtor: DebtorView; onBack: () => void }) {
  const toast = useToast();
  const [statement, setStatement] = useState<Statement | null>(null);
  const [open, setOpen] = useState<OpenInvoiceView[]>([]);
  const [paying, setPaying] = useState(false);
  const [settingTerms, setSettingTerms] = useState(false);

  const load = useCallback(async () => {
    const [s, o] = await Promise.all([
      api.get<Statement>(`/api/customers/${debtor.customerId}/statement`),
      api.get<OpenInvoiceView[]>(`/api/customers/${debtor.customerId}/account/open-invoices`),
    ]);
    setStatement(s);
    setOpen(o);
  }, [debtor.customerId]);

  useEffect(() => {
    void load().catch((err) => toast(describe(err)));
  }, [load, toast]);

  if (!statement) return <div className="p-6 text-inksoft">Loading the account…</div>;

  const outstanding = open.reduce((sum, i) => sum + num(i.outstanding), 0);
  const ageing = statement.ageing;

  return (
    <div className="grid grid-rows-[auto_1fr] h-full min-h-0">
      <div className="px-5 py-4 bg-surface border-b border-line grid gap-3">
        <div className="flex items-baseline gap-3 flex-wrap">
          <Button variant="quiet" onClick={onBack}>← Everyone who owes</Button>
          <h1 className="m-0 text-xl font-semibold">{statement.customerName}</h1>
          <span className="font-mono text-xs text-inkfaint">{statement.customerCode}</span>
          {debtor.phone && <span className="text-inksoft tnum text-[13.5px]">{debtor.phone}</span>}
          <span className="ml-auto flex gap-2">
            <Button variant="quiet" onClick={() => setSettingTerms(true)}>Credit terms</Button>
            <Button variant="default" className="py-1.5 px-3 text-[13px]" onClick={() => setPaying(true)}>
              Record a payment
            </Button>
          </span>
        </div>

        <div className="flex gap-6 flex-wrap items-end">
          <Figure label="Balance" value={num(statement.closingBalance)} />
          <Figure
            label="Credit limit"
            value={statement.creditLimit === null ? null : num(statement.creditLimit)}
            emptyWord="cash only"
          />
          <Ageing buckets={ageing} />
        </div>
      </div>

      <div className="overflow-y-auto min-h-0">
        <Section title={`Open invoices — ${money(outstanding)} outstanding`}>
          {open.length === 0 ? (
            <p className="m-0 px-5 py-3 text-inksoft text-sm">Nothing unpaid.</p>
          ) : (
            open.map((inv) => (
              <div
                key={inv.entryId}
                className="grid grid-cols-[1fr_auto] gap-x-4 px-5 py-2.5 border-b border-linesoft items-baseline"
              >
                <div>
                  <span className="font-mono text-[13px]">{inv.reference ?? `entry ${inv.entryId}`}</span>
                  <span className="text-[12.5px] text-inksoft">
                    {inv.dueOn ? ` · due ${inv.dueOn}` : ""}
                    {inv.daysOverdue > 0 ? (
                      <span className="text-danger font-medium"> · {inv.daysOverdue} days late</span>
                    ) : null}
                  </span>
                  {num(inv.allocated) > 0 && (
                    <span className="text-[12.5px] text-inksoft"> · {money(num(inv.allocated))} paid</span>
                  )}
                </div>
                <div className="tnum font-semibold">{money(num(inv.outstanding))}</div>
              </div>
            ))
          )}
        </Section>

        <Section title="Statement">
          <div className="grid grid-cols-[auto_1fr_auto_auto] gap-x-4 px-5 py-2 text-xs uppercase tracking-[.05em] text-inkfaint border-b border-linesoft">
            <span>Date</span>
            <span>What</span>
            <span className="text-right">Amount</span>
            <span className="text-right">Balance</span>
          </div>

          <div className="grid grid-cols-[auto_1fr_auto_auto] gap-x-4 px-5 py-2 text-[13px] text-inksoft border-b border-linesoft">
            <span />
            <span className="italic">Brought forward</span>
            <span />
            <span className="tnum text-right">{money(num(statement.openingBalance))}</span>
          </div>

          {statement.lines.map((line) => {
            const amount = num(line.amount);
            return (
              <div
                key={line.entryId}
                className="grid grid-cols-[auto_1fr_auto_auto] gap-x-4 px-5 py-2 text-[13.5px] border-b border-linesoft items-baseline"
              >
                <span className="tnum text-inksoft text-[12.5px]">
                  {new Date(line.occurredAt).toLocaleDateString()}
                </span>
                <span className="min-w-0">
                  <span className="capitalize">{line.entryType.replace(/_/g, " ").toLowerCase()}</span>
                  {line.reference && (
                    <span className="text-inkfaint font-mono text-xs"> {line.reference}</span>
                  )}
                </span>
                {/* Signed as the ledger stores it: what increases the debt reads
                    plainly, what reduces it is the good news. */}
                <span className={"tnum text-right " + (amount < 0 ? "text-good" : "")}>
                  {amount < 0 ? "−" : ""}
                  {money(Math.abs(amount))}
                </span>
                <span className="tnum text-right text-inksoft">{money(num(line.runningBalance))}</span>
              </div>
            );
          })}

          {statement.lines.length === 0 && (
            <p className="m-0 px-5 py-3 text-inksoft text-sm">Nothing happened this month.</p>
          )}
        </Section>
      </div>

      {paying && (
        <PaymentPanel
          customerId={debtor.customerId}
          openInvoices={open}
          onClose={() => setPaying(false)}
          onRecorded={() => {
            setPaying(false);
            void load();
          }}
        />
      )}

      {settingTerms && (
        <CreditTermsPanel
          customerId={debtor.customerId}
          currentLimit={statement.creditLimit}
          onClose={() => setSettingTerms(false)}
          onSaved={() => {
            setSettingTerms(false);
            void load();
          }}
        />
      )}
    </div>
  );
}

/* ── Taking money ───────────────────────────────────────────────────────── */

/**
 * Recording a payment.
 *
 * Left alone, the server settles the oldest invoices first, which is what
 * happens when somebody walks in and hands over money. The manual split is for
 * the other case — a customer paying against a named invoice, usually because
 * they are disputing a different one — and getting that wrong is how a dispute
 * turns into two disputes.
 */
function PaymentPanel({
  customerId,
  openInvoices,
  onClose,
  onRecorded,
}: {
  customerId: number;
  openInvoices: OpenInvoiceView[];
  onClose: () => void;
  onRecorded: () => void;
}) {
  const toast = useToast();
  const [amount, setAmount] = useState("");
  const [reference, setReference] = useState("");
  const [manual, setManual] = useState(false);
  const [split, setSplit] = useState<Record<number, string>>({});
  const [busy, setBusy] = useState(false);

  const paid = num(amount);

  /**
   * What oldest-first will settle, worked out for display only.
   *
   * Mirrors the rule the server applies to the same list in the same order.
   * The server remains the authority — this exists so nobody hands over money
   * without knowing which invoice it lands on.
   */
  const preview = useMemo(() => {
    let left = paid;
    const rows = openInvoices.map((inv) => {
      const take = Math.min(left, num(inv.outstanding));
      left = Math.round((left - take) * 100) / 100;
      return { inv, take };
    });
    return { rows: rows.filter((r) => r.take > 0), unallocated: Math.round(left * 100) / 100 };
  }, [paid, openInvoices]);

  const manualTotal = useMemo(
    () => Object.values(split).reduce((sum, v) => sum + num(v), 0),
    [split],
  );

  const mismatch = manual && Math.abs(manualTotal - paid) > 0.004;

  async function record() {
    setBusy(true);
    try {
      await api.post(`/api/customers/${customerId}/account/payments`, {
        amount: paid,
        reference: reference.trim() || null,
        allocations: manual
          ? Object.entries(split)
              .filter(([, v]) => num(v) > 0)
              .map(([entryId, v]) => ({ invoiceEntryId: Number(entryId), amount: num(v) }))
          : null,
      });
      onRecorded();
    } catch (err) {
      toast(describe(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Panel title="Record a payment" hint="Esc to cancel" onClose={onClose} wide>
      <div className="grid grid-cols-2 gap-4">
        <Field label="Amount received (GHS)">
          <input
            className={inputClass + " tnum text-right text-2xl"}
            inputMode="decimal"
            value={amount}
            autoFocus
            placeholder="0.00"
            onChange={(e) => setAmount(e.target.value)}
          />
        </Field>
        <Field label="Reference">
          <input
            className={inputClass}
            value={reference}
            placeholder="MoMo reference, cheque number…"
            onChange={(e) => setReference(e.target.value)}
          />
        </Field>
      </div>

      <label className="flex items-center gap-2.5 text-[13.5px] cursor-pointer">
        <input type="checkbox" checked={manual} onChange={(e) => setManual(e.target.checked)} />
        <span>The customer named which invoices this is for</span>
      </label>

      {!manual && paid > 0 && (
        <div className="grid gap-2">
          <div className="text-xs uppercase tracking-[.05em] text-inkfaint">
            This will settle, oldest first
          </div>
          {preview.rows.length === 0 && (
            <p className="m-0 text-inksoft text-sm">Nothing outstanding — it will sit on the account.</p>
          )}
          {preview.rows.map(({ inv, take }) => (
            <div key={inv.entryId} className="flex justify-between gap-3 px-3 py-2 bg-surface2 rounded text-[13.5px]">
              <span>
                {inv.reference ?? `entry ${inv.entryId}`}
                {inv.daysOverdue > 0 && (
                  <span className="text-danger"> · {inv.daysOverdue} days late</span>
                )}
              </span>
              <span className="tnum font-medium">
                {money(take)}
                {take < num(inv.outstanding) && (
                  <span className="text-inkfaint font-normal"> of {money(num(inv.outstanding))}</span>
                )}
              </span>
            </div>
          ))}
          {preview.unallocated > 0 && (
            <Callout tone="info">
              {money(preview.unallocated)} is more than is currently owed. It stays on the account as
              credit and settles the next invoice automatically.
            </Callout>
          )}
        </div>
      )}

      {manual && (
        <div className="grid gap-2">
          <div className="text-xs uppercase tracking-[.05em] text-inkfaint">Split it yourself</div>
          {openInvoices.map((inv) => (
            <div key={inv.entryId} className="flex items-center justify-between gap-3">
              <span className="text-[13.5px] min-w-0 truncate">
                {inv.reference ?? `entry ${inv.entryId}`}
                <span className="text-inkfaint"> · {money(num(inv.outstanding))} left</span>
              </span>
              <input
                className={inputClass + " tnum text-right w-[130px]"}
                inputMode="decimal"
                placeholder="0.00"
                value={split[inv.entryId] ?? ""}
                onChange={(e) => setSplit((s) => ({ ...s, [inv.entryId]: e.target.value }))}
              />
            </div>
          ))}
          <div className="flex justify-between gap-3 pt-2 border-t border-line text-[13.5px]">
            <span className="text-inksoft">Allocated</span>
            <span className={"tnum font-semibold " + (mismatch ? "text-danger" : "")}>
              {money(manualTotal)} of {money(paid)}
            </span>
          </div>
          {mismatch && (
            <Callout tone="danger">
              The split has to add up to what was received. Leave the box unticked to let the oldest
              invoices take it in order.
            </Callout>
          )}
        </div>
      )}

      <Button variant="primary" disabled={busy || paid <= 0 || mismatch} onClick={() => void record()}>
        {busy ? "Recording…" : `Record ${money(paid)}`}
      </Button>
    </Panel>
  );
}

/* ── Credit terms ───────────────────────────────────────────────────────── */

function CreditTermsPanel({
  customerId,
  currentLimit,
  onClose,
  onSaved,
}: {
  customerId: number;
  currentLimit: string | null;
  onClose: () => void;
  onSaved: () => void;
}) {
  const toast = useToast();
  const [limit, setLimit] = useState(currentLimit ?? "");
  const [days, setDays] = useState("30");
  const [busy, setBusy] = useState(false);

  async function save() {
    setBusy(true);
    try {
      await api.put(`/api/customers/${customerId}/account/credit-terms`, {
        creditLimit: limit.trim() === "" ? null : num(limit),
        paymentTermsDays: Number(days),
      });
      onSaved();
    } catch (err) {
      toast(describe(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Panel title="Credit terms" hint="Esc to cancel" onClose={onClose}>
      {/*
        The one thing worth stating outright, because reading it the other way
        round hands an unlimited account to every walk-in on record.
      */}
      <Callout tone="warn">
        No limit means <strong>cash only</strong>, not unlimited. Clearing this box closes the
        account rather than opening it.
      </Callout>

      <Field label="Credit limit (GHS)">
        <input
          className={inputClass + " tnum text-right text-lg"}
          inputMode="decimal"
          value={limit}
          autoFocus
          placeholder="leave blank for cash only"
          onChange={(e) => setLimit(e.target.value)}
        />
      </Field>

      <Field label="Payment terms (days)">
        <input
          className={inputClass + " tnum text-right"}
          inputMode="numeric"
          value={days}
          onChange={(e) => setDays(e.target.value)}
        />
      </Field>

      <Button variant="primary" disabled={busy} onClick={() => void save()}>
        {busy ? "Saving…" : "Save the terms"}
      </Button>
    </Panel>
  );
}

/* ── Pieces ─────────────────────────────────────────────────────────────── */

function Figure({
  label,
  value,
  tone,
  emptyWord,
}: {
  label: string;
  value: number | null;
  tone?: "good" | "danger";
  emptyWord?: string;
}) {
  const colour = tone === "danger" ? "text-danger" : tone === "good" ? "text-good" : "text-ink";
  return (
    <div className="grid gap-0.5">
      <span className="text-xs uppercase tracking-[.05em] text-inkfaint">{label}</span>
      <span className={`tnum text-2xl font-semibold ${colour}`}>
        {value === null ? <span className="text-inksoft text-base">{emptyWord}</span> : money(value)}
      </span>
    </div>
  );
}

function Ageing({ buckets }: { buckets: Statement["ageing"] }) {
  const cells: Array<[string, string, boolean]> = [
    ["Not due", buckets.current, false],
    ["1–30", buckets.days1To30, true],
    ["31–60", buckets.days31To60, true],
    ["61–90", buckets.days61To90, true],
    ["90+", buckets.days90Plus, true],
  ];
  if (num(buckets.total) === 0) return null;

  return (
    <div className="grid gap-1">
      <span className="text-xs uppercase tracking-[.05em] text-inkfaint">How late</span>
      <div className="flex gap-1">
        {cells.map(([label, value, late]) => {
          const amount = num(value);
          return (
            <div
              key={label}
              className={
                "px-2.5 py-1.5 rounded text-center min-w-[74px] " +
                (amount === 0 ? "bg-surface2 text-inkfaint" : late ? "bg-dangerwash text-danger" : "bg-surface2")
              }
            >
              <div className="text-[11px] uppercase tracking-[.04em]">{label}</div>
              <div className="tnum text-[13.5px] font-semibold">{money(amount)}</div>
            </div>
          );
        })}
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
