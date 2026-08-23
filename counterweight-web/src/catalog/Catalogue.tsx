import { useCallback, useEffect, useState } from "react";
import { api, ApiFailure } from "../api/client";
import type { AttributeView, CategoryView, ProductView, UomView } from "../api/types";
import { describe } from "../till/useSale";
import { Button, Callout, Field, Panel, inputClass, useToast } from "../ui/components";
import { ProductDetail } from "./ProductDetail";
import {
  AttributeFields,
  draftFrom,
  toPayload,
  type AttributeDraft,
} from "./attributes";

/**
 * The catalogue: the category tree on the left, what is filed under it on the
 * right, and one product at a time in front of you.
 *
 * The tree is the whole point rather than decoration. §6.1 makes a category a
 * row rather than a migration, and the fields a product carries are declared
 * by its category and inherited down the path — so where a product is filed
 * decides what it is asked for. That is also why a new product is created from
 * inside a category rather than from a blank form with a dropdown on it.
 */
export function Catalogue() {
  const toast = useToast();
  const [categories, setCategories] = useState<CategoryView[]>([]);
  const [categoryId, setCategoryId] = useState<number | null>(null);
  const [query, setQuery] = useState("");
  const [rows, setRows] = useState<ProductView[]>([]);
  const [activeOnly, setActiveOnly] = useState(true);
  const [selected, setSelected] = useState<ProductView | null>(null);
  const [creating, setCreating] = useState(false);
  const [addingCategory, setAddingCategory] = useState(false);
  const [loading, setLoading] = useState(false);

  const loadCategories = useCallback(async () => {
    const all = await api.get<CategoryView[]>("/api/categories");
    setCategories(all);
    setCategoryId((id) => id ?? all[0]?.id ?? null);
  }, []);

  useEffect(() => {
    void loadCategories().catch((err) => toast(describe(err)));
  }, [loadCategories, toast]);

  const searching = query.trim().length >= 2;

  const load = useCallback(async () => {
    setLoading(true);
    try {
      if (searching) {
        setRows(
          await api.get<ProductView[]>(
            `/api/products/search?q=${encodeURIComponent(query.trim())}&limit=50`,
          ),
        );
      } else if (categoryId) {
        setRows(
          await api.get<ProductView[]>(
            `/api/products?categoryId=${categoryId}&activeOnly=${activeOnly}`,
          ),
        );
      } else {
        setRows([]);
      }
    } catch {
      setRows([]);
    } finally {
      setLoading(false);
    }
  }, [searching, query, categoryId, activeOnly]);

  useEffect(() => {
    const timer = window.setTimeout(() => void load(), searching ? 170 : 0);
    return () => window.clearTimeout(timer);
  }, [load, searching]);

  const category = categories.find((c) => c.id === categoryId) ?? null;

  if (selected) {
    return (
      <ProductDetail
        product={selected}
        onBack={() => {
          setSelected(null);
          void load();
        }}
        onChanged={(updated) => {
          setSelected(updated);
          setRows((all) => all.map((p) => (p.id === updated.id ? updated : p)));
        }}
      />
    );
  }

  return (
    <div className="h-full grid grid-cols-[minmax(200px,260px)_1fr] min-h-0">
      <nav
        className="border-r border-line bg-surface overflow-y-auto min-h-0 py-2 grid content-start"
        aria-label="Categories"
      >
        {categories.map((c) => (
          <button
            key={c.id}
            onClick={() => {
              setCategoryId(c.id);
              setQuery("");
            }}
            aria-current={categoryId === c.id ? "true" : undefined}
            style={{ paddingLeft: `${16 + depthOf(c) * 14}px` }}
            className={
              "w-full text-left pr-3 py-1.5 text-[13.5px] border-l-2 transition-colors " +
              (categoryId === c.id
                ? "border-l-accent bg-accentwash text-accent font-medium"
                : "border-l-transparent text-inksoft hover:text-ink hover:bg-surface2")
            }
          >
            {c.name}
            {c.kind === "AGROCHEMICAL" && (
              <span className="ml-1.5 text-[10.5px] uppercase tracking-[.05em] text-inkfaint">
                agro
              </span>
            )}
          </button>
        ))}
        <div className="px-4 pt-3">
          <Button variant="quiet" onClick={() => setAddingCategory(true)}>
            New category
          </Button>
        </div>
      </nav>

      <div className="grid grid-rows-[auto_1fr] min-h-0">
        <div className="px-5 py-4 bg-surface border-b border-line grid gap-3">
          <div className="flex items-baseline gap-3 flex-wrap">
            <h1 className="m-0 text-xl font-semibold">{category?.name ?? "Catalogue"}</h1>
            <span className="text-[13px] text-inkfaint tnum">
              {rows.length} {rows.length === 1 ? "product" : "products"}
            </span>
            <span className="ml-auto">
              <Button variant="quiet" disabled={!category} onClick={() => setCreating(true)}>
                New product
              </Button>
            </span>
          </div>

          <div className="flex gap-3 flex-wrap items-center">
            <input
              className={inputClass + " py-1.5 max-w-[320px]"}
              value={query}
              placeholder="Search every category…"
              onChange={(e) => setQuery(e.target.value)}
            />
            {!searching && (
              <label className="flex items-center gap-2 text-[13px] cursor-pointer">
                <input
                  type="checkbox"
                  checked={!activeOnly}
                  onChange={(e) => setActiveOnly(!e.target.checked)}
                />
                <span>Include what is off sale</span>
              </label>
            )}
            {searching && (
              <span className="text-[12.5px] text-inkfaint">
                Search finds what is on sale. Browse the category for the rest.
              </span>
            )}
          </div>
        </div>

        <div className="overflow-auto min-h-0 bg-ground">
          {loading && rows.length === 0 && <div className="p-6 text-inksoft">Loading…</div>}
          {!loading && rows.length === 0 && (
            <div className="p-6 text-inksoft">
              {searching ? "Nothing matches that." : "Nothing is filed here yet."}
            </div>
          )}
          {rows.length > 0 && <ProductRows rows={rows} onOpen={setSelected} />}
        </div>
      </div>

      {creating && category && (
        <NewProduct
          category={category}
          onClose={() => setCreating(false)}
          onCreated={(product) => {
            setCreating(false);
            setSelected(product);
          }}
        />
      )}

      {addingCategory && (
        <NewCategory
          parent={category}
          onClose={() => setAddingCategory(false)}
          onCreated={(created) => {
            setAddingCategory(false);
            void loadCategories().then(() => setCategoryId(created.id));
          }}
        />
      )}
    </div>
  );
}

