package com.counterweight.sales.web

import com.counterweight.common.OverridePin
import com.counterweight.common.SafeText
import com.counterweight.common.Username
import com.counterweight.sales.domain.*
import com.counterweight.sales.service.*
import jakarta.validation.Valid
import jakarta.validation.constraints.*
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// ── Shared ─────────────────────────────────────────────────────────────────

/**
 * A supervisor's PIN, sent with the action it authorises.
 *
 * Deliberately not a separate "get an approval token" endpoint. A token could
 * be captured and reused for something the supervisor never saw; this way the
 * PIN authorises exactly the request it arrived on.
 */
data class ApprovalRequest(
    @field:NotBlank(message = "is required")
    @field:Username
    val username: String,

    @field:NotBlank(message = "is required")
    @field:OverridePin
    val pin: String,
) {
    fun toCredentials() = OverrideCredentials(username, pin)
}

// ── Requests ───────────────────────────────────────────────────────────────

data class StartSaleRequest(val customerId: Long? = null)

data class AddLineRequest(
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

    @field:DecimalMin(value = "0.0", message = "must be zero or more")
    @field:DecimalMax(value = "100.0", message = "cannot be more than 100")
    val discountPercent: BigDecimal? = null,

    @field:DecimalMin(value = "0.0", message = "must be zero or more")
    @field:Digits(integer = 10, fraction = 4, message = "has too many digits")
    val unitPrice: BigDecimal? = null,

    @field:Valid
    val approval: ApprovalRequest? = null,
)

data class UpdateQtyRequest(
    @field:NotNull(message = "is required")
    @field:DecimalMin(value = "0.0001", message = "must be more than zero")
    @field:Digits(integer = 12, fraction = 4, message = "has too many digits")
    val qty: BigDecimal,
)

data class DiscountRequest(
    @field:NotNull(message = "is required")
    @field:DecimalMin(value = "0.0", message = "must be zero or more")
    @field:DecimalMax(value = "100.0", message = "cannot be more than 100")
    val discountPercent: BigDecimal,

    @field:Valid
    val approval: ApprovalRequest? = null,
)

data class HoldRequest(
    @field:Size(max = 60, message = "is too long")
    @field:SafeText
    val label: String? = null,
)

data class TenderRequest(
    @field:NotBlank(message = "is required")
    @field:Pattern(
        regexp = "^(CASH|MOBILE_MONEY|BANK_TRANSFER|CHEQUE|ON_ACCOUNT|CARD)$",
        message = "is not a payment method this shop takes",
    )
    val method: String,

    @field:NotNull(message = "is required")
    @field:DecimalMin(value = "0.01", message = "must be more than zero")
    @field:Digits(integer = 12, fraction = 2, message = "has too many digits")
    val amount: BigDecimal,

    @field:DecimalMin(value = "0.0", message = "must be zero or more")
    @field:Digits(integer = 12, fraction = 2, message = "has too many digits")
    val tendered: BigDecimal? = null,

    @field:Pattern(regexp = "^(MTN|TELECEL|AT)$", message = "must be MTN, TELECEL or AT")
    val momoNetwork: String? = null,

    @field:Size(max = 120, message = "is too long")
    @field:SafeText
    val reference: String? = null,

    @field:Size(max = 80, message = "is too long")
    @field:SafeText
    val bankName: String? = null,

    @field:Size(max = 40, message = "is too long")
    @field:SafeText
    val chequeNumber: String? = null,

    @field:DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    val chequeDate: LocalDate? = null,
)

data class BuyerRecordRequest(
    @field:NotNull(message = "is required")
    val saleLineId: Long,

    @field:NotBlank(message = "is required")
    @field:Size(max = 120, message = "is too long")
    @field:SafeText
    val buyerName: String,

    @field:Size(max = 20, message = "is too long")
    @field:SafeText
    val buyerPhone: String? = null,

    @field:Size(max = 40, message = "is too long")
    @field:SafeText
    val buyerIdType: String? = null,

    @field:Size(max = 60, message = "is too long")
    @field:SafeText
    val buyerIdNumber: String? = null,

    @field:Size(max = 250, message = "is too long")
    @field:SafeText
    val intendedUse: String? = null,
)

data class CompleteSaleRequest(
    @field:NotEmpty(message = "at least one payment is required")
    @field:Size(max = 10, message = "is too many payments")
    @field:Valid
    val tenders: List<TenderRequest>,

    @field:Size(max = 20, message = "is too long")
    @field:Pattern(regexp = "^[A-Za-z0-9._-]*$", message = "may use letters, digits and . _ - only")
    val tillCode: String? = null,

    @field:Size(max = 50, message = "is too many records")
    @field:Valid
    val buyerRecords: List<BuyerRecordRequest> = emptyList(),

    /** Line id → lot id, for products the cashier picks by hand. */
    val lotChoices: Map<Long, Long> = emptyMap(),

    @field:Valid
    val creditApproval: ApprovalRequest? = null,
)

