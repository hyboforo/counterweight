package com.counterweight.billing.service

import com.counterweight.billing.domain.Quotation
import com.counterweight.billing.domain.QuotationLine
import com.counterweight.billing.repo.QuotationLineRepository
import com.counterweight.billing.repo.QuotationRepository
import com.counterweight.catalog.repo.ProductRepository
import com.counterweight.catalog.service.UomConverter
import com.counterweight.common.ApiException
import com.counterweight.identity.security.Auth
import com.counterweight.identity.service.AuditService
import com.counterweight.parties.service.CustomerService
import com.counterweight.platform.service.DocumentNumberService
import com.counterweight.pricing.service.Money
import com.counterweight.pricing.service.PricingService
import com.counterweight.sales.domain.Sale
import com.counterweight.sales.service.CartService
import com.counterweight.sales.service.OverrideCredentials
import org.slf4j.LoggerFactory
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Quotations: a priced offer that may or may not become a sale.
 *
 * A quotation is not a draft sale, and the two are deliberately different
 * things. A draft sale is a basket at a till with somebody standing behind it;
 * a quotation is a document handed to a contractor who will come back next week
 * or never. Draft sales get thrown away, quotations get filed and looked up by
 * number.
 *
 * Nothing here touches stock. Quoting for forty bags of cement must not reserve
 * forty bags — the shop would be unable to sell stock it still owns to whoever
 * walks in with cash.
 */
