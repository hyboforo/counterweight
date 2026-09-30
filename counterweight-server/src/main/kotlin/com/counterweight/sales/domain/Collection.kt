package com.counterweight.sales.domain

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant

/**
 * The owner taking the takings away, as a numbered document (COL-000001).
 *
 * A collection covers everything from where the previous one ended
 * ([periodFrom]) up to [collectedAt], exclusive. The first a branch records
 * has no predecessor and covers everything before it. V17 holds the chain in
 * the schema — no gap, no overlap, no fork — so every payment falls inside
 * exactly one collection.
 *
 * Append-only, like `audit_log`: the table rejects UPDATE and DELETE. A
 * collection is evidence of what was handed over, and a correction is the next
 * collection rather than an edit to this one.
 */
@Entity
@Table(name = "sales_collection")
class SalesCollection(
    @Column(name = "branch_id", nullable = false, updatable = false)
    var branchId: Long,

    @Column(nullable = false, updatable = false)
    var number: String,

    @Column(name = "previous_id", updatable = false)
    var previousId: Long?,

    @Column(name = "period_from", updatable = false)
    var periodFrom: Instant?,

    @Column(name = "collected_at", nullable = false, updatable = false)
    var collectedAt: Instant,

    @Column(name = "collected_by", nullable = false, updatable = false)
    var collectedBy: Long,

    /**
     * When it was written down, which may be after [collectedAt].
     *
     * Set here rather than left to the column default: `now()` is the start of
     * the transaction, which can fall a moment before a [collectedAt] of "now"
     * and trip the check that a collection is never dated in the future.
     */
    @Column(name = "recorded_at", nullable = false, updatable = false)
    var recordedAt: Instant,
) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(updatable = false)
    var note: String? = null

    override fun equals(other: Any?) = this === other || (other is SalesCollection && id != null && id == other.id)
    override fun hashCode() = javaClass.hashCode()
    override fun toString() = "SalesCollection($number)"

    companion object {
        /**
         * Every tender that brings money in. ON_ACCOUNT does not — the customer
         * owes it, and it is collected through their account, not from the shop.
         */
        val METHODS = listOf("CASH", "MOBILE_MONEY", "BANK_TRANSFER", "CHEQUE", "CARD")
    }
}

/** One tender of a collection: what the sales said should be there, and what was counted. */
@Entity
@Table(name = "sales_collection_line")
class SalesCollectionLine(
    @Column(name = "collection_id", nullable = false, updatable = false)
    var collectionId: Long,

    @Column(nullable = false, updatable = false)
    var method: String,

    /** Can be negative: a period that handed back more in refunds than it took. */
    @Column(nullable = false, updatable = false)
    var expected: BigDecimal,

    @Column(nullable = false, updatable = false)
    var collected: BigDecimal,
) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    val difference: BigDecimal get() = collected.subtract(expected)

    override fun equals(other: Any?) = this === other || (other is SalesCollectionLine && id != null && id == other.id)
    override fun hashCode() = javaClass.hashCode()
}
