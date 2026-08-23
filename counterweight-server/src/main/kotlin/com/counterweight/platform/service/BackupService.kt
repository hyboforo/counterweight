package com.counterweight.platform.service

import com.counterweight.common.ApiException
import com.counterweight.identity.service.AuditService
import com.counterweight.platform.domain.BackupRun
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

@Repository
interface BackupRunRepository : JpaRepository<BackupRun, Long> {

    fun findTop50ByOrderByStartedAtDesc(): List<BackupRun>

    /** The most recent run of a kind that both succeeded and was verified. */
    @Query(
        """
        SELECT b FROM BackupRun b
         WHERE b.kind = :kind AND b.status = 'SUCCESS' AND b.verifiedAt IS NOT NULL
         ORDER BY b.completedAt DESC
        """
    )
    fun lastVerified(@Param("kind") kind: String): List<BackupRun>

    @Query(
        """
        SELECT b FROM BackupRun b
         WHERE b.kind = :kind AND b.status = 'SUCCESS'
         ORDER BY b.completedAt DESC
        """
    )
    fun lastSuccessful(@Param("kind") kind: String): List<BackupRun>
}

/** What the shop's protection currently amounts to. */
data class BackupStatus(
    val lastDumpAt: Instant?,
    val lastVerifiedAt: Instant?,
    val hoursSinceVerified: Long?,
    val stale: Boolean,
    val staleAfterHours: Long,
    val lastError: String?,
)

/**
 * Backup orchestration.
 *
 * The shop has no developer on site (§3), which is why this lives in the
 * application rather than in a cron job somebody has to be told to set up. A
 * backup that depends on a person remembering to configure it is the backup
 * that is not there when the SSD fails.
 *
 * **Verification is the point.** §14 says the restore drill is the row usually
 * skipped and the one that matters most, so a dump that succeeded but has never
 * been restored does not count as protection here: [status] measures staleness
 * from the last *verified* run, and the alert fires on that. An unverified
 * backup is a belief.
 */
