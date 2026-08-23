package com.counterweight.inventory.repo

import com.counterweight.inventory.domain.GoodsReceipt
import com.counterweight.inventory.domain.GoodsReceiptLine
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.math.BigDecimal

/**
 * A receipt line with the product resolved, for rendering the slip.
 *
 * Everything here is read from what was recorded, not from what a client says
 * it recorded — which is the whole reason the document exists.
 */
interface ReceivedLineRow {
    val lineNo: Int
    val productName: String
    val sku: String
    val qtyReceived: BigDecimal
    val uomCode: String
    val unitCost: BigDecimal
    val qtyBase: BigDecimal
    val costPerBase: BigDecimal
    val baseUomCode: String
    val batchCode: String?
    val expiresOn: java.sql.Date?
    val lotCode: String
}

@Repository
interface GoodsReceiptRepository : JpaRepository<GoodsReceipt, Long> {

    fun findByBranchIdOrderByReceivedOnDescIdDesc(branchId: Long): List<GoodsReceipt>

    fun findByBranchIdAndNumber(branchId: Long, number: String): GoodsReceipt?

    /**
     * The lines of a receipt, joined to the product and to both units.
     *
     * Two unit codes because a line is quoted in the unit the goods arrived in
     * and stored in the product's base unit, and the slip has to be able to
     * show a carton of twelve as both.
     */
    @Query(
        value = """
            SELECT l.line_no        AS "lineNo",
                   p.name           AS "productName",
                   p.sku            AS "sku",
                   l.qty_received   AS "qtyReceived",
                   u.code           AS "uomCode",
                   l.unit_cost      AS "unitCost",
                   l.qty_base       AS "qtyBase",
                   l.cost_per_base  AS "costPerBase",
                   bu.code          AS "baseUomCode",
                   l.batch_code     AS "batchCode",
                   l.expires_on     AS "expiresOn",
                   lot.lot_code     AS "lotCode"
              FROM goods_receipt_line l
              JOIN product p       ON p.id = l.product_id
              JOIN product_uom pu  ON pu.id = l.product_uom_id
              JOIN uom u           ON u.id = pu.uom_id
              JOIN product_uom bpu ON bpu.product_id = p.id AND bpu.is_base
              JOIN uom bu          ON bu.id = bpu.uom_id
              JOIN stock_lot lot   ON lot.id = l.lot_id
             WHERE l.goods_receipt_id = :receiptId
             ORDER BY l.line_no
        """,
        nativeQuery = true,
    )
    fun linesOf(@Param("receiptId") receiptId: Long): List<ReceivedLineRow>

    /** What the delivery was worth at cost, summed from what was recorded. */
    @Query(
        value = """
            SELECT COALESCE(SUM(l.qty_received * l.unit_cost), 0)
              FROM goods_receipt_line l
             WHERE l.goods_receipt_id = :receiptId
        """,
        nativeQuery = true,
    )
    fun valueOf(@Param("receiptId") receiptId: Long): BigDecimal
}

@Repository
interface GoodsReceiptLineRepository : JpaRepository<GoodsReceiptLine, Long> {
    fun findByGoodsReceiptIdOrderByLineNoAsc(receiptId: Long): List<GoodsReceiptLine>
}
