-- ═══════════════════════════════════════════════════════════════════════════
--  Ledger conformance suite
--
--  Proves the runtime properties that V1__counterweight_baseline.sql asserts
--  but that no parser can confirm. Everything downstream — the API, the till,
--  the agent — assumes these hold.
--
--  Run against a database with the baseline applied:
--
--    psql -U counterweight -d counterweight -f ledger_conformance.sql
--
--  Exits non-zero if any check fails. Rolls back everything it does, so it is
--  safe to run against a database that already has data.
--
--  NOT covered here: true concurrent oversell (two simultaneous transactions
--  racing for the last unit). That needs a two-session harness — the
--  Testcontainers test in Phase 0. This file proves the constraint fires;
--  the harness proves it fires under a race.
-- ═══════════════════════════════════════════════════════════════════════════

\set ON_ERROR_STOP on
\timing off
\pset pager off

BEGIN;

CREATE TEMP TABLE _result (
    seq    SERIAL,
    name   TEXT,
    passed BOOLEAN,
    detail TEXT
) ON COMMIT DROP;

CREATE OR REPLACE FUNCTION _check(p_name TEXT, p_cond BOOLEAN, p_detail TEXT DEFAULT '')
RETURNS VOID AS $fn$
BEGIN
    INSERT INTO _result(name, passed, detail) VALUES (p_name, p_cond, p_detail);
END $fn$ LANGUAGE plpgsql;


-- ── Fixtures ──────────────────────────────────────────────────────────────
-- Uses the MAIN branch and the UOM/category rows the baseline seeds.

CREATE TEMP TABLE _fx (k TEXT PRIMARY KEY, v BIGINT) ON COMMIT DROP;

DO $seed$
DECLARE
    v_branch BIGINT;
    v_user   BIGINT;
    v_cat    BIGINT;
    v_uom_kg BIGINT;
    v_uom_bag BIGINT;
    v_product BIGINT;
    v_lot_a  BIGINT;
    v_lot_b  BIGINT;
BEGIN
    SELECT id INTO v_branch FROM branch WHERE code = 'MAIN';
    SELECT id INTO v_cat    FROM category WHERE code = 'HARDWARE';
    SELECT id INTO v_uom_kg FROM uom WHERE code = 'KG';
    SELECT id INTO v_uom_bag FROM uom WHERE code = 'BAG';

    INSERT INTO app_user (branch_id, username, full_name, password_hash)
    VALUES (v_branch, '_conformance_user', 'Conformance Test', 'not-a-real-hash')
    RETURNING id INTO v_user;

    INSERT INTO product (branch_id, category_id, sku, name)
    VALUES (v_branch, v_cat, '_TEST_NAILS', 'Roofing nails (test fixture)')
    RETURNING id INTO v_product;

    -- Base unit KG, plus a 25 kg bag. Mirrors the worked example in §6.2.
    INSERT INTO product_uom (product_id, uom_id, factor, is_base)
    VALUES (v_product, v_uom_kg, 1, TRUE);
    INSERT INTO product_uom (product_id, uom_id, factor, is_base)
    VALUES (v_product, v_uom_bag, 25, FALSE);

    -- Two lots with different expiries, to exercise FEFO ordering.
    INSERT INTO stock_lot (branch_id, product_id, lot_code, expires_on, unit_cost)
    VALUES (v_branch, v_product, 'LOT-A', CURRENT_DATE + 30, 12.5000)
    RETURNING id INTO v_lot_a;
    INSERT INTO stock_lot (branch_id, product_id, lot_code, expires_on, unit_cost)
    VALUES (v_branch, v_product, 'LOT-B', CURRENT_DATE + 400, 13.0000)
    RETURNING id INTO v_lot_b;

    INSERT INTO _fx VALUES
        ('branch', v_branch), ('user', v_user), ('product', v_product),
        ('lot_a', v_lot_a), ('lot_b', v_lot_b), ('cat', v_cat);
END $seed$;


-- ── 1. Balance accumulates through the trigger ────────────────────────────

DO $t$
DECLARE v_qty NUMERIC;
BEGIN
    INSERT INTO stock_movement
        (branch_id, lot_id, movement_type, qty_base, unit_cost, source_type, created_by)
    SELECT (SELECT v FROM _fx WHERE k='branch'), (SELECT v FROM _fx WHERE k='lot_a'),
           'RECEIPT', 100, 12.5, 'GRN', (SELECT v FROM _fx WHERE k='user');

    SELECT qty_base INTO v_qty FROM stock_balance
     WHERE lot_id = (SELECT v FROM _fx WHERE k='lot_a');

    PERFORM _check('balance row created by trigger on first movement',
                   v_qty = 100, format('got %s, expected 100', v_qty));