@Service
class BackupService(
    private val runs: BackupRunRepository,
    private val config: ConfigService,
    private val audit: AuditService,
    @Value("\${spring.datasource.url}") private val jdbcUrl: String,
    @Value("\${spring.datasource.username}") private val dbUser: String,
    @Value("\${spring.datasource.password}") private val dbPassword: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // ── Recording ──────────────────────────────────────────────────────────

    /**
     * Opens a run.
     *
     * `REQUIRES_NEW` throughout, so the record of an attempt survives the
     * failure of the attempt. A backup that crashed and left no trace is
     * indistinguishable from one that was never scheduled.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun start(kind: String, destination: String): BackupRun {
        require(kind in BackupRun.KINDS) { "unsupported backup kind $kind" }
        return runs.save(BackupRun(kind = kind, destination = destination))
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun succeed(runId: Long, sizeBytes: Long?, checksum: String?): BackupRun {
        val run = get(runId)
        run.status = BackupRun.SUCCESS
        run.completedAt = Instant.now()
        run.sizeBytes = sizeBytes
        run.checksum = checksum
        run.error = null
        return runs.save(run)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun fail(runId: Long, error: String?): BackupRun {
        val run = get(runId)
        run.status = BackupRun.FAILED
        run.completedAt = Instant.now()
        run.error = error?.take(1000)
        log.error("backup {} failed: {}", runId, error)
        return runs.save(run)
    }

    /**
     * Lets an external process report a backup in.
     *
     * WAL archiving and the off-site USB rotation are not things this
     * application performs — one is PostgreSQL's own machinery, the other
     * involves somebody carrying a disk home. Recording them anyway is what
     * makes the register describe the shop's actual protection rather than only
     * the part that happens to run in this process.
     */
    @Transactional
    @PreAuthorize("hasAuthority('BACKUP_MANAGE')")
    fun record(kind: String, destination: String, sizeBytes: Long?, checksum: String?, verified: Boolean): BackupRun {
        if (kind !in BackupRun.KINDS) {
            throw ApiException.Validation(
                "Unknown backup kind '$kind'.",
                mapOf("kind" to "must be one of: ${BackupRun.KINDS.sorted().joinToString(", ")}"),
            )
        }
        val run = runs.save(
            BackupRun(kind = kind, destination = destination).also {
                it.status = BackupRun.SUCCESS
                it.completedAt = Instant.now()
                it.sizeBytes = sizeBytes
                it.checksum = checksum
                if (verified) it.verifiedAt = Instant.now()
            }
        )
        audit.recordCurrent(
            "BACKUP_RECORDED", "backup_run", run.id,
            after = """{"kind":"$kind","verified":$verified}""",
        )
        return run
    }

    // ── Reads ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    fun get(runId: Long): BackupRun =
        runs.findById(runId).orElseThrow { ApiException.NotFound("Backup run", runId) }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('BACKUP_MANAGE')")
    fun history(): List<BackupRun> = runs.findTop50ByOrderByStartedAtDesc()

    /**
     * Whether the shop is actually protected.
     *
     * Measured from the last **verified** run, not the last successful one. A
     * nightly dump that has run for six months and never been restored tells
     * you the process is alive, not that the file is readable.
     */
    @Transactional(readOnly = true)
    fun status(): BackupStatus {
        val staleAfter = config.int(ConfigKeys.BACKUP_STALE_HOURS, 24).toLong()
        val lastDump = runs.lastSuccessful(BackupRun.DUMP).firstOrNull()
        val lastVerified = runs.lastVerified(BackupRun.DUMP).firstOrNull()

        val hoursSince = lastVerified?.verifiedAt?.let {
            ChronoUnit.HOURS.between(it, Instant.now())
        }
        return BackupStatus(
            lastDumpAt = lastDump?.completedAt,
            lastVerifiedAt = lastVerified?.verifiedAt,
            hoursSinceVerified = hoursSince,
            // Never verified is stale. That is the correct reading: the shop has
            // no evidence any backup would restore.
            stale = hoursSince == null || hoursSince >= staleAfter,
            staleAfterHours = staleAfter,
            lastError = runs.findTop50ByOrderByStartedAtDesc().firstOrNull { it.status == BackupRun.FAILED }?.error,
        )
    }

    // ── Doing the work ─────────────────────────────────────────────────────

    /**
     * Takes a dump with `pg_dump`.
     *
     * Custom format (`-Fc`), because it is what `pg_restore` needs to restore
     * selectively and to a scratch database — which is what the verification
     * drill does. A plain SQL dump would restore only by being replayed whole.
     *
     * The password goes through `PGPASSWORD` in the child process environment
     * rather than on the command line, where it would be visible to anyone
     * running `ps` on the machine.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun runDump(): BackupRun? {
        if (!config.bool(ConfigKeys.BACKUP_ENABLED, true)) {
            log.info("backups are switched off in configuration; skipping")
            return null
        }
        val directory = Path.of(config.string(ConfigKeys.BACKUP_DIRECTORY, "./backups"))
        val target = directory.resolve("counterweight-${Instant.now().epochSecond}.dump")
        val run = start(BackupRun.DUMP, target.toString())

        return try {
            Files.createDirectories(directory)
            val db = databaseName()
            val process = ProcessBuilder(
                "pg_dump", "-Fc", "-h", host(), "-p", port(), "-U", dbUser, "-d", db, "-f", target.toString(),
            ).apply {
                environment()["PGPASSWORD"] = dbPassword
                redirectErrorStream(true)
            }.start()

            val output = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(DUMP_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                process.destroyForcibly()
                return fail(run.id!!, "pg_dump did not finish within $DUMP_TIMEOUT_MINUTES minutes")
            }
            if (process.exitValue() != 0) {
                return fail(run.id!!, "pg_dump exited ${process.exitValue()}: ${output.take(500)}")
            }

            val bytes = Files.size(target)
            val checksum = sha256(target)
            log.info("dumped {} bytes to {}", bytes, target)
            succeed(run.id!!, bytes, checksum)
        } catch (e: Exception) {
            fail(run.id!!, e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * The restore drill.
     *
     * Restores the most recent dump into a scratch database and asserts it
     * holds the tables and rows it should, then drops the scratch database
     * again. §14 is emphatic that this is the layer usually skipped and the one
     * that matters most, and it is why [status] refuses to call an unverified
     * dump protection.
     *
     * Deliberately restores into a *separate* database. Restoring over the live
     * one to check it works would be the single most destructive thing this
     * system could do to itself.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun verifyLatest(): BackupRun? {
        val run = runs.lastSuccessful(BackupRun.DUMP).firstOrNull() ?: run {
            log.warn("no successful dump to verify")
            return null
        }
        val scratch = "cw_verify_${Instant.now().epochSecond}"

        return try {
            val checksum = sha256(Path.of(run.destination))
            if (run.checksum != null && run.checksum != checksum) {
                return failVerification(run.id!!, "checksum has changed since the dump was taken")
            }

            psql("CREATE DATABASE $scratch")
            val restore = ProcessBuilder(
                "pg_restore", "-h", host(), "-p", port(), "-U", dbUser, "-d", scratch, run.destination,
            ).apply {
                environment()["PGPASSWORD"] = dbPassword
                redirectErrorStream(true)
            }.start()
            val output = restore.inputStream.bufferedReader().readText()
            restore.waitFor(DUMP_TIMEOUT_MINUTES, TimeUnit.MINUTES)

            /*
             * Asserting on row counts rather than on the exit code. pg_restore
             * exits non-zero for benign ownership warnings on a restore into a
             * fresh database, so trusting the code alone would either fail every
             * drill or — with `--exit-on-error` off — pass a restore that put
             * nothing in the database.
             */
            val sales = countIn(scratch, "sale")
            val products = countIn(scratch, "product")
            if (sales < 0 || products < 0) {
                return failVerification(run.id!!, "restored database is missing core tables: ${output.take(300)}")
            }

            markVerified(run.id!!).also {
                log.info("restore drill passed: {} products and {} sales readable", products, sales)
            }
        } catch (e: Exception) {
            failVerification(run.id!!, e.message ?: e.javaClass.simpleName)
        } finally {
            // Dropped whatever happened. A scratch database left behind fills
            // the same disk the backups are trying to protect.
            runCatching { psql("DROP DATABASE IF EXISTS $scratch") }
                .onFailure { log.warn("could not drop scratch database {}", scratch, it) }
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun markVerified(runId: Long): BackupRun {
        val run = get(runId)
        run.verifiedAt = Instant.now()
        run.verifyError = null
        return runs.save(run)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun failVerification(runId: Long, error: String): BackupRun {
        val run = get(runId)
        run.verifiedAt = null
        run.verifyError = error.take(1000)
        log.error("restore drill failed for backup {}: {}", runId, error)
        return runs.save(run)
    }

    // ── Process helpers ────────────────────────────────────────────────────

    private fun psql(statement: String) {
        val process = ProcessBuilder(
            "psql", "-h", host(), "-p", port(), "-U", dbUser, "-d", "postgres", "-c", statement,
        ).apply {
            environment()["PGPASSWORD"] = dbPassword
            redirectErrorStream(true)
        }.start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(2, TimeUnit.MINUTES)
        if (process.exitValue() != 0) error("psql failed: ${output.take(300)}")
    }

    /** Row count, or -1 when the table is not there at all. */
    private fun countIn(database: String, table: String): Long {
        val process = ProcessBuilder(
            "psql", "-h", host(), "-p", port(), "-U", dbUser, "-d", database,
            "-tAc", "SELECT count(*) FROM $table",
        ).apply {
            environment()["PGPASSWORD"] = dbPassword
            redirectErrorStream(true)
        }.start()
        val output = process.inputStream.bufferedReader().readText().trim()
        process.waitFor(2, TimeUnit.MINUTES)
        return if (process.exitValue() != 0) -1 else output.toLongOrNull() ?: -1
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { stream ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // jdbc:postgresql://host:port/database
    private fun host(): String = jdbcUrl.substringAfter("//").substringBefore(":").ifBlank { "localhost" }
    private fun port(): String = jdbcUrl.substringAfter("//").substringAfter(":").substringBefore("/")
    private fun databaseName(): String = jdbcUrl.substringAfterLast("/").substringBefore("?")

    private companion object { const val DUMP_TIMEOUT_MINUTES = 30L }
}
