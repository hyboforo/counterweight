import { useCallback, useRef, useState } from "react";
import { api, ApiFailure, OfflineFailure } from "../api/client";
import type { SaleView, UnitPriceView } from "../api/types";

/**
 * The basket, which lives on the server.
 *
 * Every change is a round trip, and that is the point rather than a cost. The
 * draft is a row in `sale`, so refreshing the browser, closing the tab, or the
 * till losing power mid-basket loses nothing — and any till can recall a held
 * sale because it was never in one browser's memory to begin with.
 *
 * On a wired shop LAN a round trip is a few milliseconds. Keeping a mirror in
 * the client to save that would buy nothing and cost the one property worth
 * having: there is exactly one version of the basket, and it is the one the
 * completion will price.
 */
export function useSale() {
  const [sale, setSale] = useState<SaleView | null>(null);
  const [busy, setBusy] = useState(false);
  // Guards the double-add: a cashier leaning on Enter, or a scanner that
  // repeats. Requests are serialised rather than dropped, so nothing is lost.
  const queue = useRef<Promise<unknown>>(Promise.resolve());

  const run = useCallback(<T,>(fn: () => Promise<T>): Promise<T> => {
    const next = queue.current.then(fn, fn);
    queue.current = next.catch(() => undefined);
    return next as Promise<T>;
  }, []);

  const start = useCallback(
    async (customerId?: number | null) => {
      setBusy(true);
      try {
        const created = await run(() =>
          api.post<SaleView>("/api/sales", { customerId: customerId ?? null }),
        );
        setSale(created);
        return created;
      } finally {
        setBusy(false);
      }
    },
    [run],
  );

  /** Ensures there is a draft to add to, creating one on the first scan. */
  const ensure = useCallback(async (): Promise<SaleView> => {
    if (sale) return sale;
    return start(null);
  }, [sale, start]);

  const addLine = useCallback(
    async (opts: {
      productId: number;
      productUomId: number;
      qty: number;
      discountPercent?: number;
      unitPrice?: number;
      approval?: { username: string; pin: string };
    }) => {
      const current = await ensure();
      setBusy(true);
      try {
        const updated = await run(() =>
          api.post<SaleView>(`/api/sales/${current.id}/lines`, {
            productId: opts.productId,
            productUomId: opts.productUomId,
            qty: opts.qty,
            discountPercent: opts.discountPercent ?? null,
            unitPrice: opts.unitPrice ?? null,
            approval: opts.approval ?? null,
          }),
        );
        setSale(updated);
        return updated;
      } finally {
        setBusy(false);
      }
    },
    [ensure, run],
  );

  const setQty = useCallback(
    async (lineId: number, qty: number) => {
      if (!sale) return;
      setBusy(true);
      try {
        setSale(await run(() => api.put<SaleView>(`/api/sales/${sale.id}/lines/${lineId}/qty`, { qty })));
      } finally {
        setBusy(false);
      }
    },
    [sale, run],
  );

  const removeLine = useCallback(
    async (lineId: number) => {
      if (!sale) return;
      setBusy(true);
      try {
        setSale(await run(() => api.del<SaleView>(`/api/sales/${sale.id}/lines/${lineId}`)));
      } finally {
        setBusy(false);
      }
    },
    [sale, run],
  );

  const setCustomer = useCallback(
    async (customerId: number | null) => {
      const current = await ensure();
      setBusy(true);
      try {
        const q = customerId === null ? "" : `?customerId=${customerId}`;
        setSale(await run(() => api.put<SaleView>(`/api/sales/${current.id}/customer${q}`)));
      } finally {
        setBusy(false);
      }
    },
    [ensure, run],
  );

  const hold = useCallback(
    async (label: string) => {
      if (!sale) return;
      await run(() => api.put<SaleView>(`/api/sales/${sale.id}/hold`, { label }));
      setSale(null);
    },
    [sale, run],
  );

  const recall = useCallback(
    async (saleId: number) => {
      const recalled = await run(() => api.put<SaleView>(`/api/sales/${saleId}/recall`));
      setSale(recalled);
      return recalled;
    },
    [run],
  );

  const discard = useCallback(async () => {
    if (!sale) return;
    await run(() => api.del(`/api/sales/${sale.id}`));
    setSale(null);
  }, [sale, run]);

  const clear = useCallback(() => setSale(null), []);

  return {
    sale, busy, start, addLine, setQty, removeLine,
    setCustomer, hold, recall, discard, clear, setSale,
  };
}

/**
 * Turns whatever went wrong into something a person can act on.
 *
 * The server already writes messages for the counter — "There is not enough
 * cement in stock — 4 available" rather than a constraint name — so the job
 * here is to pass those through untouched and only supply words where there
 * were none.
 */
export function describe(err: unknown): { title: string; body?: string; tone: "warn" | "danger" } {
  if (err instanceof OfflineFailure) {
    return {
      title: "Lost the server",
      body: "This till is read-only until it reconnects. The basket is held on the server, so nothing here is lost.",
      tone: "danger",
    };
  }
  if (err instanceof ApiFailure) {
    // A rule violation is the shop's own policy talking, not a fault.
    const tone = err.status === 422 || err.status === 409 ? "warn" : "danger";
    return { title: err.message, tone };
  }
  return { title: "Something went wrong", body: String(err), tone: "danger" };
}

/** Prices for every sellable unit of a product, cached for the session. */
const priceCache = new Map<number, UnitPriceView[]>();

export async function unitsFor(productId: number, priceListId: number | null): Promise<UnitPriceView[]> {
  const key = priceListId ? productId * 1_000_000 + priceListId : productId;
  const hit = priceCache.get(key);
  if (hit) return hit;

  const q = priceListId ? `?priceListId=${priceListId}` : "";
  const units = await api.get<UnitPriceView[]>(`/api/prices/product/${productId}${q}`);
  priceCache.set(key, units);
  return units;
}

export function clearPriceCache() {
  priceCache.clear();
}
