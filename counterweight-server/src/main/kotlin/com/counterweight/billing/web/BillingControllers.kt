package com.counterweight.billing.web

import com.counterweight.billing.domain.Quotation
import com.counterweight.billing.domain.QuotationLine
import com.counterweight.billing.domain.SalesDocument
import com.counterweight.billing.service.DocumentService
import com.counterweight.billing.service.QuotationService
import com.counterweight.billing.service.Statement
import com.counterweight.billing.service.StatementService
import com.counterweight.billing.service.TaxSnapshot
import com.counterweight.common.SafeText
import com.counterweight.sales.web.ApprovalRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.*
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// ── Requests ───────────────────────────────────────────────────────────────

data class CreateQuotationRequest(
    val customerId: Long? = null,

    @field:DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    val validUntil: LocalDate? = null,
)

data class QuotationLineRequest(
    @field:NotNull(message = "is required")
    @field:Positive(message = "must be a valid product")
    val productId: Long,

    @field:NotNull(message = "is required")
    @field:Positive(message = "must be a valid unit")
    val productUomId: Long,

    @field:NotNull(message = "is required")
    @field:DecimalMin(value = "0.0001", message = "must be more than zero")
    @field:Digits(integer = 12, fraction = 4, message = "has too many digits")
    val qty: BigDecimal,

    /** A negotiated price. Quotations may be priced by hand; a sale re-checks it. */
    @field:DecimalMin(value = "0.0", message = "must be zero or more")
    @field:Digits(integer = 10, fraction = 4, message = "has too many digits")
    val unitPrice: BigDecimal? = null,
)

/** A supervisor PIN, when the quoted price is below list. */
data class ConvertQuotationRequest(
    @field:Valid
    val approval: ApprovalRequest? = null,
)

data class CancelQuotationRequest(
    @field:Size(max = 250, message = "is too long")
    @field:SafeText
    val reason: String? = null,
)

data class DeliveryNoteRequest(
    @field:NotNull(message = "is required")
    @field:DecimalMin(value = "0.0", message = "must be zero or more")
    @field:Digits(integer = 12, fraction = 2, message = "has too many digits")
    val total: BigDecimal,
)

// ── Responses ──────────────────────────────────────────────────────────────

data class QuotationLineView(
    val id: Long, val lineNo: Int, val productId: Long, val productUomId: Long,
    val qty: BigDecimal, val unitPrice: BigDecimal, val lineTotal: BigDecimal,
)

data class QuotationView(
    val id: Long, val number: String, val status: String, val customerId: Long?,
    val validUntil: LocalDate?, val total: BigDecimal, val convertedSaleId: Long?,
    val createdAt: Instant, val lines: List<QuotationLineView>,
)

data class DocumentView(
    val id: Long, val docType: String, val number: String,
    val saleId: Long?, val saleReturnId: Long?,
    val total: BigDecimal, val issuedAt: Instant, val issuedBy: Long,
)

private fun QuotationLine.toView() =
    QuotationLineView(id!!, lineNo, productId, productUomId, qty, unitPrice, lineTotal)

private fun Quotation.toView(lines: List<QuotationLine>) = QuotationView(
    id!!, number, status, customerId, validUntil, total, convertedSaleId,
    createdAt, lines.map { it.toView() },
)

private fun SalesDocument.toView() =
    DocumentView(id!!, docType, number, saleId, saleReturnId, total, issuedAt, issuedBy)

// ── Controllers ────────────────────────────────────────────────────────────

@RestController
@RequestMapping("/api/quotations")
class QuotationController(private val quotations: QuotationService) {

    private fun view(id: Long) = quotations.get(id).toView(quotations.lines(id))

    @GetMapping
    fun open(): List<QuotationView> = quotations.open().map { it.toView(quotations.lines(it.id!!)) }

    @GetMapping("/{id}")
    fun get(@PathVariable id: Long): QuotationView = view(id)

