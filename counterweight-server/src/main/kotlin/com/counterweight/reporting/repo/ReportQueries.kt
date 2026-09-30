package com.counterweight.reporting.repo

import com.counterweight.alerting.domain.Alert
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/*
 * Report projections.
 *
 * Interface projections rather than entities: a report is a shape, not a thing
 * that can be saved, and mapping one onto an entity invites somebody to write
 * through it.
 *
 * Everything here reads either a rollup or a materialised view, except the
 * registers and the traceability query — those are direct, because they are run
 * rarely and have to be exact rather than as-of-last-refresh.
 */

interface DailyTakings {
    val businessDate: LocalDate
    val saleCount: Int
    val gross: BigDecimal
    val discount: BigDecimal
    val tax: BigDecimal
    val net: BigDecimal
    val cost: BigDecimal
    val margin: BigDecimal
    val marginPercent: BigDecimal
}

interface CashierTakings {
    val cashierId: Long
    val username: String
    val fullName: String
    val saleCount: Int
    val net: BigDecimal
    val discount: BigDecimal
    val margin: BigDecimal
}

interface HourlyCell {
    val hour: Int
    val saleCount: Int
    val net: BigDecimal
}

interface ProductPerformance {
    val productId: Long
    val sku: String
    val name: String
    val qtyBase: BigDecimal
    val net: BigDecimal
    val cost: BigDecimal
    val margin: BigDecimal
    val marginPercent: BigDecimal
}

interface CategoryPerformance {
    val categoryId: Long
    val categoryName: String
    val net: BigDecimal
    val cost: BigDecimal
    val margin: BigDecimal
}

interface CustomerSpend {
    val customerId: Long
    val code: String
    val name: String
    val saleCount: Int
    val net: BigDecimal
}

interface StockValuationRow {
    val productId: Long
    val sku: String
    val name: String
    val qtyBase: BigDecimal
    val stockValue: BigDecimal
    val lotCount: Int
    val nearestExpiry: java.sql.Date?
}

interface AbcRow {
    val productId: Long
    val sku: String
    val name: String
    val net: BigDecimal
    val cumulativePercent: BigDecimal
    val abcClass: String
}

interface ExpiryBucketRow {
    val bucket: String
    val lotCount: Int
    val qtyBase: BigDecimal
    val stockValue: BigDecimal
}

interface TakingsRow {
    val day: LocalDate
    val tillCode: String
    val method: String
    val total: BigDecimal
    val saleCount: Int
}

interface CollectionRow {
    val number: String
    val collectedAt: Instant
    val periodFrom: Instant?
    val collectedByName: String
    val method: String
    val expected: BigDecimal
    val collected: BigDecimal
    val difference: BigDecimal
    val note: String?
}

interface RegisterRow {
    val occurredAt: Instant
    val actorName: String
    val approverName: String?
    val action: String
    val subjectType: String
    val subjectId: Long?
    val reason: String?
    val detail: String?
}

interface DiscountRow {
    val saleNumber: String?
    val completedAt: Instant?
    val cashierName: String
    val productName: String
    val discountAmount: BigDecimal
    val lineTotal: BigDecimal
    val approvedByName: String?
}

interface RestrictedSaleRow {
    val recordedAt: Instant
    val saleNumber: String?
    val productName: String
    val qty: BigDecimal
    val buyerName: String
    val buyerPhone: String?
    val buyerIdType: String?
    val buyerIdNumber: String?
    val intendedUse: String?
    val recordedByName: String
}

interface TraceabilityRow {
    val saleNumber: String?
    val completedAt: Instant?
    val qtyBase: BigDecimal
    val customerId: Long?
    val customerName: String?
    val customerPhone: String?
    val productName: String
    val lotCode: String
}

/**
 * Every report in one repository.
 *
 * Bound to [Alert] only because Spring Data needs a managed type to hang native
 * queries off; nothing here touches alerts. Splitting these across the modules
 * that own the tables was the alternative, and it would have spread reporting's
 * concerns into eight places that have no other reason to care about them.
 */
