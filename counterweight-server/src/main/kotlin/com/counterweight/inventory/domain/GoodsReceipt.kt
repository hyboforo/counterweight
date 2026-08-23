package com.counterweight.inventory.domain

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * A delivery, as a numbered document.
 *
 * Goods arriving is the one place a cost enters the system, and every margin
 * the shop ever reports is derived from it — so it is a document with a number,
 * not a side effect of some movements. [number] is ours (GRN-000001);
 * [supplierReference] is whatever the delivery note says, kept so the two
 * pieces of paper can be matched a year later.
 *
 * The RECEIPT movements this creates carry `source_id` pointing back here,
 * which is what lets the ledger say *which* receipt justifies a lot.
 */
@Entity
@Table(name = "goods_receipt")
class GoodsReceipt(
    @Column(name = "branch_id", nullable = false)
    var branchId: Long,

    @Column(nullable = false)
    var number: String,

    @Column(name = "received_by", nullable = false)
    var receivedBy: Long,
) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "supplier_reference")
    var supplierReference: String? = null

    @Column(name = "received_on", nullable = false)
    var receivedOn: LocalDate = LocalDate.now()

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()

    override fun equals(other: Any?) = this === other || (other is GoodsReceipt && id != null && id == other.id)
    override fun hashCode() = javaClass.hashCode()
    override fun toString() = "GoodsReceipt($number)"
}

/**
 * One line of a delivery.
 *
 * Carries both the figures as keyed ([qtyReceived] and [unitCost], in the unit
 * the goods arrived in) and the same figures converted to base units
 * ([qtyBase], [costPerBase]). Stored rather than derived on read for the reason
 * `sale_line` gives: a later change to a product's conversion factor must not
 * rewrite what this document said. A carton of twelve that becomes a carton of
 * twenty-four next year does not change what arrived on this delivery.
 */
@Entity
@Table(name = "goods_receipt_line")
class GoodsReceiptLine(
    @Column(name = "goods_receipt_id", nullable = false)
    var goodsReceiptId: Long,

    @Column(name = "line_no", nullable = false)
    var lineNo: Int,

    @Column(name = "product_id", nullable = false)
    var productId: Long,

    @Column(name = "product_uom_id", nullable = false)
    var productUomId: Long,

    @Column(name = "qty_received", nullable = false)
    var qtyReceived: BigDecimal,

    @Column(name = "unit_cost", nullable = false)
    var unitCost: BigDecimal,

    @Column(name = "qty_base", nullable = false)
    var qtyBase: BigDecimal,

    @Column(name = "cost_per_base", nullable = false)
    var costPerBase: BigDecimal,

    @Column(name = "lot_id", nullable = false)
    var lotId: Long,
) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "batch_code")
    var batchCode: String? = null

    @Column(name = "expires_on")
    var expiresOn: LocalDate? = null

    override fun equals(other: Any?) = this === other || (other is GoodsReceiptLine && id != null && id == other.id)
    override fun hashCode() = javaClass.hashCode()
}
