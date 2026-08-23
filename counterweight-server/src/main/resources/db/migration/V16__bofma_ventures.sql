-- ═══════════════════════════════════════════════════════════════════════════
--  V16 — the shop this is being installed for.
--
--  Three things, all of them data rather than schema:
--
--   1. The branch is Bofma Ventures. It is the name on every receipt header,
--      statement and printed document, so it is not decoration.
--
--   2. Three categories to open with — agro chemicals, hardware, garden
--      inputs. The baseline seeded two under names written for the
--      specification rather than for the shop.
--
--   3. **The agro-chemical licence fields are withdrawn** (owner's decision).
--      `epa_registration_no`, `active_ingredient`, `hazard_band` and
--      `requires_buyer_record` were declared on the agro root by V1 and V15 and
--      were the four the new-product form demanded.
--
--  Worth being explicit about what (3) costs, because it is a control and not
--  a form field. `requires_buyer_record` is what §6.3 reads to decide whether
--  selling a restricted product obliges the counter to write the buyer's name
--  and ID down; with no product carrying the flag, that register never fires.
--  `ProductAttributes.flag` reads a missing key as false, so nothing breaks —
--  the obligation simply never arises. The mechanism is untouched and the
--  `restricted_sale_record` table still stands: re-declaring the field on the
--  agro root turns the control back on for every product created afterwards,
--  and needs no code change.
--
--  What is deliberately kept: the agro root stays `AGROCHEMICAL`, so products
--  filed under it are still batch tracked and still carry an expiry date. That
--  is what drives the expiry alerts, and it is the half of the agro handling
--  the shop actually uses day to day.
-- ═══════════════════════════════════════════════════════════════════════════

-- ── 1. The shop ────────────────────────────────────────────────────────────

UPDATE branch SET name = 'Bofma Ventures' WHERE code = 'MAIN';

-- ── 2. Categories ──────────────────────────────────────────────────────────

UPDATE category SET name = 'Agro chemicals' WHERE code = 'AGRO';
UPDATE category SET name = 'Hardware',      sort_order = 1 WHERE code = 'HARDWARE';
UPDATE category SET sort_order = 0 WHERE code = 'AGRO';

INSERT INTO category (code, name, path, kind, sort_order)
VALUES ('GARDEN', 'Garden inputs', 'garden', 'GENERAL', 2)
ON CONFLICT (code) DO NOTHING;

-- ── 3. The withdrawn declarations ──────────────────────────────────────────

DELETE FROM category_attribute
 WHERE key IN ('epa_registration_no', 'active_ingredient', 'hazard_band', 'requires_buyer_record');

/*
 * And the values already written under those keys.
 *
 * `AttributeValidator` refuses a key its category does not declare, so a
 * product still carrying `epa_registration_no` would be refused the next time
 * anybody edited it — a product that cannot be saved because of a field that
 * no longer exists is the worst kind of failure to meet at a counter. Stripping
 * them here means the withdrawal is complete rather than half-applied.
 */
UPDATE product
   SET attributes = attributes
       - 'epa_registration_no' - 'active_ingredient' - 'hazard_band' - 'requires_buyer_record'
 WHERE attributes IS NOT NULL
   AND attributes ?| ARRAY['epa_registration_no', 'active_ingredient', 'hazard_band', 'requires_buyer_record'];
