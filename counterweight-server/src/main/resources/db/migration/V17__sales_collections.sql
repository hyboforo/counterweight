-- ═══════════════════════════════════════════════════════════════════════════
--  Counterweight V17 — collecting the takings
--
--  Every so often the owner takes away what the shop has taken in. A
--  collection records that: when it happened, who did it, and — per tender —
--  what the sales said should be there against what was actually counted.
--
--  This is not the cash session V13 removed. That measured a drawer nobody
--  counts against a float nobody puts in. A collection is counted by the
--  person taking the money away, which makes it the independent observation
--  the session never had.
--
--  Three things the schema holds, rather than the application:
--
--   * **Collections chain, without gaps or overlaps.** Each one starts exactly
--     where the one before it ended, so every payment falls inside one and
--     only one collection. UNIQUE (previous_id) is what stops two admins
--     recording at the same moment from both claiming the same period.
--
--   * **Nothing is collected in advance.** A collection cannot be dated after
--     the moment it was written down.
--
--   * **They are append-only**, like audit_log. A collection that has been
--     recorded is evidence of what was handed over; a correction is the next
--     collection, never an edit to this one.
-- ═══════════════════════════════════════════════════════════════════════════

-- ── Numbering ──────────────────────────────────────────────────────────────

INSERT INTO document_sequence (branch_id, doc_type, prefix)
SELECT b.id, 'COLLECTION', 'COL'
  FROM branch b
 WHERE NOT EXISTS (
     SELECT 1 FROM document_sequence s
      WHERE s.branch_id = b.id AND s.doc_type = 'COLLECTION'
 );

-- ── Collections ────────────────────────────────────────────────────────────

CREATE TABLE sales_collection (
    id           BIGSERIAL   PRIMARY KEY,
    branch_id    BIGINT      NOT NULL REFERENCES branch(id),
    number       TEXT        NOT NULL,
    -- The collection this one follows. NULL only for a branch's first, which
    -- covers everything taken before it.
    previous_id  BIGINT      UNIQUE REFERENCES sales_collection(id),
    -- Where the period starts: the previous collection's collected_at, copied
    -- so a report never has to walk the chain to say what was covered.
    period_from  TIMESTAMPTZ,
    -- When the money was taken. The period ends here, exclusive: a sale
    -- completed after this belongs to the next collection, even if this one
    -- was written down later.
    collected_at TIMESTAMPTZ NOT NULL,
    collected_by BIGINT      NOT NULL REFERENCES app_user(id),
    note         TEXT,
    recorded_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (branch_id, number),
    CONSTRAINT first_or_chained
        CHECK ((previous_id IS NULL) = (period_from IS NULL)),
    CONSTRAINT period_moves_forward
        CHECK (period_from IS NULL OR collected_at > period_from),
    CONSTRAINT not_collected_in_the_future
        CHECK (collected_at <= recorded_at)
);
-- One first collection per branch; every later one names its predecessor.
CREATE UNIQUE INDEX sales_collection_one_first
    ON sales_collection (branch_id) WHERE previous_id IS NULL;
CREATE INDEX ON sales_collection (branch_id, collected_at DESC);

/*
 * A collection's period starts where its predecessor's ended, in the same
 * branch.
 *
 * UNIQUE (previous_id) stops the chain forking; this stops it leaving a gap
 * or folding back over itself. Without it an application bug that copied the
 * wrong period_from would leave some payments in no collection at all, and
 * the report would say everything was accounted for.
 */
CREATE OR REPLACE FUNCTION assert_collection_follows_previous() RETURNS TRIGGER AS $$
DECLARE
    v_branch BIGINT;
    v_ended  TIMESTAMPTZ;
BEGIN
    IF NEW.previous_id IS NULL THEN
        RETURN NEW;
    END IF;

    SELECT branch_id, collected_at INTO v_branch, v_ended
      FROM sales_collection WHERE id = NEW.previous_id;

    IF v_branch IS DISTINCT FROM NEW.branch_id OR v_ended IS DISTINCT FROM NEW.period_from THEN
        RAISE EXCEPTION 'collection % must start where the previous collection ended', NEW.number
            USING HINT = 'period_from has to equal the previous collection''s collected_at, in the same branch.';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_sales_collection_chain
    BEFORE INSERT ON sales_collection
    FOR EACH ROW EXECUTE FUNCTION assert_collection_follows_previous();

-- ── What was expected and what was counted, per tender ─────────────────────
--
-- ON_ACCOUNT is not here: no money changed hands, so there is nothing to
-- collect. `expected` can be negative — a period with more handed back in
-- refunds than was taken in — and `collected` cannot.

CREATE TABLE sales_collection_line (
    id            BIGSERIAL PRIMARY KEY,
    collection_id BIGINT NOT NULL REFERENCES sales_collection(id),
    method        TEXT   NOT NULL CHECK (method IN ('CASH','MOBILE_MONEY',
                                                    'BANK_TRANSFER','CHEQUE','CARD')),
    expected      NUMERIC(14,2) NOT NULL,
    collected     NUMERIC(14,2) NOT NULL CHECK (collected >= 0),
    UNIQUE (collection_id, method)
);

-- ── Append-only ────────────────────────────────────────────────────────────

CREATE TRIGGER trg_sales_collection_immutable
    BEFORE UPDATE OR DELETE ON sales_collection
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

CREATE TRIGGER trg_sales_collection_line_immutable
    BEFORE UPDATE OR DELETE ON sales_collection_line
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

-- ── Permission ─────────────────────────────────────────────────────────────
--
-- ADMIN only: the owner is who takes the money away.
--
-- Not SYSTEM_ADMIN, which holds nothing commercial (V2). Not MANAGER or
-- SALES_STAFF: they ring up the sales a collection is counted against, and the
-- person whose takings are being checked should not be the one signing them
-- off. The same reasoning as STOCK_COUNT and STOCK_ADJUST (V11).

INSERT INTO permission (code, description) VALUES
    ('SALES_COLLECT', 'Collect the takings and record what was counted');

INSERT INTO role_permission (role_id, permission_code)
SELECT r.id, 'SALES_COLLECT'
  FROM role r
 WHERE r.code = 'ADMIN';

-- What a collection should hold counts voids and counter refunds by when they
-- happened, not by when the sale did. Neither was indexed by time.
CREATE INDEX IF NOT EXISTS sale_voided_at_idx
    ON sale (branch_id, voided_at) WHERE status = 'VOIDED';
CREATE INDEX IF NOT EXISTS sale_return_created_idx
    ON sale_return (branch_id, created_at);
