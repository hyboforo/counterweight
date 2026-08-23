package com.counterweight.alerting.service

import com.counterweight.alerting.repo.AlertQueryRepository
import com.counterweight.catalog.repo.ProductRepository
import com.counterweight.platform.service.ConfigKeys
import com.counterweight.platform.service.ConfigService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.temporal.ChronoUnit

data class ReorderRecomputeResult(
    val examined: Int,
    val updated: Int,
    /** Products where the computed figure diverged sharply from a manual one. */
    val diverged: List<String>,
)

/**
 * Recomputes reorder points nightly (§11.2).
 *
 *     reorder_point = ceil(avg_daily_usage × lead_time_days × safety_factor) + safety_stock
 *
 * Averaged over a trailing 90-day window of issues. The formula is simple on
 * purpose: a shop owner has to be able to look at the number and see where it
 * came from, and a forecast nobody can explain is one they override to a round
 * figure and stop trusting.
 *
 * Every term is configuration (`app_config`), because §11.2 is explicit that
 * the computed answer is wrong for anything seasonal and the shop has to be
 * able to tune it without a release.
 *
 * `lead_time_days` is a single shop-wide figure rather than a per-supplier one.
 * §11.2 assumed suppliers; purchasing is out of scope and the supplier table is
 * gone (V7), so a product that takes a fortnight to arrive and one that takes
 * two days share a lead time. That makes the computed point conservative for
 * fast-moving lines — which is the safer direction to be wrong in, and what
 * `reorder_is_manual` exists to correct where it matters.
 *
 * **A manual figure is never overwritten.** `reorder_is_manual` means somebody
 * decided, and the computed answer is wrong for anything seasonal — a
 * herbicide's planting-season level cannot be derived from dry-season sales.
 * Divergence between the two is reported instead, because that divergence is
 * usually information: either the season turned or the manual figure is stale.
 */
@Service
class ReorderPointService(
    private val queries: AlertQueryRepository,
    private val products: ProductRepository,
    private val config: ConfigService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun recompute(branchId: Long): ReorderRecomputeResult {
        val windowDays = config.int(ConfigKeys.REORDER_WINDOW_DAYS, 90)
        val since = Instant.now().minus(windowDays.toLong(), ChronoUnit.DAYS)
        val usage = queries.averageDailyUsage(branchId, since, windowDays)
            .associate { it.productId to it.avgDailyUsage }

        var updated = 0
        val diverged = mutableListOf<String>()
        val all = products.findAll().filter { it.branchId == branchId && it.isActive }

        all.forEach { product ->
            val avgDaily = usage[product.id] ?: BigDecimal.ZERO
            val computed = compute(avgDaily, product.safetyStock)

            if (product.reorderIsManual) {
                /*
                 * Left alone, but compared. A manual figure that has drifted far
                 * from what the shop is actually selling is worth a look — and
                 * the direction matters more than the size, so both are left for
                 * a human to read rather than being auto-corrected.
                 */
                val manual = product.reorderPoint
                if (manual != null && divergesSharply(manual, computed)) {
                    diverged += "${product.sku}: set to ${manual.stripTrailingZeros().toPlainString()}, " +
                        "usage suggests ${computed.stripTrailingZeros().toPlainString()}"
                }
                return@forEach
            }

            if (product.reorderPoint?.compareTo(computed) != 0) {
                product.reorderPoint = computed
                // A suggested order quantity of roughly one lead time's usage.
                // Crude, and deliberately so — the storekeeper adjusts it, and a
                // cleverer number they do not understand gets ignored.
                product.reorderQty = computed.max(BigDecimal.ONE)
                products.save(product)
                updated++
            }
        }

        log.info("recomputed reorder points for branch {}: {} of {} updated", branchId, updated, all.size)
        return ReorderRecomputeResult(all.size, updated, diverged)
    }

    /** ceil(avg × lead × safety) + safety stock. */
    private fun compute(avgDailyUsage: BigDecimal, safetyStock: BigDecimal): BigDecimal =
        avgDailyUsage
            .multiply(BigDecimal(config.int(ConfigKeys.REORDER_LEAD_TIME_DAYS, 14)))
            .multiply(config.number(ConfigKeys.REORDER_SAFETY_FACTOR, BigDecimal("1.2")))
            .setScale(0, RoundingMode.CEILING)
            .add(safetyStock)

    /**
     * Whether two figures are far enough apart to be worth mentioning.
     *
     * Ratio rather than difference: being 20 out matters on a product that
     * moves 5 a week and not on one that moves 500.
     */
    private fun divergesSharply(manual: BigDecimal, computed: BigDecimal): Boolean {
        if (manual <= BigDecimal.ZERO && computed <= BigDecimal.ZERO) return false
        val larger = manual.max(computed)
        val smaller = manual.min(computed)
        if (larger <= BigDecimal.ZERO) return false
        // More than double, or less than half.
        return smaller.multiply(BigDecimal(2)) < larger
    }
}