    @GetMapping("/customer/{customerId}")
    fun forCustomer(@PathVariable customerId: Long): List<QuotationView> =
        quotations.forCustomer(customerId).map { it.toView(quotations.lines(it.id!!)) }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody(required = false) body: CreateQuotationRequest?): QuotationView =
        quotations.create(body?.customerId, body?.validUntil).let { it.toView(emptyList()) }

    @PostMapping("/{id}/lines")
    @ResponseStatus(HttpStatus.CREATED)
    fun addLine(@PathVariable id: Long, @Valid @RequestBody body: QuotationLineRequest): QuotationView {
        quotations.addLine(id, body.productId, body.productUomId, body.qty, body.unitPrice)
        return view(id)
    }

    @DeleteMapping("/{id}/lines/{lineId}")
    fun removeLine(@PathVariable id: Long, @PathVariable lineId: Long): QuotationView {
        quotations.removeLine(id, lineId)
        return view(id)
    }

    /**
     * Turns the quotation into a draft sale at the till.
     *
     * Returns the sale id rather than the sale itself: the till already knows
     * how to read and drive a sale through `/api/sales`, and duplicating that
     * shape here would give it two representations to keep in step.
     */
    @PostMapping("/{id}/convert")
    fun convert(
        @PathVariable id: Long,
        @Valid @RequestBody(required = false) body: ConvertQuotationRequest?,
    ): Map<String, Any?> {
        val sale = quotations.convertToSale(id, body?.approval?.toCredentials())
        return mapOf("saleId" to sale.id, "quotationId" to id, "grandTotal" to sale.grandTotal)
    }

    @PutMapping("/{id}/cancel")
    fun cancel(
        @PathVariable id: Long,
        @RequestBody(required = false) body: CancelQuotationRequest?,
    ): QuotationView = quotations.cancel(id, body?.reason).let { view(id) }
}

@RestController
@RequestMapping("/api/documents")
class DocumentController(private val documents: DocumentService) {

    @GetMapping("/{id}")
    fun get(@PathVariable id: Long): DocumentView = documents.get(id).toView()

    /** The tax configuration this document was issued under, as frozen at issue. */
    @GetMapping("/{id}/tax-snapshot")
    fun taxSnapshot(@PathVariable id: Long): TaxSnapshot? = documents.taxSnapshotOf(id)

    @GetMapping("/sale/{saleId}")
    fun forSale(@PathVariable saleId: Long): List<DocumentView> =
        documents.forSale(saleId).map { it.toView() }

    @GetMapping("/return/{returnId}")
    fun forReturn(@PathVariable returnId: Long): List<DocumentView> =
        documents.forReturn(returnId).map { it.toView() }

    /**
     * The register: everything issued in a period, in number order.
     *
     * Number order rather than time order because the question it answers is
     * whether anything is missing, and a gap is only visible in sequence.
     */
    @GetMapping("/register")
    fun register(
        @RequestParam(required = false) docType: String?,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
    ): List<DocumentView> {
        val zone = ZoneId.systemDefault()
        return documents.register(
            docType,
            from.atStartOfDay(zone).toInstant(),
            to.plusDays(1).atStartOfDay(zone).toInstant(),
        ).map { it.toView() }
    }

    @PostMapping("/sale/{saleId}/delivery-note")
    @ResponseStatus(HttpStatus.CREATED)
    fun deliveryNote(
        @PathVariable saleId: Long,
        @Valid @RequestBody body: DeliveryNoteRequest,
    ): DocumentView = documents.issueDeliveryNote(saleId, body.total).toView()
}

@RestController
@RequestMapping("/api/customers/{customerId}/statement")
class StatementController(private val statements: StatementService) {

    /** The current month to date — what somebody means by "their statement". */
    @GetMapping
    fun current(@PathVariable customerId: Long): Statement = statements.currentMonth(customerId)

    /** [to] is inclusive: a statement "to the 31st" includes the 31st. */
    @GetMapping("/period")
    fun period(
        @PathVariable customerId: Long,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
    ): Statement = statements.forCustomer(customerId, from, to)
}
