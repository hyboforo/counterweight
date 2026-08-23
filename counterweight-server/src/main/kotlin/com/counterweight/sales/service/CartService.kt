package com.counterweight.sales.service

import com.counterweight.catalog.domain.Product
import com.counterweight.catalog.repo.ProductRepository
import com.counterweight.catalog.service.ProductAttributes
import com.counterweight.catalog.service.UomConverter
import com.counterweight.common.ApiException
import com.counterweight.identity.security.Auth
import com.counterweight.identity.service.AuditService
import com.counterweight.identity.service.SupervisorOverrideService
import com.counterweight.parties.service.CustomerService
import com.counterweight.pricing.service.CashRounding
import com.counterweight.pricing.service.DiscountAuthority
import com.counterweight.pricing.service.DiscountOutcome
import com.counterweight.pricing.service.Money
import com.counterweight.pricing.service.PricingService
import com.counterweight.pricing.service.TaxCalculator
import com.counterweight.sales.domain.Sale
import com.counterweight.sales.domain.SaleLine
import com.counterweight.sales.repo.SaleLineRepository
import com.counterweight.sales.repo.SaleRepository
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * A supervisor's till PIN, offered to authorise something the operator cannot
 * do alone.
 *
 * Never a boolean "approved" flag. The credentials are verified and the
 * original check is then re-run under the approver's own roles, so an override
 * grants exactly what that supervisor is entitled to grant and the audit trail
 * names them.
 */
data class OverrideCredentials(val username: String, val pin: String)

/**
 * The basket, before anybody has paid.
 *
 * Every mutation here re-prices the whole sale rather than patching a running
 * total, because the alternative is a subtotal that disagrees with the sum of
 * its lines after some edit path nobody thought about. Re-pricing a basket of a
 * dozen lines costs nothing at a counter.
 *
 * Held sales live on the server rather than in browser memory (§8.1): a
 * customer leaving to find a mobile-money agent is routine, and any till has to
 * be able to recall the basket — including the till that did not start it.
 */
