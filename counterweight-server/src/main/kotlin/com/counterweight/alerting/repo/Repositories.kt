package com.counterweight.alerting.repo

import com.counterweight.alerting.domain.Alert
import com.counterweight.alerting.domain.AlertRule
import com.counterweight.alerting.domain.NotificationOutbox
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

@Repository
interface AlertRuleRepository : JpaRepository<AlertRule, Long> {

    /**
     * Enabled rules of a type that apply to a branch.
     *
     * A null `branch_id` means every branch, so the query has to match both —
     * a rule written once for the whole business and a rule written for this
     * shop are equally in force.
     */
    @Query(
        """
        SELECT r FROM AlertRule r
         WHERE r.enabled = true AND r.ruleType = :ruleType
           AND (r.branchId IS NULL OR r.branchId = :branchId)
        """
    )
    fun activeFor(
        @Param("ruleType") ruleType: String,
        @Param("branchId") branchId: Long,
    ): List<AlertRule>

    fun findByRuleType(ruleType: String): List<AlertRule>
}

@Repository
interface AlertRepository : JpaRepository<Alert, Long> {

    fun findByDedupeKeyAndResolvedAtIsNull(dedupeKey: String): Alert?

    /**
     * The notification centre: everything open, worst first.
     *
     * Snoozed alerts are included rather than filtered here — whether to show
     * them is a decision for the caller, and a centre that silently hides rows
     * is one nobody trusts.
     */
    @Query(
        value = """
            SELECT a.* FROM alert a
             WHERE a.branch_id = :branchId
               AND a.resolved_at IS NULL
               AND (:includeSnoozed = TRUE OR a.snoozed_until IS NULL OR a.snoozed_until <= now())
             ORDER BY CASE a.severity WHEN 'CRITICAL' THEN 0 WHEN 'WARNING' THEN 1 ELSE 2 END,
                      a.raised_at DESC
        """,
        nativeQuery = true,
    )
    fun openFor(
        @Param("branchId") branchId: Long,
        @Param("includeSnoozed") includeSnoozed: Boolean,
    ): List<Alert>

    /** Counts by severity — the dashboard badges of §11.3. */
    @Query(
        value = """
            SELECT a.severity AS "severity", COUNT(*) AS "count"
              FROM alert a
             WHERE a.branch_id = :branchId
               AND a.resolved_at IS NULL
               AND (a.snoozed_until IS NULL OR a.snoozed_until <= now())
             GROUP BY a.severity
        """,
        nativeQuery = true,
    )
    fun badgeCounts(@Param("branchId") branchId: Long): List<SeverityCount>

    /** Open alerts of a type, so an evaluator can close the ones that no longer hold. */
    @Query(
        value = """
            SELECT a.* FROM alert a
              JOIN alert_rule r ON r.id = a.rule_id
             WHERE a.branch_id = :branchId AND a.resolved_at IS NULL AND r.rule_type = :ruleType
        """,
        nativeQuery = true,
    )
    fun openOfType(
        @Param("branchId") branchId: Long,
        @Param("ruleType") ruleType: String,
    ): List<Alert>

    /** What was raised overnight — the morning briefing's input. */
    @Query(
        value = """
            SELECT a.* FROM alert a
             WHERE a.branch_id = :branchId AND a.raised_at >= :since
             ORDER BY CASE a.severity WHEN 'CRITICAL' THEN 0 WHEN 'WARNING' THEN 1 ELSE 2 END,
                      a.raised_at DESC
        """,
        nativeQuery = true,
    )
    fun raisedSince(
        @Param("branchId") branchId: Long,
        @Param("since") since: Instant,
    ): List<Alert>
}

interface SeverityCount {
    val severity: String
    val count: Long
}

/** A product sitting below the level it should be reordered at. */
interface ReorderShortfall {
    val productId: Long
    val sku: String
    val name: String
    val onHand: BigDecimal
    val reorderPoint: BigDecimal
    val reorderQty: BigDecimal?
}

/** A lot approaching or past its expiry, with what is still on hand. */
interface ExpiringLot {
    val lotId: Long
    val productId: Long
    val productName: String
    val lotCode: String
    val expiresOn: java.sql.Date
    val qtyBase: BigDecimal
    val daysLeft: Int
}

/** Stock that has not moved in a long time. */
interface DeadStockRow {
    val productId: Long
    val sku: String
    val name: String
    val qtyBase: BigDecimal
    val stockValue: BigDecimal
    val lastMovedOn: java.sql.Date?
}

/**
 * Read queries backing the evaluators.
 *
 * Native and denormalised on purpose. These join across inventory, catalog and
 * sales to answer one question each, and doing it in SQL is both faster and
 * clearer than assembling the same answer from three service calls per product
 * across a catalogue.
 */
@Repository
interface AlertQueryRepository : JpaRepository<Alert, Long> {

    @Query(
        value = """
            SELECT p.id AS "productId", p.sku AS "sku", p.name AS "name",
                   COALESCE(SUM(b.qty_base), 0)  AS "onHand",
                   p.reorder_point               AS "reorderPoint",
                   p.reorder_qty                 AS "reorderQty"
              FROM product p
              LEFT JOIN stock_lot l     ON l.product_id = p.id AND l.status = 'AVAILABLE'
              LEFT JOIN stock_balance b ON b.lot_id = l.id AND b.branch_id = l.branch_id
             WHERE p.branch_id = :branchId
               AND p.is_active
               AND p.reorder_point IS NOT NULL
             GROUP BY p.id, p.sku, p.name, p.reorder_point, p.reorder_qty
            HAVING COALESCE(SUM(b.qty_base), 0) < p.reorder_point
             ORDER BY p.name
        """,
        nativeQuery = true,
    )
    fun belowReorderPoint(@Param("branchId") branchId: Long): List<ReorderShortfall>

