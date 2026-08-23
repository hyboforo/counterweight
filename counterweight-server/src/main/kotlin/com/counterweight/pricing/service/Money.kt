package com.counterweight.pricing.service

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The rounding rules for money in this system, in one place.
 *
 * Every amount that reaches a receipt, a ledger entry or a takings total goes
 * through here. Scattering `setScale` calls across the sale path is how a
 * subtotal ends up disagreeing with the sum of its lines by a pesewa, and that
 * is the one discrepancy a shopkeeper will always notice.
 *
 * Arithmetic is BigDecimal throughout, never double. This is not fastidiousness:
 * a hardware shop sells 3.5 m off a cable roll at a per-metre price, and binary
 * floating point cannot represent either operand exactly.
 */
object Money {

    /** Ghana cedi, two decimal places. Matches NUMERIC(14,2) in the schema. */
    const val SCALE = 2

    /**
     * Prices are held to four places — NUMERIC(14,4) — because a per-base-unit
     * price derived from a carton price genuinely needs them. Rounding happens
     * when the line total is struck, not before.
     */
    const val PRICE_SCALE = 4

    /**
     * The smallest amount that can actually change hands, and the fallback when
     * no configured value has been supplied.
     *
     * One-pesewa coins are legal tender but effectively out of circulation, so
     * a payable total of GHS 41.23 cannot be settled exactly. Totals round to
     * the nearest five pesewas and the difference is recorded on the sale as
     * `rounding_adjustment`, which is what lets the day's cash reconcile to the
     * cedi rather than to "about right".
     *
     * This mirrors the `currency.rounding.increment` row seeded in `app_config`.
     * That row is the real setting — a shop that starts seeing one-pesewa coins
     * again changes it to 0.01 rather than waiting for a release — so callers on
     * the sale path must read it and pass it to [toCash]. The constant exists
     * for the cases with no configuration to hand, not as a second source of
     * truth. Wiring it up is blocked on `platform`, which owns `app_config`.
     */
    val DEFAULT_CASH_INCREMENT: BigDecimal = BigDecimal("0.05")

    fun round(value: BigDecimal): BigDecimal = value.setScale(SCALE, RoundingMode.HALF_UP)

    fun roundPrice(value: BigDecimal): BigDecimal = value.setScale(PRICE_SCALE, RoundingMode.HALF_UP)

    /**
     * [value] rounded to something the shop can hand over in coin.
     *
     * HALF_UP on the tie, so 41.225 goes to 41.25 at a five-pesewa increment.
     * Half a pesewa in the shop's favour on an exact tie is the conventional
     * till behaviour and, unlike HALF_EVEN, it is a rule the person at the
     * counter can predict. The adjustment is recorded either way, so nothing is
     * hidden.
     *
     * An [increment] of 0.01 disables rounding in practice, which is how the
     * setting is meant to be switched off.
     */
    fun toCash(value: BigDecimal, increment: BigDecimal = DEFAULT_CASH_INCREMENT): BigDecimal {
        if (increment <= BigDecimal.ZERO) return round(value)
        return value.divide(increment, 0, RoundingMode.HALF_UP)
            .multiply(increment)
            .setScale(SCALE, RoundingMode.HALF_UP)
    }

    /** What [toCash] added or removed. Signed; belongs on `sale.rounding_adjustment`. */
    fun cashRounding(value: BigDecimal, increment: BigDecimal = DEFAULT_CASH_INCREMENT): BigDecimal =
        toCash(value, increment).subtract(round(value))

    /** [percent] of [base], rounded to the cedi's two places. */
    fun percentOf(base: BigDecimal, percent: BigDecimal): BigDecimal =
        round(base.multiply(percent).divide(HUNDRED, SCALE + 4, RoundingMode.HALF_UP))

    val HUNDRED: BigDecimal = BigDecimal("100")
    val ZERO: BigDecimal = BigDecimal.ZERO.setScale(SCALE)
}
