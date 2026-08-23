package com.counterweight.inventory.service

import com.counterweight.catalog.repo.CategoryRepository
import com.counterweight.catalog.repo.ProductUomRepository
import com.counterweight.catalog.service.UomConverter
import com.counterweight.common.ApiException
import com.counterweight.identity.security.Auth
import com.counterweight.identity.service.AuditService
import com.counterweight.inventory.domain.StockMovement
import com.counterweight.inventory.domain.StockTake
import com.counterweight.inventory.domain.StockTakeLine
import com.counterweight.inventory.event.StockChanged
import com.counterweight.inventory.repo.*
import com.counterweight.platform.service.DocumentNumberService
import jakarta.persistence.EntityManager
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/** Header view of a count, with enough progress to drive a list screen. */
data class StockTakeView(
    val id: Long,
    val reference: String,
    val scopePath: String?,
    val scopeName: String,
    val status: String,
    val openedAt: Instant,
    val postedAt: Instant?,
    val lineCount: Long,
    val countedCount: Long,
    val uncountedCount: Long,
)

/** What posting actually did, in units and in money. */
data class StockTakePosting(
    val reference: String,
    val linesPosted: Int,
    val linesUnchanged: Int,
    val unitsWrittenOff: BigDecimal,
    val unitsFound: BigDecimal,
    /** Cost value of what was missing. Positive number, money lost. */
    val shrinkageValue: BigDecimal,
    /** Cost value of what turned up unexpectedly. */
    val foundValue: BigDecimal,
    /** foundValue − shrinkageValue. Negative is the usual and the honest case. */
    val netValue: BigDecimal,
)

/**
 * Physical counts.
 *
 * The three states are a control, not bookkeeping. Counting is blind — the
 * sheet never shows what the system expected (§ the same reasoning as the Z
 * report) — and posting is a separate, separately-permissioned act, so the
 * person who counts short is not the person who signs the shortage away.
 */
