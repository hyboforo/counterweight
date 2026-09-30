package com.counterweight.reporting.service

import com.counterweight.identity.security.Auth
import com.counterweight.reporting.repo.*
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The report families of §12.
 *
 * Every method sits behind `REPORT_VIEW`, and the ones exposing cost or margin
 * additionally behind `COST_VIEW`. That split is not decoration: sales staff
 * are deliberately given neither, and a takings report that leaked margin would
 * hand it to them through the back door.
 *
 * Which source a report reads is a decision about how current the answer must
 * be, and it is recorded on each method rather than left to be inferred.
 */
@Service
class ReportService(
    private val queries: ReportQueries,
    private val jdbc: JdbcTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val branchId: Long get() = Auth.current().branchId

    // ── Sales ──────────────────────────────────────────────────────────────

    /** Live, from the rollup. Asked mid-shift, so it cannot wait for a refresh. */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('REPORT_VIEW') and hasAuthority('COST_VIEW')")
    fun dailyTakings(from: LocalDate, to: LocalDate): List<DailyTakings> =
        queries.dailyTakings(branchId, from, to)

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('REPORT_VIEW') and hasAuthority('COST_VIEW')")
    fun takingsByCashier(from: LocalDate, to: LocalDate): List<CashierTakings> =
        queries.takingsByCashier(branchId, from, to)

    /** Where the day's trade actually falls, for staffing it. Carries no cost. */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('REPORT_VIEW')")
    fun hourlyHeatMap(from: LocalDate, to: LocalDate): List<HourlyCell> =
        queries.hourlyHeatMap(branchId, from, to)

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('REPORT_VIEW') and hasAuthority('COST_VIEW')")
    fun topProducts(from: LocalDate, to: LocalDate, limit: Int): List<ProductPerformance> =
        queries.topProducts(branchId, from, to, limit.coerceIn(1, 500))

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('REPORT_VIEW') and hasAuthority('COST_VIEW')")
    fun salesByCategory(from: LocalDate, to: LocalDate): List<CategoryPerformance> =
        queries.salesByCategory(branchId, from, to)

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('REPORT_VIEW')")
    fun topCustomers(from: LocalDate, to: LocalDate, limit: Int): List<CustomerSpend> =
        queries.topCustomers(branchId, from, to, limit.coerceIn(1, 500))

    // ── Inventory ──────────────────────────────────────────────────────────

    /**
     * As of the last refresh, not as of now.
     *
     * Stock valuation is read by somebody making a decision, not by a cashier,
     * so yesterday's answer is fine and the aggregation happens at 02:00 when
     * the shop is shut. [refreshedAt] says how stale it is, so that is never a
     * guess.
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('REPORT_VIEW') and hasAuthority('COST_VIEW')")
    fun stockValuation(onlyInStock: Boolean): List<StockValuationRow> =
        queries.stockValuation(branchId, onlyInStock)

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('REPORT_VIEW') and hasAuthority('COST_VIEW')")
    fun abcAnalysis(from: LocalDate, to: LocalDate): List<AbcRow> =
        queries.abcAnalysis(branchId, from, to)

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('REPORT_VIEW')")
    fun expiryAgeing(): List<ExpiryBucketRow> = queries.expiryAgeing(branchId)

    // ── Money ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('REPORT_VIEW')")
    fun takings(from: LocalDate, to: LocalDate): List<TakingsRow> =
        queries.takings(branchId, from.startOfDay(), to.endOfDay())

    /**
     * What the owner collected, against what the sales said should be there.
     *
     * `REPORT_VIEW`, not `SALES_COLLECT`: reading what was collected is not
     * collecting, and an auditor checking the takings has to be able to see it.
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('REPORT_VIEW')")
    fun collections(from: LocalDate, to: LocalDate): List<CollectionRow> =
        queries.collections(branchId, from.startOfDay(), to.endOfDay())

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('REPORT_VIEW')")
    fun discountRegister(from: LocalDate, to: LocalDate): List<DiscountRow> =
        queries.discountRegister(branchId, from, to)

    // ── Control ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('AUDIT_VIEW')")
    fun voidRegister(from: LocalDate, to: LocalDate): List<RegisterRow> =
        queries.register(branchId, VOID_ACTIONS, from.startOfDay(), to.endOfDay())

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('AUDIT_VIEW')")
    fun priceOverrideLog(from: LocalDate, to: LocalDate): List<RegisterRow> =
        queries.register(branchId, PRICE_ACTIONS, from.startOfDay(), to.endOfDay())

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('AUDIT_VIEW')")
    fun adjustmentRegister(from: LocalDate, to: LocalDate): List<RegisterRow> =
        queries.register(branchId, ADJUSTMENT_ACTIONS, from.startOfDay(), to.endOfDay())

    /** Every time somebody's PIN was used, accepted or not. */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('AUDIT_VIEW')")
    fun overrideRegister(from: LocalDate, to: LocalDate): List<RegisterRow> =
        queries.register(branchId, OVERRIDE_ACTIONS, from.startOfDay(), to.endOfDay())

    // ── Compliance ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('REPORT_VIEW')")
    fun restrictedSalesRegister(from: LocalDate, to: LocalDate): List<RestrictedSaleRow> =
        queries.restrictedSalesRegister(branchId, from.startOfDay(), to.endOfDay())

    /**
     * Who received a batch.
     *
     * `REPORT_VIEW` and not `AUDIT_VIEW`, deliberately. A recall is
     * time-critical and the person who has to make the phone calls is whoever
     * is in the shop; putting it behind the auditor's permission would mean the
     * one report that must be run in a hurry is the one nobody on the floor can
     * run.
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('REPORT_VIEW')")
    fun traceBatch(lotCode: String): List<TraceabilityRow> =
        queries.traceBatch(branchId, lotCode.trim())

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('REPORT_VIEW')")
    fun traceLot(lotId: Long): List<TraceabilityRow> = queries.traceLot(lotId)

    // ── Materialised views ─────────────────────────────────────────────────

    /**
     * Rebuilds the inventory views at 02:00.
     *
     * CONCURRENTLY, so a report running at that moment neither blocks nor is
     * blocked — a plain REFRESH takes an ACCESS EXCLUSIVE lock. It needs the
     * unique indexes V8 puts on both views; PostgreSQL refuses the concurrent
     * form without them.
     *
     * Deliberately not `@Transactional`: REFRESH MATERIALIZED VIEW CONCURRENTLY
     * cannot run inside a transaction block, and annotating this would make it
     * fail at runtime rather than at compile time.
     */
    @Scheduled(cron = "0 0 2 * * *")
    fun refreshMaterialisedViews() {
        MATERIALISED_VIEWS.forEach { view ->
            runCatching {
                jdbc.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY $view")
                jdbc.update("UPDATE report_refresh SET refreshed_at = now() WHERE view_name = ?", view)
            }
                .onSuccess { log.info("refreshed {}", view) }
                .onFailure { log.error("could not refresh {}", view, it) }
        }
    }

    /** Refresh on demand, for a decision that cannot wait until 02:00. */
    @PreAuthorize("hasAuthority('REPORT_VIEW')")
    fun refreshNow() = refreshMaterialisedViews()

    /** When the inventory views were last rebuilt. */
    @Transactional(readOnly = true)
    fun refreshedAt(): Instant? = jdbc.queryForList(
        "SELECT MIN(refreshed_at) FROM report_refresh", Instant::class.java,
    ).firstOrNull()

    private fun LocalDate.startOfDay(): Instant = atStartOfDay(ZoneId.systemDefault()).toInstant()

    /** Exclusive upper bound: a report "to the 31st" includes the 31st. */
    private fun LocalDate.endOfDay(): Instant = plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant()

    private companion object {
        val MATERIALISED_VIEWS = listOf("mv_stock_valuation", "mv_expiry_ageing")

        val VOID_ACTIONS = listOf("SALE_VOIDED", "SALE_RETURNED")

        val PRICE_ACTIONS = listOf(
            "SALE_PRICE_OVERRIDDEN", "SALE_DISCOUNT_APPROVED", "PRICE_CHANGED",
            "PRICE_CORRECTED", "PRICE_CHANGE_CANCELLED",
        )

        val ADJUSTMENT_ACTIONS = listOf(
            "STOCK_ADJUSTED", "STOCK_WRITTEN_OFF",
            // Posting a count writes stock off, which is why it needs
            // STOCK_ADJUST — so it belongs in the register that exists to show
            // who wrote stock off and when.
            "STOCK_TAKE_POSTED",
            "CUSTOMER_ACCOUNT_ADJUSTED", "DEBT_WRITTEN_OFF",
        )

        val OVERRIDE_ACTIONS = listOf(
            "OVERRIDE_APPROVED", "OVERRIDE_PIN_REJECTED",
            "OVERRIDE_REFUSED_NO_PERMISSION", "CREDIT_LIMIT_OVERRIDDEN",
        )
    }
}
