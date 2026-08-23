-- ═══════════════════════════════════════════════════════════════════════════
--  Counterweight V14 — rebuilding the rollups is an authority of its own
--
--  `POST /api/reports/sales/rebuild` deletes the sales rollups for a date
--  range and recomputes them from `sale` and `sale_line`. Every other
--  reporting entry point carries a @PreAuthorize and this one carried none, so
--  any signed-in account — a storekeeper, a cashier — could wipe and recompute
--  the figures the owner reads, over any range, as often as it liked. An
--  unguarded gate is worse than a missing one: nobody re-reads a check they
--  believe is there.
--
--  No permission already on the books fits. REPORT_VIEW would hand a
--  destructive operation to AUDITOR, a role the grants deliberately keep to
--  reading. CONFIG_MANAGE belongs to SYSTEM_ADMIN, who holds nothing
--  commercial and would never be the one to notice a day's takings came out
--  short.
--
--  So a permission of its own, granted to the two accounts that would ever run
--  it. SYSTEM_ADMIN because restoring a backup is theirs and a restore is
--  precisely when the rollups need recomputing; ADMIN because the owner is who
--  reads the report and sees it is wrong. Not MANAGER: a rebuild rewrites the
--  takings-by-cashier figures, and a manager who sells is measured by them.
--
--  Rebuilding leaks nothing — it returns a row count, not a figure — which is
--  why SYSTEM_ADMIN can hold it without breaking the rule that the role holds
--  nothing commercial.
-- ═══════════════════════════════════════════════════════════════════════════

INSERT INTO permission (code, description) VALUES
    ('REPORT_REBUILD', 'Recompute derived reporting data from the sales themselves');

INSERT INTO role_permission (role_id, permission_code)
SELECT r.id, 'REPORT_REBUILD'
  FROM role r
 WHERE r.code IN ('ADMIN', 'SYSTEM_ADMIN');
