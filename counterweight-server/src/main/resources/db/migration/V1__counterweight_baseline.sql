-- ═══════════════════════════════════════════════════════════════════════════
--  Counterweight — Flyway baseline
--  POS + inventory for a hardware / agro-chemical shop.
--  PostgreSQL 16.
--
--  Conventions used throughout, and why:
--
--   * Enumerations are TEXT + CHECK, not PostgreSQL ENUM types. This diverges
--     from gig-gha-identity deliberately: the movement-type and document-type
--     vocabularies will grow, and ALTER TYPE ... ADD VALUE cannot run inside a
--     transaction, which fights Flyway. A CHECK is edited by a normal migration.
--
--   * Money is NUMERIC(14,2) unless it is a unit cost or unit price, which are
--     NUMERIC(14,4) so that per-piece costs derived from a carton price do not
--     lose precision before they are multiplied back up.
--
--   * Quantity is ALWAYS NUMERIC(16,4). Never INTEGER. Cut-to-size sales
--     (3.5 m of cable, 11 ft of angle iron) are routine in this shop and an
--     integer column here is not recoverable later.
--
--   * branch_id is present on every transactional table from day one. The shop
--     has one branch today; carrying the column now avoids a migration against
--     live sales history when a second outlet opens.
-- ═══════════════════════════════════════════════════════════════════════════

CREATE EXTENSION IF NOT EXISTS ltree;
CREATE EXTENSION IF NOT EXISTS pg_trgm;   -- fast fuzzy product search at the till