@Repository
interface ReportQueries : JpaRepository<Alert, Long> {

    // ── Sales ──────────────────────────────────────────────────────────────

    @Query(
        value = """
            SELECT r.business_date                       AS "businessDate",
                   SUM(r.sale_count)::int                AS "saleCount",
                   SUM(r.gross)                          AS "gross",
                   SUM(r.discount)                       AS "discount",
                   SUM(r.tax)                            AS "tax",
                   SUM(r.net)                            AS "net",
                   SUM(r.cost)                           AS "cost",
                   SUM(r.net) - SUM(r.tax) - SUM(r.cost) AS "margin",
                   CASE WHEN SUM(r.net) - SUM(r.tax) = 0 THEN 0
                        ELSE ROUND(((SUM(r.net) - SUM(r.tax) - SUM(r.cost))
                             / (SUM(r.net) - SUM(r.tax))) * 100, 2) END AS "marginPercent"
              FROM sales_daily_rollup r
             WHERE r.branch_id = :branchId AND r.business_date BETWEEN :from AND :to
             GROUP BY r.business_date
             ORDER BY r.business_date
        """,
        nativeQuery = true,
    )
    fun dailyTakings(
        @Param("branchId") branchId: Long,
        @Param("from") from: LocalDate,
        @Param("to") to: LocalDate,
    ): List<DailyTakings>

    @Query(
        value = """
            SELECT r.cashier_id                          AS "cashierId",
                   u.username                            AS "username",
                   u.full_name                           AS "fullName",
                   SUM(r.sale_count)::int                AS "saleCount",
                   SUM(r.net)                            AS "net",
                   SUM(r.discount)                       AS "discount",
                   SUM(r.net) - SUM(r.tax) - SUM(r.cost) AS "margin"
              FROM sales_daily_rollup r
              JOIN app_user u ON u.id = r.cashier_id
             WHERE r.branch_id = :branchId AND r.business_date BETWEEN :from AND :to
             GROUP BY r.cashier_id, u.username, u.full_name
             ORDER BY SUM(r.net) DESC
        """,
        nativeQuery = true,
    )
    fun takingsByCashier(
        @Param("branchId") branchId: Long,
        @Param("from") from: LocalDate,
        @Param("to") to: LocalDate,
    ): List<CashierTakings>

    /**
     * The hourly heat map.
     *
     * A left join against a generated series so quiet hours appear as zero
     * rather than as gaps. A heat map with holes in it reads as missing data,
     * not as a quiet afternoon, which is the opposite of what it should say.
     */
    @Query(
        value = """
            SELECT h.hour::int                    AS "hour",
                   COALESCE(SUM(r.sale_count), 0)::int AS "saleCount",
                   COALESCE(SUM(r.net), 0)        AS "net"
              FROM generate_series(0, 23) AS h(hour)
              LEFT JOIN sales_daily_rollup r
                     ON r.hour = h.hour
                    AND r.branch_id = :branchId
                    AND r.business_date BETWEEN :from AND :to
             GROUP BY h.hour
             ORDER BY h.hour
        """,
        nativeQuery = true,
    )
    fun hourlyHeatMap(
        @Param("branchId") branchId: Long,
        @Param("from") from: LocalDate,
        @Param("to") to: LocalDate,
    ): List<HourlyCell>

