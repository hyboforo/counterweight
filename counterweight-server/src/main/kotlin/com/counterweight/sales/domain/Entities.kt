package com.counterweight.sales.domain

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/*
 * Sales entities.
 *
 * The shape to notice is that payments and lot allocations are both
 * collections, never columns. Split tender is normal here — part cash, part
 * mobile money, the rest on account (§8.2) — and a single line routinely spans
 * several lots when the first one runs out mid-quantity. Columns would have
 * forced a "primary payment" and a "primary lot" that the shop does not have.
 */

/**
 * A sale, from the first item scanned to the receipt.
 *
 * DRAFT → HELD → COMPLETED, then optionally PARTIALLY_RETURNED, RETURNED or
 * VOIDED (§8.1). Held sales live here rather than in browser memory because a
 * customer leaving to find a mobile-money agent is routine, and any till has to
 * be able to recall the basket.
 */
@Entity
@Table(name = "sale")
class Sale(
    @Column(name = "branch_id", nullable = false)
    var branchId: Long,

    @Column(name = "cashier_id", nullable = false)
    var cashierId: Long,
) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    /**
     * Which till rang this up. Null for anything written up in the back office.
     *
     * Recorded on the sale itself because it is the only thing that knows: it
     * is what routes a receipt to the right printer, and what a takings report
     * groups by.
     */
    @Column(name = "till_code")
    var tillCode: String? = null

    @Column(name = "customer_id")
    var customerId: Long? = null

    /** Allocated on completion, never before — a draft has no number to leak. */
    var number: String? = null

    @Column(nullable = false)
    var status: String = "DRAFT"

    @Column(name = "held_label")
    var heldLabel: String? = null

    @Column(nullable = false)
    var subtotal: BigDecimal = BigDecimal.ZERO

    @Column(name = "discount_total", nullable = false)
    var discountTotal: BigDecimal = BigDecimal.ZERO

    @Column(name = "tax_total", nullable = false)
    var taxTotal: BigDecimal = BigDecimal.ZERO

    /** What cash rounding added or removed, so the day reconciles exactly (§8.4). */
    @Column(name = "rounding_adjustment", nullable = false)
    var roundingAdjustment: BigDecimal = BigDecimal.ZERO

    @Column(name = "grand_total", nullable = false)
    var grandTotal: BigDecimal = BigDecimal.ZERO

    @Column(name = "completed_at")
    var completedAt: Instant? = null

    @Column(name = "voided_at")
    var voidedAt: Instant? = null

    @Column(name = "void_reason")
    var voidReason: String? = null

    @Column(name = "voided_by")
    var voidedBy: Long? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()

    val isOpen: Boolean get() = status == "DRAFT" || status == "HELD"

    override fun equals(other: Any?) = this === other || (other is Sale && id != null && id == other.id)
    override fun hashCode() = javaClass.hashCode()
    override fun toString() = "Sale(${number ?: "draft $id"})"
}

@Entity
@Table(name = "sale_line")
class SaleLine(
    @Column(name = "sale_id", nullable = false)
    var saleId: Long,

    @Column(name = "line_no", nullable = false)
    var lineNo: Int,

    @Column(name = "product_id", nullable = false)
    var productId: Long,

    @Column(name = "product_uom_id", nullable = false)
    var productUomId: Long,

    /** As the counter keyed it, in the selling unit. */
    @Column(nullable = false)
    var qty: BigDecimal,

    /**
     * The same quantity in base units. Stored rather than derived so that a
     * later change to the conversion factor cannot silently rewrite history.
     */
    @Column(name = "qty_base", nullable = false)
    var qtyBase: BigDecimal,

    @Column(name = "unit_price", nullable = false)
    var unitPrice: BigDecimal,

    @Column(name = "line_total", nullable = false)
    var lineTotal: BigDecimal,

    /**
     * Cost at the moment of sale, from the lot.
     *
     * On a draft this is an estimate taken from the lots the picking rule would
     * choose, because the margin floor has to be checkable while the customer is
     * still standing there. Completion overwrites it with what was actually
     * issued, so margin reporting is exact rather than indicative.
     */
    @Column(name = "unit_cost", nullable = false)
    var unitCost: BigDecimal,
) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "discount_amount", nullable = false)
    var discountAmount: BigDecimal = BigDecimal.ZERO

    @Column(name = "tax_amount", nullable = false)
    var taxAmount: BigDecimal = BigDecimal.ZERO

    @Column(name = "price_overridden", nullable = false)
    var priceOverridden: Boolean = false

    @Column(name = "approved_by")
    var approvedBy: Long? = null

    override fun equals(other: Any?) = this === other || (other is SaleLine && id != null && id == other.id)
    override fun hashCode() = javaClass.hashCode()
}

/**
 * Which lot a line actually drew from.
 *
 * A single line spans several rows when the first lot runs out mid-quantity.
 * This table is what turns batch traceability into one query — "which customers
 * received lot 4471" for a manufacturer recall — which is a safety obligation
 * on the agro-chemical side, not a reporting nicety.
 */
