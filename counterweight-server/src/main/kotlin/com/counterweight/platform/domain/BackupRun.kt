package com.counterweight.platform.domain

import jakarta.persistence.*
import java.time.Instant

/**
 * One attempt at protecting the shop's records.
 *
 * §14 calls this the system's largest single risk and not optional hardening:
 * "local server only" means the whole stock position, price history and
 * receivables ledger live on one machine in one building, and a failed SSD is
 * not a bad day but the end of the business's records.
 *
 * [verifiedAt] is the field that matters most and the one usually skipped. A
 * backup nobody has restored is a belief, not a backup — so a run that
 * succeeded but has never been verified is deliberately not treated as
 * protection by the staleness check.
 */
@Entity
@Table(name = "backup_run")
class BackupRun(
    @Column(nullable = false)
    var kind: String,

    @Column(nullable = false)
    var destination: String,
) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "started_at", nullable = false)
    var startedAt: Instant = Instant.now()

    @Column(name = "completed_at")
    var completedAt: Instant? = null

    @Column(name = "size_bytes")
    var sizeBytes: Long? = null

    var checksum: String? = null

    @Column(nullable = false)
    var status: String = RUNNING

    var error: String? = null

    /** Set only by the restore drill, after asserting row counts against a scratch database. */
    @Column(name = "verified_at")
    var verifiedAt: Instant? = null

    @Column(name = "verify_error")
    var verifyError: String? = null

    val isUsable: Boolean get() = status == SUCCESS && verifiedAt != null

    override fun equals(other: Any?) = this === other || (other is BackupRun && id != null && id == other.id)
    override fun hashCode() = javaClass.hashCode()
    override fun toString() = "BackupRun($kind $status)"

    companion object {
        const val RUNNING = "RUNNING"
        const val SUCCESS = "SUCCESS"
        const val FAILED = "FAILED"

        /** A pg_dump to a second physical disk. Nightly. */
        const val DUMP = "DUMP"

        /** Continuous WAL archiving, reported in by whatever performs it. */
        const val WAL_ARCHIVE = "WAL_ARCHIVE"

        /** The weekly copy to a rotating USB SSD, one always off the premises. */
        const val OFFSITE = "OFFSITE"

        val KINDS = setOf(DUMP, WAL_ARCHIVE, OFFSITE)
    }
}