    /**
     * Lots expiring within [withinDays], or already expired when it is negative.
     *
     * Only AVAILABLE lots with stock: an expired lot that was already written
     * off is not news, and re-raising it every night is exactly the fatigue
     * §11.1 is about.
     */
    @Query(
        value = """
            SELECT l.id                          AS "lotId",
                   l.product_id                  AS "productId",
                   p.name                        AS "productName",
                   l.lot_code                    AS "lotCode",
                   l.expires_on                  AS "expiresOn",
                   b.qty_base                    AS "qtyBase",
                   (l.expires_on - CURRENT_DATE) AS "daysLeft"
              FROM stock_lot l
              JOIN product p            ON p.id = l.product_id
              JOIN stock_balance b      ON b.lot_id = l.id AND b.branch_id = l.branch_id
             WHERE l.branch_id = :branchId
               AND l.status = 'AVAILABLE'
               AND l.expires_on IS NOT NULL
               AND b.qty_base > 0
               AND (l.expires_on - CURRENT_DATE) <= :withinDays
             ORDER BY l.expires_on
        """,
        nativeQuery = true,
    )
    fun lotsExpiringWithin(
        @Param("branchId") branchId: Long,
        @Param("withinDays") withinDays: Int,
    ): List<ExpiringLot>

    @Query(
        value = """
            SELECT p.id AS "productId", p.sku AS "sku", p.name AS "name",
                   COALESCE(SUM(b.qty_base), 0)              AS "qtyBase",
                   COALESCE(SUM(b.qty_base * l.unit_cost), 0) AS "stockValue",
                   MAX(m.occurred_at)::date                   AS "lastMovedOn"
              FROM product p
              LEFT JOIN stock_lot l     ON l.product_id = p.id
              LEFT JOIN stock_balance b ON b.lot_id = l.id AND b.branch_id = l.branch_id
              LEFT JOIN stock_movement m ON m.lot_id = l.id AND m.movement_type = 'SALE_ISSUE'
             WHERE p.branch_id = :branchId AND p.is_active
             GROUP BY p.id, p.sku, p.name
            HAVING COALESCE(SUM(b.qty_base), 0) > 0
               AND (MAX(m.occurred_at) IS NULL OR MAX(m.occurred_at) < :movedBefore)
             ORDER BY COALESCE(SUM(b.qty_base * l.unit_cost), 0) DESC
        """,
        nativeQuery = true,
    )
    fun deadStock(
        @Param("branchId") branchId: Long,
        @Param("movedBefore") movedBefore: Instant,
    ): List<DeadStockRow>

    /**
     * Average daily usage over a trailing window, per product.
     *
     * Issues only, and unsigned. A return or an adjustment is not demand, and
     * counting one would drag the reorder point in the wrong direction exactly
     * when a product has been going wrong.
     */
    @Query(
        value = """
            SELECT l.product_id                          AS "productId",
                   ABS(SUM(m.qty_base)) / :windowDays    AS "avgDailyUsage"
              FROM stock_movement m
              JOIN stock_lot l ON l.id = m.lot_id
             WHERE l.branch_id = :branchId
               AND m.movement_type = 'SALE_ISSUE'
               AND m.occurred_at >= :since
             GROUP BY l.product_id
        """,
        nativeQuery = true,
    )
    fun averageDailyUsage(
        @Param("branchId") branchId: Long,
        @Param("since") since: Instant,
        @Param("windowDays") windowDays: Int,
    ): List<UsageRow>

    /** Customers with invoices past their terms. */
    @Query(
        value = """
            SELECT c.id AS "customerId", c.name AS "name",
                   SUM(e.amount - COALESCE(a.allocated, 0)) AS "overdueAmount",
                   MAX(CURRENT_DATE - e.due_on)             AS "daysOverdue"
              FROM customer_ledger_entry e
              JOIN customer c ON c.id = e.customer_id
              LEFT JOIN (
                   SELECT invoice_entry_id, SUM(amount) AS allocated
                     FROM payment_allocation GROUP BY invoice_entry_id
              ) a ON a.invoice_entry_id = e.id
             WHERE e.branch_id = :branchId
               AND e.entry_type IN ('INVOICE', 'OPENING_BALANCE')
               AND e.due_on IS NOT NULL
               AND e.due_on < :onDate
               AND e.amount - COALESCE(a.allocated, 0) > 0
             GROUP BY c.id, c.name
             ORDER BY MAX(CURRENT_DATE - e.due_on) DESC
        """,
        nativeQuery = true,
    )
    fun overdueCustomers(
        @Param("branchId") branchId: Long,
        @Param("onDate") onDate: LocalDate,
    ): List<OverdueRow>
}

interface UsageRow {
    val productId: Long
    val avgDailyUsage: BigDecimal
}

interface OverdueRow {
    val customerId: Long
    val name: String
    val overdueAmount: BigDecimal
    val daysOverdue: Int
}

@Repository
interface NotificationOutboxRepository : JpaRepository<NotificationOutbox, Long> {

    @Query(
        """
        SELECT n FROM NotificationOutbox n
         WHERE n.status = 'PENDING' ORDER BY n.createdAt
        """
    )
    fun pending(): List<NotificationOutbox>

    fun countByStatus(status: String): Long
}
