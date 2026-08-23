import { useCallback, useEffect, useMemo, useState } from "react";
import { api, ApiFailure, money, num, qty as fmtQty } from "../api/client";
import type {
  AttributeView,
  PriceListView,
  PriceView,
  ProductUomView,
  ProductView,
  UnitPriceView,
  UomView,
} from "../api/types";
import { useAuth } from "../auth/AuthContext";
import { describe } from "../till/useSale";
import { Button, Field, Panel, inputClass, useToast } from "../ui/components";
import {
  AttributeFields,
  OrphanedFields,
  draftFrom,
  orphanedKeys,
  toPayload,
  type AttributeDraft,
} from "./attributes";

/**
 * One product, and everything a shop is allowed to change about it.
 *
 * Four things, in the order they go wrong: the name (typed off a delivery note
 * and wrong by the afternoon), the per-category fields, the units it is
 * handled in, and what it sells for. Each saves on its own — a half-corrected
 * name is no reason to lose a price change, and the server treats them as
 * separate operations anyway.
 *
 * What is not here is as deliberate: no SKU, no base unit, no factor. All
 * three are pointed at by records this screen cannot see — shelf labels, every
 * row in the stock ledger, every price quoted per carton — and the server
 * refuses each with a sentence saying so.
 */
export function ProductDetail({
  product,
  onBack,
  onChanged,
}: {
  product: ProductView;
  onBack: () => void;
  onChanged: (updated: ProductView) => void;
}) {
  const { can } = useAuth();
  const toast = useToast();
  const mayPrice = can("PRICE_MANAGE");
  const maySeePrices = can("PRICE_VIEW");

  const [current, setCurrent] = useState(product);
  const [units, setUnits] = useState<ProductUomView[]>([]);
  const [declarations, setDeclarations] = useState<AttributeView[]>([]);
  const [addingUnit, setAddingUnit] = useState(false);

  useEffect(() => setCurrent(product), [product]);

  const reloadUnits = useCallback(async () => {
    setUnits(await api.get<ProductUomView[]>(`/api/products/${product.id}/units`));
  }, [product.id]);

  useEffect(() => {
    void reloadUnits().catch(() => setUnits([]));
  }, [reloadUnits]);

  useEffect(() => {
    void api
      .get<AttributeView[]>(`/api/categories/${product.categoryId}/attributes`)
      .then(setDeclarations)
      .catch(() => setDeclarations([]));
  }, [product.categoryId]);

  const settle = (updated: ProductView) => {
    setCurrent(updated);
    onChanged(updated);
  };

  async function setActive(active: boolean) {
    try {
      await api.put(`/api/products/${product.id}/active?active=${active}`);
      settle({ ...current, isActive: active });
      toast({ title: active ? "Back on sale" : "Taken off sale", tone: "warn" });
    } catch (err) {
      toast(describe(err));
    }
  }

  return (
    <div className="h-full overflow-y-auto min-h-0 bg-ground">
      <div className="px-5 py-4 bg-surface border-b border-line flex items-baseline gap-3 flex-wrap">
        <Button variant="quiet" onClick={onBack}>
          ← All products
        </Button>
        <span className="font-mono text-[13px] text-inkfaint">{current.sku}</span>
        <h1 className="m-0 text-xl font-semibold">{current.name}</h1>
        {!current.isActive && (
          <span className="text-[12px] uppercase tracking-[.05em] text-warn">Off sale</span>
        )}
        {current.isBatchTracked && (
          <span className="text-[12px] uppercase tracking-[.05em] text-inkfaint">
            Batch tracked · {current.pickingRule}
          </span>
        )}
        <span className="ml-auto">
          <Button variant="quiet" onClick={() => void setActive(!current.isActive)}>
            {current.isActive ? "Take off sale" : "Put back on sale"}
          </Button>
        </span>
      </div>

      <div className="p-5 grid gap-5 max-w-[980px]">
        <Names product={current} onSaved={settle} />
        <Fields product={current} declarations={declarations} onSaved={settle} />
        <Units
          product={current}
          units={units}
          onAdd={() => setAddingUnit(true)}
          onChanged={() => void reloadUnits()}
        />
        {maySeePrices && <Prices product={current} units={units} mayPrice={mayPrice} />}
      </div>

      {addingUnit && (
        <AddUnit
          product={current}
          existing={units}
          onClose={() => setAddingUnit(false)}
          onAdded={() => {
            setAddingUnit(false);
            void reloadUnits();
          }}
        />
      )}
    </div>
  );
}