@Entity
@Table(name = "sale_line_allocation")
class SaleLineAllocation(
    @Column(name = "sale_line_id", nullable = false)
    var saleLineId: Long,

    @Column(name = "lot_id", nullable = false)
    var lotId: Long,

    @Column(name = "qty_base", nullable = false)
    var qtyBase: BigDecimal,

    @Column(name = "unit_cost", nullable = false)
    var unitCost: BigDecimal,
) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    override fun equals(other: Any?) = this === other || (other is SaleLineAllocation && id != null && id == other.id)
    override fun hashCode() = javaClass.hashCode()
}

@Entity
@Table(name = "sale_payment")
class SalePayment(
    @Column(name = "sale_id", nullable = false)
    var saleId: Long,

    @Column(nullable = false)
    var method: String,

    @Column(nullable = false)
    var amount: BigDecimal,
) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    /** Cash only: what the customer handed over, and what went back. */
    @Column(name = "tendered")
    var tendered: BigDecimal? = null

    @Column(name = "change_given")
    var changeGiven: BigDecimal? = null

    /** Keyed by hand: the shop has no internet, so the reference is the record. */
    @Column(name = "momo_network")
    var momoNetwork: String? = null

    var reference: String? = null

    @Column(name = "bank_name")
    var bankName: String? = null

    @Column(name = "cheque_number")
    var chequeNumber: String? = null

    @Column(name = "cheque_date")
    var chequeDate: LocalDate? = null

    @Column(name = "received_at", nullable = false)
    var receivedAt: Instant = Instant.now()

    override fun equals(other: Any?) = this === other || (other is SalePayment && id != null && id == other.id)
    override fun hashCode() = javaClass.hashCode()

    companion object {
        val METHODS = setOf("CASH", "MOBILE_MONEY", "BANK_TRANSFER", "CHEQUE", "ON_ACCOUNT", "CARD")
        val MOMO_NETWORKS = setOf("MTN", "TELECEL", "AT")
    }
}

/**
 * Proof that a completion request was already honoured.
 *
 * The till generates a UUID when the operator presses Pay. Written inside the
 * same transaction as the sale, so a retry — double click, impatient operator,
 * a switch that dropped a frame — finds it and replays the original rather than
 * deducting stock a second time (§8.3).
 */
@Entity
@Table(name = "idempotency_record")
class IdempotencyRecord(
    @Id
    @Column(name = "key")
    var key: UUID,

    @Column(name = "sale_id", nullable = false)
    var saleId: Long,
) {
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()
}

@Entity
@Table(name = "sale_return")
class SaleReturn(
    @Column(name = "branch_id", nullable = false)
    var branchId: Long,

    @Column(name = "sale_id", nullable = false)
    var saleId: Long,

    @Column(nullable = false)
    var number: String,

    @Column(nullable = false)
    var reason: String,

    @Column(name = "refund_method", nullable = false)
    var refundMethod: String,

    @Column(nullable = false)
    var total: BigDecimal,

    @Column(name = "created_by", nullable = false)
    var createdBy: Long,
) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "approved_by")
    var approvedBy: Long? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()

    override fun equals(other: Any?) = this === other || (other is SaleReturn && id != null && id == other.id)
    override fun hashCode() = javaClass.hashCode()

    companion object {
        val REFUND_METHODS = setOf("CASH", "MOBILE_MONEY", "CREDIT_NOTE", "ACCOUNT")
    }
}

@Entity
@Table(name = "sale_return_line")
class SaleReturnLine(
    @Column(name = "sale_return_id", nullable = false)
    var saleReturnId: Long,

    @Column(name = "sale_line_id", nullable = false)
    var saleLineId: Long,

    @Column(name = "qty_base", nullable = false)
    var qtyBase: BigDecimal,

    /**
     * The lot it came from. Not nullable: a returned agro-chemical that loses
     * its lot loses its expiry date with it, and becomes stock nobody can
     * lawfully sell or recall.
     */
    @Column(name = "lot_id", nullable = false)
    var lotId: Long,

    @Column(nullable = false)
    var amount: BigDecimal,
) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    /** False for goods coming back damaged — refunded but never resold. */
    @Column(nullable = false)
    var restock: Boolean = true

    override fun equals(other: Any?) = this === other || (other is SaleReturnLine && id != null && id == other.id)
    override fun hashCode() = javaClass.hashCode()
}

/**
 * Buyer register entry for a restricted agro-chemical.
 *
 * Written where the product's `requires_buyer_record` attribute is set
 * (§6.3). The sale cannot complete without it, which is the point — a register
 * that can be filled in later is a register that gets filled in never.
 */
@Entity
@Table(name = "restricted_sale_record")
class RestrictedSaleRecord(
    @Column(name = "sale_line_id", nullable = false)
    var saleLineId: Long,

    @Column(name = "buyer_name", nullable = false)
    var buyerName: String,

    @Column(name = "recorded_by", nullable = false)
    var recordedBy: Long,
) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "buyer_phone")
    var buyerPhone: String? = null

    @Column(name = "buyer_id_type")
    var buyerIdType: String? = null

    @Column(name = "buyer_id_number")
    var buyerIdNumber: String? = null

    @Column(name = "intended_use")
    var intendedUse: String? = null

    @Column(name = "recorded_at", nullable = false)
    var recordedAt: Instant = Instant.now()

    override fun equals(other: Any?) = this === other || (other is RestrictedSaleRecord && id != null && id == other.id)
    override fun hashCode() = javaClass.hashCode()
}
