package com.counterweight.sales.service

import com.counterweight.catalog.service.ProductAttributes
import com.counterweight.catalog.repo.ProductRepository
import com.counterweight.common.ApiException
import com.counterweight.identity.security.Auth
import com.counterweight.identity.service.AuditService
import com.counterweight.identity.service.SupervisorOverrideService
import com.counterweight.inventory.service.InventoryService
import com.counterweight.inventory.service.StockIssue
import com.counterweight.parties.service.CreditOutcome
import com.counterweight.parties.service.CustomerAccountService
import com.counterweight.platform.service.DocumentNumberService
import com.counterweight.pricing.service.Money
import com.counterweight.sales.domain.*
import com.counterweight.sales.event.SaleCompleted
import com.counterweight.sales.event.SaleVoided
import com.counterweight.sales.repo.*
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** One tender against a sale. Split tender is normal, so these come as a list. */
data class TenderLine(
    val method: String,
    val amount: BigDecimal,
    /** Cash only: what the customer handed over. Change is derived. */
    val tendered: BigDecimal? = null,
    val momoNetwork: String? = null,
    val reference: String? = null,
    val bankName: String? = null,
    val chequeNumber: String? = null,
    val chequeDate: LocalDate? = null,
)

/** Buyer details for a restricted agro-chemical line (§6.3). */
data class BuyerRecord(
    val saleLineId: Long,
    val buyerName: String,
    val buyerPhone: String? = null,
    val buyerIdType: String? = null,
    val buyerIdNumber: String? = null,
    val intendedUse: String? = null,
)

data class CompleteSaleCommand(
    val saleId: Long,
    val tenders: List<TenderLine>,
    /** Which till is ringing this up. Routes a receipt; null in the back office. */
    val tillCode: String? = null,
    val buyerRecords: List<BuyerRecord> = emptyList(),
    /** Line id → lot id, for products the cashier picks by hand. */
    val lotChoices: Map<Long, Long> = emptyMap(),
    /** Offered when the sale goes over the customer's credit limit. */
    val creditApproval: OverrideCredentials? = null,
)

sealed interface SaleResult {
    val sale: Sale

    /** The sale went through on this request. */
    data class Completed(override val sale: Sale, val payments: List<SalePayment>) : SaleResult

    /** This request had already been honoured; nothing was posted again. */
    data class Replayed(override val sale: Sale) : SaleResult
}

/**
 * Turns a basket into a sale: stock out, money in, number issued.
 *
 * The whole thing is one transaction, and the order inside it matters. Stock is
 * issued before payment is recorded, because the balance CHECK is the only
 * thing standing between two tills and the same last bag of cement — finding
 * out after taking the money is worse than finding out before. The document
 * number is issued last, so a sale that fails anywhere earlier leaves no hole
 * in the register (§8.3).
 */
