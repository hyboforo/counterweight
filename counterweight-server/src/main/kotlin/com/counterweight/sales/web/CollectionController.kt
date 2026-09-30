package com.counterweight.sales.web

import com.counterweight.common.SafeText
import com.counterweight.pricing.service.Money
import com.counterweight.sales.domain.SalesCollectionLine
import com.counterweight.sales.service.CollectionRecord
import com.counterweight.sales.service.CollectionService
import com.counterweight.sales.service.CountedTender
import com.counterweight.sales.service.PendingCollection
import jakarta.validation.Valid
import jakarta.validation.constraints.*
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.time.Instant

// ── Requests ───────────────────────────────────────────────────────────────

data class CountedTenderRequest(
    @field:NotBlank(message = "is required")
    val method: String,

    /** The figure the screen showed. Checked against the server's, never stored. */
    @field:NotNull(message = "is required")
    @field:Digits(integer = 12, fraction = 2, message = "has too many digits")
    val expected: BigDecimal,

    @field:NotNull(message = "is required")
    @field:DecimalMin(value = "0.00", message = "cannot be negative")
    @field:Digits(integer = 12, fraction = 2, message = "has too many digits")
    val collected: BigDecimal,
)

data class RecordCollectionRequest(
    /** When the money was taken — the `until` the figures were read at, unless changed. */
    @field:NotNull(message = "is required")
    val collectedAt: Instant,

    @field:NotNull(message = "is required")
    @field:Size(max = 5, message = "has more tenders than there are")
    @field:Valid
    val tenders: List<CountedTenderRequest>,

    @field:Size(max = 500, message = "is too long")
    @field:SafeText
    val note: String? = null,
)

// ── Views ──────────────────────────────────────────────────────────────────

data class TenderView(val method: String, val expected: BigDecimal)

data class PendingCollectionView(
    val previousNumber: String?,
    val periodFrom: Instant?,
    val until: Instant,
    val tenders: List<TenderView>,
    val expectedTotal: BigDecimal,
)

data class CollectionLineView(
    val method: String,
    val expected: BigDecimal,
    val collected: BigDecimal,
    val difference: BigDecimal,
)

data class CollectionView(
    val id: Long,
    val number: String,
    val periodFrom: Instant?,
    val collectedAt: Instant,
    val recordedAt: Instant,
    val collectedByName: String,
    val note: String?,
    val tenders: List<CollectionLineView>,
    val expectedTotal: BigDecimal,
    val collectedTotal: BigDecimal,
    val difference: BigDecimal,
)

private fun PendingCollection.toView() = PendingCollectionView(
    previousNumber, periodFrom, until, tenders.map { TenderView(it.method, it.expected) }, expectedTotal,
)

private fun SalesCollectionLine.toView() = CollectionLineView(method, expected, collected, difference)

private fun CollectionRecord.toView(): CollectionView {
    val expected = lines.fold(Money.ZERO) { acc, l -> acc.add(l.expected) }
    val collected = lines.fold(Money.ZERO) { acc, l -> acc.add(l.collected) }
    return CollectionView(
        header.id, header.number, header.periodFrom, header.collectedAt, header.recordedAt,
        header.collectedByName, header.note, lines.map { it.toView() },
        expected, collected, collected.subtract(expected),
    )
}

/**
 * Collecting the takings.
 *
 * Every endpoint needs `SALES_COLLECT`, enforced on [CollectionService] so the
 * check holds however the service is reached. The collections report, which
 * reads what was recorded, is under `/api/reports/money/collections` behind
 * `REPORT_VIEW` instead — an auditor reads collections without making any.
 */
@RestController
@RequestMapping("/api/collections")
class CollectionController(private val collections: CollectionService) {

    /** What a collection made at [until] — or now — would cover. */
    @GetMapping("/pending")
    fun pending(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) until: Instant?,
    ): PendingCollectionView = collections.pending(until).toView()

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun record(@Valid @RequestBody body: RecordCollectionRequest): CollectionView =
        collections.record(
            collectedAt = body.collectedAt,
            counted = body.tenders.map { CountedTender(it.method, it.expected, it.collected) },
            note = body.note,
        ).toView()

    @GetMapping
    fun recent(@RequestParam(defaultValue = "20") @Min(1) @Max(200) limit: Int): List<CollectionView> =
        collections.recent(limit).map { it.toView() }
}
