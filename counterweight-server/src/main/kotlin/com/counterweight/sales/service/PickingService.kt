package com.counterweight.sales.service

import com.counterweight.catalog.domain.Product
import com.counterweight.common.ApiException
import com.counterweight.inventory.repo.StockLotRepository
import com.counterweight.pricing.service.Money
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode

/** One lot a line will draw from, and what that stock cost. */
data class LotPick(val lotId: Long, val qtyBase: BigDecimal, val unitCost: BigDecimal)

/**
 * Chooses which lots a sale draws from.
 *
 * The rule is a property of the product, not a global setting (§5.2).
 * Agro-chemicals are FEFO so the stock closest to expiry moves first; hardware
 * is FIFO so cost flows in receipt order; a few items are picked by hand
 * because the cashier can see which pile is which.
 *
 * Because cost lives on the lot and the issue cost is that lot's cost, margin
 * per sale line comes out exact without maintaining separate cost layers. The
 * price of that is that a lot's cost can never be edited once movements exist
 * against it — a mis-keyed purchase price is corrected with a compensating
 * adjustment pair, never an UPDATE.
 *
 * Nothing here reserves stock. Picking is advisory until the movements post at
 * completion, where the balance CHECK is what actually decides who gets the
 * last bag of cement.
 */
@Component
class PickingService(private val lots: StockLotRepository) {

    /**
     * Lots to draw [qtyBase] from, in the order the product's rule dictates.
     *
     * Refuses rather than short-picking when there is not enough. A partial
     * allocation would complete a sale for less than the customer asked for and
     * hand them a receipt that says so, which is worse than being told at the
     * counter that the stock is not there.
     */
    @Transactional(readOnly = true)
    fun pick(product: Product, qtyBase: BigDecimal, preferredLotId: Long? = null): List<LotPick> {
        val available = lots.onHandForProduct(product.branchId, product.id!!, product.pickingRule)

        val ordered = when {
            // MANUAL means the cashier named the pile. Honour it first, then
            // fall through to the rest — a named lot that runs out mid-quantity
            // must not silently fail.
            preferredLotId != null ->
                available.filter { it.lotId == preferredLotId } + available.filter { it.lotId != preferredLotId }

            product.pickingRule == "MANUAL" -> throw ApiException.Validation(
                "${product.name} is picked by hand — choose which batch to sell from.",
                mapOf("lotId" to "is required for a manually picked product"),
            )

            else -> available
        }

        if (preferredLotId != null && available.none { it.lotId == preferredLotId }) {
            throw ApiException.RuleViolation(
                "LOT_NOT_AVAILABLE",
                "That batch of ${product.name} has no stock on hand.",
            )
        }

        var remaining = qtyBase
        val picks = mutableListOf<LotPick>()
        for (lot in ordered) {
            if (remaining <= BigDecimal.ZERO) break
            val take = remaining.min(lot.qtyBase)
            if (take <= BigDecimal.ZERO) continue
            picks += LotPick(lot.lotId, take, lot.unitCost)
            remaining = remaining.subtract(take)
        }

        if (remaining > BigDecimal.ZERO) {
            val onHand = available.fold(BigDecimal.ZERO) { acc, l -> acc.add(l.qtyBase) }
            throw ApiException.RuleViolation(
                "INSUFFICIENT_STOCK",
                "There is not enough ${product.name} in stock — " +
                    "${onHand.stripTrailingZeros().toPlainString()} available.",
            )
        }
        return picks
    }

    /**
     * Checks there is enough stock and returns what [qtyBase] would cost,
     * averaged over the lots that would be picked.
     *
     * Both jobs at once because a basket needs both answers at the same moment.
     * The cost feeds the discount margin floor, which has to be checkable while
     * the customer is still at the counter even though the real allocation does
     * not happen until they have paid. The availability check is there so a
     * cashier finds out that nine are not in stock while keying the line, rather
     * than after the customer has queued to pay for them.
     *
     * This is not a reservation and does not pretend to be one. Between here and
     * completion another till can take the same stock, and the balance CHECK is
     * what settles that (§5). This only stops the case nobody should have to
     * discover late: stock that was never there to begin with.
     */
    @Transactional(readOnly = true)
    fun assertAvailableAndEstimateCost(product: Product, qtyBase: BigDecimal): BigDecimal {
        if (qtyBase <= BigDecimal.ZERO) return BigDecimal.ZERO
        val available = lots.onHandForProduct(product.branchId, product.id!!, product.pickingRule)
        val onHand = available.fold(BigDecimal.ZERO) { acc, l -> acc.add(l.qtyBase) }

        if (onHand < qtyBase) {
            throw ApiException.RuleViolation(
                "INSUFFICIENT_STOCK",
                "There is not enough ${product.name} in stock — " +
                    "${onHand.stripTrailingZeros().toPlainString()} available.",
            )
        }

        /*
         * Costed against the rule's own order rather than the whole pool, so
         * the estimate matches what completion will actually issue. A MANUAL
         * product has no rule-determined order until the cashier names the lot,
         * so it is costed against the pool and re-costed for real at completion.
         */
        var remaining = qtyBase
        val picks = mutableListOf<LotPick>()
        for (lot in available) {
            if (remaining <= BigDecimal.ZERO) break
            val take = remaining.min(lot.qtyBase)
            picks += LotPick(lot.lotId, take, lot.unitCost)
            remaining = remaining.subtract(take)
        }
        return weightedCost(picks, qtyBase)
    }

    /** Cost per base unit across a set of picks. */
    fun weightedCost(picks: List<LotPick>, qtyBase: BigDecimal): BigDecimal {
        if (qtyBase <= BigDecimal.ZERO || picks.isEmpty()) return BigDecimal.ZERO
        val total = picks.fold(BigDecimal.ZERO) { acc, p -> acc.add(p.qtyBase.multiply(p.unitCost)) }
        return total.divide(qtyBase, Money.PRICE_SCALE, RoundingMode.HALF_UP)
    }

}
