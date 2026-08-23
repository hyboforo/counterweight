package com.counterweight.sales.service

import com.counterweight.common.ApiException
import com.counterweight.identity.security.Auth
import com.counterweight.identity.service.AuditService
import com.counterweight.inventory.service.InventoryService
import com.counterweight.inventory.service.StockIssue
import com.counterweight.parties.service.CustomerAccountService
import com.counterweight.platform.service.DocumentNumberService
import com.counterweight.pricing.service.Money
import com.counterweight.sales.domain.Sale
import com.counterweight.sales.domain.SaleReturn
import com.counterweight.sales.domain.SaleReturnLine
import com.counterweight.sales.event.SaleReturned
import com.counterweight.sales.repo.*
import org.springframework.context.ApplicationEventPublisher
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * One line coming back.
 *
 * [qty] is in the selling unit the customer bought in, because that is how they
 * describe what they are returning — two bags, not forty kilos.
 */
data class ReturnLineRequest(
    val saleLineId: Long,
    val qty: BigDecimal,
    /** False for goods coming back damaged: refunded, but never resold. */
    val restock: Boolean = true,
)

/**
 * Customer returns.
 *
 * Goods go back to the lot they came from, which is why `sale_line_allocation`
 * exists: without it a returned agro-chemical would land in a new lot with no
 * expiry date, becoming stock that is sellable past the date on the drum and
 * invisible to a recall. Where a line drew from several lots, the return unwinds
 * them newest-allocated-first, so what goes back on the shelf is the stock with
 * the most life left.
 *
 * A refund is never more than what was actually paid for the goods, and never
 * more of them than left the shop. Both are checked here, because the friendly
 * version of "you already returned that" is worth more than a constraint name.
 */
