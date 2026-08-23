package com.counterweight.inventory.repo

import com.counterweight.inventory.domain.StockTake
import com.counterweight.inventory.domain.StockTakeLine
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.math.BigDecimal

/**
 * A row on the count sheet, with enough about the product to find it on the
 * shelf. The expected quantity is deliberately absent from what the counter is
 * shown — see [StockTakeRepository.sheet].
 */
interface CountSheetRow {
    val lineId: Long
    val lotId: Long
    val productId: Long
    val sku: String
    val name: String
    val localName: String?
    val lotCode: String
    val expiresOn: java.sql.Date?
    val uomCode: String
    val decimals: Int
    val countedQty: BigDecimal?
    val note: String?

    /** Movements against this lot since the snapshot was taken. */
    val movedSince: Int
}

/** A counted line with its variance, for the sign-off screen and the report. */
interface VarianceRow {
    val lineId: Long
    val lotId: Long
    val sku: String
    val name: String
    val lotCode: String
    val uomCode: String
    val expectedQty: BigDecimal
    val countedQty: BigDecimal?
    val variance: BigDecimal?
    val unitCost: BigDecimal
    val note: String?
    val movedSince: Int

    /** What the ledger says is on hand now, which is not the expected figure if trading continued. */
    val onHandNow: BigDecimal
}

@Repository
interface StockTakeRepository : JpaRepository<StockTake, Long> {

    fun findByBranchIdAndReference(branchId: Long, reference: String): StockTake?

    fun findByBranchIdOrderByOpenedAtDesc(branchId: Long): List<StockTake>

    /**
     * Sets the ltree scope.
     *
     * A separate statement because ltree reports as Types#OTHER over JDBC:
     * Hibernate cannot bind a String to it, and ddl-auto validate refuses a
     * String property mapped to the column. The cast is spelled out rather than
     * written with colons so Spring Data does not read them as a parameter.
     */
    @Modifying
    @Query(
        value = "UPDATE stock_take SET scope_path = CAST(:path AS ltree) WHERE id = :id",
        nativeQuery = true,
    )
    fun applyScope(@Param("id") id: Long, @Param("path") path: String?)

    /**
     * Counts already under way whose scope overlaps the one proposed.
     *
     * Two open counts covering the same lot would each snapshot it and each post
     * a variance, so one discrepancy would be written off twice. Overlap is
     * subtree containment in either direction; a null path on either side means
     * the whole branch and therefore overlaps everything.
     */
    @Query(
        value = """
            SELECT count(*) FROM stock_take t
             WHERE t.branch_id = :branchId
               AND t.status IN ('OPEN','COUNTED')
               AND (
                     t.scope_path IS NULL
                  OR CAST(:path AS ltree) IS NULL
                  OR t.scope_path <@ CAST(:path AS ltree)
                  OR CAST(:path AS ltree) <@ t.scope_path
               )
        """,
        nativeQuery = true,
    )
    fun overlappingOpenCount(@Param("branchId") branchId: Long, @Param("path") path: String?): Long

    /**
     * Snapshots the lots in scope into count-sheet lines, in one statement.
     *
     * Written as INSERT..SELECT rather than read-then-write so the snapshot is
     * one atomic read of the balance table. Looping in the application would let
     * sales land between lines, and the sheet would be a picture of a moment
     * that never existed.
     *
     * Lots with nothing on hand are left out — a sheet listing every lot the
     * shop has ever held would be unusable. Stock found for a lot the books
     * believe is empty is added as a line of its own instead.
     */
    @Modifying
    @Query(
        value = """
            INSERT INTO stock_take_line (stock_take_id, lot_id, expected_qty)
            SELECT :takeId, l.id, b.qty_base
              FROM stock_lot l
              JOIN stock_balance b ON b.lot_id = l.id AND b.branch_id = l.branch_id
              JOIN product p       ON p.id = l.product_id
              LEFT JOIN category c ON c.id = p.category_id
             WHERE l.branch_id = :branchId
               AND b.qty_base > 0
               AND (CAST(:path AS ltree) IS NULL OR c.path <@ CAST(:path AS ltree))
        """,
        nativeQuery = true,
    )
    fun snapshot(
        @Param("takeId") takeId: Long,
        @Param("branchId") branchId: Long,
        @Param("path") path: String?,
    ): Int

