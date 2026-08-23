package com.counterweight.printing.service

import com.counterweight.common.ApiException
import com.counterweight.identity.security.Auth
import com.counterweight.platform.service.ConfigKeys
import com.counterweight.platform.service.ConfigService
import com.counterweight.printing.domain.QueuedPrintJob
import com.counterweight.printing.model.PrintDocument
import com.counterweight.printing.model.PrintJob
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.persistence.LockModeType
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@ConfigurationProperties(prefix = "counterweight.printing")
data class PrintingProperties(
    /**
     * How many times a till may hand a job back before it stops being retried.
     *
     * Stays in yml rather than `app_config`: it is a tuning knob for how this
     * process behaves, not a decision the shop makes. Paper width is the
     * opposite and lives in `app_config`.
     */
    var maxAttempts: Int = 5,
)

@Repository
interface PrintJobRepository : JpaRepository<QueuedPrintJob, UUID> {

    /**
     * Jobs waiting for a till, oldest first, with a row lock.
     *
     * Locked because two browser tabs pointed at the same till will both poll,
     * and handing the same receipt to both prints it twice. The lock plus the
     * status change inside one transaction means the second poll sees an empty
     * queue rather than a duplicate.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        SELECT j FROM QueuedPrintJob j
         WHERE j.branchId = :branchId AND j.tillCode = :tillCode AND j.status = 'QUEUED'
         ORDER BY j.createdAt, j.id
        """
    )
    fun lockQueuedFor(
        @Param("branchId") branchId: Long,
        @Param("tillCode") tillCode: String,
    ): List<QueuedPrintJob>

    fun findByBranchIdAndTillCodeOrderByCreatedAtDesc(branchId: Long, tillCode: String): List<QueuedPrintJob>

    @Query(
        """
        SELECT j FROM QueuedPrintJob j
         WHERE j.branchId = :branchId AND j.template = :template
         ORDER BY j.createdAt DESC
        """
    )
    fun latestOfTemplate(
        @Param("branchId") branchId: Long,
        @Param("template") template: String,
    ): List<QueuedPrintJob>
}

/**
 * The print queue.
 *
 * Nothing here knows what a receipt looks like — templates build documents and
 * hand them over. This owns only the queue: what is waiting, who took it, and
 * what to do when a till hands one back.
 */
@Service
class PrintQueueService(
    private val jobs: PrintJobRepository,
    private val props: PrintingProperties,
    private val config: ConfigService,
    private val json: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 48 for 80 mm paper, 32 for 58 mm. The shop's setting, so `app_config`. */
    val widthChars: Int get() = config.int(ConfigKeys.RECEIPT_WIDTH, 48)

    // ── Queueing ───────────────────────────────────────────────────────────

    @Transactional
    fun enqueue(
        tillCode: String,
        template: String,
        document: PrintDocument,
        copies: Int = 1,
    ): QueuedPrintJob {
        if (template !in QueuedPrintJob.TEMPLATES) {
            throw ApiException.Validation(
                "Unknown print template '$template'.",
                mapOf("template" to "must be one of: ${QueuedPrintJob.TEMPLATES.sorted().joinToString(", ")}"),
            )
        }
        val code = tillCode.trim()
        if (code.isEmpty()) {
            throw ApiException.Validation("Which till should this print on?", mapOf("tillCode" to "is required"))
        }

        val job = jobs.save(
            QueuedPrintJob(
                id = UUID.randomUUID(),
                branchId = Auth.current().branchId,
                tillCode = code,
                template = template,
                document = json.writeValueAsString(document),
            ).also { it.copies = copies.coerceIn(1, 5).toShort() }
        )
        log.debug("queued {} job {} for till {}", template, job.id, code)
        return job
    }

    // ── The till's side ────────────────────────────────────────────────────

    /**
     * Hands every waiting job for a till to whoever asked, marking them taken.
     *
     * Claiming and returning happen in the same transaction, so a till that
     * crashes between the two leaves the jobs marked PRINTING rather than
     * QUEUED — visible as stuck rather than silently reprinted on the next
     * poll. [requeueStale] is how those come back.
     */
    @Transactional
    fun claimFor(tillCode: String): List<PrintJob> {
        val claimed = jobs.lockQueuedFor(Auth.current().branchId, tillCode.trim())
        claimed.forEach {
            it.status = QueuedPrintJob.PRINTING
            it.attempts = (it.attempts + 1).toShort()
        }
        jobs.saveAll(claimed)
        return claimed.map(::toPayload)
    }

    @Transactional
    fun markDone(jobId: UUID) {
        val job = get(jobId)
        job.status = QueuedPrintJob.DONE
        job.completedAt = Instant.now()
        job.lastError = null
        jobs.save(job)
    }

    /**
     * Records that a till could not print a job.
     *
     * Past [PrintingProperties.maxAttempts] the job stops being retried
     * automatically and stays FAILED for somebody to look at. A receipt that
     * retries for ever against a printer nobody has plugged in is how a queue
     * fills up and the useful jobs stop getting through.
     */
    @Transactional
    fun markFailed(jobId: UUID, error: String?) {
        val job = get(jobId)
        job.lastError = error?.take(500)
        job.status = if (job.attempts >= props.maxAttempts) {
            log.warn("print job {} failed {} times, giving up: {}", jobId, job.attempts, error)
            QueuedPrintJob.FAILED
        } else {
            QueuedPrintJob.QUEUED
        }
        jobs.save(job)
    }

    /** Puts a failed job back in the queue, attempts reset. Somebody fixed the printer. */
    @Transactional
    fun retry(jobId: UUID): QueuedPrintJob {
        val job = get(jobId)
        if (job.status == QueuedPrintJob.DONE) {
            throw ApiException.RuleViolation(
                "ALREADY_PRINTED",
                "That job has already printed. Reprint it instead of retrying.",
            )
        }
        job.status = QueuedPrintJob.QUEUED
        job.attempts = 0
        job.lastError = null
        return jobs.save(job)
    }

    /**
     * Queues a fresh copy of a document that already printed.
     *
     * A new job rather than resetting the old one, so the record still shows
     * that a receipt was printed twice. A reprinted receipt is the one a
     * customer might present twice, and the register should say so.
     */
    @Transactional
    fun reprint(jobId: UUID, tillCode: String? = null): QueuedPrintJob {
        val original = get(jobId)
        return jobs.save(
            QueuedPrintJob(
                id = UUID.randomUUID(),
                branchId = original.branchId,
                tillCode = tillCode?.trim()?.takeIf(String::isNotEmpty) ?: original.tillCode,
                template = original.template,
                document = original.document,
            ).also { it.copies = original.copies }
        )
    }

    /**
     * Returns jobs a till took but never reported on.
     *
     * A till that was unplugged mid-print leaves jobs stuck at PRINTING, and
     * nothing else moves them. Called on demand rather than on a timer: which
     * jobs are safe to reprint is a judgement about what came out of the
     * printer, and only the person standing there can make it.
     */
    @Transactional
    fun requeueStale(tillCode: String, olderThan: Instant): Int {
        val stuck = jobs.findByBranchIdAndTillCodeOrderByCreatedAtDesc(Auth.current().branchId, tillCode.trim())
            .filter { it.status == QueuedPrintJob.PRINTING && it.createdAt.isBefore(olderThan) }
        stuck.forEach {
            it.status = QueuedPrintJob.QUEUED
            it.lastError = "Till did not report back; requeued."
        }
        jobs.saveAll(stuck)
        return stuck.size
    }

    // ── Reads ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    fun get(jobId: UUID): QueuedPrintJob {
        val job = jobs.findById(jobId).orElseThrow { ApiException.NotFound("Print job", jobId) }
        if (job.branchId != Auth.current().branchId) throw ApiException.NotFound("Print job", jobId)
        return job
    }

    @Transactional(readOnly = true)
    fun history(tillCode: String): List<QueuedPrintJob> =
        jobs.findByBranchIdAndTillCodeOrderByCreatedAtDesc(Auth.current().branchId, tillCode.trim())

    /** The last thing of a given kind that was printed — "reprint last receipt". */
    @Transactional(readOnly = true)
    fun latest(template: String): QueuedPrintJob? =
        jobs.latestOfTemplate(Auth.current().branchId, template).firstOrNull()

    fun toPayload(job: QueuedPrintJob): PrintJob {
        val document = json.readValue(job.document, PrintDocument::class.java)
        return PrintJob(
            id = job.id,
            template = job.template,
            widthChars = document.widthChars,
            elements = document.elements,
            copies = job.copies.toInt(),
        )
    }
}
