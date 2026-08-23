-- ═══════════════════════════════════════════════════════════════════════════
--  Counterweight V2 — roles, permissions and authentication hardening
--
--  Design stance: separation of duties.
--
--  SYSTEM_ADMIN is the technical custodian — it installs, configures, backs up
--  and manages accounts. It deliberately holds NO commercial permission: it
--  cannot sell, cannot see cost or margin, cannot change a price, cannot adjust
--  stock. ADMIN is the shop owner and holds the commercial authority instead.
--
--  This matters because the audit log is the shop's only defence against
--  insider loss, and an account that can both administer the system and
--  transact on it can cover its own tracks. Neither role can delete audit
--  history — that is enforced by trigger in V1, not by permission.
-- ═══════════════════════════════════════════════════════════════════════════

-- ── Authentication hardening on the user record ────────────────────────────

ALTER TABLE app_user
    ADD COLUMN email                TEXT,
    ADD COLUMN failed_login_count   SMALLINT    NOT NULL DEFAULT 0,
    ADD COLUMN locked_until         TIMESTAMPTZ,
    ADD COLUMN password_changed_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Accounts created by an admin start with a temporary password the user
    -- must replace on first login; until they do, only the change-password
    -- endpoint is reachable.
    ADD COLUMN must_change_password BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN pin_failed_count     SMALLINT    NOT NULL DEFAULT 0,
    ADD COLUMN pin_locked_until     TIMESTAMPTZ,
    ADD COLUMN created_by           BIGINT      REFERENCES app_user(id),
    ADD COLUMN deactivated_at       TIMESTAMPTZ,
    ADD COLUMN deactivated_by       BIGINT      REFERENCES app_user(id);

CREATE UNIQUE INDEX ON app_user (lower(email)) WHERE email IS NOT NULL;
-- Usernames are compared case-insensitively; 'Ama' and 'ama' must not be two
-- accounts, or an attacker can shadow a real operator's name.
CREATE UNIQUE INDEX app_user_username_ci ON app_user (lower(username));

COMMENT ON COLUMN app_user.override_pin_hash IS
    'Argon2id hash of the supervisor override PIN. Never the PIN itself.';

-- ── Refresh tokens ─────────────────────────────────────────────────────────
--
-- Access tokens are short-lived and stateless; refresh tokens are stateful so
-- that a compromised session can actually be killed. There is no Redis in this
-- deployment (§3: one machine, no extra moving parts), so they live here.
--
-- Only the SHA-256 of the token is stored: a stolen database backup must not
-- yield usable sessions.

CREATE TABLE refresh_token (
    id           BIGSERIAL   PRIMARY KEY,
    user_id      BIGINT      NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    token_hash   TEXT        NOT NULL UNIQUE,
    issued_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ NOT NULL,
    revoked_at   TIMESTAMPTZ,
    revoked_reason TEXT,
    -- Set when this token is rotated. If a token that has already been
    -- replaced is presented again, the refresh was replayed — almost always
    -- theft — and the whole family is revoked. See AuthService.
    replaced_by  BIGINT      REFERENCES refresh_token(id),
    client_label TEXT,
    ip_address   INET,
    CONSTRAINT expiry_after_issue CHECK (expires_at > issued_at)
);
CREATE INDEX ON refresh_token (user_id) WHERE revoked_at IS NULL;
CREATE INDEX ON refresh_token (expires_at) WHERE revoked_at IS NULL;

-- ── Login attempts ─────────────────────────────────────────────────────────
--
-- Feeds both rate limiting and forensics. Recorded for failures AND successes:
-- "who logged in at the till at 21:40" is exactly the question asked after a
-- cash discrepancy.

