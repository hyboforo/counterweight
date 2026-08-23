package com.counterweight.alerting.service

import com.counterweight.alerting.domain.AlertRule
import com.counterweight.parties.service.CustomerAccountService
import com.counterweight.parties.service.CustomerService
import com.counterweight.pricing.service.Money
import com.counterweight.sales.event.SaleCompleted
import com.counterweight.sales.service.SaleDetailService
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Alerts a completed sale can answer.
 *
 * Sold below cost is the one worth having. The discount rules already stop a
 * cashier giving away margin they are not entitled to, but a supervisor
 * override, a hand-keyed price and a lot that came in dearer than the shelf
 * price all get there legitimately — and the only place that shows up is after
 * the fact, comparing what was charged against what the lot actually cost.
 *
 * Unlike billing's listener, nothing here may fail the sale. The money has
 * been taken and the customer is standing there.
 */
@Service
class SaleAlertEvaluator(
    private val alerts: AlertService,
    private val saleDetails: SaleDetailService,
    private val customers: CustomerService,
    private val accounts: CustomerAccountService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    fun onSaleCompleted(event: SaleCompleted) {
        runCatching {
            checkSoldBelowCost(event)
            checkCreditLimit(event)
        }.onFailure { log.error("could not evaluate sale alerts for sale {}", event.saleId, it) }
    }

    /**
     * A line whose net price did not cover what the stock cost.
     *
     * Compared per line rather than per sale: a basket can be profitable
     * overall while one item on it was sold at a loss, and it is the item the
     * shop needs to know about — usually a price that was never updated after
     * the supplier raised theirs.
     *
     * The alert is keyed on the sale, not the product, so a persistent pricing
     * mistake raises one alert per sale rather than being deduped away after
     * the first. Each occurrence is a separate loss.
     */
    private fun checkSoldBelowCost(event: SaleCompleted) {
        val detail = saleDetails.of(event.saleId)

        val losses = detail.lines.mapNotNull { line ->
            val l = line.line
            if (l.qty <= BigDecimal.ZERO) return@mapNotNull null

            // Both sides per selling unit: the price is quoted that way and the
            // cost is stored per base unit.
            val netPerUnit = Money.roundPrice(
                l.lineTotal.subtract(l.taxAmount).divide(l.qty, Money.PRICE_SCALE, RoundingMode.HALF_UP)
            )
            val costPerUnit = Money.roundPrice(
                l.unitCost.multiply(l.qtyBase).divide(l.qty, Money.PRICE_SCALE, RoundingMode.HALF_UP)
            )
            if (netPerUnit < costPerUnit) {
                Triple(line.productName, netPerUnit, costPerUnit)
            } else null
        }
        if (losses.isEmpty()) return

        val body = losses.joinToString("; ") { (name, price, cost) ->
            "$name sold at ${price.stripTrailingZeros().toPlainString()} " +
                "against a cost of ${cost.stripTrailingZeros().toPlainString()}"
        }
        alerts.raise(
            AlertRequest(
                ruleType = AlertRule.SOLD_BELOW_COST,
                branchId = event.branchId,
                dedupeKey = "${AlertRule.SOLD_BELOW_COST}:${event.branchId}:sale${event.saleId}",
                title = "Sold below cost on ${event.number}",
                body = body,
                subjectType = "sale",
                subjectId = event.saleId,
            )
        )
    }

    /**
     * A customer at or over their credit limit.
     *
     * Raised after the sale rather than instead of the credit check: the check
     * decides whether the sale may happen, this notices that the account has
     * arrived at its ceiling. Keyed on the customer so it stays a single open
     * alert until they pay something off, at which point it auto-resolves.
     */
    private fun checkCreditLimit(event: SaleCompleted) {
        val customerId = event.customerId ?: return
        val key = "${AlertRule.CREDIT_LIMIT_REACHED}:${event.branchId}:customer$customerId"

        val standing = accounts.standing(customerId)
        val limit = standing.creditLimit
        if (limit == null || limit <= BigDecimal.ZERO) return

        if (standing.balance >= limit) {
            val customer = customers.get(customerId)
            alerts.raise(
                AlertRequest(
                    ruleType = AlertRule.CREDIT_LIMIT_REACHED,
                    branchId = event.branchId,
                    dedupeKey = key,
                    title = "Credit limit reached: ${customer.name}",
                    body = "Owes ${standing.balance.toPlainString()} against a limit of " +
                        "${limit.toPlainString()}.",
                    subjectType = "customer",
                    subjectId = customerId,
                )
            )
        } else {
            alerts.autoResolve(key)
        }
    }
}
