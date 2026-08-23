-- ═══════════════════════════════════════════════════════════════════════════
--  V4 — pricing configuration
--
--  V1 built the pricing tables and left them empty. That is a soft lock: with
--  no default price list the branch cannot sell anything, and with no discount
--  policy nobody can take a pesewa off a price. This seeds the minimum a shop
--  needs to open, adds the lookup index the sale path leans on, and gives the
--  dormant VAT scheme its structure so registering later is configuration
--  rather than a migration against live sales history.
-- ═══════════════════════════════════════════════════════════════════════════

-- ── Price lists ────────────────────────────────────────────────────────────
--
-- Two, because the shop already prices two ways: walk-in and contractor. TRADE
-- deliberately starts empty — a trade list carries only the lines that differ,
-- and PriceRepository.findEffective falls back to the default for the rest. A
-- copy of the whole catalogue would drift within a month.

INSERT INTO price_list (branch_id, code, name, is_default)
SELECT b.id, 'RETAIL', 'Retail', TRUE FROM branch b WHERE b.code = 'MAIN';

INSERT INTO price_list (branch_id, code, name, is_default)
SELECT b.id, 'TRADE', 'Trade and contractor', FALSE FROM branch b WHERE b.code = 'MAIN';

-- The query behind every line the counter scans: narrow by unit and list, then
-- take the newest window. V1 indexed (product_id, effective_from) which serves
-- price history but not this.
CREATE INDEX idx_price_lookup
    ON price (product_uom_id, price_list_id, effective_from DESC);

-- ── Discount policy ────────────────────────────────────────────────────────
--
-- Starting values, meant to be tuned once the shop sees its own numbers. Two
-- independent limits per role because a percentage alone does not describe the
-- risk: 20% off a fat-margin fitting still makes money, 5% off a bagged
-- agro-chemical does not.
--
-- min_margin_percent is margin on the selling price, matching how the trade
-- quotes it and how DiscountAuthority computes it.
--
-- Roles absent from this table can give no discount at all, which is the
-- correct default for STOREKEEPER and AUDITOR — neither stands at a till.

INSERT INTO discount_policy (role_id, max_percent, min_margin_percent, requires_approval_above)
SELECT r.id, 5.00, 10.00, NULL FROM role r WHERE r.code = 'SALES_STAFF';

INSERT INTO discount_policy (role_id, max_percent, min_margin_percent, requires_approval_above)
SELECT r.id, 20.00, 5.00, 10.00 FROM role r WHERE r.code = 'MANAGER';

-- The owner's ceiling is 100% because writing an item off to zero on the shop
-- floor is a real thing an owner does. The margin floor is what still stops it
-- happening by accident, and the audit entry names who did it.
INSERT INTO discount_policy (role_id, max_percent, min_margin_percent, requires_approval_above)
SELECT r.id, 100.00, 0.00, 25.00 FROM role r WHERE r.code = 'ADMIN';

-- ── Tax ────────────────────────────────────────────────────────────────────
--
-- V1 seeded the active 'NONE' scheme. Exactly one scheme may be active: two
-- would change every price in the shop depending on which one a query returned
-- first, so the invariant is enforced here rather than left to the service that
-- happens to read it.

CREATE UNIQUE INDEX one_active_tax_scheme ON tax_scheme ((TRUE)) WHERE is_active;

-- The Ghana scheme, dormant, with its components but deliberately **no rates**.
--
-- The structure is the part worth capturing now and the part that does not
-- change: NHIL, GETFund and the COVID levy are charged on the taxable value,
-- while VAT is charged on that value plus those levies. Collapsing them into a
-- single combined percentage gives a different and wrong figure, which is why
-- computed_on and sort_order are modelled at all.
--
-- Rates are left unset on purpose. They move with the budget statement, and a
-- number seeded today would be stale before anyone flipped this on — silently,
-- and in the direction of under-collecting. TaxCalculator refuses to price a
-- sale under an active scheme whose components have no rate for the date,
-- so switching to this scheme without setting rates stops the till with
-- "Set the rates before selling" rather than quietly charging nothing.

INSERT INTO tax_scheme (code, name, is_active) VALUES
    ('GHANA_VAT', 'Ghana VAT and levies', FALSE);

INSERT INTO tax_component (tax_scheme_id, code, name, computed_on, is_recoverable, sort_order)
SELECT s.id, c.code, c.name, c.computed_on, c.is_recoverable, c.sort_order
  FROM tax_scheme s
 CROSS JOIN (VALUES
        ('NHIL',    'National Health Insurance Levy', 'TAXABLE_VALUE', FALSE, 10),
        ('GETFUND', 'Ghana Education Trust Fund Levy','TAXABLE_VALUE', FALSE, 20),
        ('COVID',   'COVID-19 Health Recovery Levy',  'TAXABLE_VALUE', FALSE, 30),
        -- Last, and on the running total: VAT is charged on the taxable value
        -- plus the three levies above it.
        ('VAT',     'Value Added Tax',                'RUNNING_TOTAL', TRUE,  40)
 ) AS c(code, name, computed_on, is_recoverable, sort_order)
 WHERE s.code = 'GHANA_VAT';