CREATE TABLE login_attempt (
    id           BIGSERIAL   PRIMARY KEY,
    username     TEXT        NOT NULL,
    user_id      BIGINT      REFERENCES app_user(id) ON DELETE SET NULL,
    succeeded    BOOLEAN     NOT NULL,
    failure_code TEXT,
    ip_address   INET,
    client_label TEXT,
    attempted_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ON login_attempt (lower(username), attempted_at DESC);
CREATE INDEX ON login_attempt (ip_address, attempted_at DESC);
CREATE INDEX ON login_attempt (attempted_at DESC);

-- ── Permissions ────────────────────────────────────────────────────────────

INSERT INTO permission (code, description) VALUES
    ('SALE_HOLD',           'Hold and recall a sale'),
    ('CASH_SESSION_OPEN',   'Open a cash session'),
    ('ROLE_MANAGE',         'Create roles and change their permissions'),
    ('ROLE_ASSIGN',         'Assign existing roles to users'),
    ('SYSTEM_ADMIN_GRANT',  'Grant or revoke the SYSTEM_ADMIN role'),
    ('BACKUP_MANAGE',       'Run, verify and restore backups'),
    ('SESSION_REVOKE',      'Revoke another user''s active sessions'),
    ('PASSWORD_RESET',      'Reset another user''s password'),
    ('ALERT_MANAGE',        'Create, edit and snooze alert rules'),
    ('SUPPLIER_MANAGE',     'Create and edit suppliers'),
    ('PURCHASE_ORDER_MANAGE','Raise and amend purchase orders'),
    ('PRICE_VIEW',          'See selling prices (not cost)');

-- ── Roles ──────────────────────────────────────────────────────────────────
--
-- CASHIER is renamed to SALES_STAFF: the till is only part of the job, and the
-- old name did not describe the role the shop actually staffs.

UPDATE role SET code = 'SALES_STAFF', name = 'Sales Staff' WHERE code = 'CASHIER';
UPDATE role SET code = 'ADMIN',       name = 'Administrator' WHERE code = 'OWNER';

INSERT INTO role (code, name, is_system) VALUES
    ('SYSTEM_ADMIN', 'System Administrator', TRUE);

-- ── Grants ─────────────────────────────────────────────────────────────────

-- SYSTEM_ADMIN: the system, and nothing commercial. No SALE_*, no COST_VIEW,
-- no PRICE_MANAGE, no STOCK_*. Deliberate — see the header.
INSERT INTO role_permission (role_id, permission_code)
SELECT r.id, p.code FROM role r, permission p
 WHERE r.code = 'SYSTEM_ADMIN'
   AND p.code IN ('USER_MANAGE','ROLE_MANAGE','ROLE_ASSIGN','SYSTEM_ADMIN_GRANT',
                  'CONFIG_MANAGE','BACKUP_MANAGE','SESSION_REVOKE','PASSWORD_RESET',
                  'AUDIT_VIEW');

-- ADMIN: the shop owner. Full commercial authority, and may staff the shop —
-- but cannot grant SYSTEM_ADMIN or touch system configuration.
INSERT INTO role_permission (role_id, permission_code)
SELECT r.id, p.code FROM role r, permission p
 WHERE r.code = 'ADMIN'
   AND p.code IN ('SALE_CREATE','SALE_VOID','SALE_DISCOUNT','SALE_PRICE_OVERRIDE',
                  'SALE_RETURN','SALE_HOLD','STOCK_RECEIVE','STOCK_ADJUST',
                  'STOCK_COUNT','STOCK_TRANSFER','PRODUCT_MANAGE','PRICE_MANAGE',
                  'PRICE_VIEW','COST_VIEW','CUSTOMER_MANAGE','CREDIT_APPROVE',
                  'SUPPLIER_MANAGE','PURCHASE_ORDER_MANAGE','REPORT_VIEW',
                  'REPORT_EXPORT','CASH_SESSION_OPEN','CASH_SESSION_CLOSE',
                  'USER_MANAGE','ROLE_ASSIGN','PASSWORD_RESET','ALERT_MANAGE',
                  'AUDIT_VIEW');

INSERT INTO role_permission (role_id, permission_code)
SELECT r.id, p.code FROM role r, permission p
 WHERE r.code = 'MANAGER'
   AND p.code IN ('SALE_CREATE','SALE_VOID','SALE_DISCOUNT','SALE_PRICE_OVERRIDE',
                  'SALE_RETURN','SALE_HOLD','STOCK_RECEIVE','STOCK_ADJUST',
                  'STOCK_COUNT','PRODUCT_MANAGE','PRICE_MANAGE','PRICE_VIEW',
                  'COST_VIEW','CUSTOMER_MANAGE','CREDIT_APPROVE','SUPPLIER_MANAGE',
                  'PURCHASE_ORDER_MANAGE','REPORT_VIEW','REPORT_EXPORT',
                  'CASH_SESSION_OPEN','CASH_SESSION_CLOSE','ALERT_MANAGE');

-- SALES_STAFF: sells. No cost, no margin, no stock adjustment, no voids
-- after close — those need a supervisor override, which is a separate check.
INSERT INTO role_permission (role_id, permission_code)
SELECT r.id, p.code FROM role r, permission p
 WHERE r.code = 'SALES_STAFF'
   AND p.code IN ('SALE_CREATE','SALE_HOLD','SALE_DISCOUNT','SALE_RETURN',
                  'PRICE_VIEW','CUSTOMER_MANAGE','CASH_SESSION_OPEN');

INSERT INTO role_permission (role_id, permission_code)
SELECT r.id, p.code FROM role r, permission p
 WHERE r.code = 'STOREKEEPER'
   AND p.code IN ('STOCK_RECEIVE','STOCK_COUNT','STOCK_TRANSFER','PRODUCT_MANAGE',
                  'SUPPLIER_MANAGE','PURCHASE_ORDER_MANAGE','PRICE_VIEW');

-- AUDITOR reads, and only reads. Enforced again at the service layer.
INSERT INTO role_permission (role_id, permission_code)
SELECT r.id, p.code FROM role r, permission p
 WHERE r.code = 'AUDITOR'
   AND p.code IN ('REPORT_VIEW','REPORT_EXPORT','AUDIT_VIEW','COST_VIEW','PRICE_VIEW');

-- ── Invariant: the shop can never be locked out of its own system ──────────
--
-- Deactivating or unassigning the last SYSTEM_ADMIN would leave nobody able to
-- create users or restore a backup. Enforced in the database because the
-- application is not the only thing that writes here.

CREATE OR REPLACE FUNCTION assert_system_admin_remains() RETURNS TRIGGER AS $$
DECLARE
    v_remaining INT;
BEGIN
    SELECT count(*) INTO v_remaining
      FROM user_role ur
      JOIN role r     ON r.id = ur.role_id
      JOIN app_user u ON u.id = ur.user_id
     WHERE r.code = 'SYSTEM_ADMIN'
       AND u.is_active;

    IF v_remaining = 0 THEN
        RAISE EXCEPTION 'at least one active SYSTEM_ADMIN must remain'
            USING HINT = 'Grant SYSTEM_ADMIN to another account before removing this one.';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_user_role_keeps_admin
    AFTER DELETE ON user_role
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_system_admin_remains();

CREATE CONSTRAINT TRIGGER trg_app_user_keeps_admin
    AFTER UPDATE OF is_active ON app_user
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (OLD.is_active AND NOT NEW.is_active)
    EXECUTE FUNCTION assert_system_admin_remains();

-- ── Password and lockout policy ────────────────────────────────────────────

INSERT INTO app_config (key, value, value_type, description) VALUES
    ('auth.password.min-length',        '10',  'NUMBER', 'Minimum password length'),
    ('auth.password.max-age-days',      '0',   'NUMBER', 'Force rotation after N days; 0 disables'),
    ('auth.lockout.max-attempts',       '5',   'NUMBER', 'Failed logins before the account locks'),
    ('auth.lockout.minutes',            '15',  'NUMBER', 'How long an account stays locked'),
    ('auth.pin.max-attempts',           '3',   'NUMBER', 'Failed override PINs before the PIN locks'),
    ('auth.pin.lockout-minutes',        '30',  'NUMBER', 'How long an override PIN stays locked'),
    ('auth.access-token.minutes',       '15',  'NUMBER', 'Access token lifetime'),
    ('auth.refresh-token.days',         '7',   'NUMBER', 'Refresh token lifetime'),
    ('auth.rate-limit.per-ip-per-min',  '10',  'NUMBER', 'Login attempts allowed per IP per minute');