    @Query(
        value = """
            SELECT r.product_id                    AS "productId",
                   p.sku                           AS "sku",
                   p.name                          AS "name",
                   SUM(r.qty_base)                 AS "qtyBase",
                   SUM(r.net)                      AS "net",
                   SUM(r.cost)                     AS "cost",
                   SUM(r.net) - SUM(r.cost)        AS "margin",
                   CASE WHEN SUM(r.net) = 0 THEN 0
                        ELSE ROUND(((SUM(r.net) - SUM(r.cost)) / SUM(r.net)) * 100, 2) END AS "marginPercent"
              FROM sales_product_rollup r
              JOIN product p ON p.id = r.product_id
             WHERE r.branch_id = :branchId AND r.business_date BETWEEN :from AND :to
             GROUP BY r.product_id, p.sku, p.name
             ORDER BY SUM(r.net) DESC
             LIMIT :limit
        """,
        nativeQuery = true,
    )
    fun topProducts(
        @Param("branchId") branchId: Long,
        @Param("from") from: LocalDate,
        @Param("to") to: LocalDate,
        @Param("limit") limit: Int,
    ): List<ProductPerformance>

    /**
     * Sales rolled up the category tree.
     *
     * Attributed to the top-level category via `subpath(path, 0, 1)` rather
     * than to the leaf: "how did agro do against hardware" is the question
     * being asked, and answering it per leaf category gives forty rows nobody
     * reads.
     */
    @Query(
        value = """
            SELECT root.id                       AS "categoryId",
                   root.name                     AS "categoryName",
                   SUM(r.net)                    AS "net",
                   SUM(r.cost)                   AS "cost",
                   SUM(r.net) - SUM(r.cost)      AS "margin"
              FROM sales_product_rollup r
              JOIN product p  ON p.id = r.product_id
              JOIN category c ON c.id = p.category_id
              JOIN category root ON root.path = subpath(c.path, 0, 1)
             WHERE r.branch_id = :branchId AND r.business_date BETWEEN :from AND :to
             GROUP BY root.id, root.name
             ORDER BY SUM(r.net) DESC
        """,
        nativeQuery = true,
    )
    fun salesByCategory(
        @Param("branchId") branchId: Long,
        @Param("from") from: LocalDate,
        @Param("to") to: LocalDate,
    ): List<CategoryPerformance>

    /**
     * Spend per customer.
     *
     * Direct against `sale` rather than a rollup: the customer dimension would
     * multiply the rollup's row count by the customer list for a figure that is
     * read occasionally, and `sale (customer_id, completed_at DESC)` already
     * indexes it.
     */
    @Query(
        value = """
            SELECT s.customer_id      AS "customerId",
                   c.code             AS "code",
                   c.name             AS "name",
                   COUNT(*)::int      AS "saleCount",
                   SUM(s.grand_total) AS "net"
              FROM sale s
              JOIN customer c ON c.id = s.customer_id
             WHERE s.branch_id = :branchId
               AND s.status <> 'VOIDED'
               AND s.completed_at::date BETWEEN :from AND :to
             GROUP BY s.customer_id, c.code, c.name
             ORDER BY SUM(s.grand_total) DESC
             LIMIT :limit
        """,
        nativeQuery = true,
    )
    fun topCustomers(
        @Param("branchId") branchId: Long,
        @Param("from") from: LocalDate,
        @Param("to") to: LocalDate,
        @Param("limit") limit: Int,
    ): List<CustomerSpend>

    // ── Inventory ──────────────────────────────────────────────────────────

    @Query(
        value = """
            SELECT v.product_id     AS "productId",
                   v.sku            AS "sku",
                   v.name           AS "name",
                   v.qty_base       AS "qtyBase",
                   v.stock_value    AS "stockValue",
                   v.lot_count::int AS "lotCount",
                   v.nearest_expiry AS "nearestExpiry"
              FROM mv_stock_valuation v
             WHERE v.branch_id = :branchId AND (:onlyInStock = FALSE OR v.qty_base > 0)
             ORDER BY v.stock_value DESC
        """,
        nativeQuery = true,
    )
    fun stockValuation(
        @Param("branchId") branchId: Long,
        @Param("onlyInStock") onlyInStock: Boolean,
    ): List<StockValuationRow>

