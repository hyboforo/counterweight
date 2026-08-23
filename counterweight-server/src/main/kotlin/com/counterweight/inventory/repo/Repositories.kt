package com.counterweight.inventory.repo

import com.counterweight.inventory.domain.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.math.BigDecimal

/** One row per lot with stock on hand, for a product. */
interface LotOnHand {
    val lotId: Long
    val lotCode: String
    val expiresOn: java.sql.Date?
    val unitCost: BigDecimal
    val qtyBase: BigDecimal
    val status: String
}

/** Aggregate position for a product across all its lots. */
interface ProductStock {
    val productId: Long
    val sku: String
    val name: String
    val qtyBase: BigDecimal
    val stockValue: BigDecimal
    val lotCount: Int
    val nearestExpiry: java.sql.Date?
}

@Repository
interface StockLotRepository : JpaRepository<StockLot, Long> {

    fun findByBranchIdAndProductIdAndLotCode(branchId: Long, productId: Long, lotCode: String): StockLot?

    /**
     * Lots with stock on hand, ordered by the product's picking rule.
     *
     * FEFO puts the nearest expiry first with non-expiring lots last; FIFO uses
     * receipt order. This is the query the sale path will allocate from, so the
     * partial indexes from V1 matter here.
     */
    @Query(
        value = """
            SELECT l.id            AS "lotId",
                   l.lot_code      AS "lotCode",
                   l.expires_on    AS "expiresOn",
                   l.unit_cost     AS "unitCost",
                   b.qty_base      AS "qtyBase",
                   l.status        AS "status"
              FROM stock_lot l
              JOIN stock_balance b ON b.lot_id = l.id AND b.branch_id = l.branch_id
             WHERE l.branch_id = :branchId
               AND l.product_id = :productId
               AND l.status = 'AVAILABLE'
               AND b.qty_base > 0
             ORDER BY
               CASE WHEN :rule = 'FEFO' THEN l.expires_on END ASC NULLS LAST,
               CASE WHEN :rule = 'FIFO' THEN l.received_on END ASC,
               l.id ASC
        """,
        nativeQuery = true,
    )
    fun onHandForProduct(
        @Param("branchId") branchId: Long,
        @Param("productId") productId: Long,
        @Param("rule") rule: String,
    ): List<LotOnHand>

    /**
     * Stock position across the catalogue.
     *
     * Valued at lot cost, which is exact rather than an average: cost lives on
     * the lot and the issue cost is that lot's cost (ADR-004).
     */
    @Query(
        value = """
            SELECT p.id                          AS "productId",
                   p.sku                         AS "sku",
                   p.name                        AS "name",
                   COALESCE(SUM(b.qty_base), 0)  AS "qtyBase",
                   COALESCE(SUM(b.qty_base * l.unit_cost), 0) AS "stockValue",
                   COUNT(l.id) FILTER (WHERE b.qty_base > 0)  AS "lotCount",
                   MIN(l.expires_on) FILTER (WHERE b.qty_base > 0 AND l.status = 'AVAILABLE') AS "nearestExpiry"
              FROM product p
              LEFT JOIN stock_lot l     ON l.product_id = p.id AND l.status = 'AVAILABLE'
              LEFT JOIN stock_balance b ON b.lot_id = l.id AND b.branch_id = l.branch_id
             WHERE p.branch_id = :branchId
               AND (:productId IS NULL OR p.id = :productId)
               AND p.is_active
             GROUP BY p.id, p.sku, p.name
             ORDER BY p.name
        """,
        nativeQuery = true,
    )
    fun stockPosition(
        @Param("branchId") branchId: Long,
        @Param("productId") productId: Long?,
    ): List<ProductStock>

    /** Lots at or past expiry that are still sellable — the nightly sweep's input. */
    @Query(
        value = """
            SELECT l.* FROM stock_lot l
              JOIN stock_balance b ON b.lot_id = l.id AND b.branch_id = l.branch_id
             WHERE l.status = 'AVAILABLE'
               AND l.expires_on IS NOT NULL
               AND l.expires_on <= :onDate
               AND b.qty_base > 0
        """,
        nativeQuery = true,
    )
    fun expiredWithStock(@Param("onDate") onDate: java.time.LocalDate): List<StockLot>
}

@Repository
interface StockMovementRepository : JpaRepository<StockMovement, Long> {

    fun findByLotIdOrderByOccurredAtAsc(lotId: Long): List<StockMovement>

    @Query(
        value = """
            SELECT m.* FROM stock_movement m
              JOIN stock_lot l ON l.id = m.lot_id
             WHERE l.product_id = :productId
             ORDER BY m.occurred_at DESC, m.id DESC
             LIMIT :limit
        """,
        nativeQuery = true,
    )
    fun recentForProduct(
        @Param("productId") productId: Long,
        @Param("limit") limit: Int,
    ): List<StockMovement>

    /**
     * Goods booked into a lot since a moment — the stock-take guard.
     *
     * Checking the lot's own creation time is not enough: a supplier splitting a
     * batch over two deliveries re-receives into the **existing** lot, so stock
     * can arrive mid-count against a lot that is months old. Either way the
     * units are already in the ledger from their receipt, and counting them onto
     * the sheet would post them a second time.
     */
    @Query(
        value = """
            SELECT count(*) FROM stock_movement m
             WHERE m.lot_id = :lotId
               AND m.movement_type = 'RECEIPT'
               AND m.occurred_at > :since
        """,
        nativeQuery = true,
    )
    fun receiptsSince(@Param("lotId") lotId: Long, @Param("since") since: java.time.Instant): Long

    /** The nightly tripwire — anything here means a write bypassed the trigger. */
    @Query(value = "SELECT count(*) FROM v_ledger_mismatch", nativeQuery = true)
    fun ledgerDriftCount(): Long
}

@Repository
interface StockBalanceRepository : JpaRepository<StockBalance, StockBalanceId> {

    @Query(
        value = "SELECT COALESCE(SUM(b.qty_base), 0) FROM stock_balance b " +
            "JOIN stock_lot l ON l.id = b.lot_id " +
            "WHERE l.product_id = :productId AND l.status = 'AVAILABLE'",
        nativeQuery = true,
    )
    fun totalOnHand(@Param("productId") productId: Long): BigDecimal
}