@Service
class QuotationService(
    private val quotations: QuotationRepository,
    private val quotationLines: QuotationLineRepository,
    private val products: ProductRepository,
    private val pricing: PricingService,
    private val uom: UomConverter,
    private val customers: CustomerService,
    private val numbers: DocumentNumberService,
    private val cart: CartService,
    private val audit: AuditService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // ── Reads ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    fun get(id: Long): Quotation {
        val quotation = quotations.findById(id).orElseThrow { ApiException.NotFound("Quotation", id) }
        if (quotation.branchId != Auth.current().branchId) throw ApiException.NotFound("Quotation", id)
        return quotation
    }

    @Transactional(readOnly = true)
    fun lines(quotationId: Long): List<QuotationLine> {
        get(quotationId)
        return quotationLines.findByQuotationIdOrderByLineNoAsc(quotationId)
    }

    @Transactional(readOnly = true)
    fun open(): List<Quotation> =
        quotations.findByBranchIdAndStatusOrderByCreatedAtDesc(Auth.current().branchId, "OPEN")

    @Transactional(readOnly = true)
    fun forCustomer(customerId: Long): List<Quotation> {
        customers.get(customerId)
        return quotations.findByCustomerIdOrderByCreatedAtDesc(customerId)
    }

    // ── Writing ────────────────────────────────────────────────────────────

    @Transactional
    @PreAuthorize("hasAuthority('SALE_CREATE')")
    fun create(customerId: Long?, validUntil: LocalDate?): Quotation {
        val actor = Auth.current()
        customerId?.let { customers.get(it) }
        if (validUntil != null && validUntil.isBefore(LocalDate.now())) {
            throw ApiException.Validation(
                "A quotation cannot expire before it is written.",
                mapOf("validUntil" to "must not be in the past"),
            )
        }
        val saved = quotations.save(
            Quotation(
                branchId = actor.branchId,
                number = numbers.next(actor.branchId, "QUOTATION"),
                createdBy = actor.id,
            ).also {
                it.customerId = customerId
                it.validUntil = validUntil
            }
        )
        audit.recordCurrent("QUOTATION_CREATED", "quotation", saved.id, after = """{"number":"${saved.number}"}""")
        return saved
    }

    @Transactional
    @PreAuthorize("hasAuthority('SALE_CREATE')")
    fun addLine(
        quotationId: Long,
        productId: Long,
        productUomId: Long,
        qty: BigDecimal,
        unitPriceOverride: BigDecimal? = null,
    ): QuotationLine {
        val quotation = openQuotation(quotationId)
        val product = products.findById(productId).orElseThrow { ApiException.NotFound("Product", productId) }
        if (product.branchId != quotation.branchId) throw ApiException.NotFound("Product", productId)

        uom.assertPositive(qty)
        uom.assertPrecision(productUomId, qty)

        /*
         * Priced at today's list, and then frozen. That is the promise a
         * quotation makes — re-resolving on read would mean a customer coming
         * back with a printed offer finds a different number on the screen, and
         * `valid_until` exists precisely so the shop can decide how long it is
         * prepared to hold that price.
         */
        val unitPrice = unitPriceOverride?.let(Money::roundPrice)
            ?: pricing.resolve(productId, productUomId, priceListFor(quotation)).unitPrice

        val line = quotationLines.save(
            QuotationLine(
                quotationId = quotationId,
                lineNo = quotationLines.maxLineNo(quotationId) + 1,
                productId = productId,
                productUomId = productUomId,
                qty = qty,
                unitPrice = unitPrice,
                lineTotal = Money.round(unitPrice.multiply(qty)),
            )
        )
        recalculate(quotation)
        return line
    }

    @Transactional
    @PreAuthorize("hasAuthority('SALE_CREATE')")
    fun removeLine(quotationId: Long, lineId: Long) {
        val quotation = openQuotation(quotationId)
        val line = quotationLines.findById(lineId).orElseThrow { ApiException.NotFound("Quotation line", lineId) }
        if (line.quotationId != quotationId) throw ApiException.NotFound("Quotation line", lineId)
        quotationLines.delete(line)
        recalculate(quotation)
    }

    /**
     * Turns an accepted quotation into a basket at the till.
     *
     * The quoted prices carry over as overrides rather than being re-resolved,
     * which is the whole point of having quoted them. Stock is checked here for
     * the first time — a quotation never reserved any — so a quotation for goods
     * that have since sold out fails at this step, which is the right moment to
     * find out.
     *
     * A quoted price below the list price is authorised **here**, not when the
     * quotation was written. Writing one moves no money and promises nothing the
     * shop cannot withdraw; converting it is where goods actually leave below
     * list, so that is where `SALE_PRICE_OVERRIDE` belongs. The consequence is
     * that a cashier converting a manager's quotation needs a supervisor, which
     * is why [approval] exists — without it the shop would either stop using
     * quotations or hand every cashier the override permission, and the second
     * is worse.
     *
     * The resulting sale is an ordinary draft: it still has to be paid for, and
     * it can still be edited, held or discarded like any other.
     */
    @Transactional
    @PreAuthorize("hasAuthority('SALE_CREATE')")
    fun convertToSale(quotationId: Long, approval: OverrideCredentials? = null): Sale {
        val quotation = openQuotation(quotationId)
        val lines = quotationLines.findByQuotationIdOrderByLineNoAsc(quotationId)
        if (lines.isEmpty()) {
            throw ApiException.RuleViolation("EMPTY_QUOTATION", "There is nothing on this quotation to sell.")
        }

        val sale = cart.startSale(quotation.customerId)
        lines.forEach { line ->
            cart.addLine(
                saleId = sale.id!!,
                productId = line.productId,
                productUomId = line.productUomId,
                qty = line.qty,
                unitPriceOverride = line.unitPrice,
                approval = approval,
            )
        }

        quotation.status = "CONVERTED"
        quotation.convertedSaleId = sale.id
        quotations.save(quotation)

        audit.recordCurrent(
            "QUOTATION_CONVERTED", "quotation", quotationId,
            after = """{"number":"${quotation.number}","saleId":${sale.id}}""",
        )
        return cart.get(sale.id!!)
    }

    @Transactional
    @PreAuthorize("hasAuthority('SALE_CREATE')")
    fun cancel(quotationId: Long, reason: String?): Quotation {
        val quotation = openQuotation(quotationId)
        quotation.status = "CANCELLED"
        val saved = quotations.save(quotation)
        audit.recordCurrent(
            "QUOTATION_CANCELLED", "quotation", quotationId,
            after = """{"number":"${quotation.number}"}""", reason = reason,
        )
        return saved
    }

    /**
     * Marks quotations past their validity as expired.
     *
     * Applied rather than computed on read, so a quotation the shop is no
     * longer honouring cannot be converted by whoever opens it next. Returns
     * how many were closed, which alerting reports on.
     */
    @Transactional
    fun expireStale(onDate: LocalDate = LocalDate.now()): Int {
        val stale = quotations.expiredOn(onDate)
        stale.forEach { it.status = "EXPIRED" }
        if (stale.isNotEmpty()) {
            quotations.saveAll(stale)
            log.info("expired {} quotation(s) as of {}", stale.size, onDate)
        }
        return stale.size
    }

    // ── Internals ──────────────────────────────────────────────────────────

    private fun openQuotation(quotationId: Long): Quotation {
        val quotation = get(quotationId)
        if (!quotation.isOpen) {
            throw ApiException.RuleViolation(
                "QUOTATION_NOT_OPEN",
                "That quotation is ${quotation.status.lowercase()} and can no longer be used.",
            )
        }
        if (quotation.validUntil?.isBefore(LocalDate.now()) == true) {
            // Caught here as well as by the sweep, so a quotation that expired
            // overnight cannot be converted before the sweep next runs.
            throw ApiException.RuleViolation(
                "QUOTATION_EXPIRED",
                "That quotation was only valid until ${quotation.validUntil}. Price it again.",
            )
        }
        return quotation
    }

    private fun recalculate(quotation: Quotation): Quotation {
        val lines = quotationLines.findByQuotationIdOrderByLineNoAsc(quotation.id!!)
        quotation.total = lines.fold(Money.ZERO) { acc, l -> acc.add(l.lineTotal) }
        return quotations.save(quotation)
    }

    private fun priceListFor(quotation: Quotation): Long? =
        quotation.customerId?.let { customers.get(it).priceListId }
}