    /**
     * ABC analysis: which products earn the attention.
     *
     * Classified on the share of turnover that comes *before* each item — A up
     * to 80%, B to 95%, the tail is C. Testing the share up to and including the
     * item would class the shop's single biggest seller as C whenever it alone
     * accounts for more than 80% of trade, which is exactly backwards: the item
     * that crosses a threshold belongs on the near side of it.
     *
     * Ranked by what sold rather than by what is on the shelf, because the
     * question is where to spend counting and reordering effort, and a pallet of
     * something nobody buys is a C however much it is worth.
     */
    @Query(
        value = """
            WITH sold AS (
                SELECT r.product_id, p.sku, p.name, SUM(r.net) AS net
                  FROM sales_product_rollup r
                  JOIN product p ON p.id = r.product_id
                 WHERE r.branch_id = :branchId AND r.business_date BETWEEN :from AND :to
                 GROUP BY r.product_id, p.sku, p.name
                HAVING SUM(r.net) > 0
            ), ranked AS (
                SELECT sold.*,
                       SUM(net) OVER (ORDER BY net DESC ROWS UNBOUNDED PRECEDING) AS running,
                       SUM(net) OVER () AS total
                  FROM sold
            )
            SELECT product_id AS "productId", sku AS "sku", name AS "name", net AS "net",
                   ROUND((running / NULLIF(total, 0)) * 100, 2) AS "cumulativePercent",
                   CASE WHEN ((running - net) / NULLIF(total, 0)) < 0.80 THEN 'A'
                        WHEN ((running - net) / NULLIF(total, 0)) < 0.95 THEN 'B'
                        ELSE 'C' END AS "abcClass"
              FROM ranked
             ORDER BY net DESC
        """,
        nativeQuery = true,
    )
    fun abcAnalysis(
        @Param("branchId") branchId: Long,
        @Param("from") from: LocalDate,
        @Param("to") to: LocalDate,
    ): List<AbcRow>

    @Query(
        value = """
            SELECT e.bucket             AS "bucket",
                   COUNT(*)::int        AS "lotCount",
                   SUM(e.qty_base)      AS "qtyBase",
                   SUM(e.stock_value)   AS "stockValue"
              FROM mv_expiry_ageing e
             WHERE e.branch_id = :branchId
             GROUP BY e.bucket
             ORDER BY CASE e.bucket
                        WHEN 'EXPIRED' THEN 0 WHEN 'WITHIN_7' THEN 1 WHEN 'WITHIN_30' THEN 2
                        WHEN 'WITHIN_60' THEN 3 WHEN 'WITHIN_90' THEN 4 ELSE 5 END
        """,
        nativeQuery = true,
    )
    fun expiryAgeing(@Param("branchId") branchId: Long): List<ExpiryBucketRow>

    // ── Money ──────────────────────────────────────────────────────────────

    /**
     * What was taken, by day, till and tender method.
     *
     * Derived from the payments themselves rather than from a counted drawer —
     * there is no drawer to count. `amount` is already net of change, so a
     * customer who hands over 50 for a 30 purchase contributes 30 here.
     */
    @Query(
        value = """
            SELECT s.completed_at::date       AS "day",
                   COALESCE(s.till_code, '-') AS "tillCode",
                   p.method                   AS "method",
                   SUM(p.amount)              AS "total",
                   COUNT(DISTINCT s.id)::int  AS "saleCount"
              FROM sale_payment p
              JOIN sale s ON s.id = p.sale_id
             WHERE s.branch_id = :branchId
               AND s.status <> 'VOIDED'
               AND s.completed_at >= :from AND s.completed_at < :until
             GROUP BY s.completed_at::date, COALESCE(s.till_code, '-'), p.method
             ORDER BY 1 DESC, 2, 3
        """,
        nativeQuery = true,
    )
    fun takings(
        @Param("branchId") branchId: Long,
        @Param("from") from: Instant,
        @Param("until") until: Instant,
    ): List<TakingsRow>

