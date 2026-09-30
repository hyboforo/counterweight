package com.counterweight.sales.service

import com.counterweight.common.ApiException
import com.counterweight.identity.security.Auth
import com.counterweight.identity.service.AuditService
import com.counterweight.platform.service.DocumentNumberService
import com.counterweight.pricing.service.Money
import com.counterweight.sales.domain.SalesCollection
import com.counterweight.sales.domain.SalesCollectionLine
import com.counterweight.sales.repo.CollectionHeader
import com.counterweight.sales.repo.SalesCollectionLineRepository
import com.counterweight.sales.repo.SalesCollectionRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** What the sales say should be in hand for one tender. */
data class TenderExpectation(val method: String, val expected: BigDecimal)

/**
 * What a collection made now would cover.
 *
 * [until] is the moment the figures were read. The screen sends it back as the
 * collection's time, so a sale rung up while the owner is counting lands in the
 * next collection rather than turning up here as a shortage nobody saw.
 */
data class PendingCollection(
    val previousNumber: String?,
    val periodFrom: Instant?,
    val until: Instant,
    /** Every collectable tender, zero included, in [SalesCollection.METHODS] order. */
    val tenders: List<TenderExpectation>,
) {
    val expectedTotal: BigDecimal get() = tenders.fold(Money.ZERO) { acc, t -> acc.add(t.expected) }
}

/**
 * One tender as the owner counted it.
 *
 * [expected] is the figure the screen showed, sent back so the server can tell
 * whether it still holds. The server never stores it — it recomputes its own.
 */
data class CountedTender(val method: String, val expected: BigDecimal, val collected: BigDecimal)

data class CollectionRecord(val header: CollectionHeader, val lines: List<SalesCollectionLine>)

/**
 * Collecting the takings.
 *
 * The owner takes away what the shop has taken in since the last collection,
 * counts it, and writes down what they counted against what the sales say.
 * Every tender that brings money in is counted separately — cash, mobile
 * money, transfers, cheques, card — because a shortage in one is not made good
 * by a surplus in another.
 *
 * Behind `SALES_COLLECT`, which only ADMIN holds (V17). The people who ring up
 * the sales are not the ones who sign their takings off.
 */
