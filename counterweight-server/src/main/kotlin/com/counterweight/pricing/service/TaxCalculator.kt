package com.counterweight.pricing.service

import com.counterweight.common.ApiException
import com.counterweight.pricing.domain.TaxScheme
import com.counterweight.pricing.repo.EffectiveTaxComponent
import com.counterweight.pricing.repo.TaxComponentRepository
import com.counterweight.pricing.repo.TaxRateRepository
import com.counterweight.pricing.repo.TaxSchemeRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate

/** One levy's contribution to a total, itemised the way it prints on a receipt. */
data class TaxComponentAmount(
    val code: String,
    val name: String,
    val ratePercent: BigDecimal,
    val amount: BigDecimal,
    val isRecoverable: Boolean,
)

data class TaxBreakdown(
    /** Null when no scheme is active — the shop's normal state today. */
    val schemeCode: String?,
    val taxableValue: BigDecimal,
    val components: List<TaxComponentAmount>,
    val total: BigDecimal,
) {
    companion object {
        fun none(taxableValue: BigDecimal) =
            TaxBreakdown(null, Money.round(taxableValue), emptyList(), Money.ZERO)
    }
}

/**
 * Computes tax on a taxable value.
 *
 * **Prices in this system are tax-exclusive.** The value passed in is what the
 * customer is charged before tax, and the components are added on top. If the
 * shop ever moves to tax-inclusive shelf pricing, that is a back-out step in
 * front of this class and not a change to it — the compound structure below is
 * linear in the taxable value, so it inverts cleanly.
 *
 * The engine exists in full even though the shop is not VAT-registered (§1).
 * With no active scheme every call returns a well-formed zero rather than
 * failing, so the sale path has no special case to forget: registration later
 * is a row in `tax_scheme`, not a code change.
 */
@Service
class TaxCalculator(
    private val schemes: TaxSchemeRepository,
    private val components: TaxComponentRepository,
    private val rates: TaxRateRepository,
) {

    /**
     * The scheme in force, or null if the shop is charging no tax.
     *
     * Two active schemes would silently change every price in the shop
     * depending on which one a query happened to return first, so it is refused
     * outright rather than resolved by a tie-break.
     */
    @Transactional(readOnly = true)
    fun activeScheme(): TaxScheme? {
        val active = schemes.findByIsActiveTrue()
        if (active.size > 1) {
            throw ApiException.RuleViolation(
                "TAX_CONFIG_AMBIGUOUS",
                "More than one tax scheme is switched on (${active.joinToString { it.code }}). " +
                    "Exactly one must be active before anything can be sold.",
            )
        }
        return active.firstOrNull()
    }

    /**
     * The components and rates in force, for showing the configuration back to
     * whoever set it up. Empty when no scheme is active.
     */
    @Transactional(readOnly = true)
    fun activeComponents(onDate: LocalDate = LocalDate.now()): List<EffectiveTaxComponent> {
        val scheme = activeScheme() ?: return emptyList()
        return rates.findEffectiveComponents(scheme.id!!, onDate)
    }

    /**
     * Tax on [taxableValue] under the active scheme on [onDate].
     *
     * Components apply in `sort_order`, and each one is charged on either the
     * taxable value or the running total depending on its `computed_on`. That
     * distinction is the whole reason this is not a single percentage: NHIL and
     * GETFund are charged on the taxable value, VAT on that value plus those
     * levies. Multiplying by a combined 21.5% gives a different — and wrong —
     * answer.
     *
     * Each component is rounded to the cedi's two places before the next one is
     * computed, and the total is the sum of those rounded figures. A receipt
     * that itemises three levies has to add up to the total printed beneath it.
     */
    @Transactional(readOnly = true)
    fun calculate(taxableValue: BigDecimal, onDate: LocalDate = LocalDate.now()): TaxBreakdown {
        val scheme = activeScheme() ?: return TaxBreakdown.none(taxableValue)
        val taxable = Money.round(taxableValue)

        val applicable = rates.findEffectiveComponents(scheme.id!!, onDate)
        if (applicable.isEmpty()) {
            /*
             * A scheme declaring no components at all is how a zero-tax shop is
             * legitimately configured, and returns zero. A scheme that declares
             * components but has no rate covering today is a configuration gap:
             * charging nothing there would under-collect silently for as long as
             * nobody looked, so it stops the sale instead.
             */
            if (components.findByTaxSchemeIdOrderBySortOrderAscIdAsc(scheme.id!!).isNotEmpty()) {
                throw ApiException.RuleViolation(
                    "TAX_RATE_MISSING",
                    "The ${scheme.code} tax scheme is active but has no rate set for $onDate. " +
                        "Set the rates before selling.",
                )
            }
            return TaxBreakdown(scheme.code, taxable, emptyList(), Money.ZERO)
        }

        var runningTotal = taxable
        val applied = applicable.map { c ->
            val base = when (c.computedOn) {
                "TAXABLE_VALUE" -> taxable
                "RUNNING_TOTAL" -> runningTotal
                else -> throw ApiException.RuleViolation(
                    "TAX_CONFIG_INVALID",
                    "Tax component ${c.code} has an unrecognised basis '${c.computedOn}'.",
                )
            }
            val amount = Money.percentOf(base, c.ratePercent)
            runningTotal = runningTotal.add(amount)
            TaxComponentAmount(c.code, c.name, c.ratePercent, amount, c.isRecoverable)
        }

        return TaxBreakdown(
            schemeCode = scheme.code,
            taxableValue = taxable,
            components = applied,
            total = applied.fold(Money.ZERO) { acc, c -> acc.add(c.amount) },
        )
    }
}
