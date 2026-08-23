package com.counterweight.catalog.service

import com.counterweight.catalog.repo.ProductUomRepository
import com.counterweight.catalog.repo.UomRepository
import com.counterweight.common.ApiException
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Converts between the unit a person used and the base unit the ledger stores.
 *
 * The ledger speaks only base units (§6.2). Everything else — a bag, a carton,
 * an olonka — is a factor onto that, and conversion happens strictly at the
 * edges. Nothing downstream of a movement ever needs to know which unit the
 * counter typed.
 *
 * All arithmetic is BigDecimal. Quantities here are genuinely fractional —
 * 3.5 m off a cable roll is routine — and a double would eventually put 2.9999
 * metres in the ledger.
 */
@Component
class UomConverter(
    private val productUoms: ProductUomRepository,
    private val uoms: UomRepository,
) {

    /** Base-unit quantity for [qty] expressed in [productUomId]. */
    fun toBase(productId: Long, productUomId: Long, qty: BigDecimal): BigDecimal {
        val pu = productUoms.findById(productUomId).orElseThrow {
            ApiException.NotFound("Unit", productUomId)
        }
        if (pu.productId != productId) {
            // Mismatched ids would otherwise convert using another product's
            // factor and quietly write a wrong quantity into the ledger.
            throw ApiException.Validation(
                "That unit does not belong to this product.",
                mapOf("productUomId" to "does not belong to product $productId"),
            )
        }
        return (qty * pu.factor).stripTrailingZeros()
    }

    /** Base-unit quantity back into [productUomId], for display. */
    fun fromBase(productUomId: Long, qtyBase: BigDecimal): BigDecimal {
        val pu = productUoms.findById(productUomId).orElseThrow {
            ApiException.NotFound("Unit", productUomId)
        }
        return qtyBase.divide(pu.factor, SCALE, RoundingMode.HALF_UP).stripTrailingZeros()
    }

    /**
     * Rejects a quantity with more decimal places than the unit permits.
     *
     * `uom.decimals` is 0 for PCS and 3 for KG, so "2.5 pieces" is refused
     * while "2.5 kg" is fine. Catching it here keeps a nonsensical fraction out
     * of an append-only ledger, where correcting it means posting a
     * compensating movement rather than an edit.
     */
    fun assertPrecision(productUomId: Long, qty: BigDecimal) {
        val pu = productUoms.findById(productUomId).orElseThrow {
            ApiException.NotFound("Unit", productUomId)
        }
        val uom = uoms.findById(pu.uomId).orElseThrow { ApiException.NotFound("Unit of measure", pu.uomId) }
        val scale = qty.stripTrailingZeros().scale().coerceAtLeast(0)
        if (scale > uom.decimals) {
            throw ApiException.Validation(
                if (uom.decimals.toInt() == 0) "${uom.name} is sold whole — ${qty.toPlainString()} is not a valid quantity."
                else "${uom.name} allows at most ${uom.decimals} decimal place(s).",
                mapOf("qty" to "has too many decimal places for ${uom.code}"),
            )
        }
    }

    fun assertPositive(qty: BigDecimal, field: String = "qty") {
        if (qty <= BigDecimal.ZERO) {
            throw ApiException.Validation(
                "Quantity must be more than zero.",
                mapOf(field to "must be greater than zero"),
            )
        }
    }

    private companion object { const val SCALE = 6 }
}
