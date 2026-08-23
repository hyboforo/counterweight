-- ═══════════════════════════════════════════════════════════════════════════
--  V9 — platform configuration and backup monitoring
--
--  Until now `app_config` held rows nothing read, while the same settings lived
--  in `application.yml` where the code actually read them. The database said
--  one thing and the running system did another — a rounding increment changed
--  here would have had no effect, silently.
--
--  This migration makes `app_config` the source of truth for what the *shop*
--  decides. `application.yml` keeps what the *machine* needs to start: the
--  datasource, the JWT secret, the port. That line is not arbitrary — a signing
--  key that could be changed from a settings screen would not be a signing key,
--  and a datasource URL is needed before there is a database to read it from.
-- ═══════════════════════════════════════════════════════════════════════════

-- ── Settings that had no row ───────────────────────────────────────────────
--
-- These were yml-only, so the shop could not change them at all. The values
-- match the defaults the code already used, so applying this changes nothing
-- about how the system behaves today — only about who can change it tomorrow.

INSERT INTO app_config (key, value, value_type, description) VALUES
    ('reorder.lead.time.days', '14',  'NUMBER',
     'Days from placing an order to goods arriving. One figure for the whole shop.'),
    ('dead.stock.days',        '180', 'NUMBER',
     'No sale in this many days makes stock dead'),
    ('backup.enabled',         'true','BOOL',
     'Whether the server takes its own nightly dump'),
    ('backup.directory',       './backups', 'STRING',
     'Where nightly dumps are written. Point this at the SECOND physical disk.');

-- ── The duplicate that could disagree ──────────────────────────────────────
--
-- `tax.scheme.code` mirrored `tax_scheme.is_active`, and two sources of truth
-- for which tax the shop charges is one more than is safe. `is_active` wins:
-- it is what TaxCalculator reads, it is enforced by a unique index (V4), and it
-- cannot drift from the scheme it points at because it *is* the scheme.

DELETE FROM app_config WHERE key = 'tax.scheme.code';

-- ── Descriptions ───────────────────────────────────────────────────────────
--
-- These rows now appear on a settings screen, so the description is what an
-- owner reads before changing something. Worth being plain about consequences.

UPDATE app_config SET description =
    'Payable totals round to this. Set to 0.01 to stop rounding. Changing it changes what customers pay.'
 WHERE key = 'currency.rounding.increment';

UPDATE app_config SET description =
    'Characters per line: 48 for 80mm paper, 32 for 58mm. Wrong value means wrapped receipts.'
 WHERE key = 'receipt.width.chars';

UPDATE app_config SET description =
    'Hours without a VERIFIED backup before a critical alert. Verified means restored and checked, not merely written.'
 WHERE key = 'backup.stale.hours';

-- ── Backup monitoring ──────────────────────────────────────────────────────
--
-- The staleness check reads the most recent verified dump, so it needs to find
-- it without scanning the register.

CREATE INDEX idx_backup_run_verified
    ON backup_run (kind, completed_at DESC)
    WHERE status = 'SUCCESS' AND verified_at IS NOT NULL;

CREATE INDEX idx_backup_run_recent ON backup_run (started_at DESC);

-- The alert §11 lists and V6 could not seed, because nothing recorded backups
-- to alert on. CRITICAL without apology: §14 calls losing this machine the end
-- of the business's records, and an unverified backup is a belief rather than
-- a backup.
INSERT INTO alert_rule (branch_id, rule_type, scope, params, severity, channels, enabled)
VALUES (NULL, 'BACKUP_STALE', '{}', '{"hours":24}', 'CRITICAL', ARRAY['IN_APP'], TRUE);