@Service
class SaleCompletionService(
    private val sales: SaleRepository,
    private val saleLines: SaleLineRepository,
    private val allocations: SaleLineAllocationRepository,
    private val payments: SalePaymentRepository,
    private val idempotency: IdempotencyRecordRepository,
    private val restrictedRecords: RestrictedSaleRecordRepository,
    private val cart: CartService,
    private val picking: PickingService,
    private val inventory: InventoryService,
    private val products: ProductRepository,
    private val productAttributes: ProductAttributes,
    private val accounts: CustomerAccountService,
    private val documentNumbers: DocumentNumberService,
    private val overrides: SupervisorOverrideService,
    private val audit: AuditService,
    private val events: ApplicationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    @PreAuthorize("hasAuthority('SALE_CREATE')")
    fun complete(cmd: CompleteSaleCommand, idempotencyKey: UUID): SaleResult {
        /*
         * The replay check comes first and does nothing else. A retried request
         * — double click, impatient operator, a switch that dropped the
         * response — must return the original sale rather than deduct stock a
         * second time (§8.3).
         *
         * Two genuinely simultaneous requests with the same key both miss here;
         * the primary key on idempotency_record then lets exactly one commit
         * and rolls the other back, and its retry finds the record. The
         * constraint is the guarantee, this lookup is the fast path.
         */
        idempotency.findById(idempotencyKey).orElse(null)?.let { existing ->
            log.info("replaying sale {} for idempotency key {}", existing.saleId, idempotencyKey)
            return SaleResult.Replayed(sales.findById(existing.saleId).orElseThrow())
        }

        val sale = cart.openSale(cmd.saleId)
        val lines = saleLines.findBySaleIdOrderByLineNoAsc(sale.id!!)
        if (lines.isEmpty()) {
            throw ApiException.RuleViolation("EMPTY_SALE", "There is nothing in this sale to pay for.")
        }

        // Re-priced rather than trusted: the basket may have been sitting on
        // hold since before a price change, and the customer pays what the shop
        // charges now.
        cart.recalculate(sale)

        requireBuyerRecords(lines, cmd.buyerRecords)
        allocateAndIssue(sale, lines, cmd.lotChoices)
        validateTenders(sale, cmd.tenders)

        val recorded = recordPayments(sale, cmd)

        sale.tillCode = cmd.tillCode?.trim()?.takeIf(String::isNotEmpty)
        sale.number = documentNumbers.next(sale.branchId, "RECEIPT")
        sale.status = "COMPLETED"
        sale.completedAt = Instant.now()
        sales.saveAndFlush(sale)

        idempotency.save(IdempotencyRecord(idempotencyKey, sale.id!!))

        /*
         * Published inside this transaction, and listeners run synchronously on
         * this thread. `billing` issues the receipt and any invoice from here,
         * and if it cannot, the sale rolls back — which is what we want, since a
         * completed sale nobody can produce paperwork for is not a completed
         * sale. See SaleCompleted for why this is an event rather than a call.
         */
        events.publishEvent(
            SaleCompleted(
                saleId = sale.id!!,
                branchId = sale.branchId,
                number = sale.number!!,
                customerId = sale.customerId,
                grandTotal = sale.grandTotal,
                taxTotal = sale.taxTotal,
                onAccountAmount = recorded.onAccountAmount,
                ledgerEntryId = recorded.ledgerEntryId,
                productIds = lines.map { it.productId }.distinct(),
            )
        )

        audit.recordCurrent(
            "SALE_COMPLETED", "sale", sale.id,
            after = """{"number":"${sale.number}","total":"${sale.grandTotal.toPlainString()}",""" +
                """"lines":${lines.size}}""",
        )
        return SaleResult.Completed(sale, recorded.payments)
    }

    /**
     * Reverses a completed sale in full.
     *
     * Distinct from a return: a return is the customer changing their mind
     * about goods they took, while a void says the transaction should never
     * have been recorded — a mis-scan, a training mistake, the wrong customer.
     * Stock goes back to the lots it came from and any account posting is
     * reversed, but the sale itself stays on the record marked VOIDED, because
     * a sale that vanishes is indistinguishable from one that was stolen.
     */
    @Transactional
    @PreAuthorize("hasAuthority('SALE_VOID')")
    fun voidSale(saleId: Long, reason: String): Sale {
        if (reason.isBlank()) {
            throw ApiException.Validation(
                "Say why the sale is being voided.",
                mapOf("reason" to "is required"),
            )
        }
        val sale = cart.get(saleId)
        if (sale.status != "COMPLETED") {
            throw ApiException.RuleViolation(
                "SALE_NOT_VOIDABLE",
                "Only a completed sale can be voided; this one is ${sale.status.lowercase()}.",
            )
        }

        val issues = saleLines.findBySaleIdOrderByLineNoAsc(saleId).flatMap { line ->
            allocations.findBySaleLineId(line.id!!).map { StockIssue(it.lotId, it.qtyBase, it.unitCost) }
        }
        inventory.returnToStock(saleId, issues, "VOID:$reason")

        // Anything that went on account has to come back off it, or voiding a
        // sale would leave the customer owing for goods the shop still has.
        sale.customerId?.let { customerId ->
            val onAccount = payments.findBySaleId(saleId)
                .filter { it.method == "ON_ACCOUNT" }
                .fold(BigDecimal.ZERO) { acc, p -> acc.add(p.amount) }
            if (onAccount > BigDecimal.ZERO) {
                accounts.postCreditNote(customerId, onAccount, "Void of ${sale.number}")
            }
        }

        sale.status = "VOIDED"
        sale.voidedAt = Instant.now()
        sale.voidReason = reason
        sale.voidedBy = Auth.current().id
        val saved = sales.save(sale)

        events.publishEvent(SaleVoided(saleId, sale.branchId, sale.number))

        audit.recordCurrent(
            "SALE_VOIDED", "sale", saleId,
            before = """{"number":"${sale.number}","total":"${sale.grandTotal.toPlainString()}"}""",
            reason = reason,
        )
        return saved
    }

    // ── Steps ──────────────────────────────────────────────────────────────

    /**
     * Refuses to complete a sale of restricted goods without the buyer written
     * down.
     *
     * The register has to be filled in at the counter or it does not get filled
     * in at all — "we will take their details later" is how a licence condition
     * turns into a stack of blank rows.
     */
    private fun requireBuyerRecords(lines: List<SaleLine>, supplied: List<BuyerRecord>) {
        val byLine = supplied.associateBy { it.saleLineId }
        // In bulk, like the rest of the completion path: a contractor's basket
        // runs to thirty lines and the customer is standing at the counter.
        val productsById = products.findAllById(lines.map { it.productId }).associateBy { it.id!! }
        lines.forEach { line ->
            val product = productsById[line.productId]
            if (product == null || !productAttributes.requiresBuyerRecord(product)) return@forEach

            val record = byLine[line.id]
                ?: throw ApiException.RuleViolation(
                    "BUYER_RECORD_REQUIRED",
                    "${product.name} is restricted — record the buyer's " +
                        "name and ID before completing the sale.",
                )
            if (record.buyerName.isBlank()) {
                throw ApiException.Validation(
                    "The buyer's name is required for restricted products.",
                    mapOf("buyerRecords" to "buyerName is required"),
                )
            }
            restrictedRecords.save(
                RestrictedSaleRecord(
                    saleLineId = line.id!!,
                    buyerName = record.buyerName.trim(),
                    recordedBy = Auth.current().id,
                ).also {
                    it.buyerPhone = record.buyerPhone?.trim()
                    it.buyerIdType = record.buyerIdType?.trim()
                    it.buyerIdNumber = record.buyerIdNumber?.trim()
                    it.intendedUse = record.intendedUse?.trim()
                }
            )
        }
    }

    /**
     * Picks the lots, posts the issues and writes the allocation trail.
     *
     * The line's cost is rewritten here from what was actually issued. Up to
     * this point it held an estimate taken from the lots the rule *would* pick,
     * which is what let the margin floor work at the counter; from here it is
     * the real figure, so margin reporting needs no recomputation later.
     */
    private fun allocateAndIssue(sale: Sale, lines: List<SaleLine>, lotChoices: Map<Long, Long>) {
        val issues = mutableListOf<StockIssue>()

        lines.forEach { line ->
            val product = products.findById(line.productId)
                .orElseThrow { ApiException.NotFound("Product", line.productId) }

            val picks = picking.pick(product, line.qtyBase, lotChoices[line.id])
            picks.forEach { pick ->
                allocations.save(
                    SaleLineAllocation(
                        saleLineId = line.id!!,
                        lotId = pick.lotId,
                        qtyBase = pick.qtyBase,
                        unitCost = pick.unitCost,
                    )
                )
                issues += StockIssue(pick.lotId, pick.qtyBase, pick.unitCost)
            }
            line.unitCost = picking.weightedCost(picks, line.qtyBase)
            saleLines.save(line)
        }

        inventory.issueForSale(sale.id!!, issues)
    }

    /**
     * The tenders must add up to the payable total exactly.
     *
     * Not "at least": an overpayment that is not cash has nowhere to go, since
     * only cash can give change. A customer paying GHS 100 by mobile money for
     * a GHS 97 basket has to be handled as a payment on account, not silently
     * absorbed.
     */
    private fun validateTenders(sale: Sale, tenders: List<TenderLine>) {
        if (tenders.isEmpty()) {
            throw ApiException.Validation(
                "How is this being paid for?",
                mapOf("tenders" to "at least one payment is required"),
            )
        }
        tenders.forEach { tender ->
            if (tender.method !in SalePayment.METHODS) {
                throw ApiException.Validation(
                    "Unknown payment method '${tender.method}'.",
                    mapOf("tenders" to "must be one of: ${SalePayment.METHODS.sorted().joinToString(", ")}"),
                )
            }
            if (tender.amount <= BigDecimal.ZERO) {
                throw ApiException.Validation(
                    "A payment must be more than zero.",
                    mapOf("tenders" to "amount must be greater than zero"),
                )
            }
            if (tender.method == "MOBILE_MONEY") {
                if (tender.momoNetwork !in SalePayment.MOMO_NETWORKS) {
                    throw ApiException.Validation(
                        "Which mobile-money network was used?",
                        mapOf("tenders" to "momoNetwork must be MTN, TELECEL or AT"),
                    )
                }
                // No API to confirm it against — the shop has no internet — so
                // the reference the customer reads out is the entire record.
                if (tender.reference.isNullOrBlank()) {
                    throw ApiException.Validation(
                        "Key in the mobile-money reference the customer read out.",
                        mapOf("tenders" to "reference is required for mobile money"),
                    )
                }
            }
            if (tender.method == "CHEQUE" && tender.chequeNumber.isNullOrBlank()) {
                throw ApiException.Validation(
                    "Record the cheque number.",
                    mapOf("tenders" to "chequeNumber is required for a cheque"),
                )
            }
            if (tender.method == "CASH" && tender.tendered != null && tender.tendered < tender.amount) {
                throw ApiException.Validation(
                    "The cash tendered is less than the amount being paid.",
                    mapOf("tenders" to "tendered is less than amount"),
                )
            }
        }

        val total = tenders.fold(Money.ZERO) { acc, t -> acc.add(Money.round(t.amount)) }
        if (total.compareTo(sale.grandTotal) != 0) {
            throw ApiException.Validation(
                "The payments come to GHS ${total.toPlainString()} but the sale is " +
                    "GHS ${sale.grandTotal.toPlainString()}.",
                mapOf("tenders" to "must add up to the sale total"),
            )
        }
    }

    /** What recording the tenders produced, including anything that went on account. */
    private data class PaymentOutcome(
        val payments: List<SalePayment>,
        val onAccountAmount: BigDecimal,
        val ledgerEntryId: Long?,
    )

    private fun recordPayments(sale: Sale, cmd: CompleteSaleCommand): PaymentOutcome {
        var onAccount = BigDecimal.ZERO
        var ledgerEntryId: Long? = null

        val recorded = cmd.tenders.map { tender ->
            if (tender.method == "ON_ACCOUNT") {
                onAccount = onAccount.add(Money.round(tender.amount))
                // Split tender can name ON_ACCOUNT more than once; the ledger
                // gets one entry per tender line and billing points its invoice
                // at the last, which is the only one a single column can hold.
                ledgerEntryId = chargeToAccount(sale, tender.amount, cmd.creditApproval)
            }

            payments.save(
                SalePayment(
                    saleId = sale.id!!,
                    method = tender.method,
                    amount = Money.round(tender.amount),
                ).also {
                    it.tendered = tender.tendered?.let(Money::round)
                    // Change is derived rather than accepted from the client:
                    // it is arithmetic, and the takings are computed from it.
                    it.changeGiven = tender.tendered
                        ?.let { given -> Money.round(given.subtract(tender.amount)) }
                        ?.takeIf { change -> change > BigDecimal.ZERO }
                    it.momoNetwork = tender.momoNetwork
                    it.reference = tender.reference?.trim()?.takeIf(String::isNotEmpty)
                    it.bankName = tender.bankName?.trim()
                    it.chequeNumber = tender.chequeNumber?.trim()
                    it.chequeDate = tender.chequeDate
                }
            )
        }
        return PaymentOutcome(recorded, onAccount, ledgerEntryId)
    }

    /**
     * Puts an amount on the customer's account, checking their credit first.
     *
     * Over the limit is a supervisor's call, so a PIN can carry it (§8.2). No
     * account at all is not overridable — there is nothing to charge, and the
     * fix is to open one.
     */
    private fun chargeToAccount(sale: Sale, amount: BigDecimal, approval: OverrideCredentials?): Long {
        val customerId = sale.customerId
            ?: throw ApiException.RuleViolation(
                "NO_CUSTOMER_ON_SALE",
                "Choose the customer before putting this sale on account.",
            )

        val decision = accounts.evaluateOnAccount(customerId, amount)
        when (decision.outcome) {
            CreditOutcome.ALLOWED -> Unit

            CreditOutcome.REFUSED -> throw ApiException.RuleViolation(
                "CREDIT_REFUSED",
                decision.reason ?: "This sale cannot go on account.",
            )

            CreditOutcome.REQUIRES_APPROVAL -> {
                val credentials = approval ?: throw ApiException.RuleViolation(
                    "CREDIT_NEEDS_APPROVAL",
                    decision.reason ?: "This sale is over the customer's credit limit.",
                )
                val approver = overrides.verify(
                    credentials.username, credentials.pin, "CREDIT_APPROVE",
                    "credit over limit on sale ${sale.id}",
                )
                audit.recordCurrent(
                    "CREDIT_LIMIT_OVERRIDDEN", "customer", customerId,
                    after = """{"amount":"${amount.toPlainString()}","approvedBy":${approver.userId}}""",
                )
            }
        }
        // The document does not exist yet — billing issues it from the
        // SaleCompleted event and points this entry at it afterwards.
        return accounts.postInvoice(customerId, amount, null, sale.number ?: "SALE-${sale.id}").id!!
    }

}
