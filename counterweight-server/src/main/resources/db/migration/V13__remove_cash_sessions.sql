-- ═══════════════════════════════════════════════════════════════════════════
--  Counterweight V13 — there is no cash drawer
--
--  The shop does not have one. The cash-session ceremony in V1 — open with a
--  counted float, close against a counted drawer, sign the variance — was
--  built for a shop that does, and every part of it measures something that
--  does not exist here: a float nobody puts in, a drawer nobody counts, a
--  variance between two numbers that were never independently observed.
--
--  What is worth keeping from it is which till rang a sale up. That was only
--  ever reachable through the session, so it moves onto the sale itself, where
--  it routes a receipt to the right printer and groups the takings report.
--
--  Takings are now derived from sale_payment — the payments are the record,
--  and they always were. See ReportQueries.takings.
-- ═══════════════════════════════════════════════════════════════════════════

-- Which till rang the sale up. Null for anything written up in the back office.
ALTER TABLE sale ADD COLUMN till_code TEXT;

-- Carry across what the sessions knew, so history keeps its till.
UPDATE sale s
   SET till_code = cs.till_code
  FROM cash_session cs
 WHERE cs.id = s.cash_session_id;

CREATE INDEX ON sale (branch_id, till_code, completed_at DESC);

ALTER TABLE sale DROP COLUMN cash_session_id;

DROP TABLE cash_session;

-- ── Permissions ────────────────────────────────────────────────────────────
-- Nothing grants or checks these any more.
DELETE FROM role_permission WHERE permission_code IN ('CASH_SESSION_OPEN', 'CASH_SESSION_CLOSE');
DELETE FROM permission      WHERE code            IN ('CASH_SESSION_OPEN', 'CASH_SESSION_CLOSE');

-- ── Alerting ───────────────────────────────────────────────────────────────
-- The variance rule measured the drawer, and its threshold configured it.
-- alert.rule_id and notification.alert_id both cascade, so the rule is enough.
DELETE FROM alert_rule WHERE rule_type = 'CASH_VARIANCE';
DELETE FROM app_config WHERE key = 'cash.variance.threshold';
