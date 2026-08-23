-- ═══════════════════════════════════════════════════════════════════════════
--  V12 — Goods receipt as a document again
-- ═══════════════════════════════════════════════════════════════════════════

/*
 * V1 had a goods_receipt header. V7 dropped it along with purchasing, because
 * it hung off supplier and purchase_order — but goods still arrive, and only
 * the ordering that preceded them was out of scope. What went with it was the
 * document itself, which left two things broken.
 *
 * First, every RECEIPT movement carries source_type 'GRN' and a NULL
 * source_id: the ledger says a receipt justifies the stock and cannot say
 * which one. Every other movement type points at its source.
 *
 * Second, the printed slip — the paper a storekeeper signs and files beside
 * the delivery note, and the trail behind every lot cost the ledger later
 * depends on — could only be rendered from whatever the client sent, because
 * the server had nothing of its own to read.
 *
 * V7 also deleted the GOODS_RECEIPT numbering, so it is restored below. Note
 * PURCHASE_ORDER is deliberately not: the shop does not raise purchase orders,
 * and a sequence for a document nobody issues is exactly the kind of dead
 * configuration V10 was about.
 */

INSERT INTO document_sequence (branch_id, doc_type, prefix)
SELECT b.id, 'GOODS_RECEIPT', 'GRN'
  FROM branch b
 WHERE NOT EXISTS (
     SELECT 1 FROM document_sequence s
      WHERE s.branch_id = b.id AND s.doc_type = 'GOODS_RECEIPT'
 );

CREATE TABLE goods_receipt (
    id                 BIGSERIAL PRIMARY KEY,
    branch_id          BIGINT      NOT NULL REFERENCES branch(id),
    -- Ours: GRN-000001, from the document sequence seeded in V1.
    number             TEXT        NOT NULL,
    -- Theirs: whatever the delivery note says, for matching against paper.
    supplier_reference TEXT,
    received_on        DATE        NOT NULL DEFAULT CURRENT_DATE,
    received_by        BIGINT      NOT NULL REFERENCES app_user(id),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (branch_id, number)
);
CREATE INDEX ON goods_receipt (branch_id, received_on DESC);

CREATE TABLE goods_receipt_line (
    id               BIGSERIAL PRIMARY KEY,
    goods_receipt_id BIGINT NOT NULL REFERENCES goods_receipt(id) ON DELETE CASCADE,
    line_no          INT    NOT NULL,
    product_id       BIGINT NOT NULL REFERENCES product(id),
    product_uom_id   BIGINT NOT NULL REFERENCES product_uom(id),

    -- As the storekeeper keyed it, in the unit the goods arrived in.
    qty_received     NUMERIC(16,4) NOT NULL CHECK (qty_received > 0),
    unit_cost        NUMERIC(14,4) NOT NULL CHECK (unit_cost >= 0),

    /*
     * And the same figures converted to base units. Stored rather than derived
     * for the reason sale_line gives: a later change to a product's conversion
     * factor must not silently rewrite what this document said. A carton of
     * twelve that becomes a carton of twenty-four next year does not change
     * what arrived on this delivery.
     */
    qty_base         NUMERIC(16,4) NOT NULL CHECK (qty_base > 0),
    cost_per_base    NUMERIC(14,4) NOT NULL CHECK (cost_per_base >= 0),

    -- The lot this line created or added to.
    lot_id           BIGINT NOT NULL REFERENCES stock_lot(id),
    batch_code       TEXT,
    expires_on       DATE,
    UNIQUE (goods_receipt_id, line_no)
);
CREATE INDEX ON goods_receipt_line (lot_id);
CREATE INDEX ON goods_receipt_line (product_id);

/*
 * V1 described STOCK_RECEIVE as receiving "against a purchase order". There
 * are no purchase orders in this system — V7 removed them — so the permission
 * has been describing a workflow that does not exist. Same class of problem as
 * a setting nothing reads.
 */
UPDATE permission
   SET description = 'Receive goods and book them into stock'
 WHERE code = 'STOCK_RECEIVE';