END $t$;


-- ── 2. Balance decrements, and decimal quantity survives ──────────────────
-- 3.5 kg off the roll. If qty_base were INTEGER this is where it would break.

DO $t$
DECLARE v_qty NUMERIC;
BEGIN
    INSERT INTO stock_movement
        (branch_id, lot_id, movement_type, qty_base, unit_cost, source_type, created_by)
    SELECT (SELECT v FROM _fx WHERE k='branch'), (SELECT v FROM _fx WHERE k='lot_a'),
           'SALE_ISSUE', -3.5, 12.5, 'SALE', (SELECT v FROM _fx WHERE k='user');

    SELECT qty_base INTO v_qty FROM stock_balance
     WHERE lot_id = (SELECT v FROM _fx WHERE k='lot_a');

    PERFORM _check('decimal quantity accumulates exactly (100 - 3.5)',
                   v_qty = 96.5, format('got %s, expected 96.5', v_qty));
END $t$;


-- ── 3. Oversell is blocked by the CHECK constraint ────────────────────────
-- The heart of the design. This is what removes the need for application
-- locking on the last unit of stock.

DO $t$
DECLARE ok BOOLEAN := FALSE; v_qty NUMERIC;
BEGIN
    BEGIN
        INSERT INTO stock_movement
            (branch_id, lot_id, movement_type, qty_base, unit_cost, source_type, created_by)
        SELECT (SELECT v FROM _fx WHERE k='branch'), (SELECT v FROM _fx WHERE k='lot_a'),
               'SALE_ISSUE', -1000, 12.5, 'SALE', (SELECT v FROM _fx WHERE k='user');
    EXCEPTION WHEN check_violation THEN
        ok := TRUE;
    END;

    PERFORM _check('oversell rejected by CHECK (qty_base >= 0)', ok,
                   'a -1000 issue against 96.5 on hand must raise check_violation');

    SELECT qty_base INTO v_qty FROM stock_balance
     WHERE lot_id = (SELECT v FROM _fx WHERE k='lot_a');
    PERFORM _check('balance unchanged after rejected oversell',
                   v_qty = 96.5, format('got %s, expected 96.5', v_qty));
END $t$;


-- ── 4. Selling exactly to zero is allowed ─────────────────────────────────
-- Off-by-one guard: the constraint is >= 0, not > 0.

DO $t$
DECLARE v_qty NUMERIC; ok BOOLEAN := TRUE;
BEGIN
    BEGIN
        INSERT INTO stock_movement
            (branch_id, lot_id, movement_type, qty_base, unit_cost, source_type, created_by)
        SELECT (SELECT v FROM _fx WHERE k='branch'), (SELECT v FROM _fx WHERE k='lot_b'),
               'RECEIPT', 10, 13.0, 'GRN', (SELECT v FROM _fx WHERE k='user');
        INSERT INTO stock_movement
            (branch_id, lot_id, movement_type, qty_base, unit_cost, source_type, created_by)
        SELECT (SELECT v FROM _fx WHERE k='branch'), (SELECT v FROM _fx WHERE k='lot_b'),
               'SALE_ISSUE', -10, 13.0, 'SALE', (SELECT v FROM _fx WHERE k='user');
    EXCEPTION WHEN check_violation THEN
        ok := FALSE;
    END;

    SELECT qty_base INTO v_qty FROM stock_balance
     WHERE lot_id = (SELECT v FROM _fx WHERE k='lot_b');
    PERFORM _check('selling down to exactly zero is permitted', ok AND v_qty = 0,
                   format('ok=%s qty=%s', ok, v_qty));
END $t$;


-- ── 5. stock_movement is append-only ──────────────────────────────────────

DO $t$
DECLARE upd_blocked BOOLEAN := FALSE; del_blocked BOOLEAN := FALSE;
BEGIN
    BEGIN
        UPDATE stock_movement SET qty_base = 1
         WHERE lot_id = (SELECT v FROM _fx WHERE k='lot_a');
    EXCEPTION WHEN OTHERS THEN upd_blocked := TRUE;
    END;

    BEGIN
        DELETE FROM stock_movement
         WHERE lot_id = (SELECT v FROM _fx WHERE k='lot_a');
    EXCEPTION WHEN OTHERS THEN del_blocked := TRUE;
    END;

    PERFORM _check('stock_movement rejects UPDATE', upd_blocked);
    PERFORM _check('stock_movement rejects DELETE', del_blocked);