/** Nesting depth from the ltree path: `agro.herbicide` sits one in. */
function depthOf(category: CategoryView): number {
  return category.path ? category.path.split(".").length - 1 : 0;
}

function ProductRows({
  rows,
  onOpen,
}: {
  rows: ProductView[];
  onOpen: (p: ProductView) => void;
}) {
  return (
    <table className="w-full border-collapse text-[13.5px]">
      <thead className="sticky top-0 bg-ground">
        <tr className="text-left text-[11px] uppercase tracking-[.06em] text-inkfaint">
          <th className="px-5 py-2 font-medium">SKU</th>
          <th className="px-3 py-2 font-medium">Name</th>
          <th className="px-3 py-2 font-medium">Also called</th>
          <th className="px-5 py-2 font-medium" />
        </tr>
      </thead>
      <tbody>
        {rows.map((p) => (
          <tr
            key={p.id}
            onClick={() => onOpen(p)}
            className="border-t border-linesoft cursor-pointer hover:bg-surface2"
          >
            <td className="px-5 py-2 font-mono text-[12.5px] text-inksoft">{p.sku}</td>
            <td className="px-3 py-2">{p.name}</td>
            <td className="px-3 py-2 text-inksoft">{p.localName ?? ""}</td>
            <td className="px-5 py-2 text-right text-[12px] uppercase tracking-[.05em]">
              {!p.isActive && <span className="text-warn">off sale</span>}
              {p.isActive && p.isBatchTracked && <span className="text-inkfaint">batch</span>}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/**
 * A new product, created inside the category that decides what it is asked.
 *
 * Only the base unit is set here. It is the one thing that can never be
 * changed afterwards — every row in the stock ledger is counted in it — so it
 * is asked for on its own, and cartons and packets are added later on the
 * product's own screen where the factor can be explained.
 */
function NewProduct({
  category,
  onClose,
  onCreated,
}: {
  category: CategoryView;
  onClose: () => void;
  onCreated: (product: ProductView) => void;
}) {
  const toast = useToast();
  const agro = category.kind === "AGROCHEMICAL";

  const [declarations, setDeclarations] = useState<AttributeView[]>([]);
  const [measures, setMeasures] = useState<UomView[]>([]);
  const [sku, setSku] = useState("");
  const [name, setName] = useState("");
  const [localName, setLocalName] = useState("");
  const [uomCode, setUomCode] = useState("PCS");
  const [barcode, setBarcode] = useState("");
  const [batchTracked, setBatchTracked] = useState(agro);
  const [draft, setDraft] = useState<AttributeDraft>({});
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    void api
      .get<AttributeView[]>(`/api/categories/${category.id}/attributes`)
      .then((all) => {
        /*
         * Creation asks only for the fields a product cannot be without.
         *
         * The agro root declares nine; four are required — active ingredient,
         * EPA number, hazard band, and whether the buyer must be recorded —
         * and those four are what the licence control and the printed receipt
         * read. The other five carry no consumer yet, and asking for
         * concentration and storage notes before the item can be put on a
         * shelf makes adding stock a form-filling exercise. They stay declared
         * and are filled in on the product's own screen.
         */
        const atCreation = all.filter((d) => d.required);
        setDeclarations(atCreation);
        setDraft(draftFrom(atCreation));
      })
      .catch(() => setDeclarations([]));
    void api
      .get<UomView[]>("/api/uoms")
      .then(setMeasures)
      .catch(() => setMeasures([]));
  }, [category.id]);

  async function create() {
    setBusy(true);
    setErrors({});
    try {
      onCreated(
        await api.post<ProductView>("/api/products", {
          sku: sku.trim(),
          name: name.trim(),
          categoryId: category.id,
          localName: localName.trim() || null,
          isBatchTracked: batchTracked,
          attributes: toPayload(declarations, draft),
          units: [
            {
              uomCode,
              factor: 1,
              isBase: true,
              sellable: true,
              purchasable: true,
              barcode: barcode.trim() || null,
            },
          ],
        }),
      );
    } catch (err) {
      if (err instanceof ApiFailure) setErrors(err.fieldErrors);
      toast(describe(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Panel title={`New product in ${category.name}`} wide onClose={onClose}>
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label="SKU" error={errors.sku}>
          <input
            className={inputClass + " font-mono"}
            value={sku}
            autoFocus
            placeholder="the shop's own code"
            onChange={(e) => setSku(e.target.value)}
          />
        </Field>
        <Field label="Name" error={errors.name}>
          <input className={inputClass} value={name} onChange={(e) => setName(e.target.value)} />
        </Field>
        <Field label="What customers call it" error={errors.localName}>
          <input
            className={inputClass}
            value={localName}
            onChange={(e) => setLocalName(e.target.value)}
          />
        </Field>
        <Field label="Base unit — cannot be changed later" error={errors.units}>
          <select
            className={inputClass}
            value={uomCode}
            onChange={(e) => setUomCode(e.target.value)}
          >
            {measures.map((m) => (
              <option key={m.id} value={m.code}>
                {m.code} — {m.name}
              </option>
            ))}
          </select>
        </Field>
        <Field label="Barcode on the pack" error={errors.barcode}>
          <input
            className={inputClass + " font-mono"}
            value={barcode}
            placeholder="scan it, or leave empty"
            onChange={(e) => setBarcode(e.target.value)}
          />
        </Field>
        <label className="flex items-center gap-2.5 text-[13.5px] cursor-pointer self-end pb-2.5">
          <input
            type="checkbox"
            checked={batchTracked}
            disabled={agro}
            onChange={(e) => setBatchTracked(e.target.checked)}
          />
          <span>
            Track batches and expiry
            {agro && <span className="text-inkfaint"> — required here</span>}
          </span>
        </label>
      </div>

      {declarations.length > 0 && (
        <div className="grid gap-3 pt-1">
          <span className="text-[11px] uppercase tracking-[.06em] text-inkfaint">
            {category.name} fields
          </span>
          <span className="text-[12.5px] text-inkfaint">
            The fields this category requires. Anything optional is on the
            product's own screen once it exists.
          </span>
          <AttributeFields
            declarations={declarations}
            draft={draft}
            errors={errors}
            onChange={(key, value) => setDraft((d) => ({ ...d, [key]: value }))}
          />
        </div>
      )}

      <div className="flex gap-2">
        <Button
          variant="primary"
          disabled={busy || sku.trim() === "" || name.trim() === "" || !uomCode}
          onClick={() => void create()}
        >
          {busy ? "Creating…" : "Create it"}
        </Button>
        <Button variant="quiet" onClick={onClose}>
          Cancel
        </Button>
      </div>
    </Panel>
  );
}

/**
 * A new category — data entry, which is the whole promise of §6.1.
 *
 * It is created under whatever is selected in the rail, and its kind follows
 * the parent: everything under the agro root inherits the EPA and hazard
 * fields, so it is agro-chemical whether or not anybody ticks a box. The
 * server refuses the other reading.
 */
function NewCategory({
  parent,
  onClose,
  onCreated,
}: {
  parent: CategoryView | null;
  onClose: () => void;
  onCreated: (created: CategoryView) => void;
}) {
  const toast = useToast();
  const underAgro = parent?.kind === "AGROCHEMICAL";

  const [code, setCode] = useState("");
  const [name, setName] = useState("");
  const [nest, setNest] = useState(parent !== null);
  const [agro, setAgro] = useState(underAgro);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);

  useEffect(() => setAgro(underAgro), [underAgro]);

  async function create() {
    setBusy(true);
    setErrors({});
    try {
      onCreated(
        await api.post<CategoryView>("/api/categories", {
          code: code.trim().toUpperCase(),
          name: name.trim(),
          parentId: nest ? (parent?.id ?? null) : null,
          kind: agro ? "AGROCHEMICAL" : "GENERAL",
        }),
      );
    } catch (err) {
      if (err instanceof ApiFailure) setErrors(err.fieldErrors);
      toast(describe(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Panel title="New category" onClose={onClose}>
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label="Code" error={errors.code}>
          <input
            className={inputClass + " font-mono"}
            value={code}
            autoFocus
            placeholder="HERBICIDE"
            onChange={(e) => setCode(e.target.value)}
          />
        </Field>
        <Field label="Name" error={errors.name}>
          <input className={inputClass} value={name} onChange={(e) => setName(e.target.value)} />
        </Field>
      </div>

      {parent && (
        <label className="flex items-center gap-2.5 text-[13.5px] cursor-pointer">
          <input type="checkbox" checked={nest} onChange={(e) => setNest(e.target.checked)} />
          <span>
            Inside <strong>{parent.name}</strong> — it inherits the fields declared there
          </span>
        </label>
      )}

      <label className="flex items-center gap-2.5 text-[13.5px] cursor-pointer">
        <input
          type="checkbox"
          checked={agro}
          disabled={nest && underAgro}
          onChange={(e) => setAgro(e.target.checked)}
        />
        <span>
          Agro-chemical — products here must be batch tracked
          {nest && underAgro && <span className="text-inkfaint"> — inherited</span>}
        </span>
      </label>

      {errors.kind && <Callout tone="danger">{errors.kind}</Callout>}

      <div className="flex gap-2">
        <Button
          variant="primary"
          disabled={busy || code.trim() === "" || name.trim() === ""}
          onClick={() => void create()}
        >
          {busy ? "Creating…" : "Create it"}
        </Button>
        <Button variant="quiet" onClick={onClose}>
          Cancel
        </Button>
      </div>
    </Panel>
  );
}
