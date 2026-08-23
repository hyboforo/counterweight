-- ═══════════════════════════════════════════════════════════════════════════
--  V8 — reporting
--
--  One database serves both trading and analysis (§12), so the discipline is
--  to keep heavy aggregation off the tables the till writes to during opening
--  hours. Two mechanisms, and the choice between them is about *when* the
--  answer has to be current:
--
--    * Rollup tables, incremented as sales complete. Sales figures are asked
--      for mid-shift — "what have we taken today" — so they must be live, and
--      a GROUP BY over a day of sale_line while the counter is busy is exactly
--      what this section exists to avoid.
--
--    * Materialised views, refreshed at 02:00. Stock valuation and expiry
--      ageing are read a few times a week by somebody making a decision, not
--      by a cashier. Yesterday's answer is fine, and the refresh happens when
--      the shop is shut.
-- ═══════════════════════════════════════════════════════════════════════════

-- ── Sales rollups ──────────────────────────────────────────────────────────
--
-- Two grains, because one table cannot serve both questions without either
-- exploding the row count or losing a dimension somebody needs.
--
-- `business_date` rather than a timestamp: a shop's day is a day, and a sale
-- rung up at 23:58 belongs to the day it was rung up on. The hour is kept
-- alongside it for the heat map.

CREATE TABLE sales_daily_rollup (
    branch_id     BIGINT   NOT NULL REFERENCES branch(id),
    business_date DATE     NOT NULL,
    hour          SMALLINT NOT NULL CHECK (hour BETWEEN 0 AND 23),
    cashier_id    BIGINT   NOT NULL REFERENCES app_user(id),
    sale_count    INT      NOT NULL DEFAULT 0,
    gross         NUMERIC(14,2) NOT NULL DEFAULT 0,
    discount      NUMERIC(14,2) NOT NULL DEFAULT 0,
    tax           NUMERIC(14,2) NOT NULL DEFAULT 0,
    net           NUMERIC(14,2) NOT NULL DEFAULT 0,
    -- Cost of what was sold, from the lots it drew from. Margin is derived
    -- rather than stored, so it can never disagree with the two figures above.
    cost          NUMERIC(14,2) NOT NULL DEFAULT 0,
    PRIMARY KEY (branch_id, business_date, hour, cashier_id)
);
CREATE INDEX idx_sales_rollup_date ON sales_daily_rollup (branch_id, business_date DESC);

CREATE TABLE sales_product_rollup (
    branch_id     BIGINT NOT NULL REFERENCES branch(id),
    business_date DATE   NOT NULL,
    product_id    BIGINT NOT NULL REFERENCES product(id),
    qty_base      NUMERIC(16,4) NOT NULL DEFAULT 0,
    gross         NUMERIC(14,2) NOT NULL DEFAULT 0,
    discount      NUMERIC(14,2) NOT NULL DEFAULT 0,
    net           NUMERIC(14,2) NOT NULL DEFAULT 0,
    cost          NUMERIC(14,2) NOT NULL DEFAULT 0,
    PRIMARY KEY (branch_id, business_date, product_id)
);
CREATE INDEX idx_sales_product_rollup_date ON sales_product_rollup (branch_id, business_date DESC);
CREATE INDEX idx_sales_product_rollup_product ON sales_product_rollup (product_id, business_date DESC);

-- ── Inventory materialised views ───────────────────────────────────────────
--
-- Valued at lot cost, which is exact rather than an average: cost lives on the
-- lot and the issue cost is that lot's cost, so a valuation and a margin report
-- built the same way always agree.

CREATE MATERIALIZED VIEW mv_stock_valuation AS
SELECT p.branch_id                                   AS branch_id,
       p.id                                          AS product_id,
       p.sku                                         AS sku,
       p.name                                        AS name,
       p.category_id                                 AS category_id,
       COALESCE(SUM(b.qty_base), 0)                  AS qty_base,
       COALESCE(SUM(b.qty_base * l.unit_cost), 0)    AS stock_value,
       COUNT(l.id) FILTER (WHERE b.qty_base > 0)     AS lot_count,
       MIN(l.expires_on) FILTER (WHERE b.qty_base > 0) AS nearest_expiry
  FROM product p
  LEFT JOIN stock_lot l     ON l.product_id = p.id AND l.status = 'AVAILABLE'
  LEFT JOIN stock_balance b ON b.lot_id = l.id AND b.branch_id = l.branch_id
 WHERE p.is_active
 GROUP BY p.branch_id, p.id, p.sku, p.name, p.category_id;

-- UNIQUE so the view can be refreshed CONCURRENTLY, which matters more than it
-- looks: a plain REFRESH takes an ACCESS EXCLUSIVE lock, and a report reading
-- the view at 02:00 would block the refresh, or the refresh would block it.
CREATE UNIQUE INDEX ON mv_stock_valuation (branch_id, product_id);

CREATE MATERIALIZED VIEW mv_expiry_ageing AS
SELECT l.branch_id                        AS branch_id,
       l.id                               AS lot_id,
       l.product_id                       AS product_id,
       p.name                             AS product_name,
       l.lot_code                         AS lot_code,
       l.expires_on                       AS expires_on,
       b.qty_base                         AS qty_base,
       b.qty_base * l.unit_cost           AS stock_value,
       (l.expires_on - CURRENT_DATE)      AS days_left,
       CASE
           WHEN l.expires_on < CURRENT_DATE                     THEN 'EXPIRED'
           WHEN l.expires_on - CURRENT_DATE <= 7                THEN 'WITHIN_7'
           WHEN l.expires_on - CURRENT_DATE <= 30               THEN 'WITHIN_30'
           WHEN l.expires_on - CURRENT_DATE <= 60               THEN 'WITHIN_60'
           WHEN l.expires_on - CURRENT_DATE <= 90               THEN 'WITHIN_90'
           ELSE 'BEYOND_90'
       END                                AS bucket
  FROM stock_lot l
  JOIN product p       ON p.id = l.product_id
  JOIN stock_balance b ON b.lot_id = l.id AND b.branch_id = l.branch_id
 WHERE l.expires_on IS NOT NULL
   AND b.qty_base > 0;

CREATE UNIQUE INDEX ON mv_expiry_ageing (branch_id, lot_id);

-- When each view was last rebuilt.
--
-- PostgreSQL does not record this anywhere — there is no last_refresh column on
-- pg_matviews, and the analyze timestamps in pg_stat are a different thing that
-- happens to move at similar times. A reader deciding on a stock valuation
-- needs to know whether it is from last night or from three days ago, so the
-- refresh writes it down rather than leaving it to be inferred.
CREATE TABLE report_refresh (
    view_name    TEXT        PRIMARY KEY,
    refreshed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO report_refresh (view_name) VALUES ('mv_stock_valuation'), ('mv_expiry_ageing');

-- ── Registers ──────────────────────────────────────────────────────────────
--
-- The control reports read the audit log by action, over a date range. V1
-- indexed nothing for that — the log was written far more than it was read.

CREATE INDEX idx_audit_action_time ON audit_log (branch_id, action, occurred_at DESC);

-- Batch traceability: "which customers received lot 4471" must be one query
-- (§12), not an afternoon with a paper ledger. V1 indexed
-- sale_line_allocation(lot_id), which gets from the lot to the lines; this gets
-- from the lines back to the sales without a scan.
CREATE INDEX idx_sale_line_sale ON sale_line (sale_id, product_id);