    /**
     * Every collection, one row per tender counted.
     *
     * Direct rather than rolled up: collections are few, and the figures are
     * the record of what was handed over, so they are read as written. By
     * tender rather than one total per collection because a shortage in cash
     * is not made good by a surplus in mobile money, and a total would show
     * the two cancelling out.
     */
    @Query(
        value = """
            SELECT c.number                 AS "number",
                   c.collected_at           AS "collectedAt",
                   c.period_from            AS "periodFrom",
                   u.full_name              AS "collectedByName",
                   l.method                 AS "method",
                   l.expected               AS "expected",
                   l.collected              AS "collected",
                   l.collected - l.expected AS "difference",
                   c.note                   AS "note"
              FROM sales_collection c
              JOIN sales_collection_line l ON l.collection_id = c.id
              JOIN app_user u ON u.id = c.collected_by
             WHERE c.branch_id = :branchId
               AND c.collected_at >= :from AND c.collected_at < :until
             ORDER BY c.collected_at DESC,
                      CASE l.method WHEN 'CASH' THEN 0 WHEN 'MOBILE_MONEY' THEN 1
                                    WHEN 'BANK_TRANSFER' THEN 2 WHEN 'CHEQUE' THEN 3 ELSE 4 END
        """,
        nativeQuery = true,
    )
    fun collections(
        @Param("branchId") branchId: Long,
        @Param("from") from: Instant,
        @Param("until") until: Instant,
    ): List<CollectionRow>

    /**
     * Every discount given, with who authorised it.
     *
     * A register rather than a total: the point is to be able to read down the
     * list and notice a pattern — one cashier, one product, one customer —
     * which a summary figure hides by construction.
     */
    @Query(
        value = """
            SELECT s.number            AS "saleNumber",
                   s.completed_at      AS "completedAt",
                   u.full_name         AS "cashierName",
                   p.name              AS "productName",
                   sl.discount_amount  AS "discountAmount",
                   sl.line_total       AS "lineTotal",
                   a.full_name         AS "approvedByName"
              FROM sale_line sl
              JOIN sale s     ON s.id = sl.sale_id
              JOIN product p  ON p.id = sl.product_id
              JOIN app_user u ON u.id = s.cashier_id
              LEFT JOIN app_user a ON a.id = sl.approved_by
             WHERE s.branch_id = :branchId
               AND s.status <> 'VOIDED'
               AND sl.discount_amount > 0
               AND s.completed_at::date BETWEEN :from AND :to
             ORDER BY s.completed_at DESC
        """,
        nativeQuery = true,
    )
    fun discountRegister(
        @Param("branchId") branchId: Long,
        @Param("from") from: LocalDate,
        @Param("to") to: LocalDate,
    ): List<DiscountRow>

    // ── Control ────────────────────────────────────────────────────────────

    /**
     * The audit log, filtered to the actions a register asks about.
     *
     * Reads the log rather than the operational tables on purpose: the log is
     * append-only and rejects UPDATE and DELETE at the database, so a register
     * built from it cannot be quietly edited afterwards. A void register built
     * from `sale` would show whatever `sale` says now.
     */
    @Query(
        value = """
            SELECT al.occurred_at   AS "occurredAt",
                   u.full_name      AS "actorName",
                   ap.full_name     AS "approverName",
                   al.action        AS "action",
                   al.subject_type  AS "subjectType",
                   al.subject_id    AS "subjectId",
                   al.reason        AS "reason",
                   al.after_value::text AS "detail"
              FROM audit_log al
              JOIN app_user u ON u.id = al.actor_id
              LEFT JOIN app_user ap ON ap.id = al.approver_id
             WHERE al.branch_id = :branchId
               AND al.action IN (:actions)
               AND al.occurred_at >= :from AND al.occurred_at < :until
             ORDER BY al.occurred_at DESC
        """,
        nativeQuery = true,
    )
    fun register(
        @Param("branchId") branchId: Long,
        @Param("actions") actions: Collection<String>,
        @Param("from") from: Instant,
        @Param("until") until: Instant,
    ): List<RegisterRow>

    // ── Compliance ─────────────────────────────────────────────────────────