    /**
     * The count sheet.
     *
     * The expected quantity is not selected. A counter who can see what the
     * system expects will find what the system expects — the number anchors
     * them and the count stops being independent evidence. Same control as the
     * blind count sheet, and the only reason a stock take is worth doing.
     */
    @Query(
        value = """
            SELECT sl.id          AS "lineId",
                   sl.lot_id      AS "lotId",
                   p.id           AS "productId",
                   p.sku          AS "sku",
                   p.name         AS "name",
                   p.local_name   AS "localName",
                   l.lot_code     AS "lotCode",
                   l.expires_on   AS "expiresOn",
                   u.code         AS "uomCode",
                   u.decimals     AS "decimals",
                   sl.counted_qty AS "countedQty",
                   sl.note        AS "note",
                   (SELECT count(*) FROM stock_movement m
                     WHERE m.lot_id = sl.lot_id AND m.occurred_at > t.opened_at) AS "movedSince"
              FROM stock_take_line sl
              JOIN stock_take t     ON t.id = sl.stock_take_id
              JOIN stock_lot l      ON l.id = sl.lot_id
              JOIN product p        ON p.id = l.product_id
              JOIN product_uom pu   ON pu.product_id = p.id AND pu.is_base
              JOIN uom u            ON u.id = pu.uom_id
             WHERE sl.stock_take_id = :takeId
             ORDER BY p.name, l.expires_on NULLS LAST, l.lot_code
        """,
        nativeQuery = true,
    )
    fun sheet(@Param("takeId") takeId: Long): List<CountSheetRow>

    /**
     * The sign-off view: what was counted against what the books said.
     *
     * Carries onHandNow alongside the expected figure because the two differ
     * whenever the shop kept trading, and the person posting should see that
     * rather than discover it from a constraint violation.
     *
     * Ordered by the cash value of the discrepancy. A missing bag of cement and
     * a missing wheelbarrow are not the same problem, and the one worth chasing
     * should be at the top of the page.
     */
    @Query(
        value = """
            SELECT sl.id           AS "lineId",
                   sl.lot_id       AS "lotId",
                   p.sku           AS "sku",
                   p.name          AS "name",
                   l.lot_code      AS "lotCode",
                   u.code          AS "uomCode",
                   sl.expected_qty AS "expectedQty",
                   sl.counted_qty  AS "countedQty",
                   sl.variance     AS "variance",
                   l.unit_cost     AS "unitCost",
                   sl.note         AS "note",
                   (SELECT count(*) FROM stock_movement m
                     WHERE m.lot_id = sl.lot_id AND m.occurred_at > t.opened_at) AS "movedSince",
                   COALESCE(b.qty_base, 0) AS "onHandNow"
              FROM stock_take_line sl
              JOIN stock_take t     ON t.id = sl.stock_take_id
              JOIN stock_lot l      ON l.id = sl.lot_id
              JOIN product p        ON p.id = l.product_id
              JOIN product_uom pu   ON pu.product_id = p.id AND pu.is_base
              JOIN uom u            ON u.id = pu.uom_id
              LEFT JOIN stock_balance b ON b.lot_id = l.id AND b.branch_id = l.branch_id
             WHERE sl.stock_take_id = :takeId
               AND (:variancesOnly = FALSE OR COALESCE(sl.variance, 0) <> 0)
             ORDER BY abs(COALESCE(sl.variance, 0)) * l.unit_cost DESC, p.name
        """,
        nativeQuery = true,
    )
    fun variances(
        @Param("takeId") takeId: Long,
        @Param("variancesOnly") variancesOnly: Boolean,
    ): List<VarianceRow>
}

@Repository
interface StockTakeLineRepository : JpaRepository<StockTakeLine, Long> {

    fun findByStockTakeIdAndLotId(takeId: Long, lotId: Long): StockTakeLine?

    fun countByStockTakeIdAndCountedQtyIsNull(takeId: Long): Long

    fun countByStockTakeId(takeId: Long): Long

    /** Lines of a take sitting on a given product, so a scan can address them. */
    @Query(
        value = """
            SELECT sl.* FROM stock_take_line sl
              JOIN stock_lot l ON l.id = sl.lot_id
             WHERE sl.stock_take_id = :takeId AND l.product_id = :productId
        """,
        nativeQuery = true,
    )
    fun findForProduct(@Param("takeId") takeId: Long, @Param("productId") productId: Long): List<StockTakeLine>
}