data class VoidSaleRequest(
    @field:NotBlank(message = "is required")
    @field:Size(max = 250, message = "is too long")
    @field:SafeText
    val reason: String,
)


data class ReturnLineDto(
    @field:NotNull(message = "is required")
    val saleLineId: Long,

    @field:NotNull(message = "is required")
    @field:DecimalMin(value = "0.0001", message = "must be more than zero")
    @field:Digits(integer = 12, fraction = 4, message = "has too many digits")
    val qty: BigDecimal,

    val restock: Boolean = true,
)

data class CreateReturnRequest(
    @field:NotEmpty(message = "nothing was selected to return")
    @field:Size(max = 100, message = "is too many lines")
    @field:Valid
    val lines: List<ReturnLineDto>,

    @field:NotBlank(message = "is required")
    @field:Size(max = 250, message = "is too long")
    @field:SafeText
    val reason: String,

    @field:Pattern(
        regexp = "^(CASH|MOBILE_MONEY|CREDIT_NOTE|ACCOUNT)$",
        message = "must be CASH, MOBILE_MONEY, CREDIT_NOTE or ACCOUNT",
    )
    val refundMethod: String,
)

// ── Responses ──────────────────────────────────────────────────────────────

data class SaleLineView(
    val id: Long, val lineNo: Int, val productId: Long, val productUomId: Long,
    val qty: BigDecimal, val qtyBase: BigDecimal, val unitPrice: BigDecimal,
    val discountAmount: BigDecimal, val taxAmount: BigDecimal, val lineTotal: BigDecimal,
    val priceOverridden: Boolean,
    /**
     * §6.3: the counter has to write the buyer down before this can be sold.
     *
     * On the line rather than left for the till to work out from the product,
     * so the basket can say so as the item is scanned instead of the sale
     * being refused once the money is on the counter.
     */
    val requiresBuyerRecord: Boolean,
)

data class SaleView(
    val id: Long, val number: String?, val status: String,
    val customerId: Long?, val cashierId: Long, val tillCode: String?,
    val heldLabel: String?,
    val subtotal: BigDecimal, val discountTotal: BigDecimal, val taxTotal: BigDecimal,
    val roundingAdjustment: BigDecimal, val grandTotal: BigDecimal,
    val completedAt: Instant?, val lines: List<SaleLineView>,
)

data class PaymentView(
    val id: Long, val method: String, val amount: BigDecimal,
    val tendered: BigDecimal?, val changeGiven: BigDecimal?,
    val momoNetwork: String?, val reference: String?,
)

data class CompletedSaleView(
    val sale: SaleView,
    val payments: List<PaymentView>,
    /** True when this request had already been honoured and nothing was re-posted. */
    val replayed: Boolean,
)

data class SaleReturnView(
    val id: Long, val number: String, val saleId: Long, val reason: String,
    val refundMethod: String, val total: BigDecimal, val createdAt: Instant,
)

private fun SaleLine.toView(restricted: Set<Long>) = SaleLineView(
    id!!, lineNo, productId, productUomId, qty, qtyBase, unitPrice,
    discountAmount, taxAmount, lineTotal, priceOverridden,
    requiresBuyerRecord = productId in restricted,
)

private fun SalePayment.toView() =
    PaymentView(id!!, method, amount, tendered, changeGiven, momoNetwork, reference)

private fun SaleReturn.toView() =
    SaleReturnView(id!!, number, saleId, reason, refundMethod, total, createdAt)

private fun Sale.toView(lines: List<SaleLine>, restricted: Set<Long> = emptySet()) = SaleView(
    id!!, number, status, customerId, cashierId, tillCode, heldLabel,
    subtotal, discountTotal, taxTotal, roundingAdjustment, grandTotal, completedAt,
    lines.map { it.toView(restricted) },
)

// ── Controllers ────────────────────────────────────────────────────────────

