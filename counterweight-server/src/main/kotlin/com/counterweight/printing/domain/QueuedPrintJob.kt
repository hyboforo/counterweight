package com.counterweight.printing.domain

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * A print job waiting for, or already handed to, a till.
 *
 * Queued on the server rather than fired at a printer, because the till is a
 * browser and the printer is on the other side of it. The job sits here until
 * the till picks it up, which also means a receipt survives the browser being
 * closed, the tab being refreshed, or the till being unplugged mid-sale.
 *
 * The status here is the *server's* view — whether the till has taken the job.
 * The agent keeps its own spool, so a job marked DONE has been handed over and
 * accepted, not necessarily inked onto paper. That distinction is what makes
 * "reprint the last receipt" a real feature rather than a lie: a jam or an empty
 * roll is the agent's problem and the job stays spooled there (§10).
 */
@Entity
@Table(name = "print_job")
class QueuedPrintJob(
    @Id
    @Column(name = "id")
    var id: UUID,

    @Column(name = "branch_id", nullable = false)
    var branchId: Long,

    @Column(name = "till_code", nullable = false)
    var tillCode: String,

    @Column(nullable = false)
    var template: String,

    /** The structured document. The server never stores ESC/POS bytes. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "document", columnDefinition = "jsonb", nullable = false)
    var document: String,
) {
    @Column(nullable = false)
    var copies: Short = 1

    @Column(nullable = false)
    var status: String = QUEUED

    @Column(nullable = false)
    var attempts: Short = 0

    @Column(name = "last_error")
    var lastError: String? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()

    @Column(name = "completed_at")
    var completedAt: Instant? = null

    override fun equals(other: Any?) = this === other || (other is QueuedPrintJob && id == other.id)
    override fun hashCode() = javaClass.hashCode()
    override fun toString() = "QueuedPrintJob($template $id)"

    companion object {
        const val QUEUED = "QUEUED"
        const val PRINTING = "PRINTING"
        const val DONE = "DONE"
        const val FAILED = "FAILED"

        /** Templates the server knows how to build. */
        const val RECEIPT = "RECEIPT"
        const val SHELF_LABEL = "SHELF_LABEL"
        const val GOODS_RECEIPT = "GOODS_RECEIPT"

        /**
         * The slip at open of business (§11.3). Built by `alerting`, which
         * hands it here as a document like any other — printing knows nothing
         * about alerts.
         */
        const val MORNING_BRIEFING = "MORNING_BRIEFING"

        /**
         * The blind sheet a storekeeper carries down the aisle, and the
         * variance slip that gets signed afterwards. Two templates rather than
         * one because only the second may carry the expected figures.
         */
        const val COUNT_SHEET = "COUNT_SHEET"
        const val COUNT_VARIANCE = "COUNT_VARIANCE"

        val TEMPLATES = setOf(
            RECEIPT, SHELF_LABEL, GOODS_RECEIPT, MORNING_BRIEFING,
            COUNT_SHEET, COUNT_VARIANCE,
        )
    }
}
