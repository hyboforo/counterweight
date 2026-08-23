package com.counterweight.alerting.service

import com.counterweight.alerting.domain.AlertRule
import com.counterweight.catalog.repo.ProductRepository
import com.counterweight.inventory.event.StockChanged
import com.counterweight.inventory.repo.StockBalanceRepository
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

/**
 * Alerts that a stock movement can answer immediately.
 *
 * Out of stock and below reorder point are evaluated on the movement rather
 * than nightly, because the shop needs to know while the storekeeper is still
 * standing where the empty shelf is (§11). Everything that depends on the
 * calendar instead is in [ScheduledAlertEvaluator].
 *
 * Both conditions auto-resolve: receiving stock closes the alert without anyone
 * clicking anything, which is the mechanism that keeps the notification centre
 * describing the shop as it is rather than as it once was.
 */
@Service
class StockAlertEvaluator(
    private val alerts: AlertService,
    private val balances: StockBalanceRepository,
    private val products: ProductRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Re-checks every product a movement touched.
     *
     * Runs in the moving transaction so it reads the balance as it will be
     * committed, but [AlertService.raise] writes in its own — an alert is a
     * true observation whether or not the movement that revealed it survives.
     *
     * Never fails the movement. Goods that physically arrived have to be
     * recorded even if nothing can be said about them afterwards.
     */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    fun onStockChanged(event: StockChanged) {
        runCatching { event.productIds.forEach { evaluate(event.branchId, it) } }
            .onFailure { log.error("could not evaluate stock alerts for {}", event.productIds, it) }
    }

    private fun evaluate(branchId: Long, productId: Long) {
        val product = products.findById(productId).orElse(null) ?: return
        if (!product.isActive) return

        val onHand = balances.totalOnHand(productId)

        // ── Out of stock ──
        val outKey = "${AlertRule.OUT_OF_STOCK}:$branchId:$productId"
        if (onHand <= BigDecimal.ZERO) {
            alerts.raise(
                AlertRequest(
                    ruleType = AlertRule.OUT_OF_STOCK,
                    branchId = branchId,
                    dedupeKey = outKey,
                    title = "Out of stock: ${product.name}",
                    body = "${product.name} (${product.sku}) has nothing on hand.",
                    subjectType = "product",
                    subjectId = productId,
                )
            )
        } else {
            alerts.autoResolve(outKey)
        }

        // ── Below reorder point ──
        //
        // Skipped entirely where no reorder point is set. A product nobody has
        // decided a level for is not one the shop wants telling about, and
        // treating "unset" as zero would raise it for the whole catalogue the
        // first night the recompute ran.
        val reorderPoint = product.reorderPoint ?: return
        val reorderKey = "${AlertRule.BELOW_REORDER_POINT}:$branchId:$productId"

        if (onHand < reorderPoint && onHand > BigDecimal.ZERO) {
            val suggestion = product.reorderQty
                ?.let { " Suggested order: ${it.stripTrailingZeros().toPlainString()}." }
                ?: ""
            alerts.raise(
                AlertRequest(
                    ruleType = AlertRule.BELOW_REORDER_POINT,
                    branchId = branchId,
                    dedupeKey = reorderKey,
                    title = "Low stock: ${product.name}",
                    body = "${onHand.stripTrailingZeros().toPlainString()} on hand, " +
                        "reorder point ${reorderPoint.stripTrailingZeros().toPlainString()}.$suggestion",
                    subjectType = "product",
                    subjectId = productId,
                )
            )
        } else {
            // Also resolves when stock hits zero: at that point the out-of-stock
            // alert is the accurate one, and holding both would report the same
            // shelf twice at two severities.
            alerts.autoResolve(reorderKey)
        }
    }
}
