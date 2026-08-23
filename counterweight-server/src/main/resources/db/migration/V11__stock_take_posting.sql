-- ═══════════════════════════════════════════════════════════════════════════
--  V11 — Stock takes: posting authority and the indexes the sheet needs
-- ═══════════════════════════════════════════════════════════════════════════

/*
 * Posting a count is gated on STOCK_ADJUST, not STOCK_COUNT.
 *
 * V1 described STOCK_COUNT as "Open, count and post a stock take", which would
 * have let the storekeeper who counts a shelf short also sign the shortage
 * away — the one control a stock take exists to provide, removed. The role
 * grants already draw the line in the right place: STOREKEEPER holds
 * STOCK_COUNT and not STOCK_ADJUST, so counting stays with the storekeeper and
 * disposing of the variance needs a manager or the owner.
 *
 * Nothing is regranted here. Only the description, which no longer matches what
 * the permission does.
 */
UPDATE permission
   SET description = 'Open and count a stock take'
 WHERE code = 'STOCK_COUNT';

UPDATE permission
   SET description = 'Post a manual stock adjustment or a stock-take variance'
 WHERE code = 'STOCK_ADJUST';

/*
 * The count sheet flags every line whose lot moved since the snapshot, so the
 * person signing off can see which figures the shop traded through. That is one
 * correlated subquery per row over (lot_id, occurred_at); the existing index
 * leads with branch_id and cannot serve it.
 */
CREATE INDEX IF NOT EXISTS stock_movement_lot_occurred_idx
    ON stock_movement (lot_id, occurred_at);

/* Opening a count checks for an overlapping one — a small table, but this is on
   the path of every count and the predicate is always the same two columns. */
CREATE INDEX IF NOT EXISTS stock_take_open_idx
    ON stock_take (branch_id, status)
 WHERE status IN ('OPEN', 'COUNTED');