/* ── Sections ───────────────────────────────────────────────────────────── */

function Section({
  title,
  note,
  children,
  action,
}: {
  title: string;
  note?: string;
  children: React.ReactNode;
  action?: React.ReactNode;
}) {
  return (
    <section className="bg-surface border border-line rounded">
      <div className="flex items-baseline gap-3 px-4 py-3 border-b border-line">
        <h2 className="m-0 text-[15px] font-semibold">{title}</h2>
        {note && <span className="text-[12.5px] text-inkfaint">{note}</span>}
        {action && <span className="ml-auto">{action}</span>}
      </div>
      <div className="p-4 grid gap-4">{children}</div>
    </section>
  );
}

function Names({ product, onSaved }: { product: ProductView; onSaved: (p: ProductView) => void }) {
  const toast = useToast();
  const [name, setName] = useState(product.name);
  const [localName, setLocalName] = useState(product.localName ?? "");
  const [description, setDescription] = useState(product.description ?? "");
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    setName(product.name);
    setLocalName(product.localName ?? "");
    setDescription(product.description ?? "");
  }, [product]);

  const dirty =
    name !== product.name ||
    localName !== (product.localName ?? "") ||
    description !== (product.description ?? "");

  async function save() {
    setBusy(true);
    setErrors({});
    try {
      onSaved(
        await api.put<ProductView>(`/api/products/${product.id}`, {
          name: name.trim(),
          localName: localName.trim() || null,
          description: description.trim() || null,
        }),
      );
      toast({ title: "Name saved", tone: "warn" });
    } catch (err) {
      if (err instanceof ApiFailure) setErrors(err.fieldErrors);
      toast(describe(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Section title="What it is called" note={`SKU ${product.sku} — fixed`}>
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label="Name" error={errors.name}>
          <input className={inputClass} value={name} onChange={(e) => setName(e.target.value)} />
        </Field>
        <Field label="What customers call it" error={errors.localName}>
          <input
            className={inputClass}
            value={localName}
            placeholder="searched alongside the name"
            onChange={(e) => setLocalName(e.target.value)}
          />
        </Field>
      </div>
      <Field label="Description" error={errors.description}>
        <textarea
          className={inputClass + " min-h-[64px]"}
          value={description}
          onChange={(e) => setDescription(e.target.value)}
        />
      </Field>
      <div>
        <Button variant="quiet" disabled={!dirty || busy} onClick={() => void save()}>
          {busy ? "Saving…" : "Save"}
        </Button>
      </div>
    </Section>
  );
}

function Fields({
  product,
  declarations,
  onSaved,
}: {
  product: ProductView;
  declarations: AttributeView[];
  onSaved: (p: ProductView) => void;
}) {
  const toast = useToast();
  const [draft, setDraft] = useState<AttributeDraft>({});
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    setDraft(draftFrom(declarations, product.attributes));
  }, [declarations, product]);

  const orphans = useMemo(
    () => orphanedKeys(declarations, product.attributes),
    [declarations, product.attributes],
  );

  async function save() {
    setBusy(true);
    setErrors({});
    try {
      onSaved(
        await api.put<ProductView>(`/api/products/${product.id}/attributes`, {
          attributes: toPayload(declarations, draft),
        }),
      );
      toast({ title: "Details saved", tone: "warn" });
    } catch (err) {
      if (err instanceof ApiFailure) setErrors(err.fieldErrors);
      toast(describe(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Section title="Category fields" note="declared by the category, inherited down the tree">
      {orphans.length > 0 && <OrphanedFields keys={orphans} />}
      <AttributeFields
        declarations={declarations}
        draft={draft}
        errors={errors}
        onChange={(key, value) => setDraft((d) => ({ ...d, [key]: value }))}
      />
      {declarations.length > 0 && (
        <div>
          <Button variant="quiet" disabled={busy} onClick={() => void save()}>
            {busy ? "Saving…" : "Save fields"}
          </Button>
        </div>
      )}
    </Section>
  );
}

/**
 * The units the product is handled in.
 *
 * The factor is shown and never editable. Re-sizing a carton from 12 to 24
 * would halve the per-piece price of every price row quoted against it without
 * changing a figure anybody could see, so a pack that changes size is a new
 * unit and the old one stops being sellable.
 */
function Units({
  product,
  units,
  onAdd,
  onChanged,
}: {
  product: ProductView;
  units: ProductUomView[];
  onAdd: () => void;
  onChanged: () => void;
}) {
  const toast = useToast();
  const [busyId, setBusyId] = useState<number | null>(null);

  async function save(unit: ProductUomView, changes: Partial<ProductUomView>) {
    setBusyId(unit.id);
    try {
      await api.put<ProductUomView>(`/api/products/${product.id}/units/${unit.id}`, {
        barcode: (changes.barcode ?? unit.barcode)?.trim() || null,
        sellable: changes.sellable ?? unit.sellable,
        purchasable: changes.purchasable ?? unit.purchasable,
      });
      onChanged();
    } catch (err) {
      toast(describe(err));
      // Re-read either way: the row on screen is now a guess about what the
      // server refused, and a barcode that did not stick must not look stuck.
      onChanged();
    } finally {
      setBusyId(null);
    }
  }

  return (
    <Section
      title="Units"
      note="the base unit and every factor are fixed"
      action={
        <Button variant="quiet" onClick={onAdd}>
          Add a unit
        </Button>
      }
    >
      <table className="w-full border-collapse text-[13.5px]">
        <thead>
          <tr className="text-left text-[11px] uppercase tracking-[.06em] text-inkfaint">
            <th className="py-1.5 pr-3 font-medium">Unit</th>
            <th className="py-1.5 pr-3 font-medium text-right">Base units in one</th>
            <th className="py-1.5 pr-3 font-medium">Barcode</th>
            <th className="py-1.5 pr-3 font-medium">Sold</th>
            <th className="py-1.5 font-medium">Bought</th>
          </tr>
        </thead>
        <tbody>
          {units.map((unit) => (
            <tr key={unit.id} className="border-t border-linesoft">
              <td className="py-2 pr-3">
                <span className="font-mono">{unit.uomCode}</span>{" "}
                <span className="text-inkfaint">{unit.uomName}</span>
                {unit.isBase && (
                  <span className="ml-2 text-[11px] uppercase tracking-[.05em] text-accent">
                    base
                  </span>
                )}
              </td>
              <td className="py-2 pr-3 text-right tnum">{fmtQty(Number(unit.factor))}</td>
              <td className="py-2 pr-3">
                <BarcodeCell
                  unit={unit}
                  busy={busyId === unit.id}
                  onSave={(barcode) => void save(unit, { barcode })}
                />
              </td>
              <td className="py-2 pr-3">
                <input
                  type="checkbox"
                  checked={unit.sellable}
                  disabled={busyId === unit.id}
                  onChange={(e) => void save(unit, { sellable: e.target.checked })}
                />
              </td>
              <td className="py-2">
                <input
                  type="checkbox"
                  checked={unit.purchasable}
                  disabled={busyId === unit.id}
                  onChange={(e) => void save(unit, { purchasable: e.target.checked })}
                />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </Section>
  );
}

/**
 * A barcode is typed or scanned into the row it belongs to.
 *
 * Saved when the field is left rather than on every keystroke: a wedge scanner
 * types the whole code in about forty milliseconds, and a save per character
 * would race itself against the uniqueness index.
 */
function BarcodeCell({
  unit,
  busy,
  onSave,
}: {
  unit: ProductUomView;
  busy: boolean;
  onSave: (barcode: string | null) => void;
}) {
  const [value, setValue] = useState(unit.barcode ?? "");

  /*
   * Reset on the row, not on the barcode.
   *
   * The rows are re-read after every attempt, refused ones included, and a
   * refusal leaves the stored code exactly as it was — so a dependency on the
   * value never fires in the one case that matters, and the code the server
   * rejected sits in the field looking saved.
   */
  useEffect(() => setValue(unit.barcode ?? ""), [unit]);

  const commit = () => {
    const next = value.trim();
    if (next === (unit.barcode ?? "")) return;
    onSave(next || null);
  };

  return (
    <input
      className={inputClass + " font-mono py-1 w-[190px]"}
      value={value}
      disabled={busy}
      placeholder="none"
      onChange={(e) => setValue(e.target.value)}
      onBlur={commit}
      onKeyDown={(e) => {
        if (e.key === "Enter") (e.target as HTMLInputElement).blur();
      }}
    />
  );
}

function AddUnit({
  product,
  existing,
  onClose,
  onAdded,
}: {
  product: ProductView;
  existing: ProductUomView[];
  onClose: () => void;
  onAdded: () => void;
}) {
  const toast = useToast();
  const [measures, setMeasures] = useState<UomView[]>([]);
  const [uomCode, setUomCode] = useState("");
  const [factor, setFactor] = useState("");
  const [barcode, setBarcode] = useState("");
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    void api
      .get<UomView[]>("/api/uoms")
      .then(setMeasures)
      .catch(() => setMeasures([]));
  }, []);

  const base = existing.find((u) => u.isBase);
  const taken = new Set(existing.map((u) => u.uomCode));
  const offered = measures.filter((m) => !taken.has(m.code));

  async function add() {
    setBusy(true);
    setErrors({});
    try {
      await api.post(`/api/products/${product.id}/units`, {
        uomCode,
        factor: Number(factor),
        isBase: false,
        sellable: true,
        purchasable: true,
        barcode: barcode.trim() || null,
      });
      onAdded();
    } catch (err) {
      if (err instanceof ApiFailure) setErrors(err.fieldErrors);
      toast(describe(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Panel title="Add a unit" hint={base ? `base: ${base.uomCode}` : undefined} onClose={onClose}>
      <p className="m-0 text-[13px] text-inksoft max-w-[60ch]">
        How many <strong>{base?.uomCode ?? "base units"}</strong> are in one of these. A carton of
        twelve pieces has a factor of 12; get it wrong and every cost and price quoted in cartons
        is wrong by that multiple.
      </p>
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label="Unit" error={errors.uomCode}>
          <select
            className={inputClass}
            value={uomCode}
            onChange={(e) => setUomCode(e.target.value)}
          >
            <option value="">Choose…</option>
            {offered.map((m) => (
              <option key={m.id} value={m.code}>
                {m.code} — {m.name}
              </option>
            ))}
          </select>
        </Field>
        <Field label={`${base?.uomCode ?? "Base units"} in one`} error={errors.factor}>
          <input
            className={inputClass + " tnum text-right"}
            inputMode="decimal"
            value={factor}
            onChange={(e) => setFactor(e.target.value)}
          />
        </Field>
      </div>
      <Field label="Barcode on the pack" error={errors.barcode}>
        <input
          className={inputClass + " font-mono"}
          value={barcode}
          placeholder="scan it, or leave empty"
          onChange={(e) => setBarcode(e.target.value)}
        />
      </Field>
      <div className="flex gap-2">
        <Button
          variant="primary"
          disabled={busy || !uomCode || num(factor) <= 0}
          onClick={() => void add()}
        >
          {busy ? "Adding…" : "Add the unit"}
        </Button>
        <Button variant="quiet" onClick={onClose}>
          Cancel
        </Button>
      </div>
    </Panel>
  );
}

/* ── Prices ─────────────────────────────────────────────────────────────── */

function today(): string {
  return new Date().toISOString().slice(0, 10);
}

/**
 * What it sells for, per unit, per price list.
 *
 * A price is never edited in place: setting one closes the row it supersedes
 * the day before, so the table answers "what did this cost on the 3rd" without
 * reconstructing anything. That is also why the date cannot be in the past —
 * back-dating changes no sale that already happened and only breaks a report.
 *
 * A row priced from another list is marked. A trade list inherits retail where
 * it says nothing, and a contractor's price that is quietly the walk-in one is
 * worth seeing before somebody quotes it.
 */
function Prices({
  product,
  units,
  mayPrice,
}: {
  product: ProductView;
  units: ProductUomView[];
  mayPrice: boolean;
}) {
  const toast = useToast();
  const [lists, setLists] = useState<PriceListView[]>([]);
  const [listId, setListId] = useState<number | null>(null);
  const [rows, setRows] = useState<UnitPriceView[]>([]);
  const [history, setHistory] = useState<PriceView[]>([]);
  const [pricing, setPricing] = useState<UnitPriceView | null>(null);

  useEffect(() => {
    void api
      .get<PriceListView[]>("/api/price-lists")
      .then((all) => {
        setLists(all);
        setListId((id) => id ?? all.find((l) => l.isDefault)?.id ?? all[0]?.id ?? null);
      })
      .catch(() => setLists([]));
  }, []);

  const reload = useCallback(async () => {
    if (!listId) return;
    setRows(
      await api.get<UnitPriceView[]>(`/api/prices/product/${product.id}?priceListId=${listId}`),
    );
    setHistory(
      await api.get<PriceView[]>(
        `/api/prices/history?priceListId=${listId}&productId=${product.id}`,
      ),
    );
  }, [listId, product.id]);

  /*
   * Re-read when the units change too, not only the product or the list.
   *
   * Only sellable units carry a price, so adding a carton or clearing `sold`
   * on one changes which rows belong here — and the panel that decides what to
   * charge is not a place to show a list that was true a minute ago.
   */
  useEffect(() => {
    void reload().catch(() => {
      setRows([]);
      setHistory([]);
    });
  }, [reload, units]);

  const unitName = (productUomId: number) =>
    units.find((u) => u.id === productUomId)?.uomCode ?? String(productUomId);

  const scheduled = history.filter((p) => p.effectiveFrom > today());

  async function cancel(price: PriceView) {
    try {
      await api.del(`/api/prices/${price.id}`);
      await reload();
    } catch (err) {
      toast(describe(err));
    }
  }

  return (
    <Section
      title="Prices"
      action={
        lists.length > 1 && (
          <select
            className={inputClass + " py-1 w-auto"}
            value={listId ?? ""}
            onChange={(e) => setListId(Number(e.target.value))}
          >
            {lists.map((l) => (
              <option key={l.id} value={l.id}>
                {l.name}
                {l.isDefault ? " (default)" : ""}
              </option>
            ))}
          </select>
        )
      }
    >
      {rows.length === 0 ? (
        <p className="m-0 text-[13px] text-inksoft">
          No sellable unit to price. A unit has to be marked sold before it can carry a price.
        </p>
      ) : (
        <table className="w-full border-collapse text-[13.5px]">
          <tbody>
            {rows.map((row) => (
              <tr key={row.productUomId} className="border-t border-linesoft first:border-t-0">
                <td className="py-2 pr-3 font-mono">{row.uomCode}</td>
                <td className="py-2 pr-3 text-right tnum">
                  {row.unitPrice == null ? (
                    <span className="text-warn">not priced</span>
                  ) : (
                    money(num(row.unitPrice))
                  )}
                </td>
                <td className="py-2 pr-3 text-[12.5px] text-inkfaint">
                  {row.priceListId != null &&
                    row.priceListId !== listId &&
                    "from the default list"}
                </td>
                <td className="py-2 text-right">
                  {mayPrice && (
                    <Button variant="quiet" onClick={() => setPricing(row)}>
                      Set a price
                    </Button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {scheduled.length > 0 && (
        <div className="grid gap-1.5">
          <span className="text-[11px] uppercase tracking-[.06em] text-inkfaint">
            Starting later
          </span>
          {scheduled.map((p) => (
            <div key={p.id} className="flex items-center gap-3 text-[13px]">
              <span className="font-mono">{unitName(p.productUomId)}</span>
              <span className="tnum">{money(num(p.unitPrice))}</span>
              <span className="text-inkfaint">from {p.effectiveFrom}</span>
              {mayPrice && (
                <Button variant="quiet" onClick={() => void cancel(p)}>
                  Cancel
                </Button>
              )}
            </div>
          ))}
        </div>
      )}

      {history.length > 0 && (
        <details className="text-[13px]">
          <summary className="cursor-pointer text-inksoft">Price history</summary>
          <div className="grid gap-1 pt-2">
            {history.map((p) => (
              <div key={p.id} className="flex gap-3 text-inksoft">
                <span className="font-mono w-[70px]">{unitName(p.productUomId)}</span>
                <span className="tnum w-[90px] text-right">{money(num(p.unitPrice))}</span>
                <span className="tnum">
                  {p.effectiveFrom} → {p.effectiveTo ?? "now"}
                </span>
              </div>
            ))}
          </div>
        </details>
      )}

      {pricing && listId && (
        <SetPrice
          product={product}
          list={lists.find((l) => l.id === listId)!}
          row={pricing}
          onClose={() => setPricing(null)}
          onSet={() => {
            setPricing(null);
            void reload();
          }}
        />
      )}
    </Section>
  );
}

function SetPrice({
  product,
  list,
  row,
  onClose,
  onSet,
}: {
  product: ProductView;
  list: PriceListView;
  row: UnitPriceView;
  onClose: () => void;
  onSet: () => void;
}) {
  const toast = useToast();
  const [price, setPrice] = useState(row.unitPrice == null ? "" : String(row.unitPrice));
  const [from, setFrom] = useState(today());
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);

  async function set() {
    setBusy(true);
    setErrors({});
    try {
      await api.put("/api/prices", {
        priceListId: list.id,
        productId: product.id,
        productUomId: row.productUomId,
        unitPrice: Number(price),
        effectiveFrom: from,
      });
      onSet();
    } catch (err) {
      if (err instanceof ApiFailure) setErrors(err.fieldErrors);
      toast(describe(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Panel title={`${product.name} — per ${row.uomCode}`} hint={list.name} onClose={onClose}>
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label="Price" error={errors.unitPrice}>
          <input
            className={inputClass + " tnum text-right"}
            inputMode="decimal"
            autoFocus
            value={price}
            onChange={(e) => setPrice(e.target.value)}
          />
        </Field>
        <Field label="From" error={errors.effectiveFrom}>
          <input
            type="date"
            className={inputClass + " tnum"}
            value={from}
            min={today()}
            onChange={(e) => setFrom(e.target.value)}
          />
        </Field>
      </div>
      <p className="m-0 text-[13px] text-inksoft max-w-[60ch]">
        {from === today()
          ? "Takes effect at the till immediately. Sales already rung up keep the price they charged."
          : `The price in force stands until ${from}. A scheduled price can be cancelled before it starts.`}
      </p>
      <div className="flex gap-2">
        <Button variant="primary" disabled={busy || price.trim() === ""} onClick={() => void set()}>
          {busy ? "Setting…" : "Set the price"}
        </Button>
        <Button variant="quiet" onClick={onClose}>
          Cancel
        </Button>
      </div>
    </Panel>
  );
}