END $t$;


-- ── 6. audit_log is append-only ───────────────────────────────────────────

DO $t$
DECLARE ins_ok BOOLEAN := TRUE; upd_blocked BOOLEAN := FALSE;
BEGIN
    INSERT INTO audit_log (branch_id, actor_id, action, subject_type, subject_id)
    SELECT (SELECT v FROM _fx WHERE k='branch'), (SELECT v FROM _fx WHERE k='user'),
           'TEST', 'product', (SELECT v FROM _fx WHERE k='product');

    BEGIN
        UPDATE audit_log SET action = 'TAMPERED' WHERE action = 'TEST';
    EXCEPTION WHEN OTHERS THEN upd_blocked := TRUE;
    END;

    PERFORM _check('audit_log accepts INSERT', ins_ok);
    PERFORM _check('audit_log rejects UPDATE', upd_blocked);
END $t$;


-- ── 7. Reconciliation view is clean when the ledger is consistent ─────────
-- The nightly tripwire must not produce false positives.

DO $t$
DECLARE v_drift INT;
BEGIN
    SELECT count(*) INTO v_drift FROM v_ledger_mismatch;
    PERFORM _check('v_ledger_mismatch empty on a consistent ledger',
                   v_drift = 0, format('%s lot(s) reported drift', v_drift));
END $t$;


-- ── 8. Reconciliation view actually detects drift ─────────────────────────
-- Prove the tripwire is not simply always-empty. Disable the trigger, insert a
-- movement the balance never sees, and confirm the view catches it.

DO $t$
DECLARE v_drift INT;
BEGIN
    ALTER TABLE stock_movement DISABLE TRIGGER trg_apply_stock_movement;
    INSERT INTO stock_movement
        (branch_id, lot_id, movement_type, qty_base, unit_cost, source_type,
         reason_code, created_by)
    SELECT (SELECT v FROM _fx WHERE k='branch'), (SELECT v FROM _fx WHERE k='lot_a'),
           'ADJUST_IN', 7, 12.5, 'ADJUSTMENT', 'CONFORMANCE_DRIFT_PROBE',
           (SELECT v FROM _fx WHERE k='user');
    ALTER TABLE stock_movement ENABLE TRIGGER trg_apply_stock_movement;

    SELECT count(*) INTO v_drift FROM v_ledger_mismatch;
    PERFORM _check('v_ledger_mismatch detects a bypassed trigger',
                   v_drift = 1, format('expected 1 drifting lot, got %s', v_drift));
END $t$;


-- ── 9. Gapless document numbering ─────────────────────────────────────────

DO $t$
DECLARE a TEXT; b TEXT;
BEGIN
    SELECT next_document_number((SELECT v FROM _fx WHERE k='branch'), 'RECEIPT') INTO a;
    SELECT next_document_number((SELECT v FROM _fx WHERE k='branch'), 'RECEIPT') INTO b;
    PERFORM _check('document numbers are sequential and zero-padded',
                   a = 'RCT-000001' AND b = 'RCT-000002', format('got %s then %s', a, b));
END $t$;

DO $t$
DECLARE ok BOOLEAN := FALSE;
BEGIN
    BEGIN
        PERFORM next_document_number((SELECT v FROM _fx WHERE k='branch'), 'NO_SUCH_TYPE');
    EXCEPTION WHEN OTHERS THEN ok := TRUE;
    END;
    PERFORM _check('unknown document type raises rather than returning junk', ok);
END $t$;


-- ── 10. Catalog invariants ────────────────────────────────────────────────

DO $t$
DECLARE ok BOOLEAN := FALSE;
BEGIN
    BEGIN
        -- factor must be 1 for a base unit (base_factor_is_one), so this
        -- probe reaches the partial unique index rather than tripping the
        -- CHECK first.
        INSERT INTO product_uom (product_id, uom_id, factor, is_base)
        SELECT (SELECT v FROM _fx WHERE k='product'),
               (SELECT id FROM uom WHERE code = 'G'), 1, TRUE;
    EXCEPTION WHEN unique_violation THEN ok := TRUE;
    END;
    PERFORM _check('a product cannot have two base units', ok);
END $t$;

