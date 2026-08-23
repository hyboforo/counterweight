package com.counterweight.pricing.web

import com.counterweight.pricing.domain.Price
import com.counterweight.pricing.domain.PriceList
import com.counterweight.pricing.service.*
import com.counterweight.common.SafeText
import jakarta.validation.Valid
import jakarta.validation.constraints.*
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.time.LocalDate

// ── Requests ───────────────────────────────────────────────────────────────

data class CreatePriceListRequest(
    @field:NotBlank(message = "is required")
    @field:Pattern(regexp = "^[A-Za-z0-9_]{1,30}$", message = "may use letters, digits and underscore only")
    val code: String,

    @field:NotBlank(message = "is required")
    @field:Size(max = 60, message = "is too long")
    @field:SafeText
    val name: String,

    val makeDefault: Boolean = false,
)

data class SetPriceRequest(
    @field:NotNull(message = "is required")
    @field:Positive(message = "must be a valid price list")
    val priceListId: Long,

    @field:NotNull(message = "is required")
    @field:Positive(message = "must be a valid product")
    val productId: Long,

    @field:NotNull(message = "is required")
    @field:Positive(message = "must be a valid unit")
    val productUomId: Long,

    @field:NotNull(message = "is required")
    @field:DecimalMin(value = "0.0", message = "must be zero or more")
    @field:Digits(integer = 10, fraction = 4, message = "has too many digits")
    val unitPrice: BigDecimal,

    /** Defaults to today. Never accepted in the past — see PricingService. */
    @field:DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    val effectiveFrom: LocalDate? = null,
)

// ── Responses ──────────────────────────────────────────────────────────────

data class PriceListView(
    val id: Long, val code: String, val name: String, val isDefault: Boolean,
)

data class PriceView(
    val id: Long, val priceListId: Long, val productId: Long, val productUomId: Long,
    val unitPrice: BigDecimal, val effectiveFrom: LocalDate, val effectiveTo: LocalDate?,
)

data class UnitPriceView(
    val productUomId: Long, val uomCode: String, val factor: BigDecimal,
    val unitPrice: BigDecimal?, val priceListId: Long?,
)

data class TaxComponentView(
    val code: String, val name: String, val ratePercent: BigDecimal,
    val computedOn: String, val isRecoverable: Boolean,
)

data class TaxQuoteView(
    val schemeCode: String?, val taxableValue: BigDecimal,
    val components: List<TaxComponentAmount>, val total: BigDecimal,
    val grandTotal: BigDecimal,
)

private fun PriceList.toView() = PriceListView(id!!, code, name, isDefault)
private fun Price.toView() =
    PriceView(id!!, priceListId, productId, productUomId, unitPrice, effectiveFrom, effectiveTo)

// ── Controllers ────────────────────────────────────────────────────────────

@RestController
@RequestMapping("/api/price-lists")
class PriceListController(private val priceLists: PriceListService) {

    @GetMapping
    @PreAuthorize("hasAuthority('PRICE_VIEW')")
    fun list(): List<PriceListView> = priceLists.all().map { it.toView() }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PRICE_VIEW')")
    fun get(@PathVariable id: Long): PriceListView = priceLists.get(id).toView()

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody body: CreatePriceListRequest): PriceListView =
        priceLists.create(body.code, body.name, body.makeDefault).toView()

    @PutMapping("/{id}/default")
    fun setDefault(@PathVariable id: Long): PriceListView = priceLists.setDefault(id).toView()
}

@RestController
@RequestMapping("/api/prices")
class PriceController(private val pricing: PricingService) {

    /**
     * What to charge for one product in one unit.
     *
     * `priceListId` is the customer's list where the sale has one; omitting it
     * prices at the branch default, which is the walk-in case.
     */
    @GetMapping("/resolve")
    @PreAuthorize("hasAuthority('PRICE_VIEW')")
    fun resolve(
        @RequestParam productId: Long,
        @RequestParam productUomId: Long,
        @RequestParam(required = false) priceListId: Long?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) onDate: LocalDate?,
    ): ResolvedPrice = pricing.resolve(productId, productUomId, priceListId, onDate ?: LocalDate.now())

    /** Every sellable unit with its price — what the till shows on selecting an item. */
    @GetMapping("/product/{productId}")
    @PreAuthorize("hasAuthority('PRICE_VIEW')")
    fun forProduct(
        @PathVariable productId: Long,
        @RequestParam(required = false) priceListId: Long?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) onDate: LocalDate?,
    ): List<UnitPriceView> =
        pricing.unitPricesFor(productId, priceListId, onDate ?: LocalDate.now())
            .map { UnitPriceView(it.productUomId, it.uomCode, it.factor, it.unitPrice, it.priceListId) }

    @GetMapping("/history")
    fun history(
        @RequestParam priceListId: Long,
        @RequestParam productId: Long,
    ): List<PriceView> = pricing.history(priceListId, productId).map { it.toView() }

    @PutMapping
    fun setPrice(@Valid @RequestBody body: SetPriceRequest): PriceView =
        pricing.setPrice(
            body.priceListId, body.productId, body.productUomId, body.unitPrice, body.effectiveFrom,
        ).toView()

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun cancelScheduled(@PathVariable id: Long) = pricing.cancelScheduled(id)
}

@RestController
@RequestMapping("/api/discounts")
class DiscountController(private val discounts: DiscountAuthority) {

    /**
     * What the signed-in operator may take off a price.
     *
     * Deliberately the only discount endpoint. Evaluating a specific discount
     * needs the lot cost, and an endpoint taking cost from the client would
     * both leak it to a till that must not see it and let the client choose the
     * number the margin rule is checked against. That evaluation belongs on the
     * sale path, where the cost comes from the allocation.
     */
    @GetMapping("/allowance")
    fun allowance(): DiscountAllowance = discounts.currentAllowance()
}

@RestController
@RequestMapping("/api/tax")
class TaxController(private val tax: TaxCalculator) {

    /** The live configuration. Empty components mean the shop charges no tax. */
    @GetMapping("/scheme")
    @PreAuthorize("hasAuthority('PRICE_VIEW')")
    fun scheme(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) onDate: LocalDate?,
    ): List<TaxComponentView> =
        tax.activeComponents(onDate ?: LocalDate.now())
            .map { TaxComponentView(it.code, it.name, it.ratePercent, it.computedOn, it.isRecoverable) }

    /** Tax on an amount, itemised the way it prints. */
    @GetMapping("/quote")
    @PreAuthorize("hasAuthority('PRICE_VIEW')")
    fun quote(
        @RequestParam @DecimalMin(value = "0.0", message = "must be zero or more") amount: BigDecimal,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) onDate: LocalDate?,
    ): TaxQuoteView {
        val breakdown = tax.calculate(amount, onDate ?: LocalDate.now())
        return TaxQuoteView(
            schemeCode = breakdown.schemeCode,
            taxableValue = breakdown.taxableValue,
            components = breakdown.components,
            total = breakdown.total,
            grandTotal = breakdown.taxableValue.add(breakdown.total),
        )
    }
}
