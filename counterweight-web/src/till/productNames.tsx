import { useEffect, useState } from "react";
import { api } from "../api/client";
import type { ProductView } from "../api/types";

/**
 * Product names are resolved and cached client-side.
 *
 * The sale line carries an id, not a name — the server is not going to
 * denormalise a name onto a line just so a screen can render it, because the
 * line has to keep meaning what it meant if the product is later renamed.
 *
 * Shared between the basket and the panels rather than living in either. The
 * buyer register has to name the product it is refusing to sell, and a second
 * cache would mean the same product fetched twice and, on a slow till, named
 * differently in two places at once.
 */
const cache = new Map<number, string>();

export function useProductName(productId: number | undefined): string {
  const [name, setName] = useState(productId === undefined ? "" : cache.get(productId) ?? "");

  useEffect(() => {
    if (productId === undefined || cache.has(productId)) {
      if (productId !== undefined) setName(cache.get(productId) ?? "");
      return;
    }
    let live = true;
    api
      .get<ProductView>(`/api/products/${productId}`)
      .then((p) => {
        cache.set(productId, p.name);
        if (live) setName(p.name);
      })
      .catch(() => undefined);
    return () => {
      live = false;
    };
  }, [productId]);

  if (name) return name;
  return productId === undefined ? "This product" : `Item ${productId}`;
}

export function LineName({ productId }: { productId: number }) {
  return <>{useProductName(productId)}</>;
}
