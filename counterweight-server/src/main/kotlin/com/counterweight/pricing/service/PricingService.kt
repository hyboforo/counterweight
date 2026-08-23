package com.counterweight.pricing.service

import com.counterweight.catalog.repo.ProductRepository
import com.counterweight.catalog.repo.ProductUomRepository
import com.counterweight.common.ApiException
import com.counterweight.identity.security.Auth
import com.counterweight.identity.service.AuditService
import com.counterweight.pricing.domain.Price
import com.counterweight.pricing.domain.PriceList
import com.counterweight.pricing.repo.PriceListRepository
import com.counterweight.pricing.repo.PriceRepository
import com.counterweight.pricing.repo.UnitPrice
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate

@Service
class PriceListService(
    private val priceLists: PriceListRepository,
    private val audit: AuditService,
) {

    @Transactional(readOnly = true)
    fun all(): List<PriceList> = priceLists.findByBranchIdOrderByCodeAsc(Auth.current().branchId)

    @Transactional(readOnly = true)
    fun get(id: Long): PriceList {
        val list = priceLists.findById(id).orElseThrow { ApiException.NotFound("Price list", id) }
        // Another branch's list is not "forbidden", it is not visible at all —
        // a 403 would confirm the id exists.
        if (list.branchId != Auth.current().branchId) throw ApiException.NotFound("Price list", id)
        return list
    }

    /**
     * The list every sale falls back to.
     *
     * Its absence stops selling, so it fails loudly here rather than letting
     * each product report "no price" and sending somebody hunting through the
     * catalogue for a problem that is one row of configuration.
     */
    @Transactional(readOnly = true)
    fun default(): PriceList =
        priceLists.findByBranchIdAndIsDefaultTrue(Auth.current().branchId)
            ?: throw ApiException.RuleViolation(
                "NO_DEFAULT_PRICE_LIST",
                "This branch has no default price list, so nothing can be sold. Set one first.",
            )

    @Transactional
    @PreAuthorize("hasAuthority('PRICE_MANAGE')")
    fun create(code: String, name: String, makeDefault: Boolean): PriceList {
        val branchId = Auth.current().branchId
        val normalised = code.trim().uppercase()
        if (priceLists.existsByBranchIdAndCode(branchId, normalised)) {
            throw ApiException.Conflict("A price list with the code '$normalised' already exists.")
        }
        // The first list has to be the default whatever the caller asked for,
        // or the branch is left unable to sell until somebody notices.
        val isFirst = priceLists.findByBranchIdAndIsDefaultTrue(branchId) == null
        val saved = priceLists.save(
            PriceList(branchId = branchId, code = normalised, name = name.trim())
                .also { it.isDefault = makeDefault || isFirst }
        )
        audit.recordCurrent("PRICE_LIST_CREATED", "price_list", saved.id, after = """{"code":"$normalised"}""")
        return saved
    }

    /**
     * Promotes a list to default, demoting the incumbent.
     *
     * `one_default_price_list_per_branch` is a partial unique index and is
     * checked per statement, so the demotion must reach the database before the
     * promotion does — hence the explicit flush rather than two saves.
     */
    @Transactional
    @PreAuthorize("hasAuthority('PRICE_MANAGE')")
    fun setDefault(id: Long): PriceList {
        val list = get(id)
        if (list.isDefault) return list

        priceLists.findByBranchIdAndIsDefaultTrue(list.branchId)?.let { incumbent ->
            incumbent.isDefault = false
            priceLists.saveAndFlush(incumbent)
        }
        list.isDefault = true
        val saved = priceLists.save(list)
        audit.recordCurrent("PRICE_LIST_DEFAULTED", "price_list", id, after = """{"code":"${list.code}"}""")
        return saved
    }
}

/**
 * What the counter should charge, and where the figure came from.
 *
 * [fromFallback] is not decoration: a trade customer whose line is missing from
 * their own list quietly gets the walk-in price, and the only way anybody finds
 * out is if the till can say so.
 */
data class ResolvedPrice(
    val priceId: Long,
    val priceListId: Long,
    val productId: Long,
    val productUomId: Long,
    val unitPrice: BigDecimal,
    val effectiveFrom: LocalDate,
    val effectiveTo: LocalDate?,
    val fromFallback: Boolean,
)

