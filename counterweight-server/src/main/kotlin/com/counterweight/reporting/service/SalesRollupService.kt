package com.counterweight.reporting.service

import com.counterweight.sales.event.SaleCompleted
import com.counterweight.sales.event.SaleVoided
import com.counterweight.sales.service.SaleDetailService
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

/**
 * Keeps the sales rollups current as sales complete.
 *
 * §12's governing constraint is that one database serves both trading and
 * analysis, so heavy aggregation must stay off the tables the till writes to
 * during opening hours. "What have we taken today" is asked mid-shift, which
 * rules out a nightly refresh — so the figures are incremented here instead, a
 * few rows per sale.
 *
 * Written with `ON CONFLICT ... DO UPDATE` rather than read-modify-write. Two
 * tills completing sales in the same hour would otherwise race on the same
 * rollup row, and the loser's takings would vanish — silently, and only from
 * the reports rather than from the sales themselves, which is the worst kind of
 * discrepancy to find.
 *
 * Like printing and alerting, this must never fail a sale. A rollup can be
 * rebuilt from `sale` and `sale_line` at any time; a refused sale cannot.
 */
@Service
class SalesRollupService(
    private val jdbc: JdbcTemplate,
    private val saleDetails: SaleDetailService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    fun onSaleCompleted(event: SaleCompleted) {
        runCatching { roll(event) }
            .onFailure { log.error("could not roll up sale {}; reports will be short until rebuilt", event.saleId, it) }
    }

    private fun roll(event: SaleCompleted) {
        val detail = saleDetails.of(event.saleId)
        val sale = detail.sale
        val completedAt = sale.completedAt ?: return
        val zoned = completedAt.atZone(ZoneId.systemDefault())
        val businessDate = zoned.toLocalDate()
        val hour = zoned.hour

        // Cost of what actually left, from the lots the lines drew from. Summed
        // here rather than derived later so margin never needs the allocation
        // trail re-walked.
        val cost = detail.lines.fold(BigDecimal.ZERO) { acc, line ->
            acc.add(line.line.unitCost.multiply(line.line.qtyBase))
        }

        jdbc.update(
            """
            INSERT INTO sales_daily_rollup
                   (branch_id, business_date, hour, cashier_id, sale_count, gross, discount, tax, net, cost)
            VALUES (?, ?, ?, ?, 1, ?, ?, ?, ?, ?)
            ON CONFLICT (branch_id, business_date, hour, cashier_id) DO UPDATE SET
                sale_count = sales_daily_rollup.sale_count + 1,
                gross      = sales_daily_rollup.gross      + EXCLUDED.gross,
                discount   = sales_daily_rollup.discount   + EXCLUDED.discount,
                tax        = sales_daily_rollup.tax        + EXCLUDED.tax,
                net        = sales_daily_rollup.net        + EXCLUDED.net,
                cost       = sales_daily_rollup.cost       + EXCLUDED.cost
            """.trimIndent(),
            sale.branchId, businessDate, hour, sale.cashierId,
            sale.subtotal, sale.discountTotal, sale.taxTotal, sale.grandTotal, cost,
        )

        detail.lines.forEach { line ->
            val l = line.line
            jdbc.update(
                """
                INSERT INTO sales_product_rollup
                       (branch_id, business_date, product_id, qty_base, gross, discount, net, cost)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (branch_id, business_date, product_id) DO UPDATE SET
                    qty_base = sales_product_rollup.qty_base + EXCLUDED.qty_base,
                    gross    = sales_product_rollup.gross    + EXCLUDED.gross,
                    discount = sales_product_rollup.discount + EXCLUDED.discount,
                    net      = sales_product_rollup.net      + EXCLUDED.net,
                    cost     = sales_product_rollup.cost     + EXCLUDED.cost
                """.trimIndent(),
                sale.branchId, businessDate, l.productId,
                l.qtyBase,
                l.unitPrice.multiply(l.qty),
                l.discountAmount,
                l.lineTotal,
                l.unitCost.multiply(l.qtyBase),
            )
        }
    }

    /**
     * Takes a voided sale back out of the figures.
     *
     * Without this the live rollup keeps counting a sale the shop has reversed:
     * the stock went back, the customer was refunded, and today's takings still
     * include it until somebody happens to run a rebuild. An owner who voids a
     * mis-scan and watches the day's total not move is an owner who stops
     * believing the total.
     *
     * Subtraction rather than recomputation, to match how the figures went in.
     * The rebuild is what corrects any drift between the two.
     */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    fun onSaleVoided(event: SaleVoided) {
        runCatching { unroll(event.saleId) }
            .onFailure { log.error("could not unroll voided sale {}; rebuild to correct", event.saleId, it) }
    }

    private fun unroll(saleId: Long) {
        val detail = saleDetails.of(saleId)
        val sale = detail.sale
        val completedAt = sale.completedAt ?: return
        val zoned = completedAt.atZone(ZoneId.systemDefault())

        val cost = detail.lines.fold(BigDecimal.ZERO) { acc, line ->
            acc.add(line.line.unitCost.multiply(line.line.qtyBase))
        }

        jdbc.update(
            """
            UPDATE sales_daily_rollup
               SET sale_count = sale_count - 1,
                   gross      = gross      - ?,
                   discount   = discount   - ?,
                   tax        = tax        - ?,
                   net        = net        - ?,
                   cost       = cost       - ?
             WHERE branch_id = ? AND business_date = ? AND hour = ? AND cashier_id = ?
            """.trimIndent(),
            sale.subtotal, sale.discountTotal, sale.taxTotal, sale.grandTotal, cost,
            sale.branchId, zoned.toLocalDate(), zoned.hour, sale.cashierId,
        )

        detail.lines.forEach { line ->
            val l = line.line
            jdbc.update(
                """
                UPDATE sales_product_rollup
                   SET qty_base = qty_base - ?,
                       gross    = gross    - ?,
                       discount = discount - ?,
                       net      = net      - ?,
                       cost     = cost     - ?
                 WHERE branch_id = ? AND business_date = ? AND product_id = ?
                """.trimIndent(),
                l.qtyBase, l.unitPrice.multiply(l.qty), l.discountAmount,
                l.lineTotal, l.unitCost.multiply(l.qtyBase),
                sale.branchId, zoned.toLocalDate(), l.productId,
            )
        }
    }

    /**
     * Rebuilds the rollups for a date range from the sales themselves.
     *
     * The rollups are derived data, and derived data drifts — a listener that
     * threw, a restore from backup, a sale voided after the fact. Being able to
     * say "recompute from source" is what makes an incremental rollup safe to
     * rely on at all; without it the only remedy for a discrepancy is to
     * distrust every report.
     *
     * Voided sales are excluded, which is also how a void quietly corrects a
     * day's figures once this is run.
     */
    @Transactional
    fun rebuild(branchId: Long, from: LocalDate, to: LocalDate): Int {
        jdbc.update(
            "DELETE FROM sales_daily_rollup WHERE branch_id = ? AND business_date BETWEEN ? AND ?",
            branchId, from, to,
        )
        jdbc.update(
            "DELETE FROM sales_product_rollup WHERE branch_id = ? AND business_date BETWEEN ? AND ?",
            branchId, from, to,
        )

        /*
         * Aggregated in a subquery rather than with ON CONFLICT.
         *
         * Cost has to come from sale_line while the other figures live on sale,
         * and joining the two would multiply every sale-level total by its line
         * count — hence the correlated subquery, which forces one row per sale.
         * Rolling those up then has to happen in an outer GROUP BY: Postgres
         * refuses an ON CONFLICT DO UPDATE that would touch the same row twice
         * within one statement, which is exactly what two sales in the same
         * hour by the same cashier would do.
         *
         * The range was deleted above, so there is nothing left to conflict
         * with anyway.
         */
        val days = jdbc.update(
            """
            INSERT INTO sales_daily_rollup
                   (branch_id, business_date, hour, cashier_id, sale_count, gross, discount, tax, net, cost)
            SELECT x.branch_id, x.business_date, x.hour, x.cashier_id,
                   COUNT(*), SUM(x.subtotal), SUM(x.discount_total),
                   SUM(x.tax_total), SUM(x.grand_total), SUM(x.cost)
              FROM (
                   SELECT s.branch_id,
                          s.completed_at::date                        AS business_date,
                          EXTRACT(HOUR FROM s.completed_at)::smallint AS hour,
                          s.cashier_id, s.subtotal, s.discount_total,
                          s.tax_total, s.grand_total,
                          COALESCE((
                              SELECT SUM(sl.unit_cost * sl.qty_base)
                                FROM sale_line sl WHERE sl.sale_id = s.id
                          ), 0) AS cost
                     FROM sale s
                    WHERE s.branch_id = ?
                      AND s.status <> 'VOIDED'
                      AND s.completed_at IS NOT NULL
                      AND s.completed_at::date BETWEEN ? AND ?
              ) x
             GROUP BY x.branch_id, x.business_date, x.hour, x.cashier_id
            """.trimIndent(),
            branchId, from, to,
        )

        jdbc.update(
            """
            INSERT INTO sales_product_rollup
                   (branch_id, business_date, product_id, qty_base, gross, discount, net, cost)
            SELECT s.branch_id, s.completed_at::date, sl.product_id,
                   SUM(sl.qty_base),
                   SUM(sl.unit_price * sl.qty),
                   SUM(sl.discount_amount),
                   SUM(sl.line_total),
                   SUM(sl.unit_cost * sl.qty_base)
              FROM sale s
              JOIN sale_line sl ON sl.sale_id = s.id
             WHERE s.branch_id = ?
               AND s.status <> 'VOIDED'
               AND s.completed_at IS NOT NULL
               AND s.completed_at::date BETWEEN ? AND ?
             GROUP BY s.branch_id, s.completed_at::date, sl.product_id
            """.trimIndent(),
            branchId, from, to,
        )

        log.info("rebuilt sales rollups for branch {} from {} to {}", branchId, from, to)
        return days
    }
}