-- ═══════════════════════════════════════════════════════════════════════════
--  PLATFORM
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TABLE branch (
    id          BIGSERIAL PRIMARY KEY,
    code        TEXT        NOT NULL UNIQUE,
    name        TEXT        NOT NULL,
    address     TEXT,
    phone       TEXT,
    is_active   BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE app_config (
    key         TEXT        PRIMARY KEY,
    value       TEXT        NOT NULL,
    value_type  TEXT        NOT NULL DEFAULT 'STRING'
                            CHECK (value_type IN ('STRING','NUMBER','BOOL','JSON')),
    description TEXT,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Gapless document numbering.
--
-- Deliberately NOT a PostgreSQL SEQUENCE: sequences are non-transactional and
-- leak numbers on rollback by design. A document register must not have holes,
-- so numbers come from a row that is locked by the allocating transaction and
-- released if that transaction fails.
CREATE TABLE document_sequence (
    id          BIGSERIAL PRIMARY KEY,
    branch_id   BIGINT      NOT NULL REFERENCES branch(id),
    doc_type    TEXT        NOT NULL,
    prefix      TEXT        NOT NULL,
    next_value  BIGINT      NOT NULL DEFAULT 1,
    pad_width   SMALLINT    NOT NULL DEFAULT 6,
    UNIQUE (branch_id, doc_type)
);

CREATE OR REPLACE FUNCTION next_document_number(p_branch BIGINT, p_type TEXT)
RETURNS TEXT AS $$
DECLARE
    v_prefix TEXT;
    v_value  BIGINT;
    v_pad    SMALLINT;
BEGIN
    UPDATE document_sequence
       SET next_value = next_value + 1
     WHERE branch_id = p_branch
       AND doc_type  = p_type
    RETURNING prefix, next_value - 1, pad_width
         INTO v_prefix, v_value, v_pad;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'no document sequence configured for branch % / type %',
                        p_branch, p_type;
    END IF;

    RETURN v_prefix || '-' || lpad(v_value::TEXT, v_pad, '0');
END;
$$ LANGUAGE plpgsql;


-- ═══════════════════════════════════════════════════════════════════════════
--  IDENTITY, ACCESS AND AUDIT
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TABLE app_user (
    id            BIGSERIAL PRIMARY KEY,
    branch_id     BIGINT      NOT NULL REFERENCES branch(id),
    username      TEXT        NOT NULL UNIQUE,
    full_name     TEXT        NOT NULL,
    phone         TEXT,
    password_hash TEXT        NOT NULL,
    -- Separate short PIN for supervisor override at the till. Overriding a
    -- discount must not require typing a full password in front of a customer.
    override_pin_hash TEXT,
    is_active     BOOLEAN     NOT NULL DEFAULT TRUE,
    last_login_at TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE role (
    id          BIGSERIAL PRIMARY KEY,
    code        TEXT NOT NULL UNIQUE,
    name        TEXT NOT NULL,
    is_system   BOOLEAN NOT NULL DEFAULT FALSE   -- system roles cannot be deleted
);

CREATE TABLE permission (
    code        TEXT PRIMARY KEY,
    description TEXT NOT NULL
);

CREATE TABLE role_permission (
    role_id         BIGINT NOT NULL REFERENCES role(id) ON DELETE CASCADE,
    permission_code TEXT   NOT NULL REFERENCES permission(code),
    PRIMARY KEY (role_id, permission_code)
);

CREATE TABLE user_role (
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    role_id BIGINT NOT NULL REFERENCES role(id),
    PRIMARY KEY (user_id, role_id)
);

-- Append-only. See the immutability trigger at the foot of this file.
CREATE TABLE audit_log (
    id           BIGSERIAL PRIMARY KEY,
    branch_id    BIGINT      NOT NULL REFERENCES branch(id),
    actor_id     BIGINT      NOT NULL REFERENCES app_user(id),
    -- Set only when a supervisor authorised an action taken by someone else.
    approver_id  BIGINT      REFERENCES app_user(id),
    action       TEXT        NOT NULL,
    subject_type TEXT        NOT NULL,
    subject_id   BIGINT,
    before_value JSONB,
    after_value  JSONB,
    reason       TEXT,
    occurred_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ON audit_log (branch_id, occurred_at DESC);
CREATE INDEX ON audit_log (subject_type, subject_id);
CREATE INDEX ON audit_log (actor_id, occurred_at DESC);


-- ═══════════════════════════════════════════════════════════════════════════
--  PARTIES
--
--  customer and supplier are kept separate rather than unified behind a single
--  party table. The overlap in this trade is rare enough that the extra nulls
--  and the weaker constraints of a unified table are not worth it: a customer
--  has a credit limit and ageing, a supplier has lead times and a payables
--  balance, and almost nothing is shared beyond a name and a phone number.
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TABLE customer (
    id             BIGSERIAL PRIMARY KEY,
    branch_id      BIGINT      NOT NULL REFERENCES branch(id),
    code           TEXT        NOT NULL,
    name           TEXT        NOT NULL,
    phone          TEXT,
    alt_phone      TEXT,
    address        TEXT,
    -- Contractors and masons run accounts. NULL limit means cash only.
    credit_limit   NUMERIC(14,2),
    payment_terms_days SMALLINT NOT NULL DEFAULT 0,
    price_list_id  BIGINT,          -- FK added after price_list exists
    is_active      BOOLEAN     NOT NULL DEFAULT TRUE,
    notes          TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (branch_id, code)
);
CREATE INDEX ON customer USING GIN (name gin_trgm_ops);
CREATE INDEX ON customer (phone);

CREATE TABLE supplier (
    id             BIGSERIAL PRIMARY KEY,
    branch_id      BIGINT      NOT NULL REFERENCES branch(id),
    code           TEXT        NOT NULL,
    name           TEXT        NOT NULL,
    phone          TEXT,
    address        TEXT,
    -- Feeds the reorder-point calculation. Default is a guess until there is
    -- enough goods-receipt history to compute the real figure.
    lead_time_days SMALLINT    NOT NULL DEFAULT 7,
    payment_terms_days SMALLINT NOT NULL DEFAULT 0,
    is_active      BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (branch_id, code)
);
CREATE INDEX ON supplier USING GIN (name gin_trgm_ops);


-- ═══════════════════════════════════════════════════════════════════════════
--  CATALOG
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TABLE uom (
    id        BIGSERIAL PRIMARY KEY,
    code      TEXT     NOT NULL UNIQUE,     -- PCS, KG, M, FT, L, BAG, CARTON, OLONKA
    name      TEXT     NOT NULL,
    -- How many decimals the till should accept. KG allows 3; PCS allows 0.
    decimals  SMALLINT NOT NULL DEFAULT 0 CHECK (decimals BETWEEN 0 AND 4)
);

CREATE TABLE category (
    id         BIGSERIAL PRIMARY KEY,
    parent_id  BIGINT REFERENCES category(id),
    code       TEXT  NOT NULL UNIQUE,
    name       TEXT  NOT NULL,
    -- Materialised path, e.g. 'agro.herbicide.selective'. Maintained by the
    -- application on insert/move; GiST-indexed for subtree queries.
    path       LTREE NOT NULL,
    kind       TEXT  NOT NULL DEFAULT 'GENERAL'
                     CHECK (kind IN ('GENERAL','AGROCHEMICAL')),
    sort_order INT   NOT NULL DEFAULT 0,
    is_active  BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE INDEX ON category USING GIST (path);
CREATE UNIQUE INDEX ON category (path);

-- Declaring a category's fields is data entry, never DDL. A product inherits
-- every attribute declared on any ancestor of its category path, so the EPA
-- number and hazard band are declared once on 'agro' and picked up by every
-- descendant.
CREATE TABLE category_attribute (
    id          BIGSERIAL PRIMARY KEY,
    category_id BIGINT  NOT NULL REFERENCES category(id) ON DELETE CASCADE,
    key         TEXT    NOT NULL,
    label       TEXT    NOT NULL,
    data_type   TEXT    NOT NULL
                        CHECK (data_type IN ('TEXT','NUMBER','BOOL','DATE','ENUM')),
    enum_values TEXT[],
    unit        TEXT,                       -- 'mm²', 'g/L', 'days'
    required    BOOLEAN NOT NULL DEFAULT FALSE,
    sort_order  INT     NOT NULL DEFAULT 0,
    UNIQUE (category_id, key),
    CONSTRAINT enum_needs_values
        CHECK (data_type <> 'ENUM' OR enum_values IS NOT NULL)
);

CREATE TABLE product (
    id            BIGSERIAL PRIMARY KEY,
    branch_id     BIGINT      NOT NULL REFERENCES branch(id),
    category_id   BIGINT      NOT NULL REFERENCES category(id),
    sku           TEXT        NOT NULL,
    name          TEXT        NOT NULL,
    -- What customers actually call it at the counter. Searched alongside name.
    local_name    TEXT,
    description   TEXT,
    -- Dynamic per-category fields, validated on write against the resolved
    -- category_attribute set. See §6.1 of the architecture note.
    attributes    JSONB       NOT NULL DEFAULT '{}',
    picking_rule  TEXT        NOT NULL DEFAULT 'FIFO'
                              CHECK (picking_rule IN ('FIFO','FEFO','MANUAL')),
    is_batch_tracked BOOLEAN  NOT NULL DEFAULT FALSE,
    -- Reorder inputs. Computed nightly, but every term is overridable because
    -- the computed answer is wrong for anything seasonal.
    reorder_point       NUMERIC(16,4),
    reorder_qty         NUMERIC(16,4),
    reorder_is_manual   BOOLEAN NOT NULL DEFAULT FALSE,
    safety_stock        NUMERIC(16,4) NOT NULL DEFAULT 0,
    preferred_supplier_id BIGINT REFERENCES supplier(id),
    is_active     BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (branch_id, sku),
    -- A batch-tracked product must not be picked FIFO: expiry is the whole
    -- point of tracking it.
    CONSTRAINT batch_implies_fefo
        CHECK (NOT is_batch_tracked OR picking_rule IN ('FEFO','MANUAL'))
);
CREATE INDEX ON product USING GIN (attributes jsonb_path_ops);
CREATE INDEX ON product USING GIN (name gin_trgm_ops);
CREATE INDEX ON product USING GIN (local_name gin_trgm_ops);
CREATE INDEX ON product (category_id) WHERE is_active;

-- Supplementary profile for regulated agro-chemical products. 1:1 with product,
-- present only where the category kind is AGROCHEMICAL.
CREATE TABLE agro_profile (
    product_id           BIGINT PRIMARY KEY REFERENCES product(id) ON DELETE CASCADE,
    epa_registration_no  TEXT,
    active_ingredient    TEXT,
    concentration        TEXT,               -- '480 g/L'
    -- WHO acute hazard band. Drives the colour coding in the UI and on the
    -- printed receipt.
    hazard_band          TEXT CHECK (hazard_band IN ('IA','IB','II','III','U')),
    formulation          TEXT,               -- EC, SC, WP, SL, GR
    ppe_notes            TEXT,
    reentry_interval_hours SMALLINT,
    storage_notes        TEXT,
    -- When true, the till must capture buyer identity before completing a sale.
    requires_buyer_record BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE product_uom (
    id          BIGSERIAL PRIMARY KEY,
    product_id  BIGINT  NOT NULL REFERENCES product(id) ON DELETE CASCADE,
    uom_id      BIGINT  NOT NULL REFERENCES uom(id),
    -- How many BASE units are contained in one of these. The base row has 1.0.
    factor      NUMERIC(16,6) NOT NULL,
    is_base     BOOLEAN NOT NULL DEFAULT FALSE,
    sellable    BOOLEAN NOT NULL DEFAULT TRUE,
    purchasable BOOLEAN NOT NULL DEFAULT TRUE,
    barcode     TEXT,
    UNIQUE (product_id, uom_id),
    CONSTRAINT factor_positive CHECK (factor > 0),
    CONSTRAINT base_factor_is_one CHECK (NOT is_base OR factor = 1)
);
-- Exactly one base unit per product.
CREATE UNIQUE INDEX one_base_uom_per_product ON product_uom (product_id) WHERE is_base;
CREATE UNIQUE INDEX ON product_uom (barcode) WHERE barcode IS NOT NULL;


-- ═══════════════════════════════════════════════════════════════════════════
--  PRICING AND TAX
--
--  The shop is not VAT-registered. The tax tables are created and joined
--  through on every line anyway, configured to a zero scheme, so that
--  registering later is INSERTs plus a config flag rather than a migration
--  against live sales history.
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TABLE price_list (
    id         BIGSERIAL PRIMARY KEY,
    branch_id  BIGINT  NOT NULL REFERENCES branch(id),
    code       TEXT    NOT NULL,
    name       TEXT    NOT NULL,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (branch_id, code)
);
CREATE UNIQUE INDEX one_default_price_list_per_branch
    ON price_list (branch_id) WHERE is_default;

ALTER TABLE customer
    ADD CONSTRAINT customer_price_list_fk
    FOREIGN KEY (price_list_id) REFERENCES price_list(id);

CREATE TABLE price (
    id            BIGSERIAL PRIMARY KEY,
    price_list_id BIGINT NOT NULL REFERENCES price_list(id) ON DELETE CASCADE,
    product_id    BIGINT NOT NULL REFERENCES product(id) ON DELETE CASCADE,
    -- Prices are quoted per selling unit, not per base unit: the counter thinks
    -- in "GHS per bag", not "GHS per kg".
    product_uom_id BIGINT NOT NULL REFERENCES product_uom(id),
    unit_price    NUMERIC(14,4) NOT NULL CHECK (unit_price >= 0),
    effective_from DATE NOT NULL DEFAULT CURRENT_DATE,
    effective_to   DATE,
    CONSTRAINT price_window_valid
        CHECK (effective_to IS NULL OR effective_to >= effective_from)
);
-- At most one open-ended price per product/uom/list.
CREATE UNIQUE INDEX one_open_price
    ON price (price_list_id, product_id, product_uom_id)
    WHERE effective_to IS NULL;
CREATE INDEX ON price (product_id, effective_from DESC);

CREATE TABLE discount_policy (
    id                  BIGSERIAL PRIMARY KEY,
    role_id             BIGINT NOT NULL REFERENCES role(id) ON DELETE CASCADE,
    max_percent         NUMERIC(5,2) NOT NULL DEFAULT 0
                        CHECK (max_percent BETWEEN 0 AND 100),
    -- Below this margin over lot cost the sale needs a supervisor override,
    -- regardless of the percentage discount applied.
    min_margin_percent  NUMERIC(5,2) NOT NULL DEFAULT 0,
    requires_approval_above NUMERIC(5,2),
    UNIQUE (role_id)
);

CREATE TABLE tax_scheme (
    id         BIGSERIAL PRIMARY KEY,
    code       TEXT    NOT NULL UNIQUE,
    name       TEXT    NOT NULL,
    is_active  BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE tax_component (
    id            BIGSERIAL PRIMARY KEY,
    tax_scheme_id BIGINT NOT NULL REFERENCES tax_scheme(id) ON DELETE CASCADE,
    code          TEXT   NOT NULL,          -- VAT, NHIL, GETFUND
    name          TEXT   NOT NULL,
    -- Some Ghanaian levies are computed on the taxable value rather than
    -- compounding on VAT. Order and base matter, so both are explicit.
    computed_on   TEXT   NOT NULL DEFAULT 'TAXABLE_VALUE'
                         CHECK (computed_on IN ('TAXABLE_VALUE','RUNNING_TOTAL')),
    is_recoverable BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order    INT    NOT NULL DEFAULT 0,
    UNIQUE (tax_scheme_id, code)
);

CREATE TABLE tax_rate (
    id               BIGSERIAL PRIMARY KEY,
    tax_component_id BIGINT NOT NULL REFERENCES tax_component(id) ON DELETE CASCADE,
    rate_percent     NUMERIC(6,3) NOT NULL CHECK (rate_percent >= 0),
    effective_from   DATE NOT NULL,
    effective_to     DATE,
    CONSTRAINT rate_window_valid
        CHECK (effective_to IS NULL OR effective_to >= effective_from)
);
CREATE INDEX ON tax_rate (tax_component_id, effective_from DESC);


-- ═══════════════════════════════════════════════════════════════════════════
--  INVENTORY — the ledger
--
--  This is the core of the system. Read §5 of the architecture note before
--  changing anything here.
-- ═══════════════════════════════════════════════════════════════════════════

-- Every on-hand quantity in the system belongs to a lot. Agro-chemicals carry
-- a real manufacturer batch and expiry; hardware gets a lot generated at
-- goods-receipt with a NULL expiry. One mechanism serves both.
CREATE TABLE stock_lot (
    id           BIGSERIAL PRIMARY KEY,
    branch_id    BIGINT        NOT NULL REFERENCES branch(id),
    product_id   BIGINT        NOT NULL REFERENCES product(id),
    lot_code     TEXT          NOT NULL,
    expires_on   DATE,                        -- NULL = does not expire
    received_on  DATE          NOT NULL DEFAULT CURRENT_DATE,
    -- Cost per BASE unit, in GHS. Once movements exist against this lot the
    -- cost must never be edited: correct it with a compensating adjustment
    -- pair so the correction is itself part of the record.
    unit_cost    NUMERIC(14,4) NOT NULL CHECK (unit_cost >= 0),
    supplier_id  BIGINT        REFERENCES supplier(id),
    status       TEXT          NOT NULL DEFAULT 'AVAILABLE'
                               CHECK (status IN ('AVAILABLE','QUARANTINED',
                                                 'EXPIRED','WRITTEN_OFF')),
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    UNIQUE (branch_id, product_id, lot_code)
);
-- Supports FEFO picking: nearest expiry first, non-expiring lots last.
CREATE INDEX ON stock_lot (branch_id, product_id, expires_on NULLS LAST)
    WHERE status = 'AVAILABLE';
CREATE INDEX ON stock_lot (branch_id, product_id, received_on)
    WHERE status = 'AVAILABLE';
-- Drives the expiry alert sweep.
CREATE INDEX ON stock_lot (expires_on) WHERE expires_on IS NOT NULL
                                         AND status = 'AVAILABLE';

-- APPEND-ONLY. Enforced by trigger, not by convention. A mistake is corrected
-- by posting a compensating movement, never by UPDATE or DELETE.
CREATE TABLE stock_movement (
    id            BIGSERIAL     PRIMARY KEY,
    branch_id     BIGINT        NOT NULL REFERENCES branch(id),
    lot_id        BIGINT        NOT NULL REFERENCES stock_lot(id),
    movement_type TEXT          NOT NULL CHECK (movement_type IN (
                      'RECEIPT','SALE_ISSUE','SALE_RETURN','SUPPLIER_RETURN',
                      'ADJUST_IN','ADJUST_OUT','BULK_BREAK_IN','BULK_BREAK_OUT',
                      'TRANSFER_IN','TRANSFER_OUT','STOCK_TAKE',
                      'EXPIRY_WRITE_OFF','DAMAGE_WRITE_OFF')),
    -- Signed, in the product's BASE unit. Positive is in, negative is out.
    qty_base      NUMERIC(16,4) NOT NULL,
    unit_cost     NUMERIC(14,4) NOT NULL,
    source_type   TEXT          NOT NULL CHECK (source_type IN (
                      'GRN','SALE','RETURN','ADJUSTMENT','STOCK_TAKE',
                      'TRANSFER','WRITE_OFF','BULK_BREAK','OPENING_BALANCE')),
    source_id     BIGINT,
    reason_code   TEXT,
    occurred_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by    BIGINT        NOT NULL REFERENCES app_user(id),
    approved_by   BIGINT        REFERENCES app_user(id),
    CONSTRAINT qty_nonzero CHECK (qty_base <> 0),
    -- Manual corrections must always say why.
    CONSTRAINT adjustments_need_reason
        CHECK (movement_type NOT IN ('ADJUST_IN','ADJUST_OUT',
                                     'DAMAGE_WRITE_OFF','SUPPLIER_RETURN')
               OR reason_code IS NOT NULL)
);
CREATE INDEX ON stock_movement (branch_id, lot_id, occurred_at);
CREATE INDEX ON stock_movement (source_type, source_id);
CREATE INDEX ON stock_movement (branch_id, occurred_at DESC);
CREATE INDEX ON stock_movement (movement_type, occurred_at DESC);

-- Derived from stock_movement by trigger. The CHECK is the oversell guard:
-- two cashiers selling the last bag of cement at the same instant do not need
-- application locking, because the second transaction violates it and rolls
-- back. Negative stock is therefore impossible by construction.
CREATE TABLE stock_balance (
    branch_id  BIGINT        NOT NULL REFERENCES branch(id),
    lot_id     BIGINT        NOT NULL REFERENCES stock_lot(id),
    qty_base   NUMERIC(16,4) NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (branch_id, lot_id),
    CONSTRAINT qty_never_negative CHECK (qty_base >= 0)
);

-- The balance row is created with the lot, at zero, so that the movement
-- trigger below only ever has to UPDATE.
--
-- This is not incidental. PostgreSQL evaluates CHECK constraints against the
-- proposed tuple BEFORE ON CONFLICT can divert to the DO UPDATE path, so an
-- INSERT ... ON CONFLICT carrying a negative delta trips qty_never_negative
-- even when the existing balance would comfortably absorb it — i.e. on every
-- sale. Creating the row up front removes the insert path entirely.
CREATE OR REPLACE FUNCTION create_stock_balance_row() RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO stock_balance (branch_id, lot_id, qty_base)
    VALUES (NEW.branch_id, NEW.id, 0)
    ON CONFLICT (branch_id, lot_id) DO NOTHING;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_create_stock_balance
    AFTER INSERT ON stock_lot
    FOR EACH ROW EXECUTE FUNCTION create_stock_balance_row();

-- Pure UPDATE. The row lock it takes is also what makes the oversell guard
-- work under concurrency: two tills selling the last bag serialise here, the
-- second one reads the already-decremented balance, and its CHECK fires.
CREATE OR REPLACE FUNCTION apply_stock_movement() RETURNS TRIGGER AS $$
BEGIN
    UPDATE stock_balance
       SET qty_base   = qty_base + NEW.qty_base,
           updated_at = now()
     WHERE branch_id = NEW.branch_id
       AND lot_id    = NEW.lot_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'no stock_balance row for lot % (branch %)',
                        NEW.lot_id, NEW.branch_id
            USING HINT = 'Balance rows are created with the lot. A missing one '
                         'means trg_create_stock_balance was bypassed.';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_apply_stock_movement
    AFTER INSERT ON stock_movement
    FOR EACH ROW EXECUTE FUNCTION apply_stock_movement();

CREATE OR REPLACE FUNCTION reject_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION '% is append-only; attempted %', TG_TABLE_NAME, TG_OP
        USING HINT = 'Post a compensating row instead of mutating history.';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_stock_movement_immutable
    BEFORE UPDATE OR DELETE ON stock_movement
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

CREATE TRIGGER trg_audit_log_immutable
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();


-- Stock takes -------------------------------------------------------------

CREATE TABLE stock_take (
    id          BIGSERIAL PRIMARY KEY,
    branch_id   BIGINT      NOT NULL REFERENCES branch(id),
    reference   TEXT        NOT NULL,
    -- A full count freezes nothing; a partial count is scoped to a category
    -- subtree so a shop can count the agro-chemical shelf without stopping.
    scope_path  LTREE,
    status      TEXT        NOT NULL DEFAULT 'OPEN'
                            CHECK (status IN ('OPEN','COUNTED','POSTED','CANCELLED')),
    opened_by   BIGINT      NOT NULL REFERENCES app_user(id),
    opened_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    posted_by   BIGINT      REFERENCES app_user(id),
    posted_at   TIMESTAMPTZ,
    UNIQUE (branch_id, reference)
);

CREATE TABLE stock_take_line (
    id            BIGSERIAL PRIMARY KEY,
    stock_take_id BIGINT NOT NULL REFERENCES stock_take(id) ON DELETE CASCADE,
    lot_id        BIGINT NOT NULL REFERENCES stock_lot(id),
    -- Snapshot at the moment counting began, so the variance is meaningful
    -- even if trading continued.
    expected_qty  NUMERIC(16,4) NOT NULL,
    counted_qty   NUMERIC(16,4),
    variance      NUMERIC(16,4) GENERATED ALWAYS AS (counted_qty - expected_qty) STORED,
    note          TEXT,
    UNIQUE (stock_take_id, lot_id)
);


-- ═══════════════════════════════════════════════════════════════════════════
--  PURCHASING
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TABLE purchase_order (
    id           BIGSERIAL PRIMARY KEY,
    branch_id    BIGINT      NOT NULL REFERENCES branch(id),
    supplier_id  BIGINT      NOT NULL REFERENCES supplier(id),
    number       TEXT        NOT NULL,
    status       TEXT        NOT NULL DEFAULT 'DRAFT'
                             CHECK (status IN ('DRAFT','SENT','PARTIAL',
                                               'RECEIVED','CANCELLED')),
    ordered_on   DATE        NOT NULL DEFAULT CURRENT_DATE,
    expected_on  DATE,
    created_by   BIGINT      NOT NULL REFERENCES app_user(id),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (branch_id, number)
);

CREATE TABLE purchase_order_line (
    id                BIGSERIAL PRIMARY KEY,
    purchase_order_id BIGINT NOT NULL REFERENCES purchase_order(id) ON DELETE CASCADE,
    product_id        BIGINT NOT NULL REFERENCES product(id),
    product_uom_id    BIGINT NOT NULL REFERENCES product_uom(id),
    qty_ordered       NUMERIC(16,4) NOT NULL CHECK (qty_ordered > 0),
    unit_cost         NUMERIC(14,4) NOT NULL CHECK (unit_cost >= 0),
    -- Suppliers part-fill constantly; back-orders are the norm, not an error.
    qty_received      NUMERIC(16,4) NOT NULL DEFAULT 0
);

CREATE TABLE goods_receipt (
    id                BIGSERIAL PRIMARY KEY,
    branch_id         BIGINT      NOT NULL REFERENCES branch(id),
    supplier_id       BIGINT      NOT NULL REFERENCES supplier(id),
    purchase_order_id BIGINT      REFERENCES purchase_order(id),
    number            TEXT        NOT NULL,
    supplier_invoice_no TEXT,
    received_on       DATE        NOT NULL DEFAULT CURRENT_DATE,
    received_by       BIGINT      NOT NULL REFERENCES app_user(id),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (branch_id, number)
);

CREATE TABLE goods_receipt_line (
    id               BIGSERIAL PRIMARY KEY,
    goods_receipt_id BIGINT NOT NULL REFERENCES goods_receipt(id) ON DELETE CASCADE,
    product_id       BIGINT NOT NULL REFERENCES product(id),
    product_uom_id   BIGINT NOT NULL REFERENCES product_uom(id),
    qty_received     NUMERIC(16,4) NOT NULL CHECK (qty_received > 0),
    unit_cost        NUMERIC(14,4) NOT NULL CHECK (unit_cost >= 0),
    -- The lot this receipt created. Batch and expiry are captured here at the
    -- point the storekeeper reads them off the carton.
    lot_id           BIGINT NOT NULL REFERENCES stock_lot(id),
    batch_code       TEXT,
    expires_on       DATE
);


-- ═══════════════════════════════════════════════════════════════════════════
--  SALES
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TABLE cash_session (
    id            BIGSERIAL PRIMARY KEY,
    branch_id     BIGINT      NOT NULL REFERENCES branch(id),
    till_code     TEXT        NOT NULL,
    cashier_id    BIGINT      NOT NULL REFERENCES app_user(id),
    opened_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    opening_float NUMERIC(14,2) NOT NULL CHECK (opening_float >= 0),
    closed_at     TIMESTAMPTZ,
    expected_cash NUMERIC(14,2),
    counted_cash  NUMERIC(14,2),
    variance      NUMERIC(14,2) GENERATED ALWAYS AS
                      (counted_cash - expected_cash) STORED,
    status        TEXT        NOT NULL DEFAULT 'OPEN'
                              CHECK (status IN ('OPEN','CLOSED')),
    closed_by     BIGINT      REFERENCES app_user(id)
);
-- One open session per till at a time.
CREATE UNIQUE INDEX one_open_session_per_till
    ON cash_session (branch_id, till_code) WHERE status = 'OPEN';
CREATE INDEX ON cash_session (cashier_id, opened_at DESC);

CREATE TABLE sale (
    id              BIGSERIAL PRIMARY KEY,
    branch_id       BIGINT      NOT NULL REFERENCES branch(id),
    cash_session_id BIGINT      REFERENCES cash_session(id),
    customer_id     BIGINT      REFERENCES customer(id),
    number          TEXT,                      -- allocated on completion
    status          TEXT        NOT NULL DEFAULT 'DRAFT'
                                CHECK (status IN ('DRAFT','HELD','COMPLETED',
                                                  'PARTIALLY_RETURNED',
                                                  'RETURNED','VOIDED')),
    -- Held sales live on the server, not in browser memory: a customer who
    -- leaves to find a mobile-money agent is routine, and any till must be
    -- able to recall the basket.
    held_label      TEXT,
    subtotal        NUMERIC(14,2) NOT NULL DEFAULT 0,
    discount_total  NUMERIC(14,2) NOT NULL DEFAULT 0,
    tax_total       NUMERIC(14,2) NOT NULL DEFAULT 0,
    -- Payable total rounds to the nearest 5 pesewas; the difference is kept
    -- here so the day's cash reconciles exactly.
    rounding_adjustment NUMERIC(14,2) NOT NULL DEFAULT 0,
    grand_total     NUMERIC(14,2) NOT NULL DEFAULT 0,
    cashier_id      BIGINT      NOT NULL REFERENCES app_user(id),
    completed_at    TIMESTAMPTZ,
    voided_at       TIMESTAMPTZ,
    void_reason     TEXT,
    voided_by       BIGINT      REFERENCES app_user(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT completed_has_number
        CHECK (status = 'DRAFT' OR status = 'HELD' OR number IS NOT NULL),
    CONSTRAINT void_has_reason
        CHECK (status <> 'VOIDED' OR void_reason IS NOT NULL)
);
CREATE UNIQUE INDEX ON sale (branch_id, number) WHERE number IS NOT NULL;
CREATE INDEX ON sale (branch_id, completed_at DESC) WHERE status = 'COMPLETED';
CREATE INDEX ON sale (cash_session_id);
CREATE INDEX ON sale (customer_id, completed_at DESC);
CREATE INDEX ON sale (branch_id, status) WHERE status = 'HELD';

CREATE TABLE sale_line (
    id             BIGSERIAL PRIMARY KEY,
    sale_id        BIGINT NOT NULL REFERENCES sale(id) ON DELETE CASCADE,
    line_no        INT    NOT NULL,
    product_id     BIGINT NOT NULL REFERENCES product(id),
    product_uom_id BIGINT NOT NULL REFERENCES product_uom(id),
    -- Quantity as the counter keyed it, in the selling unit.
    qty            NUMERIC(16,4) NOT NULL CHECK (qty > 0),
    -- The same quantity converted to base units. Stored, not derived, so that
    -- a later change to the conversion factor cannot silently rewrite history.
    qty_base       NUMERIC(16,4) NOT NULL,
    unit_price     NUMERIC(14,4) NOT NULL CHECK (unit_price >= 0),
    discount_amount NUMERIC(14,2) NOT NULL DEFAULT 0,
    tax_amount     NUMERIC(14,2) NOT NULL DEFAULT 0,
    line_total     NUMERIC(14,2) NOT NULL,
    -- Cost at the moment of sale, from the lot. Makes margin reporting exact
    -- without recomputing anything later.
    unit_cost      NUMERIC(14,4) NOT NULL,
    price_overridden BOOLEAN NOT NULL DEFAULT FALSE,
    approved_by    BIGINT REFERENCES app_user(id),
    UNIQUE (sale_id, line_no)
);
CREATE INDEX ON sale_line (product_id);

-- Which lots a line actually drew from. A single line can span several lots
-- when the first one runs out mid-quantity.
--
-- This table is what makes batch traceability a single query: "which customers
-- received lot 4471" for a manufacturer recall.
CREATE TABLE sale_line_allocation (
    id           BIGSERIAL PRIMARY KEY,
    sale_line_id BIGINT NOT NULL REFERENCES sale_line(id) ON DELETE CASCADE,
    lot_id       BIGINT NOT NULL REFERENCES stock_lot(id),
    qty_base     NUMERIC(16,4) NOT NULL CHECK (qty_base > 0),
    unit_cost    NUMERIC(14,4) NOT NULL
);
CREATE INDEX ON sale_line_allocation (lot_id);

CREATE TABLE sale_payment (
    id           BIGSERIAL PRIMARY KEY,
    sale_id      BIGINT NOT NULL REFERENCES sale(id) ON DELETE CASCADE,
    method       TEXT   NOT NULL CHECK (method IN ('CASH','MOBILE_MONEY',
                                                   'BANK_TRANSFER','CHEQUE',
                                                   'ON_ACCOUNT','CARD')),
    amount       NUMERIC(14,2) NOT NULL CHECK (amount > 0),
    -- Split tender is normal, not exceptional: part cash, part MoMo, the
    -- rest on account. Hence a collection, never columns on sale.
    tendered     NUMERIC(14,2),          -- cash only
    change_given NUMERIC(14,2),          -- cash only
    -- Mobile money is keyed by hand: the shop has no internet, so the
    -- reference the customer reads out is the record.
    momo_network TEXT CHECK (momo_network IN ('MTN','TELECEL','AT')),
    reference    TEXT,
    bank_name    TEXT,
    cheque_number TEXT,
    cheque_date  DATE,
    received_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ON sale_payment (sale_id);
CREATE INDEX ON sale_payment (method, received_at DESC);

-- Guards against a double-click or a dropped LAN frame deducting stock twice.
CREATE TABLE idempotency_record (
    key         UUID        PRIMARY KEY,
    sale_id     BIGINT      NOT NULL REFERENCES sale(id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE sale_return (
    id           BIGSERIAL PRIMARY KEY,
    branch_id    BIGINT      NOT NULL REFERENCES branch(id),
    sale_id      BIGINT      NOT NULL REFERENCES sale(id),
    number       TEXT        NOT NULL,
    reason       TEXT        NOT NULL,
    refund_method TEXT       NOT NULL CHECK (refund_method IN
                                ('CASH','MOBILE_MONEY','CREDIT_NOTE','ACCOUNT')),
    total        NUMERIC(14,2) NOT NULL,
    created_by   BIGINT      NOT NULL REFERENCES app_user(id),
    approved_by  BIGINT      REFERENCES app_user(id),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (branch_id, number)
);

CREATE TABLE sale_return_line (
    id             BIGSERIAL PRIMARY KEY,
    sale_return_id BIGINT NOT NULL REFERENCES sale_return(id) ON DELETE CASCADE,
    sale_line_id   BIGINT NOT NULL REFERENCES sale_line(id),
    qty_base       NUMERIC(16,4) NOT NULL CHECK (qty_base > 0),
    -- Returns go back to the lot they came from where that is known, so a
    -- returned agro-chemical does not lose its expiry date.
    lot_id         BIGINT NOT NULL REFERENCES stock_lot(id),
    amount         NUMERIC(14,2) NOT NULL,
    restock        BOOLEAN NOT NULL DEFAULT TRUE
);

-- Buyer register for restricted agro-chemicals, where the product's
-- agro_profile.requires_buyer_record is set.
CREATE TABLE restricted_sale_record (
    id           BIGSERIAL PRIMARY KEY,
    sale_line_id BIGINT      NOT NULL REFERENCES sale_line(id) ON DELETE CASCADE,
    buyer_name   TEXT        NOT NULL,
    buyer_phone  TEXT,
    buyer_id_type TEXT,
    buyer_id_number TEXT,
    intended_use TEXT,
    recorded_by  BIGINT      NOT NULL REFERENCES app_user(id),
    recorded_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);


-- ═══════════════════════════════════════════════════════════════════════════
--  BILLING AND RECEIVABLES
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TABLE quotation (
    id           BIGSERIAL PRIMARY KEY,
    branch_id    BIGINT      NOT NULL REFERENCES branch(id),
    customer_id  BIGINT      REFERENCES customer(id),
    number       TEXT        NOT NULL,
    -- Quotations exist before a sale does, so they are their own document
    -- rather than a state of sale.
    status       TEXT        NOT NULL DEFAULT 'OPEN'
                             CHECK (status IN ('OPEN','CONVERTED','EXPIRED','CANCELLED')),
    valid_until  DATE,
    total        NUMERIC(14,2) NOT NULL DEFAULT 0,
    converted_sale_id BIGINT REFERENCES sale(id),
    created_by   BIGINT      NOT NULL REFERENCES app_user(id),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (branch_id, number)
);

CREATE TABLE quotation_line (
    id             BIGSERIAL PRIMARY KEY,
    quotation_id   BIGINT NOT NULL REFERENCES quotation(id) ON DELETE CASCADE,
    line_no        INT    NOT NULL,
    product_id     BIGINT NOT NULL REFERENCES product(id),
    product_uom_id BIGINT NOT NULL REFERENCES product_uom(id),
    qty            NUMERIC(16,4) NOT NULL CHECK (qty > 0),
    unit_price     NUMERIC(14,4) NOT NULL,
    line_total     NUMERIC(14,2) NOT NULL,
    UNIQUE (quotation_id, line_no)
);

-- Issued documents against a sale. A single credit sale produces an invoice
-- and may also produce a delivery note; both point at the same sale.
CREATE TABLE sales_document (
    id          BIGSERIAL PRIMARY KEY,
    branch_id   BIGINT      NOT NULL REFERENCES branch(id),
    sale_id     BIGINT      REFERENCES sale(id),
    sale_return_id BIGINT   REFERENCES sale_return(id),
    doc_type    TEXT        NOT NULL CHECK (doc_type IN
                    ('RECEIPT','INVOICE','DELIVERY_NOTE','CREDIT_NOTE')),
    number      TEXT        NOT NULL,
    issued_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    issued_by   BIGINT      NOT NULL REFERENCES app_user(id),
    total       NUMERIC(14,2) NOT NULL,
    -- Snapshot of the tax scheme in force when issued, so reprinting an old
    -- document after a rate change reproduces the original figures.
    tax_snapshot JSONB,
    UNIQUE (branch_id, doc_type, number),
    CONSTRAINT document_has_a_source
        CHECK (sale_id IS NOT NULL OR sale_return_id IS NOT NULL)
);
CREATE INDEX ON sales_document (sale_id);

-- Customer account ledger. Signed entries; balance is their sum.
CREATE TABLE customer_ledger_entry (
    id           BIGSERIAL PRIMARY KEY,
    branch_id    BIGINT      NOT NULL REFERENCES branch(id),
    customer_id  BIGINT      NOT NULL REFERENCES customer(id),
    entry_type   TEXT        NOT NULL CHECK (entry_type IN
                    ('INVOICE','PAYMENT','CREDIT_NOTE','OPENING_BALANCE',
                     'WRITE_OFF','ADJUSTMENT')),
    -- Positive increases what the customer owes; negative reduces it.
    amount       NUMERIC(14,2) NOT NULL CHECK (amount <> 0),
    sales_document_id BIGINT REFERENCES sales_document(id),
    due_on       DATE,
    reference    TEXT,
    occurred_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by   BIGINT      NOT NULL REFERENCES app_user(id)
);
CREATE INDEX ON customer_ledger_entry (customer_id, occurred_at DESC);
CREATE INDEX ON customer_ledger_entry (branch_id, due_on)
    WHERE entry_type = 'INVOICE';

-- Which payment settled which invoice. Oldest-first by default, overridable.
CREATE TABLE payment_allocation (
    id              BIGSERIAL PRIMARY KEY,
    payment_entry_id BIGINT NOT NULL REFERENCES customer_ledger_entry(id) ON DELETE CASCADE,
    invoice_entry_id BIGINT NOT NULL REFERENCES customer_ledger_entry(id),
    amount          NUMERIC(14,2) NOT NULL CHECK (amount > 0),
    UNIQUE (payment_entry_id, invoice_entry_id)
);


-- ═══════════════════════════════════════════════════════════════════════════
--  ALERTING
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TABLE alert_rule (
    id         BIGSERIAL PRIMARY KEY,
    branch_id  BIGINT REFERENCES branch(id),    -- NULL = all branches
    rule_type  TEXT    NOT NULL,
    -- {"categoryPath": "agro.*", "productId": 42, "supplierId": 7}
    scope      JSONB   NOT NULL DEFAULT '{}',
    -- {"days": 60, "thresholdPercent": 10}
    params     JSONB   NOT NULL DEFAULT '{}',
    severity   TEXT    NOT NULL CHECK (severity IN ('CRITICAL','WARNING','INFO')),
    channels   TEXT[]  NOT NULL DEFAULT ARRAY['IN_APP'],
    enabled    BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE alert (
    id              BIGSERIAL PRIMARY KEY,
    rule_id         BIGINT      NOT NULL REFERENCES alert_rule(id) ON DELETE CASCADE,
    branch_id       BIGINT      NOT NULL REFERENCES branch(id),
    -- 'LOW_STOCK:branch1:product482'
    dedupe_key      TEXT        NOT NULL,
    severity        TEXT        NOT NULL CHECK (severity IN ('CRITICAL','WARNING','INFO')),
    title           TEXT        NOT NULL,
    body            TEXT        NOT NULL,
    subject_type    TEXT,
    subject_id      BIGINT,
    raised_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    acknowledged_at TIMESTAMPTZ,
    acknowledged_by BIGINT      REFERENCES app_user(id),
    resolved_at     TIMESTAMPTZ,
    snoozed_until   TIMESTAMPTZ
);
-- At most ONE open alert per condition. Re-raising while it is still open is
-- a no-op rather than a new row and a new notification. This partial index is
-- the whole anti-fatigue mechanism.
CREATE UNIQUE INDEX one_open_alert_per_condition
    ON alert (dedupe_key) WHERE resolved_at IS NULL;
CREATE INDEX ON alert (branch_id, severity, raised_at DESC)
    WHERE resolved_at IS NULL;

-- Outbound notifications that could not be delivered immediately (SMS with no
-- connection). Strictly queue-and-forward: nothing on the sale path ever waits
-- on this table.
CREATE TABLE notification_outbox (
    id           BIGSERIAL PRIMARY KEY,
    alert_id     BIGINT      REFERENCES alert(id) ON DELETE CASCADE,
    channel      TEXT        NOT NULL CHECK (channel IN ('SMS','EMAIL')),
    destination  TEXT        NOT NULL,
    payload      TEXT        NOT NULL,
    status       TEXT        NOT NULL DEFAULT 'PENDING'
                             CHECK (status IN ('PENDING','SENT','FAILED','ABANDONED')),
    attempts     SMALLINT    NOT NULL DEFAULT 0,
    last_error   TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at      TIMESTAMPTZ
);
CREATE INDEX ON notification_outbox (status, created_at) WHERE status = 'PENDING';


-- ═══════════════════════════════════════════════════════════════════════════
--  PRINTING
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TABLE print_job (
    id          UUID        PRIMARY KEY,
    branch_id   BIGINT      NOT NULL REFERENCES branch(id),
    till_code   TEXT        NOT NULL,
    template    TEXT        NOT NULL,
    -- The structured print document. The server never emits ESC/POS bytes;
    -- the agent on the till encodes this for whatever printer it holds.
    document    JSONB       NOT NULL,
    copies      SMALLINT    NOT NULL DEFAULT 1,
    status      TEXT        NOT NULL DEFAULT 'QUEUED'
                            CHECK (status IN ('QUEUED','PRINTING','DONE','FAILED')),
    attempts    SMALLINT    NOT NULL DEFAULT 0,
    last_error  TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ
);
CREATE INDEX ON print_job (branch_id, till_code, created_at DESC);
CREATE INDEX ON print_job (status) WHERE status IN ('QUEUED','FAILED');


-- ═══════════════════════════════════════════════════════════════════════════
--  BACKUP REGISTER
--
--  The alert engine watches this table. An unverified backup is a belief, not
--  a backup, so verified_at is separate from completed_at and a stale
--  verification raises a CRITICAL alert.
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TABLE backup_run (
    id            BIGSERIAL PRIMARY KEY,
    kind          TEXT        NOT NULL CHECK (kind IN ('DUMP','WAL_ARCHIVE','OFFSITE')),
    destination   TEXT        NOT NULL,
    started_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at  TIMESTAMPTZ,
    size_bytes    BIGINT,
    checksum      TEXT,
    status        TEXT        NOT NULL DEFAULT 'RUNNING'
                              CHECK (status IN ('RUNNING','SUCCESS','FAILED')),
    error         TEXT,
    -- Set only by the automated restore drill, after asserting row counts and
    -- checksums against a scratch database.
    verified_at   TIMESTAMPTZ,
    verify_error  TEXT
);
CREATE INDEX ON backup_run (kind, started_at DESC);


-- ═══════════════════════════════════════════════════════════════════════════
--  RECONCILIATION VIEW
--
--  The nightly tripwire: re-sum the ledger and compare against the stored
--  balance. Any row returned means a code path bypassed the trigger, and
--  raises a CRITICAL alert.
-- ═══════════════════════════════════════════════════════════════════════════

CREATE VIEW v_ledger_mismatch AS
SELECT b.branch_id,
       b.lot_id,
       b.qty_base                        AS stored_qty,
       COALESCE(SUM(m.qty_base), 0)      AS ledger_qty,
       b.qty_base - COALESCE(SUM(m.qty_base), 0) AS drift
  FROM stock_balance b
  LEFT JOIN stock_movement m
         ON m.branch_id = b.branch_id
        AND m.lot_id    = b.lot_id
 GROUP BY b.branch_id, b.lot_id, b.qty_base
HAVING b.qty_base <> COALESCE(SUM(m.qty_base), 0);


-- ═══════════════════════════════════════════════════════════════════════════
--  SEED — the minimum needed for the application to start
-- ═══════════════════════════════════════════════════════════════════════════

INSERT INTO branch (code, name) VALUES ('MAIN', 'Main Shop');

INSERT INTO uom (code, name, decimals) VALUES
    ('PCS',    'Piece',    0),
    ('KG',     'Kilogram', 3),
    ('G',      'Gram',     0),
    ('L',      'Litre',    3),
    ('ML',     'Millilitre', 0),
    ('M',      'Metre',    2),
    ('FT',     'Foot',     2),
    ('BAG',    'Bag',      0),
    ('CARTON', 'Carton',   0),
    ('PACKET', 'Packet',   0),
    ('ROLL',   'Roll',     0),
    ('BUNDLE', 'Bundle',   0),
    ('OLONKA', 'Olonka',   2),   -- the tin measure customers ask for
    ('GALLON', 'Gallon',   2);

INSERT INTO category (code, name, path, kind) VALUES
    ('HARDWARE', 'Hardware',      'hardware', 'GENERAL'),
    ('AGRO',     'Agro-chemical', 'agro',     'AGROCHEMICAL');

-- Declared once on the agro root; inherited by every descendant category.
-- NULL is cast explicitly: without it the first two branches infer TEXT while
-- the third is TEXT[], and UNION cannot reconcile the two.
INSERT INTO category_attribute (category_id, key, label, data_type, enum_values, required, sort_order)
SELECT id, 'epa_registration_no', 'EPA Registration No.', 'TEXT', NULL::TEXT[], TRUE, 1 FROM category WHERE code = 'AGRO'
UNION ALL
SELECT id, 'active_ingredient', 'Active Ingredient', 'TEXT', NULL::TEXT[], TRUE, 2 FROM category WHERE code = 'AGRO'
UNION ALL
SELECT id, 'hazard_band', 'WHO Hazard Band', 'ENUM',
       ARRAY['IA','IB','II','III','U'], TRUE, 3 FROM category WHERE code = 'AGRO';

-- Dormant tax scheme. Registering for VAT later means inserting components and
-- rates and flipping is_active — not a migration against live sales history.
INSERT INTO tax_scheme (code, name, is_active) VALUES ('NONE', 'Not VAT registered', TRUE);

INSERT INTO role (code, name, is_system) VALUES
    ('OWNER',       'Owner',       TRUE),
    ('MANAGER',     'Manager',     TRUE),
    ('STOREKEEPER', 'Storekeeper', TRUE),
    ('CASHIER',     'Cashier',     TRUE),
    ('AUDITOR',     'Auditor',     TRUE);

INSERT INTO permission (code, description) VALUES
    ('SALE_CREATE',        'Create and complete sales'),
    ('SALE_VOID',          'Void a completed sale'),
    ('SALE_DISCOUNT',      'Apply a discount within role allowance'),
    ('SALE_PRICE_OVERRIDE','Override a unit price'),
    ('SALE_RETURN',        'Process a customer return'),
    ('STOCK_RECEIVE',      'Receive goods against a purchase order'),
    ('STOCK_ADJUST',       'Post a manual stock adjustment'),
    ('STOCK_COUNT',        'Open, count and post a stock take'),
    ('STOCK_TRANSFER',     'Transfer stock between branches'),
    ('PRODUCT_MANAGE',     'Create and edit products and categories'),
    ('PRICE_MANAGE',       'Set and change prices'),
    ('COST_VIEW',          'See cost prices and margin'),
    ('CUSTOMER_MANAGE',    'Create and edit customers and credit limits'),
    ('CREDIT_APPROVE',     'Approve a sale above a customer credit limit'),
    ('REPORT_VIEW',        'View reports'),
    ('REPORT_EXPORT',      'Export reports'),
    ('CASH_SESSION_CLOSE', 'Close a cash session and post variance'),
    ('USER_MANAGE',        'Create users and assign roles'),
    ('CONFIG_MANAGE',      'Change system configuration'),
    ('AUDIT_VIEW',         'Read the audit log');

INSERT INTO app_config (key, value, value_type, description) VALUES
    ('currency.code',              'GHS',  'STRING', 'ISO currency code'),
    ('currency.rounding.increment','0.05', 'NUMBER', 'Payable total rounds to this; set 0.01 to disable'),
    ('receipt.width.chars',        '48',   'NUMBER', '48 for 80mm paper, 32 for 58mm'),
    ('agent.port',                 '9110', 'NUMBER', 'Local peripherals agent port'),
    ('expiry.warn.days',           '[90,60,30,7]', 'JSON', 'Expiry alert horizons'),
    ('reorder.safety.factor',      '1.2',  'NUMBER', 'Multiplier in the reorder-point formula'),
    ('reorder.window.days',        '90',   'NUMBER', 'Trailing window for average daily usage'),
    ('cash.variance.threshold',    '20.00','NUMBER', 'Z-report variance above this raises an alert'),
    ('backup.stale.hours',         '24',   'NUMBER', 'Hours without a verified backup before CRITICAL'),
    ('tax.scheme.code',            'NONE', 'STRING', 'Active tax scheme');

INSERT INTO document_sequence (branch_id, doc_type, prefix)
SELECT b.id, t.doc_type, t.prefix
  FROM branch b
 CROSS JOIN (VALUES
        ('RECEIPT',       'RCT'),
        ('INVOICE',       'INV'),
        ('QUOTATION',     'QUO'),
        ('DELIVERY_NOTE', 'DN'),
        ('CREDIT_NOTE',   'CRN'),
        ('PURCHASE_ORDER','PO'),
        ('GOODS_RECEIPT', 'GRN'),
        ('SALE_RETURN',   'RET'),
        ('STOCK_TAKE',    'STK')
 ) AS t(doc_type, prefix)
 WHERE b.code = 'MAIN';