    /**
     * The restricted agro-chemical buyer register.
     *
     * A licence condition rather than a report. Ordered oldest-first because
     * that is how a register is read when somebody official asks for it.
     */
    @Query(
        value = """
            SELECT rsr.recorded_at     AS "recordedAt",
                   s.number            AS "saleNumber",
                   p.name              AS "productName",
                   sl.qty              AS "qty",
                   rsr.buyer_name      AS "buyerName",
                   rsr.buyer_phone     AS "buyerPhone",
                   rsr.buyer_id_type   AS "buyerIdType",
                   rsr.buyer_id_number AS "buyerIdNumber",
                   rsr.intended_use    AS "intendedUse",
                   u.full_name         AS "recordedByName"
              FROM restricted_sale_record rsr
              JOIN sale_line sl ON sl.id = rsr.sale_line_id
              JOIN sale s       ON s.id = sl.sale_id
              JOIN product p    ON p.id = sl.product_id
              JOIN app_user u   ON u.id = rsr.recorded_by
             WHERE s.branch_id = :branchId
               AND rsr.recorded_at >= :from AND rsr.recorded_at < :until
             ORDER BY rsr.recorded_at
        """,
        nativeQuery = true,
    )
    fun restrictedSalesRegister(
        @Param("branchId") branchId: Long,
        @Param("from") from: Instant,
        @Param("until") until: Instant,
    ): List<RestrictedSaleRow>

    /**
     * Which customers received a batch.
     *
     * §12 singles this out: when a manufacturer recalls a batch, this must be
     * one query rather than an afternoon with a paper ledger. It is one query
     * only because the sale line stores the lot it drew from — that is what
     * `sale_line_allocation` is for, and this is the report that justifies it.
     *
     * Walk-in sales appear with a null customer. That is not a gap in the data,
     * it is the honest answer: the shop does not know who bought it, and a
     * recall has to be told that rather than shown a shorter list.
     */
    @Query(
        value = """
            SELECT s.number       AS "saleNumber",
                   s.completed_at AS "completedAt",
                   sla.qty_base   AS "qtyBase",
                   c.id           AS "customerId",
                   c.name         AS "customerName",
                   c.phone        AS "customerPhone",
                   p.name         AS "productName",
                   l.lot_code     AS "lotCode"
              FROM sale_line_allocation sla
              JOIN stock_lot l  ON l.id = sla.lot_id
              JOIN sale_line sl ON sl.id = sla.sale_line_id
              JOIN sale s       ON s.id = sl.sale_id
              JOIN product p    ON p.id = sl.product_id
              LEFT JOIN customer c ON c.id = s.customer_id
             WHERE l.id = :lotId AND s.status <> 'VOIDED'
             ORDER BY s.completed_at
        """,
        nativeQuery = true,
    )
    fun traceLot(@Param("lotId") lotId: Long): List<TraceabilityRow>

    /** The same, by batch code, since that is what is printed on the drum. */
    @Query(
        value = """
            SELECT s.number       AS "saleNumber",
                   s.completed_at AS "completedAt",
                   sla.qty_base   AS "qtyBase",
                   c.id           AS "customerId",
                   c.name         AS "customerName",
                   c.phone        AS "customerPhone",
                   p.name         AS "productName",
                   l.lot_code     AS "lotCode"
              FROM sale_line_allocation sla
              JOIN stock_lot l  ON l.id = sla.lot_id
              JOIN sale_line sl ON sl.id = sla.sale_line_id
              JOIN sale s       ON s.id = sl.sale_id
              JOIN product p    ON p.id = sl.product_id
              LEFT JOIN customer c ON c.id = s.customer_id
             WHERE l.branch_id = :branchId AND l.lot_code = :lotCode AND s.status <> 'VOIDED'
             ORDER BY s.completed_at
        """,
        nativeQuery = true,
    )
    fun traceBatch(
        @Param("branchId") branchId: Long,
        @Param("lotCode") lotCode: String,
    ): List<TraceabilityRow>
}