DO $t$
DECLARE ok BOOLEAN := FALSE;
BEGIN
    BEGIN
        INSERT INTO product (branch_id, category_id, sku, name, is_batch_tracked, picking_rule)
        SELECT (SELECT v FROM _fx WHERE k='branch'), (SELECT v FROM _fx WHERE k='cat'),
               '_TEST_BAD', 'Batch-tracked but FIFO', TRUE, 'FIFO';
    EXCEPTION WHEN check_violation THEN ok := TRUE;
    END;
    PERFORM _check('batch-tracked product cannot be picked FIFO', ok);
END $t$;

DO $t$
DECLARE ok BOOLEAN := FALSE;
BEGIN
    BEGIN
        INSERT INTO stock_movement
            (branch_id, lot_id, movement_type, qty_base, unit_cost, source_type, created_by)
        SELECT (SELECT v FROM _fx WHERE k='branch'), (SELECT v FROM _fx WHERE k='lot_a'),
               'ADJUST_OUT', -1, 12.5, 'ADJUSTMENT', (SELECT v FROM _fx WHERE k='user');
    EXCEPTION WHEN check_violation THEN ok := TRUE;
    END;
    PERFORM _check('adjustment without a reason code is rejected', ok);
END $t$;


-- ── 11. Alert deduplication ───────────────────────────────────────────────
-- The partial unique index is the whole anti-fatigue mechanism.

DO $t$
DECLARE v_rule BIGINT; ok BOOLEAN := FALSE; reopened BOOLEAN := TRUE;
BEGIN
    INSERT INTO alert_rule (branch_id, rule_type, severity)
    SELECT (SELECT v FROM _fx WHERE k='branch'), 'LOW_STOCK', 'WARNING'
    RETURNING id INTO v_rule;

    INSERT INTO alert (rule_id, branch_id, dedupe_key, severity, title, body)
    SELECT v_rule, (SELECT v FROM _fx WHERE k='branch'), '_test:dedupe', 'WARNING', 't', 'b';

    BEGIN
        INSERT INTO alert (rule_id, branch_id, dedupe_key, severity, title, body)
        SELECT v_rule, (SELECT v FROM _fx WHERE k='branch'), '_test:dedupe', 'WARNING', 't', 'b';
    EXCEPTION WHEN unique_violation THEN ok := TRUE;
    END;
    PERFORM _check('a second OPEN alert for the same condition is rejected', ok);

    -- Once resolved, the same condition may legitimately raise again.
    UPDATE alert SET resolved_at = now() WHERE dedupe_key = '_test:dedupe';
    BEGIN
        INSERT INTO alert (rule_id, branch_id, dedupe_key, severity, title, body)
        SELECT v_rule, (SELECT v FROM _fx WHERE k='branch'), '_test:dedupe', 'WARNING', 't', 'b';
    EXCEPTION WHEN unique_violation THEN reopened := FALSE;
    END;
    PERFORM _check('the same condition may re-raise after being resolved', reopened);
END $t$;


-- ── 12. FEFO ordering returns nearest expiry first ────────────────────────

DO $t$
DECLARE v_first TEXT;
BEGIN
    SELECT lot_code INTO v_first
      FROM stock_lot
     WHERE product_id = (SELECT v FROM _fx WHERE k='product')
       AND status = 'AVAILABLE'
     ORDER BY expires_on NULLS LAST
     LIMIT 1;
    PERFORM _check('FEFO picks the nearest expiry first',
                   v_first = 'LOT-A', format('got %s, expected LOT-A', v_first));
END $t$;


-- ── Report ────────────────────────────────────────────────────────────────

\echo ''
\echo '─────────────────────────────────────────────────────────────'
\echo ' LEDGER CONFORMANCE'
\echo '─────────────────────────────────────────────────────────────'

SELECT lpad(seq::text, 2) AS "#",
       CASE WHEN passed THEN 'PASS' ELSE 'FAIL' END AS result,
       name,
       CASE WHEN passed THEN '' ELSE detail END AS detail
  FROM _result
 ORDER BY seq;

SELECT count(*) FILTER (WHERE passed)       AS passed,
       count(*) FILTER (WHERE NOT passed)   AS failed,
       count(*)                             AS total
  FROM _result;

-- Fail the script (and the exit code) if anything did not pass.
DO $final$
DECLARE v_failed INT;
BEGIN
    SELECT count(*) INTO v_failed FROM _result WHERE NOT passed;
    IF v_failed > 0 THEN
        RAISE EXCEPTION '% conformance check(s) FAILED', v_failed;
    END IF;
    RAISE NOTICE 'All conformance checks passed.';
END $final$;

-- Nothing is kept. Safe to run against a populated database.
ROLLBACK;
