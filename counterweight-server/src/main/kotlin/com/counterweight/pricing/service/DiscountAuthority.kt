package com.counterweight.pricing.service

import com.counterweight.common.ApiException
import com.counterweight.identity.security.Auth
import com.counterweight.pricing.repo.DiscountPolicyRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode

enum class DiscountOutcome {
    /** Apply it and move on. */
    ALLOWED,

    /** A supervisor with a wider allowance must authorise it. */
    REQUIRES_APPROVAL,

    /** Beyond what anyone with these roles may do, approval or not. */
    REFUSED,
}

/** What one operator may take off a price. Carries no cost figure. */
data class DiscountAllowance(
    val maxPercent: BigDecimal,
    val minMarginPercent: BigDecimal,
    val requiresApprovalAbove: BigDecimal?,
)

/**
 * The verdict on one requested discount.
 *
 * [resultingMarginPercent] is derived from lot cost and must **not** be
 * serialised to a client that lacks `COST_VIEW` — margin and cost are the same
 * secret wearing different clothes, and sales staff are deliberately not given
 * either. The controllers in this module do not expose this type.
 */
data class DiscountDecision(
    val outcome: DiscountOutcome,
    val requestedPercent: BigDecimal,
    val netUnitPrice: BigDecimal,
    val resultingMarginPercent: BigDecimal,
    val allowance: DiscountAllowance,
    /** What to show the person at the counter. Null when allowed outright. */
    val reason: String?,
)

/**
 * Decides whether a discount may be given.
 *
 * Two independent limits, because a percentage on its own does not describe the
 * risk: 20% off a fat-margin fitting still makes money, while 5% off a bagged
 * agro-chemical bought on a thin margin does not. So the policy caps the
 * percentage *and* puts a floor under the margin, and either can stop a sale.
 *
 * Approval is modelled as re-evaluation, not as a flag. When a supervisor
 * authorises an override the same discount is evaluated again under *their*
 * roles; if it clears, it clears for a real reason that the audit entry can
 * name. Nothing here lets a caller assert "approved" and bypass the check.
 */
@Service
class DiscountAuthority(private val policies: DiscountPolicyRepository) {

    /**
     * The combined allowance of a set of roles.
     *
     * The most permissive role wins on every axis. Taking the minimum instead
     * would mean that granting somebody an additional role could quietly reduce
     * what they were already allowed to do, which is not how anyone reads
     * "we also made her a supervisor".
     */
    @Transactional(readOnly = true)
    fun allowanceFor(roleCodes: Set<String>): DiscountAllowance {
        if (roleCodes.isEmpty()) return NO_ALLOWANCE
        val found = policies.findByRoleCodes(roleCodes)
        if (found.isEmpty()) return NO_ALLOWANCE

        return DiscountAllowance(
            maxPercent = found.maxOf { it.maxPercent },
            minMarginPercent = found.minOf { it.minMarginPercent },
            // A null threshold means "never needs approval", so it is the most
            // permissive value and wins over any number.
            requiresApprovalAbove =
                if (found.any { it.requiresApprovalAbove == null }) null
                else found.mapNotNull { it.requiresApprovalAbove }.max(),
        )
    }

    /** The signed-in operator's own allowance — what the till greys out. */
    @Transactional(readOnly = true)
    fun currentAllowance(): DiscountAllowance = allowanceFor(Auth.current().roles)

    /**
     * Evaluates [requestedPercent] off [unitPrice] against the roles given.
     *
     * [unitCost] is the lot cost the line will actually draw from, which is why
     * this cannot be answered by the pricing module alone — the caller on the
     * sale path supplies it after allocation.
     */
    @Transactional(readOnly = true)
    fun evaluate(
        roleCodes: Set<String>,
        requestedPercent: BigDecimal,
        unitPrice: BigDecimal,
        unitCost: BigDecimal,
    ): DiscountDecision {
        if (requestedPercent < BigDecimal.ZERO || requestedPercent > Money.HUNDRED) {
            throw ApiException.Validation(
                "A discount must be between 0 and 100 percent.",
                mapOf("discountPercent" to "must be between 0 and 100"),
            )
        }
        val allowance = allowanceFor(roleCodes)
        val net = Money.roundPrice(
            unitPrice.multiply(Money.HUNDRED.subtract(requestedPercent))
                .divide(Money.HUNDRED, Money.PRICE_SCALE + 2, RoundingMode.HALF_UP)
        )
        val margin = marginPercent(net, unitCost)

        fun decide(outcome: DiscountOutcome, reason: String?) =
            DiscountDecision(outcome, requestedPercent, net, margin, allowance, reason)

        if (requestedPercent > allowance.maxPercent) {
            return decide(
                DiscountOutcome.REFUSED,
                "That is more than the ${allowance.maxPercent.stripTrailingZeros().toPlainString()}% " +
                    "discount this account may give. A supervisor can authorise it.",
            )
        }
        if (margin < allowance.minMarginPercent) {
            return decide(
                DiscountOutcome.REQUIRES_APPROVAL,
                "That price leaves too little margin on this item and needs a supervisor.",
            )
        }
        val threshold = allowance.requiresApprovalAbove
        if (threshold != null && requestedPercent > threshold) {
            return decide(
                DiscountOutcome.REQUIRES_APPROVAL,
                "Discounts above ${threshold.stripTrailingZeros().toPlainString()}% need a supervisor.",
            )
        }
        return decide(DiscountOutcome.ALLOWED, null)
    }

    /**
     * Margin on the selling price, as a percentage: (price − cost) / price.
     *
     * On the price rather than on the cost, because that is what "we work on
     * 20%" means to a shopkeeper and what the policy's floor is expressed in.
     *
     * A giveaway has no defined margin — the denominator is zero. It reports
     * −100 when there is any cost to lose and 0 when there is not, which keeps
     * the comparison against a floor meaningful in both directions rather than
     * throwing on a case the till can legitimately reach.
     */
    fun marginPercent(netPrice: BigDecimal, unitCost: BigDecimal): BigDecimal {
        if (netPrice <= BigDecimal.ZERO) {
            return if (unitCost > BigDecimal.ZERO) MINUS_HUNDRED else BigDecimal.ZERO.setScale(2)
        }
        return netPrice.subtract(unitCost)
            .divide(netPrice, 6, RoundingMode.HALF_UP)
            .multiply(Money.HUNDRED)
            .setScale(2, RoundingMode.HALF_UP)
    }

    private companion object {
        val NO_ALLOWANCE = DiscountAllowance(BigDecimal.ZERO, BigDecimal.ZERO, null)
        val MINUS_HUNDRED: BigDecimal = BigDecimal("-100").setScale(2)
    }
}
