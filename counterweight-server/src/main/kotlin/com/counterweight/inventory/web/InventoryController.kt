package com.counterweight.inventory.web

import com.counterweight.common.SafeText
import com.counterweight.inventory.domain.GoodsReceipt
import com.counterweight.inventory.domain.StockMovement
import com.counterweight.inventory.repo.LotOnHand
import com.counterweight.inventory.repo.ProductStock
import com.counterweight.inventory.service.InventoryService
import com.counterweight.inventory.service.ReceiptLine
import jakarta.validation.Valid
import jakarta.validation.constraints.*
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

// ── Requests ───────────────────────────────────────────────────────────────

data class ReceiptLineRequest(
    @field:NotNull(message = "is required")
    @field:Positive(message = "must be a valid product")
    val productId: Long,

    @field:NotNull(message = "is required")
    @field:Positive(message = "must be a valid unit")
    val productUomId: Long,

    @field:NotNull(message = "is required")
    @field:DecimalMin(value = "0.0001", message = "must be greater than zero")
    @field:Digits(integer = 12, fraction = 4, message = "has too many digits")
    val qty: BigDecimal,

    @field:NotNull(message = "is required")
    @field:DecimalMin(value = "0.0", message = "cannot be negative")
    @field:Digits(integer = 10, fraction = 4, message = "has too many digits")
    val unitCost: BigDecimal,

    @field:Size(max = 60, message = "is too long")
    @field:SafeText
    val batchCode: String? = null,

    /** Required for batch-tracked products; the service enforces that. */
    @field:Future(message = "must be a future date")
    val expiresOn: LocalDate? = null,
)

data class GoodsReceiptRequest(
    @field:NotEmpty(message = "at least one line is required")
    @field:Size(max = 500, message = "is too many lines for one receipt")
    @field:Valid
    val lines: List<ReceiptLineRequest>,


    @field:Size(max = 60, message = "is too long")
    @field:SafeText
    val reference: String? = null,
)

data class AdjustmentRequest(
    @field:NotNull(message = "is required")
    @field:Digits(integer = 12, fraction = 4, message = "has too many digits")
    val qtyBaseDelta: BigDecimal,

    @field:NotBlank(message = "is required")
    @field:Size(max = 40, message = "is too long")
    @field:Pattern(regexp = "^[A-Z0-9_]+$", message = "must be an uppercase reason code")
    val reasonCode: String,

    @field:Size(max = 300, message = "is too long")
    @field:SafeText
    val note: String? = null,
)

data class WriteOffRequest(
    @field:Pattern(
        regexp = "^(DAMAGE_WRITE_OFF|EXPIRY_WRITE_OFF)$",
        message = "must be DAMAGE_WRITE_OFF or EXPIRY_WRITE_OFF",
    )
    val movementType: String,

    @field:NotBlank(message = "is required")
    @field:Size(max = 40, message = "is too long")
    val reasonCode: String,
)

// ── Responses ──────────────────────────────────────────────────────────────

data class LotView(
    val lotId: Long, val lotCode: String, val expiresOn: LocalDate?,
    val unitCost: BigDecimal, val qtyBase: BigDecimal, val status: String,
)

data class StockPositionView(
    val productId: Long, val sku: String, val name: String,
    val qtyBase: BigDecimal, val stockValue: BigDecimal,
    val lotCount: Int, val nearestExpiry: LocalDate?,
)

data class GoodsReceiptView(
    val id: Long,
    /** Ours — GRN-000001. */
    val number: String,
    /** Theirs — whatever the delivery note said. */
    val supplierReference: String?,
    val receivedOn: LocalDate,
    val receivedBy: Long,
    val lineCount: Int?,
)

/**
 * A received line, without cost.
 *
 * Deliberately: `COST_VIEW` is a real permission and a storekeeper does not
 * hold it. Entering a cost off the delivery note is not the same as being able
 * to read back what the shop paid, and the module README is explicit that cost
 * is not shipped and hidden — it is not shipped.
 */
data class ReceivedLineView(
    val lineNo: Int,
    val sku: String,
    val productName: String,
    val qtyReceived: BigDecimal,
    val uomCode: String,
    val qtyBase: BigDecimal,
    val baseUomCode: String,
    val lotCode: String,
    val batchCode: String?,
    val expiresOn: LocalDate?,
)