@Service
class PricingService(
    private val prices: PriceRepository,
    private val priceLists: PriceListService,
    private val products: ProductRepository,
    private val productUoms: ProductUomRepository,
    private val audit: AuditService,
) {

    // ── Resolution ─────────────────────────────────────────────────────────

    /**
     * The price for one product in one selling unit.
     *
     * [priceListId] is the customer's list where they have one. It is passed in
     * rather than looked up because the customer lives in `parties`, and pricing
     * has no business reaching into another module's tables to find out who is
     * standing at the counter.
     */
    @Transactional(readOnly = true)
    fun resolve(
        productId: Long,
        productUomId: Long,
        priceListId: Long? = null,
        onDate: LocalDate = LocalDate.now(),
    ): ResolvedPrice {
        val defaultList = priceLists.default()
        val preferredId = priceListId?.let { priceLists.get(it).id!! } ?: defaultList.id!!

        val price = prices.findEffective(productId, productUomId, preferredId, defaultList.id!!, onDate)
            ?: throw ApiException.RuleViolation(
                "NO_PRICE",
                "This item has no price for the unit selected, so it cannot be sold yet.",
            )

        return ResolvedPrice(
            priceId = price.id!!,
            priceListId = price.priceListId,
            productId = productId,
            productUomId = productUomId,
            unitPrice = price.unitPrice,
            effectiveFrom = price.effectiveFrom,
            effectiveTo = price.effectiveTo,
            fromFallback = price.priceListId != preferredId,
        )
    }

    /** Every sellable unit of a product with its current price, for the till. */
    @Transactional(readOnly = true)
    fun unitPricesFor(
        productId: Long,
        priceListId: Long? = null,
        onDate: LocalDate = LocalDate.now(),
    ): List<UnitPrice> {
        requireOwnProduct(productId)
        val defaultList = priceLists.default()
        val preferredId = priceListId?.let { priceLists.get(it).id!! } ?: defaultList.id!!
        return prices.findUnitPrices(productId, preferredId, defaultList.id!!, onDate)
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('PRICE_VIEW')")
    fun history(priceListId: Long, productId: Long): List<Price> {
        priceLists.get(priceListId)
        requireOwnProduct(productId)
        return prices.findByPriceListIdAndProductIdOrderByEffectiveFromDescIdDesc(priceListId, productId)
    }

    // ── Changing a price ───────────────────────────────────────────────────

    /**
     * Sets a price from [effectiveFrom] onward, closing whatever it supersedes.
     *
     * A price is never edited in place once it has been live for a day. The
     * current row is closed the day before the new one opens, so the table
     * answers "what did this cost on the 3rd" by itself. Sale lines snapshot
     * their own price anyway, so history is safe either way — but a report that
     * has to reconstruct last month's shelf price from an audit trail is a
     * report nobody runs.
     *
     * Same-day changes are the exception: a price keyed in wrong an hour ago is
     * a correction, not a new commercial decision, and closing a window that
     * never lasted a day would leave a zero-length row that the
     * `price_window_valid` check rejects anyway.
     */
    @Transactional
    @PreAuthorize("hasAuthority('PRICE_MANAGE')")
    fun setPrice(
        priceListId: Long,
        productId: Long,
        productUomId: Long,
        unitPrice: BigDecimal,
        effectiveFrom: LocalDate?,
    ): Price {
        val list = priceLists.get(priceListId)
        requireOwnProduct(productId)
        requireSellableUnitOf(productId, productUomId)

        if (unitPrice < BigDecimal.ZERO) {
            throw ApiException.Validation(
                "A price cannot be negative.",
                mapOf("unitPrice" to "must be zero or more"),
            )
        }
        val today = LocalDate.now()
        val from = effectiveFrom ?: today
        if (from.isBefore(today)) {
            // Back-dating cannot change what past sales charged, so it buys
            // nothing and costs a report that no longer reconciles.
            throw ApiException.Validation(
                "A price can start today or later, not in the past.",
                mapOf("effectiveFrom" to "must not be before $today"),
            )
        }

        val affected = prices.findOpenOnOrAfter(priceListId, productId, productUomId, from)

        affected.firstOrNull { it.effectiveFrom.isAfter(from) }?.let { scheduled ->
            throw ApiException.RuleViolation(
                "PRICE_CHANGE_ALREADY_SCHEDULED",
                "A price change for this item is already booked for ${scheduled.effectiveFrom}. " +
                    "Cancel that one before setting a price from $from.",
            )
        }

        affected.firstOrNull { it.effectiveFrom == from }?.let { sameDay ->
            val before = sameDay.unitPrice
            if (before.compareTo(unitPrice) == 0) return sameDay
            sameDay.unitPrice = unitPrice
            val saved = prices.save(sameDay)
            audit.recordCurrent(
                "PRICE_CORRECTED", "price", saved.id,
                before = """{"unitPrice":"${before.toPlainString()}"}""",
                after = """{"unitPrice":"${unitPrice.toPlainString()}","from":"$from"}""",
            )
            return saved
        }

        // Close the outgoing window the day before the new one opens. Flushed
        // per row because `one_open_price` is checked as each statement runs,
        // and Hibernate would otherwise be free to order the insert first.
        var supersededPrice: BigDecimal? = null
        affected.filter { it.effectiveFrom.isBefore(from) }.forEach { outgoing ->
            supersededPrice = outgoing.unitPrice
            outgoing.effectiveTo = from.minusDays(1)
            prices.saveAndFlush(outgoing)
        }

        val created = prices.save(
            Price(
                priceListId = priceListId,
                productId = productId,
                productUomId = productUomId,
                unitPrice = unitPrice,
            ).also { it.effectiveFrom = from }
        )
        audit.recordCurrent(
            "PRICE_CHANGED", "price", created.id,
            before = supersededPrice?.let { """{"unitPrice":"${it.toPlainString()}"}""" },
            after = """{"list":"${list.code}","unitPrice":"${unitPrice.toPlainString()}","from":"$from"}""",
        )
        return created
    }

    /**
     * Removes a price change that has not taken effect yet.
     *
     * Only future-dated rows can go. Deleting a window that has already been
     * live would rewrite what the shop was charging on a day it was open, and
     * the correction for that is a new price, not a disappearance.
     */
    @Transactional
    @PreAuthorize("hasAuthority('PRICE_MANAGE')")
    fun cancelScheduled(priceId: Long) {
        val price = prices.findById(priceId).orElseThrow { ApiException.NotFound("Price", priceId) }
        priceLists.get(price.priceListId)
        if (!price.effectiveFrom.isAfter(LocalDate.now())) {
            throw ApiException.RuleViolation(
                "PRICE_ALREADY_IN_EFFECT",
                "That price started on ${price.effectiveFrom} and cannot be removed. " +
                    "Set a new price instead.",
            )
        }
        prices.delete(price)
        audit.recordCurrent(
            "PRICE_CHANGE_CANCELLED", "price", priceId,
            before = """{"unitPrice":"${price.unitPrice.toPlainString()}","from":"${price.effectiveFrom}"}""",
        )
    }

    // ── Guards ─────────────────────────────────────────────────────────────

    private fun requireOwnProduct(productId: Long) {
        val product = products.findById(productId).orElseThrow {
            ApiException.NotFound("Product", productId)
        }
        if (product.branchId != Auth.current().branchId) throw ApiException.NotFound("Product", productId)
    }

    private fun requireSellableUnitOf(productId: Long, productUomId: Long) {
        val unit = productUoms.findById(productUomId).orElseThrow {
            ApiException.NotFound("Unit", productUomId)
        }
        if (unit.productId != productId) {
            throw ApiException.Validation(
                "That unit does not belong to this product.",
                mapOf("productUomId" to "does not belong to product $productId"),
            )
        }
        if (!unit.sellable) {
            // A purchase-only unit — a pallet the shop buys but never sells —
            // with a selling price on it is a data-entry slip that surfaces
            // much later as a price the till will not offer.
            throw ApiException.Validation(
                "That unit is not sold, so it cannot carry a selling price.",
                mapOf("productUomId" to "is not a sellable unit"),
            )
        }
    }
}