@Service
class SaleReturnService(
    private val sales: SaleRepository,
    private val saleLines: SaleLineRepository,
    private val allocations: SaleLineAllocationRepository,
    private val returns: SaleReturnRepository,
    private val returnLines: SaleReturnLineRepository,
    private val inventory: InventoryService,
    private val accounts: CustomerAccountService,
    private val documentNumbers: DocumentNumberService,
    private val cart: CartService,
    private val audit: AuditService,
    private val events: ApplicationEventPublisher,
) {

    @Transactional(readOnly = true)
    fun forSale(saleId: Long): List<SaleReturn> {
        cart.get(saleId)
        return returns.findBySaleIdOrderByCreatedAtDesc(saleId)
    }

    @Transactional(readOnly = true)
    fun linesOf(returnId: Long): List<SaleReturnLine> = returnLines.findBySaleReturnId(returnId)

    @Transactional
    @PreAuthorize("hasAuthority('SALE_RETURN')")
    fun createReturn(
        saleId: Long,
        lines: List<ReturnLineRequest>,
        reason: String,
        refundMethod: String,
    ): SaleReturn {
        if (reason.isBlank()) {
            throw ApiException.Validation("Say why the goods came back.", mapOf("reason" to "is required"))
        }
        if (lines.isEmpty()) {
            throw ApiException.Validation("Nothing was selected to return.", mapOf("lines" to "is required"))
        }
        if (refundMethod !in SaleReturn.REFUND_METHODS) {
            throw ApiException.Validation(
                "Unknown refund method '$refundMethod'.",
                mapOf("refundMethod" to "must be one of: ${SaleReturn.REFUND_METHODS.sorted().joinToString(", ")}"),
            )
        }

        val sale = cart.get(saleId)
        if (sale.status !in RETURNABLE_STATUSES) {
            throw ApiException.RuleViolation(
                "SALE_NOT_RETURNABLE",
                "A ${sale.status.lowercase()} sale cannot be returned against.",
            )
        }
        if (refundMethod == "ACCOUNT" && sale.customerId == null) {
            throw ApiException.RuleViolation(
                "NO_CUSTOMER_ON_SALE",
                "This was a walk-in sale, so there is no account to refund to.",
            )
        }

        val saleReturn = returns.saveAndFlush(
            SaleReturn(
                branchId = sale.branchId,
                saleId = saleId,
                number = documentNumbers.next(sale.branchId, "SALE_RETURN"),
                reason = reason.trim(),
                refundMethod = refundMethod,
                total = BigDecimal.ZERO,
                createdBy = Auth.current().id,
            )
        )

        var refundTotal = Money.ZERO
        val restocked = mutableListOf<StockIssue>()

        lines.forEach { request ->
            val line = saleLines.findById(request.saleLineId)
                .orElseThrow { ApiException.NotFound("Sale line", request.saleLineId) }
            if (line.saleId != saleId) throw ApiException.NotFound("Sale line", request.saleLineId)
            if (request.qty <= BigDecimal.ZERO) {
                throw ApiException.Validation(
                    "A returned quantity must be more than zero.",
                    mapOf("lines" to "qty must be greater than zero"),
                )
            }

            // Base units, from the ratio the line was sold at rather than by
            // re-converting: the factor may have been edited since, and the
            // line's own qty_base is the record of what actually left.
            val qtyBase = line.qtyBase.multiply(request.qty)
                .divide(line.qty, 4, RoundingMode.HALF_UP)

            val alreadyReturned = saleLines.returnedQtyBase(line.id!!)
            if (alreadyReturned.add(qtyBase) > line.qtyBase) {
                val remaining = line.qtyBase.subtract(alreadyReturned).max(BigDecimal.ZERO)
                throw ApiException.RuleViolation(
                    "RETURN_EXCEEDS_SALE",
                    "Only ${remaining.stripTrailingZeros().toPlainString()} of that line is left to return.",
                )
            }

            // Refund the share of what was actually charged, discount and tax
            // included. Refunding list price would hand back more than was paid
            // on any discounted line.
            val lineRefund = Money.round(
                line.lineTotal.multiply(qtyBase).divide(line.qtyBase, Money.SCALE + 4, RoundingMode.HALF_UP)
            )
            refundTotal = refundTotal.add(lineRefund)

            unwindAllocations(line.id!!, qtyBase).forEach { issue ->
                returnLines.save(
                    SaleReturnLine(
                        saleReturnId = saleReturn.id!!,
                        saleLineId = line.id!!,
                        qtyBase = issue.qtyBase,
                        lotId = issue.lotId,
                        amount = Money.round(
                            lineRefund.multiply(issue.qtyBase)
                                .divide(qtyBase, Money.SCALE + 4, RoundingMode.HALF_UP)
                        ),
                    ).also { it.restock = request.restock }
                )
                if (request.restock) restocked += issue
            }
        }

        if (restocked.isNotEmpty()) {
            inventory.returnToStock(saleReturn.id!!, restocked, "RETURN:${saleReturn.number}")
        }

        saleReturn.total = refundTotal
        returns.save(saleReturn)

        refund(sale, refundMethod, refundTotal, saleReturn.number, saleReturn.id!!)
        updateSaleStatus(sale)

        audit.recordCurrent(
            "SALE_RETURNED", "sale", saleId,
            after = """{"return":"${saleReturn.number}","total":"${refundTotal.toPlainString()}",""" +
                """"method":"$refundMethod"}""",
            reason = reason,
        )
        return saleReturn
    }

    // ── Internals ──────────────────────────────────────────────────────────

    /**
     * Decides which lots [qtyBase] goes back to.
     *
     * Newest allocation first. When a line spanned two lots the customer cannot
     * say which one their bag came from, so the choice is the shop's — and
     * putting back the stock with the longest remaining life is the one that
     * loses least to expiry.
     */
    private fun unwindAllocations(saleLineId: Long, qtyBase: BigDecimal): List<StockIssue> {
        val existing = allocations.findBySaleLineId(saleLineId).sortedByDescending { it.id }
        var remaining = qtyBase
        val result = mutableListOf<StockIssue>()

        for (allocation in existing) {
            if (remaining <= BigDecimal.ZERO) break
            val take = remaining.min(allocation.qtyBase)
            result += StockIssue(allocation.lotId, take, allocation.unitCost)
            remaining = remaining.subtract(take)
        }
        if (remaining > BigDecimal.ZERO) {
            // Only reachable if the allocation trail is thinner than the line it
            // belongs to, which would mean the completion path wrote one badly.
            throw ApiException.RuleViolation(
                "ALLOCATION_TRAIL_INCOMPLETE",
                "The batch record for that line does not cover the quantity being returned.",
            )
        }
        return result
    }

    /**
     * Gives the money back.
     *
     * CASH and MOBILE_MONEY are handed over at the counter and recorded by the
     * return itself — the takings report nets cash refunds off from there. The two
     * account-based methods post to the customer ledger, which is why they need
     * a customer.
     *
     * CREDIT_NOTE additionally gets a document, because that is what the
     * customer is handed and what they will quote when they come to spend it.
     * ACCOUNT does not: the credit lands on their statement, and issuing a
     * numbered note for it would put a document in the register that nobody
     * ever holds. Billing issues it off the event — see [SaleReturned].
     */
    private fun refund(sale: Sale, method: String, amount: BigDecimal, reference: String, returnId: Long) {
        if (amount <= BigDecimal.ZERO) return
        if (method == "CREDIT_NOTE" || method == "ACCOUNT") {
            accounts.postCreditNote(sale.customerId!!, amount, reference)
        }
        events.publishEvent(
            SaleReturned(
                saleReturnId = returnId,
                branchId = sale.branchId,
                saleId = sale.id!!,
                customerId = sale.customerId,
                refundMethod = method,
                total = amount,
                taxPortion = taxPortionOf(sale, amount),
                reference = reference,
            )
        )
    }

    /**
     * The share of a refund that was tax.
     *
     * Apportioned from the sale's own totals rather than recomputed at today's
     * rate, so a credit note raised after a rate change still reverses the tax
     * that was actually charged.
     */
    private fun taxPortionOf(sale: Sale, refundAmount: BigDecimal): BigDecimal {
        if (sale.grandTotal <= BigDecimal.ZERO || sale.taxTotal <= BigDecimal.ZERO) return BigDecimal.ZERO
        return Money.round(
            sale.taxTotal.multiply(refundAmount)
                .divide(sale.grandTotal, Money.SCALE + 4, RoundingMode.HALF_UP)
        )
    }

    /**
     * Moves the sale to RETURNED or PARTIALLY_RETURNED.
     *
     * Compared on quantity rather than on money: a fully returned sale that was
     * discounted would not add back up to its own total, and would sit at
     * PARTIALLY_RETURNED forever.
     */
    private fun updateSaleStatus(sale: Sale) {
        val lines = saleLines.findBySaleIdOrderByLineNoAsc(sale.id!!)
        val fullyReturned = lines.all { line ->
            saleLines.returnedQtyBase(line.id!!).compareTo(line.qtyBase) >= 0
        }
        sale.status = if (fullyReturned) "RETURNED" else "PARTIALLY_RETURNED"
        sales.save(sale)
    }

    private companion object {
        val RETURNABLE_STATUSES = setOf("COMPLETED", "PARTIALLY_RETURNED")
    }
}
