package com.counterweight.pricing.domain

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDate

/*
 * Pricing entities.
 *
 * Two of these are effective-dated rather than mutable: [Price] and [TaxRate].
 * Neither is edited in place once it is live. A price change closes the current
 * window and opens a new one, so "what was this selling for on the 3rd" is
 * answerable from the table itself rather than by replaying an audit trail —
 * the same reasoning that makes the stock ledger append-only (§5).
 */

@Entity
@Table(name = "price_list")
class PriceList(
    @Column(name = "branch_id", nullable = false)
    var branchId: Long,

    @Column(nullable = false)
    var code: String,

    @Column(nullable = false)
    var name: String,
) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    /**
     * The list a sale falls back to when the customer has none of their own.
     *
     * `one_default_price_list_per_branch` is a partial unique index, so
     * promoting a new default means demoting the old one first, in the same
     * transaction — see PriceListService.setDefault.
     */
    @Column(name = "is_default", nullable = false)
    var isDefault: Boolean = false

    override fun equals(other: Any?) = this === other || (other is PriceList && id != null && id == other.id)
    override fun hashCode() = javaClass.hashCode()
    override fun toString() = "PriceList($code)"
}

/**
 * One price, for one product in one selling unit, over one date window.
 *
 * Quoted per **selling unit**, not per base unit: the counter thinks in "GHS
 * per bag", and a bag price divided into a per-kilo figure and multiplied back
 * out is how rounding error reaches a receipt.
 *
 * [effectiveTo] null means open-ended, and `one_open_price` allows exactly one
 * such row per list/product/unit.
 */
@Entity
@Table(name = "price")
class Price(
    @Column(name = "price_list_id", nullable = false)
    var priceListId: Long,

    @Column(name = "product_id", nullable = false)
    var productId: Long,

    @Column(name = "product_uom_id", nullable = false)
    var productUomId: Long,

    @Column(name = "unit_price", nullable = false)
    var unitPrice: BigDecimal,
) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "effective_from", nullable = false)
    var effectiveFrom: LocalDate = LocalDate.now()

    @Column(name = "effective_to")
    var effectiveTo: LocalDate? = null

    override fun equals(other: Any?) = this === other || (other is Price && id != null && id == other.id)
    override fun hashCode() = javaClass.hashCode()
}

/**
 * How much a role may take off a price, and how thin a margin it may leave.
 *
 * One row per role. A user with several roles gets the most permissive of them
 * — the alternative, taking the minimum, would mean that granting somebody an
 * extra role could silently reduce what they were already allowed to do.
 */
@Entity
@Table(name = "discount_policy")
class DiscountPolicy(
    @Column(name = "role_id", nullable = false)
    var roleId: Long,
) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "max_percent", nullable = false)
    var maxPercent: BigDecimal = BigDecimal.ZERO

    /**
     * The floor under which a sale needs a supervisor, whatever the discount
     * percentage was. A 5% discount on a thin-margin item can lose money where
     * 20% on a fat-margin one does not, so the percentage alone is not enough.
     */
    @Column(name = "min_margin_percent", nullable = false)
    var minMarginPercent: BigDecimal = BigDecimal.ZERO

    /** Above this, allowed but only with an override. Null means never. */
    @Column(name = "requires_approval_above")
    var requiresApprovalAbove: BigDecimal? = null

    override fun equals(other: Any?) = this === other || (other is DiscountPolicy && id != null && id == other.id)
    override fun hashCode() = javaClass.hashCode()
}

/**
 * A named set of tax components. Exactly one is active at a time.
 *
 * The shop is not VAT-registered, so the live configuration is a scheme with a
 * zero rate — or none active at all. The engine is built and wired regardless
 * (§1): registration is a form, not a rebuild.
 */
@Entity
@Table(name = "tax_scheme")
class TaxScheme(
    @Column(nullable = false, unique = true)
    var code: String,

    @Column(nullable = false)
    var name: String,
) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = false

    override fun equals(other: Any?) = this === other || (other is TaxScheme && id != null && id == other.id)
    override fun hashCode() = javaClass.hashCode()
    override fun toString() = "TaxScheme($code)"
}

/**
 * One levy within a scheme — VAT, NHIL, GETFund.
 *
 * [computedOn] is the reason this is modelled at all rather than as a single
 * percentage. Ghana's health and education levies are charged on the taxable
 * value, while VAT is charged on that value *plus* those levies. Collapsing the
 * two into one rate gives the wrong figure, so the base and the order are both
 * explicit.
 */
@Entity
@Table(name = "tax_component")
class TaxComponent(
    @Column(name = "tax_scheme_id", nullable = false)
    var taxSchemeId: Long,

    @Column(nullable = false)
    var code: String,

    @Column(nullable = false)
    var name: String,
) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "computed_on", nullable = false)
    var computedOn: String = "TAXABLE_VALUE"

    /** Reclaimable input tax. Reporting cares; the till does not. */
    @Column(name = "is_recoverable", nullable = false)
    var isRecoverable: Boolean = true

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0

    override fun equals(other: Any?) = this === other || (other is TaxComponent && id != null && id == other.id)
    override fun hashCode() = javaClass.hashCode()
    override fun toString() = "TaxComponent($code)"
}

/**
 * A component's rate over a date window.
 *
 * Rates change by budget statement, on a date announced in advance. Superseding
 * rather than editing means a reprint of last month's invoice still computes
 * last month's tax.
 */
@Entity
@Table(name = "tax_rate")
class TaxRate(
    @Column(name = "tax_component_id", nullable = false)
    var taxComponentId: Long,

    @Column(name = "rate_percent", nullable = false)
    var ratePercent: BigDecimal,

    @Column(name = "effective_from", nullable = false)
    var effectiveFrom: LocalDate,
) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "effective_to")
    var effectiveTo: LocalDate? = null

    override fun equals(other: Any?) = this === other || (other is TaxRate && id != null && id == other.id)
    override fun hashCode() = javaClass.hashCode()
}
