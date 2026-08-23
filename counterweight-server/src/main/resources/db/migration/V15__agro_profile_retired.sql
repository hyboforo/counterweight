-- ═══════════════════════════════════════════════════════════════════════════
--  Counterweight V15 — the regulated agro fields have one home
--
--  V1 declared the same facts twice: as typed columns on `agro_profile`, and
--  as seeded `category_attribute` rows landing in `product.attributes`. Only
--  the second was ever written — there is no field for a profile on
--  `CreateProductRequest`, no service that saves one, nothing. The table has
--  been empty since it was created.
--
--  That was not merely untidy. `SaleCompletionService.requireBuyerRecords`
--  decided whether a buyer had to be written down by reading
--  `agro_profile.requires_buyer_record`, so the flag was always false and the
--  §6.3 licence control could never fire. Both tests covering it built a
--  profile through the repository, which no production path does — green in
--  CI, dead at the counter. The same shape as a permission that is granted and
--  never read: nobody re-reads a control they believe exists.
--
--  §6.1 already settled which mechanism wins. Category attributes are what
--  make a new product category data entry rather than a migration, they
--  inherit down the tree, and `AttributeValidator` type-checks every value on
--  the way in. So the profile's declarations move there and the table goes.
-- ═══════════════════════════════════════════════════════════════════════════

-- ── The profile's fields, as declarations on the agro root ─────────────────
--
-- `requires_buyer_record` is required, like the three fields V1 declared here.
-- A licence condition should be answered deliberately for every regulated
-- product; leaving it optional would default it to false, which is exactly the
-- silence this migration exists to end.
--
-- The rest are optional and carry no consumer yet. They are declared rather
-- than dropped because they are what §6.3 asks a shop to hold about a
-- regulated product, and an attribute row costs nothing until it is filled in.

INSERT INTO category_attribute (category_id, key, label, data_type, enum_values, unit, required, sort_order)
SELECT c.id, v.key, v.label, v.data_type, v.enum_values, v.unit, v.required, v.sort_order
  FROM category c
 CROSS JOIN (VALUES
        ('requires_buyer_record', 'Buyer register required', 'BOOL',
         NULL::TEXT[], NULL::TEXT, TRUE,  4),
        ('concentration',         'Concentration',           'TEXT',
         NULL::TEXT[], NULL::TEXT, FALSE, 5),
        ('formulation',           'Formulation',             'ENUM',
         ARRAY['EC','SC','WP','SL','GR'], NULL::TEXT, FALSE, 6),
        ('reentry_interval_hours','Re-entry interval',       'NUMBER',
         NULL::TEXT[], 'hours',    FALSE, 7),
        ('ppe_notes',             'PPE required',            'TEXT',
         NULL::TEXT[], NULL::TEXT, FALSE, 8),
        ('storage_notes',         'Storage notes',           'TEXT',
         NULL::TEXT[], NULL::TEXT, FALSE, 9)
 ) AS v(key, label, data_type, enum_values, unit, required, sort_order)
 WHERE c.code = 'AGRO';

-- ── Every regulated product now carries an explicit answer ─────────────────
--
-- Without this, a product created before today would have no key at all, and
-- the read would have to decide what silence means. Both readings are bad: a
-- default of false is the bug being fixed, and a default of true stops the
-- shop selling anything it already stocks. Backfilling removes the question —
-- after this, a missing key means "not a regulated product", nothing else.
--
-- `@>` is ltree for "is an ancestor of, or equal to", matching the inheritance
-- in CategoryAttributeRepository.findInheritedForCategory: a product filed
-- under any descendant of `agro` is a regulated product.

UPDATE product p
   SET attributes = p.attributes || '{"requires_buyer_record": false}'::jsonb
  FROM category c
 WHERE c.id = p.category_id
   AND (SELECT path FROM category WHERE code = 'AGRO') @> c.path
   AND NOT (p.attributes ? 'requires_buyer_record');

-- ── The dead table ─────────────────────────────────────────────────────────
-- Empty in every database that has ever run, so there is nothing to carry
-- across. `restricted_sale_record` is untouched: the register itself was never
-- the problem, only what decided a row should be written to it.

DROP TABLE agro_profile;
