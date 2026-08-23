package com.counterweight.billing.service

import com.counterweight.billing.domain.SalesDocument
import com.counterweight.billing.repo.SalesDocumentRepository
import com.counterweight.common.ApiException
import com.counterweight.identity.security.Auth
import com.counterweight.identity.service.AuditService
import com.counterweight.parties.service.CustomerAccountService
import com.counterweight.platform.service.DocumentNumberService
import com.counterweight.pricing.service.Money
import com.counterweight.pricing.service.TaxCalculator
import com.counterweight.sales.event.SaleCompleted
import com.counterweight.sales.event.SaleReturned
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** The tax configuration as it stood when a document was issued. */
data class TaxSnapshot(
    val schemeCode: String?,
    val components: List<TaxSnapshotComponent>,
    val taxTotal: BigDecimal,
    val capturedOn: LocalDate,
)

data class TaxSnapshotComponent(
    val code: String,
    val name: String,
    val ratePercent: BigDecimal,
    val computedOn: String,
)

/**
 * Issues and reads the shop's documents.
 *
 * Numbering runs through [DocumentNumberService] inside the issuing
 * transaction, so a document that is never issued leaves no hole in the
 * register (§9). Every issue path here therefore has to be transactional, and
 * none of them may be retried "just to get a number".
 */