@RestController
@RequestMapping("/api/sales")
class SaleController(
    private val cart: CartService,
    private val completion: SaleCompletionService,
) {

    /**
     * A sale as the till renders it, restricted lines marked.
     *
     * The extra read is one query per basket, not per line, and it is what lets
     * the basket say "buyer details needed" while the customer is still
     * choosing rather than after the drawer is open.
     */
    private fun viewOf(sale: Sale, lines: List<SaleLine>) =
        sale.toView(lines, cart.restrictedProductIds(lines))

    private fun view(saleId: Long) = viewOf(cart.get(saleId), cart.lines(saleId))

    @GetMapping("/{id}")
    fun get(@PathVariable id: Long): SaleView = view(id)

    @GetMapping("/held")
    fun held(): List<SaleView> = cart.held().map { viewOf(it, cart.lines(it.id!!)) }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun start(@RequestBody(required = false) body: StartSaleRequest?): SaleView =
        cart.startSale(body?.customerId).let { it.toView(emptyList()) }

    @PutMapping("/{id}/customer")
    fun setCustomer(
        @PathVariable id: Long,
        @RequestParam(required = false) customerId: Long?,
    ): SaleView = cart.setCustomer(id, customerId).let { view(id) }

    @PostMapping("/{id}/lines")
    @ResponseStatus(HttpStatus.CREATED)
    fun addLine(@PathVariable id: Long, @Valid @RequestBody body: AddLineRequest): SaleView {
        cart.addLine(
            saleId = id,
            productId = body.productId,
            productUomId = body.productUomId,
            qty = body.qty,
            discountPercent = body.discountPercent,
            unitPriceOverride = body.unitPrice,
            approval = body.approval?.toCredentials(),
        )
        return view(id)
    }

    @PutMapping("/{id}/lines/{lineId}/qty")
    fun updateQty(
        @PathVariable id: Long,
        @PathVariable lineId: Long,
        @Valid @RequestBody body: UpdateQtyRequest,
    ): SaleView = cart.updateQty(id, lineId, body.qty).let { view(id) }

    @PutMapping("/{id}/lines/{lineId}/discount")
    fun setDiscount(
        @PathVariable id: Long,
        @PathVariable lineId: Long,
        @Valid @RequestBody body: DiscountRequest,
    ): SaleView = cart
        .setLineDiscount(id, lineId, body.discountPercent, body.approval?.toCredentials())
        .let { view(id) }

    @DeleteMapping("/{id}/lines/{lineId}")
    fun removeLine(@PathVariable id: Long, @PathVariable lineId: Long): SaleView {
        cart.removeLine(id, lineId)
        return view(id)
    }

    @PutMapping("/{id}/hold")
    fun hold(@PathVariable id: Long, @RequestBody(required = false) body: HoldRequest?): SaleView =
        cart.hold(id, body?.label).let { view(id) }

    @PutMapping("/{id}/recall")
    fun recall(@PathVariable id: Long): SaleView = cart.recall(id).let { view(id) }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun discard(@PathVariable id: Long) = cart.discard(id)

    /**
     * Takes payment and completes the sale.
     *
     * The `Idempotency-Key` header is required, not optional. The till mints a
     * UUID when the operator presses Pay, and a retry of the same request —
     * double click, or a switch that dropped the response — returns the
     * original sale rather than deducting stock twice (§8.3). Letting the
     * header be omitted would make that protection something a client could
     * forget to ask for.
     *
     * A replay answers 200 where a first completion answers 201, so the till
     * can tell the difference without comparing timestamps.
     */
    @PostMapping("/{id}/complete")
    fun complete(
        @PathVariable id: Long,
        @RequestHeader("Idempotency-Key") idempotencyKey: UUID,
        @Valid @RequestBody body: CompleteSaleRequest,
    ): ResponseEntity<CompletedSaleView> {
        val command = CompleteSaleCommand(
            saleId = id,
            tenders = body.tenders.map {
                TenderLine(
                    method = it.method, amount = it.amount, tendered = it.tendered,
                    momoNetwork = it.momoNetwork, reference = it.reference,
                    bankName = it.bankName, chequeNumber = it.chequeNumber, chequeDate = it.chequeDate,
                )
            },
            tillCode = body.tillCode,
            buyerRecords = body.buyerRecords.map {
                BuyerRecord(
                    it.saleLineId, it.buyerName, it.buyerPhone,
                    it.buyerIdType, it.buyerIdNumber, it.intendedUse,
                )
            },
            lotChoices = body.lotChoices,
            creditApproval = body.creditApproval?.toCredentials(),
        )

        return when (val result = completion.complete(command, idempotencyKey)) {
            is SaleResult.Completed -> ResponseEntity.status(HttpStatus.CREATED).body(
                CompletedSaleView(
                    // Completed, so the flag has nothing left to say: the record was
                    // taken or the sale did not happen. Not worth a query to restate.
                    result.sale.toView(cart.lines(id)),
                    result.payments.map { it.toView() },
                    replayed = false,
                )
            )
            is SaleResult.Replayed -> ResponseEntity.ok(
                CompletedSaleView(
                    result.sale.toView(cart.lines(result.sale.id!!)),
                    emptyList(),
                    replayed = true,
                )
            )
        }
    }

    @PutMapping("/{id}/void")
    fun voidSale(@PathVariable id: Long, @Valid @RequestBody body: VoidSaleRequest): SaleView =
        completion.voidSale(id, body.reason).let { view(id) }
}

@RestController
@RequestMapping("/api/sales/{id}/returns")
class SaleReturnController(private val saleReturns: SaleReturnService) {

    @GetMapping
    fun list(@PathVariable id: Long): List<SaleReturnView> = saleReturns.forSale(id).map { it.toView() }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@PathVariable id: Long, @Valid @RequestBody body: CreateReturnRequest): SaleReturnView =
        saleReturns.createReturn(
            saleId = id,
            lines = body.lines.map { ReturnLineRequest(it.saleLineId, it.qty, it.restock) },
            reason = body.reason,
            refundMethod = body.refundMethod,
        ).toView()
}

