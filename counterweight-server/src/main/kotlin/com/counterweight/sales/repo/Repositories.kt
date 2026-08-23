package com.counterweight.sales.repo

import com.counterweight.sales.domain.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.util.UUID

@Repository
interface SaleRepository : JpaRepository<Sale, Long> {

    fun findByBranchIdAndNumber(branchId: Long, number: String): Sale?

    fun findByBranchIdAndStatusOrderByCreatedAtDesc(branchId: Long, status: String): List<Sale>

}

@Repository
interface SaleLineRepository : JpaRepository<SaleLine, Long> {

    fun findBySaleIdOrderByLineNoAsc(saleId: Long): List<SaleLine>

    fun deleteBySaleIdAndId(saleId: Long, id: Long): Long

    @Query(value = "SELECT COALESCE(MAX(line_no), 0) FROM sale_line WHERE sale_id = :saleId", nativeQuery = true)
    fun maxLineNo(@Param("saleId") saleId: Long): Int

    /**
     * How much of a line has already gone back, in base units.
     *
     * Returning more than was bought is the mistake this exists to stop —
     * usually by returning the same goods twice rather than by anyone lying.
     */
    @Query(
        value = """
            SELECT COALESCE(SUM(rl.qty_base), 0)
              FROM sale_return_line rl WHERE rl.sale_line_id = :saleLineId
        """,
        nativeQuery = true,
    )
    fun returnedQtyBase(@Param("saleLineId") saleLineId: Long): BigDecimal
}

@Repository
interface SaleLineAllocationRepository : JpaRepository<SaleLineAllocation, Long> {
    fun findBySaleLineId(saleLineId: Long): List<SaleLineAllocation>

    /** Traceability: every line that drew from a lot, for a recall. */
    @Query(
        value = """
            SELECT a.* FROM sale_line_allocation a WHERE a.lot_id = :lotId
        """,
        nativeQuery = true,
    )
    fun findByLotId(@Param("lotId") lotId: Long): List<SaleLineAllocation>
}

@Repository
interface SalePaymentRepository : JpaRepository<SalePayment, Long> {
    fun findBySaleId(saleId: Long): List<SalePayment>
}

@Repository
interface IdempotencyRecordRepository : JpaRepository<IdempotencyRecord, UUID>

@Repository
interface SaleReturnRepository : JpaRepository<SaleReturn, Long> {
    fun findBySaleIdOrderByCreatedAtDesc(saleId: Long): List<SaleReturn>
}

@Repository
interface SaleReturnLineRepository : JpaRepository<SaleReturnLine, Long> {
    fun findBySaleReturnId(saleReturnId: Long): List<SaleReturnLine>
}

@Repository
interface RestrictedSaleRecordRepository : JpaRepository<RestrictedSaleRecord, Long> {
    fun findBySaleLineId(saleLineId: Long): List<RestrictedSaleRecord>
}