@Service
class DocumentService(
    private val documents: SalesDocumentRepository,
    private val numbers: DocumentNumberService,
    private val tax: TaxCalculator,
    private val accounts: CustomerAccountService,
    private val audit: AuditService,
    private val json: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // ── Reads ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    fun get(id: Long): SalesDocument {
        val document = documents.findById(id).orElseThrow { ApiException.NotFound("Document", id) }
        if (document.branchId != Auth.current().branchId) throw ApiException.NotFound("Document", id)
        return document
    }

    @Transactional(readOnly = true)
    fun forSale(saleId: Long): List<SalesDocument> = documents.findBySaleIdOrderByIssuedAtAsc(saleId)

    @Transactional(readOnly = true)
    fun forReturn(saleReturnId: Long): List<SalesDocument> =
        documents.findBySaleReturnIdOrderByIssuedAtAsc(saleReturnId)

    /** The document register for a period — what was issued, in number order. */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('REPORT_VIEW')")
    fun register(docType: String?, from: Instant, until: Instant): List<SalesDocument> {
        docType?.let { requireKnownType(it) }
        return documents.register(Auth.current().branchId, docType, from, until)
    }

    /** The tax configuration a document was issued under, as stored. */
    @Transactional(readOnly = true)
    fun taxSnapshotOf(documentId: Long): TaxSnapshot? =
        get(documentId).taxSnapshot?.let { json.readValue(it, TaxSnapshot::class.java) }

    // ── Issuing ────────────────────────────────────────────────────────────

    /**
     * Issues the paperwork a completed sale calls for.
     *
     * Runs synchronously inside the completing transaction — see [SaleCompleted]
     * for why that is deliberate. A sale whose invoice cannot be issued must not
     * complete, and a number allocated for a sale that then fails must roll back
     * with it.
     *
     * A cash-and-carry sale gets a receipt. A sale with anything on account also
     * gets an invoice, because that is the document the debt is owed against and
     * the one the customer's statement will cite. Both, for a split-tender sale
     * that was part cash and part account: the customer took goods away *and*
     * owes for some of them, and one document cannot say both.
     */
    @EventListener
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    fun onSaleCompleted(event: SaleCompleted) {
        val snapshot = captureTaxSnapshot(event.taxTotal)

        issue(
            branchId = event.branchId,
            docType = SalesDocument.RECEIPT,
            total = event.grandTotal,
            saleId = event.saleId,
            snapshot = snapshot,
            // The sale already allocated its own RCT number and stores it on
            // `sale.number` for its NOT NULL check; reusing it here keeps one
            // receipt number per sale rather than two that disagree.
            number = event.number,
        )

        if (event.onAccountAmount > BigDecimal.ZERO) {
            val invoice = issue(
                branchId = event.branchId,
                docType = SalesDocument.INVOICE,
                total = event.onAccountAmount,
                saleId = event.saleId,
                snapshot = snapshot,
            )
            // Point the ledger entry at the invoice now that one exists. The
            // entry is posted during payment, before any document, because the
            // credit check has to happen first.
            event.ledgerEntryId?.let { accounts.attachDocument(it, invoice.id!!) }
        }
    }

    /**
     * A delivery note: goods leaving without cash.
     *
     * Issued separately rather than automatically, because whether a sale needs
     * one is a question about the goods — cement and rods go on a truck, a box
     * of screws does not — and nothing in the sale itself answers it.
     *
     * Note the schema records no driver, vehicle or signature. §17.1 leaves open
     * whether the shop delivers with its own vehicle; if it does, those fields
     * are a schema change, not something to improvise into the reference field.
     */
    @Transactional
    @PreAuthorize("hasAuthority('SALE_CREATE')")
    fun issueDeliveryNote(saleId: Long, total: BigDecimal): SalesDocument {
        documents.findBySaleIdAndDocType(saleId, SalesDocument.DELIVERY_NOTE)?.let {
            throw ApiException.Conflict("A delivery note (${it.number}) has already been issued for this sale.")
        }
        return issue(
            branchId = Auth.current().branchId,
            docType = SalesDocument.DELIVERY_NOTE,
            total = total,
            saleId = saleId,
            snapshot = null,
        )
    }

    /**
     * A credit note, when a return refunds one.
     *
     * Only for the CREDIT_NOTE refund method. Cash and mobile-money refunds are
     * handed over at the counter and the return record is the evidence; an
     * ACCOUNT refund shows up on the customer's statement. Numbering a document
     * for either would put a row in the register that nobody ever holds, and
     * the register's value is that every number in it corresponds to a piece of
     * paper somebody has.
     *
     * The snapshot carries the tax the *original sale* charged, apportioned by
     * the returning module, rather than today's rates. A credit note raised
     * after a rate change has to reverse what was collected.
     */
    @EventListener
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    fun onSaleReturned(event: SaleReturned) {
        if (event.refundMethod != "CREDIT_NOTE") return

        issue(
            branchId = event.branchId,
            docType = SalesDocument.CREDIT_NOTE,
            total = event.total,
            saleReturnId = event.saleReturnId,
            snapshot = TaxSnapshot(
                schemeCode = tax.activeScheme()?.code,
                components = tax.activeComponents().map {
                    TaxSnapshotComponent(it.code, it.name, it.ratePercent, it.computedOn)
                },
                taxTotal = Money.round(event.taxPortion),
                capturedOn = LocalDate.now(),
            ),
        )
    }

    // ── Internals ──────────────────────────────────────────────────────────

    private fun issue(
        branchId: Long,
        docType: String,
        total: BigDecimal,
        snapshot: TaxSnapshot?,
        saleId: Long? = null,
        saleReturnId: Long? = null,
        number: String? = null,
    ): SalesDocument {
        requireKnownType(docType)
        if (saleId == null && saleReturnId == null) {
            // The database says the same thing (`document_has_a_source`); this
            // turns the constraint name into something a caller can act on.
            throw ApiException.Validation(
                "A document must be issued against a sale or a return.",
                mapOf("source" to "saleId or saleReturnId is required"),
            )
        }

        val allocated = number ?: numbers.next(branchId, SalesDocument.SEQUENCE_OF.getValue(docType))

        val document = documents.save(
            SalesDocument(
                branchId = branchId,
                docType = docType,
                number = allocated,
                issuedBy = Auth.current().id,
                total = Money.round(total),
            ).also {
                it.saleId = saleId
                it.saleReturnId = saleReturnId
                it.taxSnapshot = snapshot?.let(json::writeValueAsString)
            }
        )
        log.debug("issued {} {} for branch {}", docType, allocated, branchId)
        audit.recordCurrent(
            "DOCUMENT_ISSUED", "sales_document", document.id,
            after = """{"type":"$docType","number":"$allocated","total":"${Money.round(total).toPlainString()}"}""",
        )
        return document
    }

    /**
     * Freezes the tax configuration onto the document.
     *
     * The rates are read now and stored, not referenced. A live join back to
     * `tax_rate` would restate an old invoice at today's rate on every reprint,
     * which is the difference between a reprint and a reissue.
     */
    private fun captureTaxSnapshot(taxTotal: BigDecimal): TaxSnapshot {
        val components = tax.activeComponents().map {
            TaxSnapshotComponent(it.code, it.name, it.ratePercent, it.computedOn)
        }
        return TaxSnapshot(
            schemeCode = tax.activeScheme()?.code,
            components = components,
            taxTotal = Money.round(taxTotal),
            capturedOn = LocalDate.now(),
        )
    }

    private fun requireKnownType(docType: String) {
        if (docType !in SalesDocument.TYPES) {
            throw ApiException.Validation(
                "Unknown document type '$docType'.",
                mapOf("docType" to "must be one of: ${SalesDocument.TYPES.sorted().joinToString(", ")}"),
            )
        }
    }
}