@Service
class CollectionService(
    private val collections: SalesCollectionRepository,
    private val lines: SalesCollectionLineRepository,
    private val numbers: DocumentNumberService,
    private val audit: AuditService,
) {

    /** What is waiting to be collected, up to [until] or now. */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('SALES_COLLECT')")
    fun pending(until: Instant? = null): PendingCollection {
        val branchId = Auth.current().branchId
        val now = now()
        val end = until?.truncatedTo(ChronoUnit.MICROS) ?: now
        val previous = collections.findFirstByBranchIdOrderByCollectedAtDesc(branchId)
        assertCollectable(end, now, previous)
        return PendingCollection(
            previousNumber = previous?.number,
            periodFrom = previous?.collectedAt,
            until = end,
            tenders = expectedFor(branchId, previous?.collectedAt, end),
        )
    }

    /**
     * Records a collection made at [collectedAt].
     *
     * The numbering lock is taken before anything is read. Two admins recording
     * at once therefore queue on it, and the second one reads the first one's
     * collection as its predecessor rather than both claiming the same period —
     * which V17's UNIQUE (previous_id) would refuse anyway, in less helpful
     * words.
     */
    @Transactional
    @PreAuthorize("hasAuthority('SALES_COLLECT')")
    fun record(collectedAt: Instant, counted: List<CountedTender>, note: String?): CollectionRecord {
        val actor = Auth.current()
        val at = collectedAt.truncatedTo(ChronoUnit.MICROS)
        val reason = note?.trim()?.takeIf(String::isNotEmpty)

        counted.groupBy { it.method }.forEach { (method, entries) ->
            if (method !in SalesCollection.METHODS) {
                throw ApiException.Validation(
                    "'$method' is not something that can be collected.",
                    mapOf("tenders" to "must be one of: ${SalesCollection.METHODS.joinToString(", ")}"),
                )
            }
            if (entries.size > 1) {
                throw ApiException.Validation(
                    "${label(method)} was counted twice.",
                    mapOf("tenders" to "each tender may appear once"),
                )
            }
        }
        counted.forEach {
            if (it.collected < BigDecimal.ZERO) {
                throw ApiException.Validation(
                    "What was collected cannot be negative.",
                    mapOf("tenders" to "collected must be zero or more"),
                )
            }
        }

        val number = numbers.next(actor.branchId, "COLLECTION")
        val now = now()
        val previous = collections.findFirstByBranchIdOrderByCollectedAtDesc(actor.branchId)
        assertCollectable(at, now, previous)

        val expected = expectedFor(actor.branchId, previous?.collectedAt, at).associate { it.method to it.expected }
        val byMethod = counted.associateBy { it.method }

        expected.filterValues { it.signum() != 0 }.keys.firstOrNull { it !in byMethod }?.let { missing ->
            throw ApiException.Validation(
                "Count the ${label(missing).lowercase()} too — the sales say ${expected.getValue(missing).toPlainString()} came in.",
                mapOf("tenders" to "${label(missing)} is missing"),
            )
        }
        byMethod.values.firstOrNull { it.expected.compareTo(expected.getValue(it.method)) != 0 }?.let {
            throw ApiException.Conflict(
                "The sales have changed since these figures were read — ${label(it.method).lowercase()} " +
                    "is now ${expected.getValue(it.method).toPlainString()}, not ${it.expected.toPlainString()}. " +
                    "Refresh and count again.",
            )
        }

        // A tender that took nothing and was counted as nothing says nothing.
        val recorded = SalesCollection.METHODS
            .map { Tally(it, expected.getValue(it), Money.round(byMethod[it]?.collected ?: Money.ZERO)) }
            .filterNot { it.expected.signum() == 0 && it.collected.signum() == 0 }
        if (recorded.isEmpty()) {
            throw ApiException.RuleViolation(
                "NOTHING_TO_COLLECT",
                "Nothing has been taken in since ${previous?.number ?: "the shop opened"}, and nothing was counted.",
            )
        }
        if (recorded.any { it.expected.compareTo(it.collected) != 0 } && reason == null) {
            throw ApiException.Validation(
                "What was counted does not match the sales. Say why before recording it.",
                mapOf("note" to "is required when the count differs from the sales"),
            )
        }

        val collection = try {
            collections.saveAndFlush(
                SalesCollection(
                    branchId = actor.branchId,
                    number = number,
                    previousId = previous?.id,
                    periodFrom = previous?.collectedAt,
                    collectedAt = at,
                    collectedBy = actor.id,
                    recordedAt = now,
                ).also { it.note = reason }
            )
        } catch (ex: DataIntegrityViolationException) {
            throw ApiException.Conflict("Another collection was recorded a moment ago. Refresh and count again.")
        }
        val saved = lines.saveAll(
            recorded.map { SalesCollectionLine(collection.id!!, it.method, it.expected, it.collected) }
        )

        val expectedTotal = saved.fold(Money.ZERO) { acc, l -> acc.add(l.expected) }
        val collectedTotal = saved.fold(Money.ZERO) { acc, l -> acc.add(l.collected) }
        audit.recordCurrent(
            "SALES_COLLECTED", "sales_collection", collection.id,
            after = """{"collection":"$number","expected":"${expectedTotal.toPlainString()}",""" +
                """"collected":"${collectedTotal.toPlainString()}"}""",
            reason = reason,
        )
        return CollectionRecord(collections.headerOf(collection.id!!), sorted(saved))
    }

    /** The most recent collections, newest first, each with its tenders. */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('SALES_COLLECT')")
    fun recent(limit: Int): List<CollectionRecord> {
        val found = collections.history(Auth.current().branchId, limit.coerceIn(1, 200))
        if (found.isEmpty()) return emptyList()
        val linesOf = lines.findByCollectionIdIn(found.map { it.id }).groupBy { it.collectionId }
        return found.map { CollectionRecord(it, sorted(linesOf[it.id].orEmpty())) }
    }

    private fun sorted(tenders: List<SalesCollectionLine>) =
        tenders.sortedBy { SalesCollection.METHODS.indexOf(it.method) }

    private fun expectedFor(branchId: Long, from: Instant?, until: Instant): List<TenderExpectation> {
        val moved = collections.movements(branchId, from ?: Instant.EPOCH, until, SalesCollection.METHODS)
            .associate { it.method to it.amount }
        return SalesCollection.METHODS.map { TenderExpectation(it, Money.round(moved[it] ?: Money.ZERO)) }
    }

    private fun assertCollectable(at: Instant, now: Instant, previous: SalesCollection?) {
        if (at.isAfter(now)) {
            throw ApiException.Validation(
                "A collection cannot be dated in the future.",
                mapOf("collectedAt" to "must not be later than now"),
            )
        }
        if (previous != null && !at.isAfter(previous.collectedAt)) {
            throw ApiException.RuleViolation(
                "COLLECTION_OUT_OF_ORDER",
                "${previous.number} was collected at ${previous.collectedAt.local()}; this one has to be later.",
            )
        }
    }

    private fun now(): Instant = Instant.now().truncatedTo(ChronoUnit.MICROS)

    private fun Instant.local(): String = WHEN.format(atZone(ZoneId.systemDefault()))

    private fun label(method: String) = when (method) {
        "CASH" -> "Cash"
        "MOBILE_MONEY" -> "Mobile money"
        "BANK_TRANSFER" -> "Bank transfer"
        "CHEQUE" -> "Cheques"
        "CARD" -> "Card"
        else -> method
    }

    private data class Tally(val method: String, val expected: BigDecimal, val collected: BigDecimal)

    private companion object {
        val WHEN: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm")
    }
}
