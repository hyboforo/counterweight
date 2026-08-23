-- ═══════════════════════════════════════════════════════════════════════════
--  V7 — purchasing is out of scope
--
--  The shop does not raise purchase orders from this system, and does not want
--  supplier records either. Ordering stays on paper and on the phone; goods
--  arrive and are received through inventory, and the reorder alerts are acted
--  on outside the system.
--
--  This removes the schema that existed for the cancelled scope rather than
--  leaving it as a trap. Dead tables get built against eventually — somebody
--  finds `purchase_order` in a year, assumes it is load-bearing, and wires
--  something to it.
--
--  IRREVERSIBLE. Reinstating purchasing means writing these tables again, not
--  flipping a flag. That was the explicit choice over leaving them in place.
-- ═══════════════════════════════════════════════════════════════════════════

-- ── Purchasing documents ───────────────────────────────────────────────────
--
-- Children first: goods_receipt_line → goods_receipt → purchase_order_line →
-- purchase_order. All four are empty, since nothing ever wrote to them.

DROP TABLE IF EXISTS goods_receipt_line;
DROP TABLE IF EXISTS goods_receipt;
DROP TABLE IF EXISTS purchase_order_line;
DROP TABLE IF EXISTS purchase_order;

-- ── Supplier references ────────────────────────────────────────────────────
--
-- The columns go with the table. `stock_lot.supplier_id` was the trace from a
-- batch back to who delivered it; without supplier records there is nothing for
-- it to point at, and a column that can only ever be NULL invites somebody to
-- start putting an id in it that means nothing.
--
-- Worth stating plainly: this ends batch-to-supplier traceability. A
-- manufacturer recall can still find every customer who received a lot — that
-- runs through `sale_line_allocation` and is untouched — but the system can no
-- longer say which supplier that lot came from.

ALTER TABLE stock_lot DROP COLUMN IF EXISTS supplier_id;
ALTER TABLE product   DROP COLUMN IF EXISTS preferred_supplier_id;

DROP TABLE IF EXISTS supplier;

-- ── Document numbering ─────────────────────────────────────────────────────
--
-- No purchase orders and no goods-receipt documents means no registers for
-- them. The goods-receipt *slip* the storekeeper signs is still printed; it is
-- a print-out rather than a numbered document, so it never used this sequence.

DELETE FROM document_sequence WHERE doc_type IN ('PURCHASE_ORDER', 'GOODS_RECEIPT');

-- ── Permissions ────────────────────────────────────────────────────────────
--
-- Grants first: role_permission references permission(code) without a cascade,
-- so the rows have to go before the permissions they point at. Showing an
-- owner authority over a feature that does not exist is how a permissions
-- screen stops being trusted.

DELETE FROM role_permission WHERE permission_code IN ('PURCHASE_ORDER_MANAGE', 'SUPPLIER_MANAGE');
DELETE FROM permission      WHERE code            IN ('PURCHASE_ORDER_MANAGE', 'SUPPLIER_MANAGE');

-- ── The ledger's vocabulary ────────────────────────────────────────────────
--
-- SUPPLIER_RETURN named sending goods back to a supplier, which is no longer a
-- concept this system holds. Goods leaving the shop for any reason other than a
-- sale are now an ADJUST_OUT or a DAMAGE_WRITE_OFF, both of which already
-- demand a reason code — so the *why* is still recorded, just in prose rather
-- than in the movement type.
--
-- Both CHECKs are rebuilt rather than edited, because a CHECK cannot be
-- altered in place. Nothing has ever written a SUPPLIER_RETURN row, so the
-- rebuild has no data to validate against — but it is still the stock ledger,
-- and LedgerConformanceTest is what proves this left it intact.

ALTER TABLE stock_movement DROP CONSTRAINT stock_movement_movement_type_check;
ALTER TABLE stock_movement ADD CONSTRAINT stock_movement_movement_type_check
    CHECK (movement_type IN (
        'RECEIPT','SALE_ISSUE','SALE_RETURN',
        'ADJUST_IN','ADJUST_OUT','BULK_BREAK_IN','BULK_BREAK_OUT',
        'TRANSFER_IN','TRANSFER_OUT','STOCK_TAKE',
        'EXPIRY_WRITE_OFF','DAMAGE_WRITE_OFF'));

ALTER TABLE stock_movement DROP CONSTRAINT adjustments_need_reason;
ALTER TABLE stock_movement ADD CONSTRAINT adjustments_need_reason
    CHECK (movement_type NOT IN ('ADJUST_IN','ADJUST_OUT','DAMAGE_WRITE_OFF')
           OR reason_code IS NOT NULL);