@Service
class CartService(
    private val sales: SaleRepository,
    private val saleLines: SaleLineRepository,
    private val products: ProductRepository,
    private val pricing: PricingService,
    private val discounts: DiscountAuthority,
    private val tax: TaxCalculator,
    private val rounding: CashRounding,
    private val uom: UomConverter,
    private val productAttributes: ProductAttributes,
    private val picking: PickingService,
    private val customers: CustomerService,
    private val overrides: SupervisorOverrideService,
    private val audit: AuditService,
) {

    // ── Reads ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    fun get(saleId: Long): Sale {
        val sale = sales.findById(saleId).orElseThrow { ApiException.NotFound("Sale", saleId) }
        if (sale.branchId != Auth.current().branchId) throw ApiException.NotFound("Sale", saleId)
        return sale
    }

    @Transactional(readOnly = true)
    fun lines(saleId: Long): List<SaleLine> {
        get(saleId)
        return saleLines.findBySaleIdOrderByLineNoAsc(saleId)
    }

    /**
     * Which of these lines the counter must write a buyer down for (§6.3).
     *
     * The till needs this the moment the item is scanned, not when the money
     * has been counted: the register is filled in with the customer standing
     * there or it is not filled in at all. Answered by the server rather than
     * inferred on the client, because whether a product is restricted is a
     * licence question and the till is not the authority on it.
     */
    @Transactional(readOnly = true)
    fun restrictedProductIds(lines: List<SaleLine>): Set<Long> =
        products.findAllById(lines.map { it.productId })
            .filter { productAttributes.requiresBuyerRecord(it) }
            .mapNotNull { it.id }
            .toSet()

    /** Baskets parked mid-transaction, recallable from any till. */
    @Transactional(readOnly = true)
    fun held(): List<Sale> =
        sales.findByBranchIdAndStatusOrderByCreatedAtDesc(Auth.current().branchId, "HELD")

    // ── Lifecycle ──────────────────────────────────────────────────────────

    @Transactional
    @PreAuthorize("hasAuthority('SALE_CREATE')")
    fun startSale(customerId: Long?): Sale {
        val actor = Auth.current()
        customerId?.let { customers.get(it) }
        return sales.save(
            Sale(branchId = actor.branchId, cashierId = actor.id).also { it.customerId = customerId }
        )
    }

    @Transactional
    @PreAuthorize("hasAuthority('SALE_CREATE')")
    fun setCustomer(saleId: Long, customerId: Long?): Sale {
        val sale = openSale(saleId)
        customerId?.let { customers.get(it) }
        sale.customerId = customerId
        /*
         * Attaching a customer can change every price on the basket — a
         * contractor's list is why they came to this counter rather than the
         * one down the road. Re-pricing here is the only way the till shows
         * what they will actually be charged before they commit to it.
         */
        repriceAllLines(sale)
        return recalculate(sale)
    }

    @Transactional
    @PreAuthorize("hasAuthority('SALE_HOLD')")
    fun hold(saleId: Long, label: String?): Sale {
        val sale = openSale(saleId)
        if (saleLines.findBySaleIdOrderByLineNoAsc(saleId).isEmpty()) {
            throw ApiException.RuleViolation("EMPTY_SALE", "There is nothing in this sale to hold.")
        }
        sale.status = "HELD"
        sale.heldLabel = label?.trim()?.takeIf(String::isNotEmpty)
            ?: customerLabel(sale)
        return sales.save(sale)
    }

    @Transactional
    @PreAuthorize("hasAuthority('SALE_HOLD')")
    fun recall(saleId: Long): Sale {
        val sale = get(saleId)
        if (sale.status != "HELD") {
            throw ApiException.RuleViolation("NOT_HELD", "That sale is not on hold.")
        }
        sale.status = "DRAFT"
        // The recalling till takes it over. Without this the takings would go
        // against whoever happened to start the basket.
        sale.cashierId = Auth.current().id
        return sales.save(sale)
    }

    /**
     * Throws a basket away.
     *
     * Only ever a draft or a held sale — nothing has moved and no number has
     * been issued, so there is nothing to preserve. A completed sale is voided
     * instead, which reverses stock and leaves the record standing.
     */
    @Transactional
    @PreAuthorize("hasAuthority('SALE_CREATE')")
    fun discard(saleId: Long) {
        val sale = openSale(saleId)
        sales.delete(sale)
        audit.recordCurrent("SALE_DISCARDED", "sale", saleId)
    }

    // ── Lines ──────────────────────────────────────────────────────────────

    /**
     * Adds a line, priced and costed.
     *
     * The same product scanned twice becomes two lines rather than merging.
     * Merging looks tidier until one of them carries a discount, at which point
     * there is no honest single line to show — and the till can always present
     * them together without the server pretending they are one.
     */
    @Transactional
    @PreAuthorize("hasAuthority('SALE_CREATE')")
    fun addLine(
        saleId: Long,
        productId: Long,
        productUomId: Long,
        qty: BigDecimal,
        discountPercent: BigDecimal? = null,
        unitPriceOverride: BigDecimal? = null,
        approval: OverrideCredentials? = null,
    ): SaleLine {
        val sale = openSale(saleId)
        val product = activeProduct(productId)

        uom.assertPositive(qty)
        uom.assertPrecision(productUomId, qty)
        val qtyBase = uom.toBase(productId, productUomId, qty)

        var approvedBy: Long? = null
        val listPrice = pricing.resolve(productId, productUomId, priceListFor(sale)).unitPrice

        val unitPrice = if (unitPriceOverride != null) {
            approvedBy = authorisePriceOverride(product, listPrice, unitPriceOverride, approval)
            Money.roundPrice(unitPriceOverride)
        } else {
            listPrice
        }

        val unitCost = picking.assertAvailableAndEstimateCost(product, qtyBase)
        val discountAmount = discountPercent?.let {
            val approver = authoriseDiscount(product, unitPrice, unitCostPerSellingUnit(unitCost, qty, qtyBase), it, approval)
            approver?.let { id -> approvedBy = id }
            Money.round(unitPrice.multiply(qty).multiply(it).divide(Money.HUNDRED, Money.SCALE + 4, RoundingMode.HALF_UP))
        } ?: BigDecimal.ZERO

        val line = SaleLine(
            saleId = saleId,
            lineNo = saleLines.maxLineNo(saleId) + 1,
            productId = productId,
            productUomId = productUomId,
            qty = qty,
            qtyBase = qtyBase,
            unitPrice = unitPrice,
            lineTotal = BigDecimal.ZERO,
            unitCost = unitCost,
        ).also {
            it.discountAmount = discountAmount
            it.priceOverridden = unitPriceOverride != null
            it.approvedBy = approvedBy
        }
        priceLine(line)
        val saved = saleLines.save(line)
        recalculate(sale)
        return saved
    }

    @Transactional
    @PreAuthorize("hasAuthority('SALE_CREATE')")
    fun updateQty(saleId: Long, lineId: Long, qty: BigDecimal): SaleLine {
        val sale = openSale(saleId)
        val line = lineOf(saleId, lineId)
        val product = activeProduct(line.productId)

        uom.assertPositive(qty)
        uom.assertPrecision(line.productUomId, qty)

        // The discount was agreed as an amount off this line; changing the
        // quantity has to move it in proportion or a cashier could double a
        // quantity and keep a discount that was struck for half of it.
        val ratio = if (line.qty > BigDecimal.ZERO) {
            qty.divide(line.qty, 8, RoundingMode.HALF_UP)
        } else BigDecimal.ONE

        line.qty = qty
        line.qtyBase = uom.toBase(line.productId, line.productUomId, qty)
        line.discountAmount = Money.round(line.discountAmount.multiply(ratio))
        line.unitCost = picking.assertAvailableAndEstimateCost(product, line.qtyBase)
        priceLine(line)

        val saved = saleLines.save(line)
        recalculate(sale)
        return saved
    }

    @Transactional
    @PreAuthorize("hasAuthority('SALE_DISCOUNT')")
    fun setLineDiscount(
        saleId: Long,
        lineId: Long,
        discountPercent: BigDecimal,
        approval: OverrideCredentials? = null,
    ): SaleLine {
        val sale = openSale(saleId)
        val line = lineOf(saleId, lineId)
        val product = activeProduct(line.productId)

        val costPerSellingUnit = unitCostPerSellingUnit(line.unitCost, line.qty, line.qtyBase)
        authoriseDiscount(product, line.unitPrice, costPerSellingUnit, discountPercent, approval)
            ?.let { line.approvedBy = it }

        line.discountAmount = Money.round(
            line.unitPrice.multiply(line.qty).multiply(discountPercent)
                .divide(Money.HUNDRED, Money.SCALE + 4, RoundingMode.HALF_UP)
        )
        priceLine(line)
        val saved = saleLines.save(line)
        recalculate(sale)
        return saved
    }

    @Transactional
    @PreAuthorize("hasAuthority('SALE_CREATE')")
    fun removeLine(saleId: Long, lineId: Long) {
        val sale = openSale(saleId)
        val line = lineOf(saleId, lineId)
        saleLines.delete(line)
        recalculate(sale)
    }

    // ── Pricing internals ──────────────────────────────────────────────────

    /**
     * Recomputes a line's tax and total from its price, quantity and discount.
     *
     * Tax is struck per line rather than on the sale total because the receipt
     * itemises it there, and because a scheme with different rates per product
     * class — which the engine supports even though the shop does not use it
     * today — has no meaningful sale-level equivalent.
     */
    private fun priceLine(line: SaleLine) {
        val gross = Money.round(line.unitPrice.multiply(line.qty))
        val net = gross.subtract(line.discountAmount)
        if (net < BigDecimal.ZERO) {
            throw ApiException.Validation(
                "A discount cannot be more than the line is worth.",
                mapOf("discountPercent" to "exceeds the line total"),
            )
        }
        line.taxAmount = tax.calculate(net).total
        line.lineTotal = Money.round(net.add(line.taxAmount))
    }

    /** Re-resolves every line's price — used when the customer changes. */
    private fun repriceAllLines(sale: Sale) {
        val priceListId = priceListFor(sale)
        saleLines.findBySaleIdOrderByLineNoAsc(sale.id!!).forEach { line ->
            if (line.priceOverridden) return@forEach   // a hand-keyed price survives
            line.unitPrice = pricing.resolve(line.productId, line.productUomId, priceListId).unitPrice
            priceLine(line)
            saleLines.save(line)
        }
    }

    /**
     * Rolls the lines up onto the sale.
     *
     * Only the payable total is rounded to cash (§8.4). Rounding each line
     * would drift the receipt away from the sum printed under it, and the
     * whole point of storing the adjustment is that the day's cash reconciles
     * to the pesewa.
     */
    fun recalculate(sale: Sale): Sale {
        val lines = saleLines.findBySaleIdOrderByLineNoAsc(sale.id!!)

        sale.subtotal = lines.fold(Money.ZERO) { acc, l -> acc.add(Money.round(l.unitPrice.multiply(l.qty))) }
        sale.discountTotal = lines.fold(Money.ZERO) { acc, l -> acc.add(l.discountAmount) }
        sale.taxTotal = lines.fold(Money.ZERO) { acc, l -> acc.add(l.taxAmount) }

        val payable = sale.subtotal.subtract(sale.discountTotal).add(sale.taxTotal)
        sale.grandTotal = rounding.apply(payable)
        sale.roundingAdjustment = sale.grandTotal.subtract(Money.round(payable))

        return sales.save(sale)
    }

    // ── Authorisation ──────────────────────────────────────────────────────

    /**
     * Checks a discount, escalating to a supervisor if one was offered.
     *
     * The escalation is a second evaluation under the approver's roles, not a
     * bypass: a supervisor whose own allowance does not cover the discount
     * cannot approve it either, and the audit entry records who tried.
     *
     * Returns the approver's id when one was used, for `sale_line.approved_by`.
     */
    private fun authoriseDiscount(
        product: Product,
        unitPrice: BigDecimal,
        unitCost: BigDecimal,
        percent: BigDecimal,
        approval: OverrideCredentials?,
    ): Long? {
        val actor = Auth.current()
        val decision = discounts.evaluate(actor.roles, percent, unitPrice, unitCost)
        if (decision.outcome == DiscountOutcome.ALLOWED) return null

        if (approval == null) {
            throw ApiException.RuleViolation(
                "DISCOUNT_NEEDS_APPROVAL",
                decision.reason ?: "That discount needs a supervisor.",
            )
        }
        val approver = overrides.verify(
            approval.username, approval.pin, "SALE_DISCOUNT",
            "discount of ${percent.stripTrailingZeros().toPlainString()}% on ${product.sku}",
        )
        val second = discounts.evaluate(approver.roles, percent, unitPrice, unitCost)
        if (second.outcome != DiscountOutcome.ALLOWED) {
            throw ApiException.Forbidden(
                "${approver.username} cannot approve that either — " +
                    (second.reason ?: "it is beyond their allowance.")
            )
        }
        audit.recordCurrent(
            "SALE_DISCOUNT_APPROVED", "product", product.id,
            after = """{"percent":"${percent.toPlainString()}","approvedBy":${approver.userId}}""",
        )
        return approver.userId
    }

    /**
     * Checks a hand-keyed price.
     *
     * A price override is a separate permission from a discount because it is a
     * different act: a discount is a percentage off a price the shop set, while
     * an override replaces that price with a number somebody typed. Only the
     * second one can quietly sell below cost without any percentage looking
     * unusual.
     */
    private fun authorisePriceOverride(
        product: Product,
        listPrice: BigDecimal,
        newPrice: BigDecimal,
        approval: OverrideCredentials?,
    ): Long? {
        if (newPrice < BigDecimal.ZERO) {
            throw ApiException.Validation(
                "A price cannot be negative.",
                mapOf("unitPrice" to "must be zero or more"),
            )
        }
        val actor = Auth.current()
        // Overriding upward is not a giveaway and needs no supervisor; only a
        // price below the list price is a concession.
        val isConcession = newPrice < listPrice
        if (!isConcession || actor.has("SALE_PRICE_OVERRIDE")) {
            if (isConcession) {
                audit.recordCurrent(
                    "SALE_PRICE_OVERRIDDEN", "product", product.id,
                    before = """{"listPrice":"${listPrice.toPlainString()}"}""",
                    after = """{"unitPrice":"${newPrice.toPlainString()}"}""",
                )
            }
            return null
        }
        if (approval == null) {
            throw ApiException.RuleViolation(
                "PRICE_OVERRIDE_NEEDS_APPROVAL",
                "Changing the price on ${product.name} needs a supervisor.",
            )
        }
        val approver = overrides.verify(
            approval.username, approval.pin, "SALE_PRICE_OVERRIDE",
            "price override on ${product.sku} to ${newPrice.toPlainString()}",
        )
        audit.recordCurrent(
            "SALE_PRICE_OVERRIDDEN", "product", product.id,
            before = """{"listPrice":"${listPrice.toPlainString()}"}""",
            after = """{"unitPrice":"${newPrice.toPlainString()}","approvedBy":${approver.userId}}""",
        )
        return approver.userId
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /** The sale, insisting it is still open to edits. */
    fun openSale(saleId: Long): Sale {
        val sale = get(saleId)
        if (!sale.isOpen) {
            throw ApiException.RuleViolation(
                "SALE_NOT_OPEN",
                "That sale is ${sale.status.lowercase()} and can no longer be changed.",
            )
        }
        return sale
    }

    private fun lineOf(saleId: Long, lineId: Long): SaleLine {
        val line = saleLines.findById(lineId).orElseThrow { ApiException.NotFound("Sale line", lineId) }
        if (line.saleId != saleId) throw ApiException.NotFound("Sale line", lineId)
        return line
    }

    private fun activeProduct(productId: Long): Product {
        val product = products.findById(productId).orElseThrow { ApiException.NotFound("Product", productId) }
        if (product.branchId != Auth.current().branchId) throw ApiException.NotFound("Product", productId)
        if (!product.isActive) {
            throw ApiException.RuleViolation(
                "PRODUCT_INACTIVE",
                "${product.name} is no longer sold.",
            )
        }
        return product
    }

    fun priceListFor(sale: Sale): Long? =
        sale.customerId?.let { customers.get(it).priceListId }

    /**
     * Lot cost is per base unit; the discount check compares against the price,
     * which is per selling unit. Converting here keeps the margin comparison
     * between two figures that mean the same thing.
     */
    private fun unitCostPerSellingUnit(
        unitCostBase: BigDecimal,
        qty: BigDecimal,
        qtyBase: BigDecimal,
    ): BigDecimal {
        if (qty <= BigDecimal.ZERO) return unitCostBase
        return unitCostBase.multiply(qtyBase).divide(qty, Money.PRICE_SCALE, RoundingMode.HALF_UP)
    }

    private fun customerLabel(sale: Sale): String =
        sale.customerId?.let { runCatching { customers.get(it).name }.getOrNull() }
            ?: "Held ${sale.id}"
}
