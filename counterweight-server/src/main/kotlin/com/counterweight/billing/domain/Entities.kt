package com.counterweight.billing.domain

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/*
 * Billing entities — the documents.
 *
 * A document is not a state of a sale, it is a thing that was issued. That
 * distinction is why `sales_document` is its own table: one credit sale
 * produces an invoice and may also produce a delivery note, and a return
 * produces a credit note, all pointing at the transaction that caused them.
 * Modelling them as columns on `sale` would have forced a single document per
 * sale and lost the register.
 */

/**
 * A priced offer.
 *
 * Its own document rather than a state of a sale, because a quotation exists
 * before any sale does — a contractor pricing a job may never come back, and
 * the offer still has to be findable when they do.
 */
@Entity
@Table(name = "quotation")
class Quotation(
    @Column(name = "branch_id", nullable = false)
    var branchId: Long,

    @Column(nullable = false)
    var number: String,

    @Column(name = "created_by", nullable = false)
    var createdBy: Long,
) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "customer_id")
    var customerId: Long? = null

    @Column(nullable = false)
    var status: String = "OPEN"

    /** After this the prices quoted are no longer promised. Null never expires. */
    @Column(name = "valid_until")
    var validUntil: LocalDate? = null

    @Column(nullable = false)
    var total: BigDecimal = BigDecimal.ZERO

    @Column(name = "converted_sale_id")
    var convertedSaleId: Long? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()

    val isOpen: Boolean get() = status == "OPEN"

    override fun equals(other: Any?) = this === other || (other is Quotation && id != null && id == other.id)
    override fun hashCode() = javaClass.hashCode()
    override fun toString() = "Quotation($number)"

    companion object {
        val STATUSES = setOf("OPEN", "CONVERTED", "EXPIRED", "CANCELLED")
    }
}

@Entity
@Table(name = "quotation_line")
class QuotationLine(
    @Column(name = "quotation_id", nullable = false)
    var quotationId: Long,

    @Column(name = "line_no", nullable = false)
    var lineNo: Int,

    @Column(name = "product_id", nullable = false)
    var productId: Long,

    @Column(name = "product_uom_id", nullable = false)
    var productUomId: Long,

    @Column(nullable = false)
    var qty: BigDecimal,

    @Column(name = "unit_price", nullable = false)
    var unitPrice: BigDecimal,

    @Column(name = "line_total", nullable = false)
    var lineTotal: BigDecimal,
) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    override fun equals(other: Any?) = this === other || (other is QuotationLine && id != null && id == other.id)
    override fun hashCode() = javaClass.hashCode()
}

/**
 * An issued document against a sale or a return.
 *
 * [taxSnapshot] is the reason this carries figures of its own rather than
 * deriving them from the sale on every read. Reprinting last quarter's invoice
 * after a rate change has to reproduce the original figures, and a live join
 * against `tax_rate` would quietly restate history at the current rate — the
 * kind of error nobody notices until an auditor holds two copies of the same
 * invoice side by side.
 */
@Entity
@Table(name = "sales_document")
class SalesDocument(
    @Column(name = "branch_id", nullable = false)
    var branchId: Long,

    @Column(name = "doc_type", nullable = false)
    var docType: String,

    @Column(nullable = false)
    var number: String,

    @Column(name = "issued_by", nullable = false)
    var issuedBy: Long,

    @Column(nullable = false)
    var total: BigDecimal,
) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "sale_id")
    var saleId: Long? = null

    @Column(name = "sale_return_id")
    var saleReturnId: Long? = null

    @Column(name = "issued_at", nullable = false)
    var issuedAt: Instant = Instant.now()

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tax_snapshot", columnDefinition = "jsonb")
    var taxSnapshot: String? = null

    override fun equals(other: Any?) = this === other || (other is SalesDocument && id != null && id == other.id)
    override fun hashCode() = javaClass.hashCode()
    override fun toString() = "SalesDocument($number)"

    companion object {
        const val RECEIPT = "RECEIPT"
        const val INVOICE = "INVOICE"
        const val DELIVERY_NOTE = "DELIVERY_NOTE"
        const val CREDIT_NOTE = "CREDIT_NOTE"

        val TYPES = setOf(RECEIPT, INVOICE, DELIVERY_NOTE, CREDIT_NOTE)

        /** Document type → the `document_sequence` row that numbers it. */
        val SEQUENCE_OF = mapOf(
            RECEIPT to "RECEIPT",
            INVOICE to "INVOICE",
            DELIVERY_NOTE to "DELIVERY_NOTE",
            CREDIT_NOTE to "CREDIT_NOTE",
        )
    }
}
