package com.counterweight.inventory.domain

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * A lot: the unit of on-hand stock.
 *
 * Every quantity in the system belongs to one. Agro-chemicals carry a real
 * manufacturer batch and expiry; hardware gets a lot generated at goods-receipt
 * with a null expiry (§2.2). One mechanism, one costing rule, one picking
 * engine.
 *
 * [unitCost] must never be edited once movements exist against this lot —
 * correct it with a compensating adjustment pair instead (§5.2). Nothing in the
 * code path below updates it.
 */
@Entity
@Table(name = "stock_lot")
class StockLot(
    @Column(name = "branch_id", nullable = false)
    var branchId: Long,

    @Column(name = "product_id", nullable = false)
    var productId: Long,

    @Column(name = "lot_code", nullable = false)
    var lotCode: String,

    @Column(name = "unit_cost", nullable = false)
    var unitCost: BigDecimal,
) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "expires_on")
    var expiresOn: LocalDate? = null

    @Column(name = "received_on", nullable = false)
    var receivedOn: LocalDate = LocalDate.now()

    @Column(nullable = false)
    var status: String = "AVAILABLE"

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()

    override fun equals(other: Any?) = this === other || (other is StockLot && id != null && id == other.id)
    override fun hashCode() = javaClass.hashCode()
    override fun toString() = "StockLot($lotCode)"
}

/**
 * An immutable, signed entry in the stock ledger.
 *
 * There is no setter path that mutates one of these, and the database rejects
 * UPDATE and DELETE outright (V1). A mistake is corrected by posting a
 * compensating movement, which is itself part of the record.
 */
@Entity
@Table(name = "stock_movement")
class StockMovement(
    @Column(name = "branch_id", nullable = false)
    var branchId: Long,

    @Column(name = "lot_id", nullable = false)
    var lotId: Long,

    @Column(name = "movement_type", nullable = false)
    var movementType: String,

    /** Signed, in the product's base unit. Positive is in, negative is out. */
    @Column(name = "qty_base", nullable = false)
    var qtyBase: BigDecimal,

    @Column(name = "unit_cost", nullable = false)
    var unitCost: BigDecimal,

    @Column(name = "source_type", nullable = false)
    var sourceType: String,

    @Column(name = "created_by", nullable = false)
    var createdBy: Long,
) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "source_id")
    var sourceId: Long? = null

    @Column(name = "reason_code")
    var reasonCode: String? = null

    @Column(name = "occurred_at", nullable = false)
    var occurredAt: Instant = Instant.now()

    @Column(name = "approved_by")
    var approvedBy: Long? = null

    override fun equals(other: Any?) = this === other || (other is StockMovement && id != null && id == other.id)
    override fun hashCode() = javaClass.hashCode()
}

/**
 * Derived from the ledger by trigger. Read-only from the application's side:
 * writing here directly would put the balance out of step with the movements
 * that justify it, which is exactly what v_ledger_mismatch exists to catch.
 */
@Entity
@Table(name = "stock_balance")
@IdClass(StockBalanceId::class)
class StockBalance {
    @Id @Column(name = "branch_id") var branchId: Long = 0
    @Id @Column(name = "lot_id") var lotId: Long = 0

    @Column(name = "qty_base", nullable = false, insertable = false, updatable = false)
    var qtyBase: BigDecimal = BigDecimal.ZERO

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    var updatedAt: Instant = Instant.now()
}

data class StockBalanceId(var branchId: Long = 0, var lotId: Long = 0) : java.io.Serializable
