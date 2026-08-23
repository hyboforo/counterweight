-- ═══════════════════════════════════════════════════════════════════════════
--  V6 — alerting rules
--
--  V1 built the alerting tables and left them empty, which is a silent failure
--  rather than a loud one: every evaluator runs, finds no enabled rule of its
--  type, and raises nothing. The shop would conclude the feature does not work.
--
--  Rules are rows, not code (§11). A type maps to an evaluator and needs a
--  release; everything else here — severity, channels, on/off — is
--  configuration the owner changes from the rules screen.
-- ═══════════════════════════════════════════════════════════════════════════

-- branch_id NULL means every branch, which is right for all of these: a second
-- shop wants the same catalogue of conditions, not a copy of these rows.
--
-- Channels are IN_APP only. SMS is opt-in per rule and critical-only even then
-- (§11.3) — an owner who gets a text about every slow-moving product stops
-- reading texts from the shop. Switching one on is a row update once a gateway
-- exists.
--
-- Severities follow the catalogue in §11 exactly. They are the difference
-- between a list somebody scans and a list somebody skims, so they are not
-- adjusted here to taste.

INSERT INTO alert_rule (branch_id, rule_type, scope, params, severity, channels, enabled) VALUES
    -- Stock that must not be sold, or cannot be.
    (NULL, 'EXPIRED_STOCK',        '{}', '{}',                        'CRITICAL', ARRAY['IN_APP'], TRUE),
    (NULL, 'OUT_OF_STOCK',         '{}', '{}',                        'CRITICAL', ARRAY['IN_APP'], TRUE),

    -- The ledger disagreeing with itself is a correctness failure, not a
    -- business condition. It should never fire; if it does, somebody looks.
    (NULL, 'LEDGER_MISMATCH',      '{}', '{}',                        'CRITICAL', ARRAY['IN_APP'], TRUE),

    -- Selling below what the stock cost. Usually a shelf price that was never
    -- updated after the supplier raised theirs.
    (NULL, 'SOLD_BELOW_COST',      '{}', '{}',                        'CRITICAL', ARRAY['IN_APP'], TRUE),

    -- Things needing a decision, not a reaction.
    (NULL, 'EXPIRY_APPROACHING',   '{}', '{"horizons":[90,60,30,7]}', 'WARNING',  ARRAY['IN_APP'], TRUE),
    (NULL, 'BELOW_REORDER_POINT',  '{}', '{}',                        'WARNING',  ARRAY['IN_APP'], TRUE),
    (NULL, 'CASH_VARIANCE',        '{}', '{"threshold":"20.00"}',     'WARNING',  ARRAY['IN_APP'], TRUE),
    (NULL, 'CREDIT_LIMIT_REACHED', '{}', '{}',                        'WARNING',  ARRAY['IN_APP'], TRUE),
    (NULL, 'INVOICE_OVERDUE',      '{}', '{}',                        'WARNING',  ARRAY['IN_APP'], TRUE),

    -- Money on a shelf. Weekly, and info — treating it with the urgency of an
    -- expired pesticide is how a notification centre teaches people to skim.
    (NULL, 'DEAD_STOCK',           '{}', '{"days":180}',              'INFO',     ARRAY['IN_APP'], TRUE);

-- One enabled rule per type per branch. Two would mean an evaluator picking
-- whichever the query returned first, and the severity of an alert would come
-- down to an ORDER BY nobody wrote.
CREATE UNIQUE INDEX one_enabled_rule_per_type_per_branch
    ON alert_rule (rule_type, COALESCE(branch_id, 0)) WHERE enabled;

-- The morning briefing reads open alerts by branch and severity; V1 indexed
-- that. This one serves "what was raised overnight", which is the other half of
-- the slip and scans by time.
CREATE INDEX idx_alert_raised_at ON alert (branch_id, raised_at DESC);
