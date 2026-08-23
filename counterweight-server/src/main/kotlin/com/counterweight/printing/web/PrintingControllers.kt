package com.counterweight.printing.web

import com.counterweight.common.SafeText
import com.counterweight.printing.domain.QueuedPrintJob
import com.counterweight.printing.model.PrintJob
import com.counterweight.printing.service.PrintQueueService
import com.counterweight.printing.service.PrintingService
import jakarta.validation.Valid
import jakarta.validation.constraints.*
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

// ── Requests ───────────────────────────────────────────────────────────────

private const val TILL_PATTERN = "^[A-Za-z0-9._-]+$"

data class JobStatusRequest(
    @field:NotBlank(message = "is required")
    @field:Pattern(regexp = "^(DONE|FAILED)$", message = "must be DONE or FAILED")
    val status: String,

    /** What the agent reported: paper out, cover open, no printer paired. */
    @field:Size(max = 500, message = "is too long")
    @field:SafeText
    val error: String? = null,
)

data class PrintReceiptRequest(
    @field:NotNull(message = "is required")
    val saleId: Long,

    @field:NotBlank(message = "is required")
    @field:Pattern(regexp = TILL_PATTERN, message = "may use letters, digits and . _ - only")
    val tillCode: String,

    /** Marks the paper as a second copy. The till sends this from sale history. */
    val isReprint: Boolean = false,
)

data class ShelfLabelRequest(
    @field:NotNull(message = "is required")
    val productId: Long,

    @field:NotNull(message = "is required")
    val productUomId: Long,

    @field:NotBlank(message = "is required")
    @field:Pattern(regexp = TILL_PATTERN, message = "may use letters, digits and . _ - only")
    val tillCode: String,

    @field:Min(1) @field:Max(50)
    val copies: Int = 1,
)


// ── Responses ──────────────────────────────────────────────────────────────

data class PrintJobView(
    val id: UUID, val tillCode: String, val template: String, val status: String,
    val copies: Int, val attempts: Int, val lastError: String?,
    val createdAt: Instant, val completedAt: Instant?,
)

private fun QueuedPrintJob.toView() = PrintJobView(
    id, tillCode, template, status, copies.toInt(), attempts.toInt(), lastError, createdAt, completedAt,
)

// ── Controllers ────────────────────────────────────────────────────────────

/**
 * The till's side of the print queue.
 *
 * The browser polls [claim], relays each job to the local agent at
 * `127.0.0.1:9110`, and reports back through [updateStatus]. The server never
 * talks to the agent itself — it has no route to a machine behind the till's
 * own loopback, and would not know which printer is attached even if it did.
 */
@RestController
@RequestMapping("/api/print-jobs")
class PrintJobController(
    private val queue: PrintQueueService,
    private val printing: PrintingService,
) {

    /**
     * Takes every job waiting for this till.
     *
     * Claiming marks them PRINTING in the same transaction that returns them,
     * so two browser tabs polling the same till cannot both be handed the same
     * receipt.
     */
    @GetMapping("/claim")
    fun claim(
        @RequestParam @Pattern(regexp = TILL_PATTERN) tillCode: String,
    ): List<PrintJob> = queue.claimFor(tillCode)

    /** What the agent said happened. FAILED requeues until the attempt limit. */
    @PutMapping("/{id}/status")
    fun updateStatus(@PathVariable id: UUID, @Valid @RequestBody body: JobStatusRequest) {
        if (body.status == "DONE") queue.markDone(id) else queue.markFailed(id, body.error)
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): PrintJobView = queue.get(id).toView()

    @GetMapping
    fun history(
        @RequestParam @Pattern(regexp = TILL_PATTERN) tillCode: String,
    ): List<PrintJobView> = queue.history(tillCode).map { it.toView() }

    /** Somebody fixed the printer. Puts a failed job back in the queue. */
    @PutMapping("/{id}/retry")
    fun retry(@PathVariable id: UUID): PrintJobView = queue.retry(id).toView()

    /** Queues a fresh copy of something that already printed. */
    @PostMapping("/{id}/reprint")
    @ResponseStatus(HttpStatus.CREATED)
    fun reprint(
        @PathVariable id: UUID,
        @RequestParam(required = false) tillCode: String?,
    ): PrintJobView = printing.reprintJob(id, tillCode).toView()

    /**
     * Returns jobs a till took but never reported on.
     *
     * Deliberately manual. Whether a stuck job is safe to print again is a
     * judgement about what actually came out of the printer, and only somebody
     * standing at the till can make it.
     */
    @PostMapping("/requeue-stale")
    fun requeueStale(
        @RequestParam @Pattern(regexp = TILL_PATTERN) tillCode: String,
        @RequestParam(defaultValue = "300") @Min(30) olderThanSeconds: Long,
    ): Map<String, Int> = mapOf(
        "requeued" to queue.requeueStale(tillCode, Instant.now().minusSeconds(olderThanSeconds)),
    )
}

/** Asking for something to be printed. */
@RestController
@RequestMapping("/api/printing")
class PrintingController(private val printing: PrintingService) {

    @PostMapping("/receipt")
    @ResponseStatus(HttpStatus.CREATED)
    fun receipt(@Valid @RequestBody body: PrintReceiptRequest): PrintJobView =
        printing.printReceipt(body.saleId, body.tillCode, body.isReprint).toView()

    @PostMapping("/shelf-label")
    @ResponseStatus(HttpStatus.CREATED)
    fun shelfLabel(@Valid @RequestBody body: ShelfLabelRequest): PrintJobView =
        printing.printShelfLabel(body.productId, body.productUomId, body.tillCode, body.copies).toView()

    /** The blind count sheet the storekeeper carries. Carries no expected quantities. */
    @PostMapping("/stock-takes/{takeId}/sheet")
    @ResponseStatus(HttpStatus.CREATED)
    fun countSheet(
        @PathVariable takeId: Long,
        @RequestParam @Pattern(regexp = TILL_PATTERN) tillCode: String,
    ): PrintJobView = printing.printCountSheet(takeId, tillCode).toView()

    /** The variance slip, for signing. Behind STOCK_ADJUST — it carries cost. */
    @PostMapping("/stock-takes/{takeId}/variances")
    @ResponseStatus(HttpStatus.CREATED)
    fun countVariances(
        @PathVariable takeId: Long,
        @RequestParam @Pattern(regexp = TILL_PATTERN) tillCode: String,
    ): PrintJobView = printing.printCountVariances(takeId, tillCode).toView()

    /**
     * The slip for a delivery, by its receipt number.
     *
     * Takes an id, not a list of lines. The slip is the evidence behind a lot
     * cost, so it is rendered from `goods_receipt_line`; a document assembled
     * from a request body says whatever the caller says, which is no use to
     * anybody reading it a year later.
     */
    @PostMapping("/goods-receipts/{receiptId}")
    @ResponseStatus(HttpStatus.CREATED)
    fun goodsReceipt(
        @PathVariable receiptId: Long,
        @RequestParam @Pattern(regexp = TILL_PATTERN) tillCode: String,
    ): PrintJobView = printing.printGoodsReceipt(receiptId, tillCode).toView()
}