data class GoodsReceiptDetailView(
    val receipt: GoodsReceiptView,
    val lines: List<ReceivedLineView>,
)

data class MovementView(
    val id: Long, val lotId: Long, val movementType: String,
    val qtyBase: BigDecimal, val unitCost: BigDecimal,
    val sourceType: String, val reasonCode: String?,
    val occurredAt: Instant, val createdBy: Long,
)

private fun LotOnHand.toView() =
    LotView(lotId, lotCode, expiresOn?.toLocalDate(), unitCost, qtyBase, status)

private fun ProductStock.toView() =
    StockPositionView(productId, sku, name, qtyBase, stockValue, lotCount, nearestExpiry?.toLocalDate())

private fun GoodsReceipt.toView(lineCount: Int?) =
    GoodsReceiptView(id!!, number, supplierReference, receivedOn, receivedBy, lineCount)

private fun StockMovement.toView() =
    MovementView(id!!, lotId, movementType, qtyBase, unitCost, sourceType, reasonCode, occurredAt, createdBy)

// ── Controller ─────────────────────────────────────────────────────────────

/**
 * Reads are open to any authenticated user; writes carry their permission on
 * the service method, so the check applies however the service is reached —
 * not only through this controller.
 *
 * Note there is no endpoint that writes a stock balance. The only way to change
 * stock is to post a movement, which is the whole point of the ledger.
 */
@RestController
@RequestMapping("/api/inventory")
class InventoryController(private val inventory: InventoryService) {

    /** Lots with stock on hand, already in the product's picking order. */
    @GetMapping("/products/{productId}/lots")
    fun lots(@PathVariable productId: Long): List<LotView> =
        inventory.onHand(productId).map { it.toView() }

    /** Stock position and value, for one product or the whole catalogue. */
    @GetMapping("/position")
    fun position(@RequestParam(required = false) productId: Long?): List<StockPositionView> =
        inventory.position(productId).map { it.toView() }

    @GetMapping("/products/{productId}/ledger")
    fun ledger(
        @PathVariable productId: Long,
        @RequestParam(defaultValue = "50") @Min(1) @Max(500) limit: Int,
    ): List<MovementView> = inventory.ledgerFor(productId, limit).map { it.toView() }

    /**
     * Books a delivery in.
     *
     * Returns the receipt as a numbered document, because that is what it is:
     * the id is how the slip is printed and how a RECEIPT movement is traced
     * back to the paper that justifies it.
     */
    @PostMapping("/receipts")
    @ResponseStatus(HttpStatus.CREATED)
    fun receive(@Valid @RequestBody body: GoodsReceiptRequest): GoodsReceiptView {
        val receipt = inventory.receive(
            lines = body.lines.map {
                ReceiptLine(it.productId, it.productUomId, it.qty, it.unitCost, it.batchCode, it.expiresOn)
            },
            reference = body.reference,
        )
        return receipt.toView(body.lines.size)
    }

    @GetMapping("/receipts")
    fun receipts(): List<GoodsReceiptView> = inventory.recentReceipts().map { it.toView(null) }

    @GetMapping("/receipts/{id}")
    fun receipt(@PathVariable id: Long): GoodsReceiptDetailView {
        val receipt = inventory.receipt(id)
        return GoodsReceiptDetailView(
            receipt.toView(null),
            inventory.receiptLines(id).map {
                ReceivedLineView(
                    it.lineNo, it.sku, it.productName, it.qtyReceived, it.uomCode,
                    it.qtyBase, it.baseUomCode, it.lotCode,
                    it.batchCode, it.expiresOn?.toLocalDate(),
                )
            },
        )
    }

    @PostMapping("/lots/{lotId}/adjust")
    fun adjust(@PathVariable lotId: Long, @Valid @RequestBody body: AdjustmentRequest): Map<String, Any> =
        mapOf("onHandAfter" to inventory.adjust(lotId, body.qtyBaseDelta, body.reasonCode, body.note))

    @PostMapping("/lots/{lotId}/write-off")
    fun writeOff(@PathVariable lotId: Long, @Valid @RequestBody body: WriteOffRequest): Map<String, Any> =
        mapOf("quantityWrittenOff" to inventory.writeOff(lotId, body.movementType, body.reasonCode))
}