@Service
class StockTakeService(
    private val takes: StockTakeRepository,
    private val lines: StockTakeLineRepository,
    private val lots: StockLotRepository,
    private val movements: StockMovementRepository,
    private val productUoms: ProductUomRepository,
    private val categories: CategoryRepository,
    private val uomConverter: UomConverter,
    private val numbers: DocumentNumberService,
    private val audit: AuditService,
    private val events: ApplicationEventPublisher,
    private val entities: EntityManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // ── Opening ────────────────────────────────────────────────────────────

    /**
     * Opens a count and freezes the sheet.
     *
     * [categoryId] narrows it to that category and everything under it. Null
     * counts the whole shop, which a shop with one storekeeper will do roughly
     * never — the point of the scope is that the agro-chemical shelf can be
     * counted on a Tuesday morning without closing the hardware side.
     */
    @Transactional
    @PreAuthorize("hasAuthority('STOCK_COUNT')")
    fun open(categoryId: Long?): StockTakeView {
        val actor = Auth.current()

        val category = categoryId?.let {
            categories.findById(it).orElseThrow { ApiException.NotFound("Category", it) }
        }
        val path = category?.path
        if (category != null && path == null) {
            // Only reachable if the ltree trigger did not run for this row.
            throw ApiException.RuleViolation(
                "CATEGORY_NOT_PLACED",
                "${category.name} has no place in the category tree yet, so it cannot be counted as a section.",
            )
        }

        /*
         * Taken before the overlap check, and that order is load-bearing.
         *
         * Issuing the number locks this branch's document_sequence row until
         * the transaction ends, so a second open waits here and does its own
         * overlap check against a committed picture. Checking first and
         * numbering second would let two people open counts over the same
         * shelf in the same second, and the same discrepancy would be written
         * off twice. Nothing leaks if the check then fails — the number is
         * issued inside the caller's transaction and rolls back with it.
         */
        val reference = numbers.next(actor.branchId, "STOCK_TAKE")

        if (takes.overlappingOpenCount(actor.branchId, path) > 0) {
            throw ApiException.RuleViolation(
                "COUNT_ALREADY_OPEN",
                "A count covering this stock is already open. Post or cancel it before starting another, " +
                    "or the same discrepancy gets written off twice.",
            )
        }

        val take = takes.saveAndFlush(
            StockTake(branchId = actor.branchId, reference = reference, openedBy = actor.id)
        )
        takes.applyScope(take.id!!, path)
        // scope_path is written by a native statement and read back through a
        // formula, so the instance in the persistence context still believes it
        // is null. Without this the response to *opening* a count reports no
        // scope while every later read reports one.
        entities.refresh(take)

        val snapshotted = takes.snapshot(take.id!!, actor.branchId, path)

        if (snapshotted == 0) {
            throw ApiException.RuleViolation(
                "NOTHING_TO_COUNT",
                "There is no stock on hand in " + (category?.name ?: "the shop") + " to count.",
            )
        }

        audit.recordCurrent(
            "STOCK_TAKE_OPENED", "stock_take", take.id,
            after = """{"reference":"${take.reference}","scope":"${path ?: "ALL"}","lines":$snapshotted}""",
        )
        log.info("stock take {} opened over {} lot(s)", take.reference, snapshotted)
        return view(take, category?.name)
    }

    // ── Counting ───────────────────────────────────────────────────────────

    /**
     * Records what was physically found on a line.
     *
     * Zero is a real count and means the shelf was empty; it is not the same as
     * leaving the line alone, which means nobody has looked yet. Passing null
     * puts the line back to uncounted, for the case where a figure was typed
     * against the wrong row.
     */
    @Transactional
    @PreAuthorize("hasAuthority('STOCK_COUNT')")
    fun count(lineId: Long, countedQty: BigDecimal?, note: String?): StockTakeLine {
        val line = lines.findById(lineId).orElseThrow { ApiException.NotFound("Count line", lineId) }
        val take = openTake(line.stockTakeId)

        if (countedQty != null) {
            if (countedQty < BigDecimal.ZERO) {
                throw ApiException.Validation(
                    "A count cannot be negative.",
                    mapOf("countedQty" to "must be zero or more"),
                )
            }
            val lot = lots.findById(line.lotId).orElseThrow { ApiException.NotFound("Stock lot", line.lotId) }
            /*
             * Counts are in base units, so the precision rule is the base
             * unit's: 2.5 kg of nails is a real count, 2.5 padlocks is a typo,
             * and an append-only ledger is the wrong place to discover that.
             */
            val base = productUoms.findByProductIdAndIsBaseTrue(lot.productId)
                ?: throw ApiException.NotFound("Base unit for product", lot.productId)
            uomConverter.assertPrecision(base.id!!, countedQty)
        }

        line.countedQty = countedQty
        line.note = note?.trim()?.takeIf(String::isNotEmpty) ?: line.note

        // A take goes back to OPEN if a line is un-counted after sign-off, or
        // the header would claim a completeness the sheet no longer has.
        if (take.status == StockTake.COUNTED && countedQty == null) {
            take.status = StockTake.OPEN
            takes.save(take)
        }
        return lines.save(line)
    }

    /**
     * Counts against a lot, adding the line if the books did not expect it.
     *
     * This is the path a scan takes. Stock that turns up for a lot the ledger
     * believes is empty is real and has to be recordable, but a lot **created**
     * after counting began is goods that arrived mid-count: that stock is
     * already in the ledger from its receipt, and counting it again would post
     * the same units twice.
     */
    @Transactional
    @PreAuthorize("hasAuthority('STOCK_COUNT')")
    fun countLot(takeId: Long, lotId: Long, countedQty: BigDecimal, note: String?): StockTakeLine {
        val take = openTake(takeId)
        val existing = lines.findByStockTakeIdAndLotId(takeId, lotId)
        if (existing != null) return count(existing.id!!, countedQty, note)

        val lot = lots.findById(lotId).orElseThrow { ApiException.NotFound("Stock lot", lotId) }
        if (lot.branchId != take.branchId) throw ApiException.NotFound("Stock lot", lotId)

        // Both halves matter: a brand-new lot, and a re-receipt into an old lot
        // when a supplier splits a batch over two deliveries.
        if (lot.createdAt.isAfter(take.openedAt) || movements.receiptsSince(lotId, take.openedAt) > 0) {
            throw ApiException.RuleViolation(
                "RECEIVED_DURING_COUNT",
                "${lot.lotCode} was received after this count started, so it is already in the ledger. " +
                    "Leave it off the sheet.",
            )
        }

        val added = lines.save(
            StockTakeLine(stockTakeId = takeId, lotId = lotId, expectedQty = BigDecimal.ZERO)
        )
        return count(added.id!!, countedQty, note)
    }

    /**
     * Signs the sheet off as complete.
     *
     * Refuses while any line is uncounted. Skipping straight to posting with
     * blank lines is how a count "passes" having never looked at half the shelf
     * — if a lot genuinely has nothing on it, that is a count of zero and
     * somebody has to say so.
     */
    @Transactional
    @PreAuthorize("hasAuthority('STOCK_COUNT')")
    fun markCounted(takeId: Long): StockTakeView {
        val take = openTake(takeId)
        val outstanding = lines.countByStockTakeIdAndCountedQtyIsNull(takeId)
        if (outstanding > 0) {
            throw ApiException.RuleViolation(
                "COUNT_INCOMPLETE",
                "$outstanding line(s) have not been counted. Enter zero for anything the shelf did not have.",
            )
        }
        take.status = StockTake.COUNTED
        takes.save(take)
        audit.recordCurrent("STOCK_TAKE_COUNTED", "stock_take", takeId, after = """{"reference":"${take.reference}"}""")
        return view(take, null)
    }

    // ── Posting ────────────────────────────────────────────────────────────

    /**
     * Writes the variances into the ledger.
     *
     * Gated on STOCK_ADJUST rather than STOCK_COUNT, which is the whole
     * segregation: a storekeeper holds STOCK_COUNT and can count all day, but
     * turning a shortage into a write-off needs the authority to adjust stock.
     * A count that both discovers and disposes of its own shrinkage is not a
     * control.
     *
     * §5.1 settles what gets posted: **the variance is the movement.** Not
     * counted-minus-on-hand — the sales that happened during the count are
     * already in the ledger explaining themselves, and re-deriving against a
     * moved balance would post them a second time.
     */
    @Transactional
    @PreAuthorize("hasAuthority('STOCK_ADJUST')")
    fun post(takeId: Long, note: String?): StockTakePosting {
        val actor = Auth.current()
        val take = takes.findById(takeId).orElseThrow { ApiException.NotFound("Stock take", takeId) }
        if (take.branchId != actor.branchId) throw ApiException.NotFound("Stock take", takeId)
        if (take.status != StockTake.COUNTED) {
            throw ApiException.RuleViolation(
                "COUNT_NOT_SIGNED_OFF",
                "Only a completed count can be posted. This one is ${take.status.lowercase()}.",
            )
        }

        val rows = takes.variances(takeId, true)

        /*
         * A shortage bigger than what is left on the shelf cannot be posted:
         * the balance constraint would reject it and take the whole posting
         * down with it. It means trading outran the count — the goods were sold
         * after being counted — and the honest fix is a recount of those lines,
         * not a number nudged until the database accepts it.
         */
        val impossible = rows.filter { row ->
            val variance = row.variance ?: BigDecimal.ZERO
            variance < BigDecimal.ZERO && variance.abs() > row.onHandNow
        }
        if (impossible.isNotEmpty()) {
            val worst = impossible.first()
            throw ApiException.RuleViolation(
                "VARIANCE_EXCEEDS_STOCK",
                "${worst.name} (${worst.lotCode}) is short by ${worst.variance?.abs()?.stripTrailingZeros()?.toPlainString()} " +
                    "but only ${worst.onHandNow.stripTrailingZeros().toPlainString()} remain — it sold after being counted. " +
                    "Recount " + (if (impossible.size == 1) "that line" else "${impossible.size} lines") + " and post again.",
            )
        }

        var shrinkage = BigDecimal.ZERO
        var found = BigDecimal.ZERO
        var unitsOut = BigDecimal.ZERO
        var unitsIn = BigDecimal.ZERO

        rows.forEach { row ->
            val variance = row.variance ?: return@forEach
            movements.save(
                StockMovement(
                    branchId = take.branchId,
                    lotId = row.lotId,
                    movementType = "STOCK_TAKE",
                    qtyBase = variance,
                    unitCost = row.unitCost,
                    sourceType = "STOCK_TAKE",
                    createdBy = actor.id,
                ).also {
                    it.sourceId = takeId
                    it.reasonCode = take.reference
                }
            )
            val value = variance.abs().multiply(row.unitCost).setScale(2, RoundingMode.HALF_UP)
            if (variance < BigDecimal.ZERO) {
                shrinkage += value
                unitsOut += variance.abs()
            } else {
                found += value
                unitsIn += variance
            }
        }

        take.status = StockTake.POSTED
        take.postedBy = actor.id
        take.postedAt = Instant.now()
        takes.save(take)

        val touched = lots.findAllById(rows.map { it.lotId }).map { it.productId }.distinct()
        if (touched.isNotEmpty()) {
            // Alerting decides whether a balance that just dropped is worth
            // telling anyone about; inventory only says that it moved.
            events.publishEvent(StockChanged(take.branchId, touched, "STOCK_TAKE"))
        }

        val total = lines.countByStockTakeId(takeId)
        audit.recordCurrent(
            "STOCK_TAKE_POSTED", "stock_take", takeId,
            after = """{"reference":"${take.reference}","lines":${rows.size},"shrinkage":"${shrinkage.toPlainString()}"}""",
            reason = note,
        )
        log.warn(
            "stock take {} posted: {} variance line(s), shrinkage {} found {}",
            take.reference, rows.size, shrinkage.toPlainString(), found.toPlainString(),
        )

        return StockTakePosting(
            reference = take.reference,
            linesPosted = rows.size,
            linesUnchanged = (total - rows.size).toInt(),
            unitsWrittenOff = unitsOut,
            unitsFound = unitsIn,
            shrinkageValue = shrinkage,
            foundValue = found,
            netValue = found.subtract(shrinkage),
        )
    }

    /** Abandons a count. The sheet stays as evidence that it happened. */
    @Transactional
    @PreAuthorize("hasAuthority('STOCK_COUNT')")
    fun cancel(takeId: Long, reason: String): StockTakeView {
        if (reason.isBlank()) {
            throw ApiException.Validation(
                "Say why the count is being abandoned.",
                mapOf("reason" to "is required"),
            )
        }
        val take = openTake(takeId)
        take.status = StockTake.CANCELLED
        takes.save(take)
        audit.recordCurrent(
            "STOCK_TAKE_CANCELLED", "stock_take", takeId,
            after = """{"reference":"${take.reference}"}""", reason = reason,
        )
        return view(take, null)
    }

    // ── Reads ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('STOCK_COUNT')")
    fun list(): List<StockTakeView> =
        takes.findByBranchIdOrderByOpenedAtDesc(Auth.current().branchId).map { view(it, null) }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('STOCK_COUNT')")
    fun get(takeId: Long): StockTakeView = view(requireVisible(takeId), null)

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('STOCK_COUNT')")
    fun sheet(takeId: Long): List<CountSheetRow> {
        requireVisible(takeId)
        return takes.sheet(takeId)
    }

    /**
     * The variance report.
     *
     * Gated on STOCK_ADJUST, not STOCK_COUNT: it carries the expected figures
     * and the cost value of every discrepancy. Handing that to the person
     * holding the count sheet un-blinds the count, and cost is permission-gated
     * everywhere else in the system for the same reason.
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('STOCK_ADJUST')")
    fun variances(takeId: Long, onlyVariances: Boolean): List<VarianceRow> {
        requireVisible(takeId)
        return takes.variances(takeId, onlyVariances)
    }

    /**
     * Lines a scanned barcode addresses.
     *
     * A barcode identifies a product, and a batch-tracked product has a line per
     * lot — so this returns candidates rather than guessing. Picking the wrong
     * batch would attach the count to the wrong expiry date.
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('STOCK_COUNT')")
    fun linesForProduct(takeId: Long, productId: Long): List<CountSheetRow> {
        requireVisible(takeId)
        val wanted = lines.findForProduct(takeId, productId).mapNotNull { it.id }.toSet()
        return takes.sheet(takeId).filter { it.lineId in wanted }
    }

    // ── Internals ──────────────────────────────────────────────────────────

    private fun openTake(takeId: Long): StockTake {
        val take = takes.findById(takeId).orElseThrow { ApiException.NotFound("Stock take", takeId) }
        if (take.branchId != Auth.current().branchId) throw ApiException.NotFound("Stock take", takeId)
        if (!take.isOpen) {
            throw ApiException.RuleViolation(
                "COUNT_CLOSED",
                "${take.reference} is ${take.status.lowercase()} and cannot be changed.",
            )
        }
        return take
    }

    private fun requireVisible(takeId: Long): StockTake {
        val take = takes.findById(takeId).orElseThrow { ApiException.NotFound("Stock take", takeId) }
        if (take.branchId != Auth.current().branchId) throw ApiException.NotFound("Stock take", takeId)
        return take
    }

    private fun view(take: StockTake, scopeName: String?): StockTakeView {
        val total = lines.countByStockTakeId(take.id!!)
        val outstanding = lines.countByStockTakeIdAndCountedQtyIsNull(take.id!!)
        return StockTakeView(
            id = take.id!!,
            reference = take.reference,
            scopePath = take.scopePath,
            scopeName = scopeName ?: take.scopePath?.let { nameForPath(it) } ?: "Whole shop",
            status = take.status,
            openedAt = take.openedAt,
            postedAt = take.postedAt,
            lineCount = total,
            countedCount = total - outstanding,
            uncountedCount = outstanding,
        )
    }

    private fun nameForPath(path: String): String =
        categories.findByPath(path)?.name ?: path
}
