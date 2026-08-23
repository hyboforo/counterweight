package com.counterweight.billing.repo

import com.counterweight.billing.domain.Quotation
import com.counterweight.billing.domain.QuotationLine
import com.counterweight.billing.domain.SalesDocument
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.LocalDate

@Repository
interface QuotationRepository : JpaRepository<Quotation, Long> {

    fun findByBranchIdAndNumber(branchId: Long, number: String): Quotation?

    fun findByBranchIdAndStatusOrderByCreatedAtDesc(branchId: Long, status: String): List<Quotation>

    fun findByCustomerIdOrderByCreatedAtDesc(customerId: Long): List<Quotation>

    /**
     * Open quotations whose validity has run out.
     *
     * The sweep's input. Expiry is applied rather than merely computed on read,
     * so that a quotation the shop is no longer honouring cannot be converted
     * by whoever opens it next.
     */
    @Query(
        value = """
            SELECT q.* FROM quotation q
             WHERE q.status = 'OPEN'
               AND q.valid_until IS NOT NULL
               AND q.valid_until < :onDate
        """,
        nativeQuery = true,
    )
    fun expiredOn(@Param("onDate") onDate: LocalDate): List<Quotation>
}

@Repository
interface QuotationLineRepository : JpaRepository<QuotationLine, Long> {

    fun findByQuotationIdOrderByLineNoAsc(quotationId: Long): List<QuotationLine>

    @Query(value = "SELECT COALESCE(MAX(line_no), 0) FROM quotation_line WHERE quotation_id = :id", nativeQuery = true)
    fun maxLineNo(@Param("id") quotationId: Long): Int
}

@Repository
interface SalesDocumentRepository : JpaRepository<SalesDocument, Long> {

    fun findByBranchIdAndDocTypeAndNumber(branchId: Long, docType: String, number: String): SalesDocument?

    fun findBySaleIdOrderByIssuedAtAsc(saleId: Long): List<SalesDocument>

    fun findBySaleReturnIdOrderByIssuedAtAsc(saleReturnId: Long): List<SalesDocument>

    fun findBySaleIdAndDocType(saleId: Long, docType: String): SalesDocument?

    /**
     * The register: everything issued in a period, in the order it was issued.
     *
     * Ordered by number within a type rather than by timestamp alone, because
     * the question this answers is "is anything missing", and a gap is only
     * visible in number order.
     */
    @Query(
        value = """
            SELECT d.* FROM sales_document d
             WHERE d.branch_id = :branchId
               AND (:docType IS NULL OR d.doc_type = :docType)
               AND d.issued_at >= :from
               AND d.issued_at < :until
             ORDER BY d.doc_type, d.number
        """,
        nativeQuery = true,
    )
    fun register(
        @Param("branchId") branchId: Long,
        @Param("docType") docType: String?,
        @Param("from") from: java.time.Instant,
        @Param("until") until: java.time.Instant,
    ): List<SalesDocument>
}
