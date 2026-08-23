package com.counterweight.platform.web

import com.counterweight.common.SafeText
import com.counterweight.platform.domain.AppConfig
import com.counterweight.platform.domain.BackupRun
import com.counterweight.platform.domain.Branch
import com.counterweight.platform.service.BackupService
import com.counterweight.platform.service.BackupStatus
import com.counterweight.platform.service.BranchService
import com.counterweight.platform.service.ConfigService
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.time.Instant

// ── Requests ───────────────────────────────────────────────────────────────

data class SetConfigRequest(
    @field:NotBlank(message = "is required")
    @field:Size(max = 2000, message = "is too long")
    @field:SafeText
    val value: String,
)

data class RecordBackupRequest(
    @field:Pattern(regexp = "^(DUMP|WAL_ARCHIVE|OFFSITE)$", message = "must be DUMP, WAL_ARCHIVE or OFFSITE")
    val kind: String,

    @field:NotBlank(message = "is required")
    @field:Size(max = 500, message = "is too long")
    @field:SafeText
    val destination: String,

    val sizeBytes: Long? = null,

    @field:Size(max = 128, message = "is too long")
    @field:Pattern(regexp = "^[a-fA-F0-9]*$", message = "should be a hex checksum")
    val checksum: String? = null,

    /** Only true if whoever reports it actually restored and checked the copy. */
    val verified: Boolean = false,
)

// ── Responses ──────────────────────────────────────────────────────────────

data class ConfigView(
    val key: String, val value: String, val valueType: String,
    val description: String?, val updatedAt: Instant,
)

data class BranchView(
    val id: Long, val code: String, val name: String,
    val address: String?, val phone: String?, val isActive: Boolean,
)

data class BackupRunView(
    val id: Long, val kind: String, val destination: String, val status: String,
    val startedAt: Instant, val completedAt: Instant?, val sizeBytes: Long?,
    val verifiedAt: Instant?, val error: String?, val verifyError: String?,
)

private fun AppConfig.toView() = ConfigView(key, value, valueType, description, updatedAt)
private fun Branch.toView() = BranchView(id!!, code, name, address, phone, isActive)
private fun BackupRun.toView() = BackupRunView(
    id!!, kind, destination, status, startedAt, completedAt, sizeBytes, verifiedAt, error, verifyError,
)

// ── Controllers ────────────────────────────────────────────────────────────

/**
 * The shop's settings.
 *
 * Every key here corresponds to code that reads it, which is why keys cannot be
 * created from this endpoint — only set. A settings screen that accepts
 * arbitrary keys accumulates typos sitting next to the real ones, and a shop
 * convinced it changed something when it changed nothing.
 */
@RestController
@RequestMapping("/api/config")
@PreAuthorize("hasAuthority('CONFIG_MANAGE')")
class ConfigController(private val config: ConfigService) {

    @GetMapping
    fun all(): List<ConfigView> = config.all().map { it.toView() }

    @PutMapping("/{key}")
    fun set(@PathVariable key: String, @Valid @RequestBody body: SetConfigRequest): ConfigView =
        config.set(key, body.value).toView()

    /** Drops the cache, for a value changed in the database by hand. */
    @PostMapping("/reload")
    fun reload(): Map<String, String> {
        config.reload()
        return mapOf("status" to "reloaded")
    }
}

@RestController
@RequestMapping("/api/branch")
class BranchController(private val branches: BranchService) {

    /** The shop, for a receipt header or a statement letterhead. */
    @GetMapping
    fun current(): BranchView = branches.current().toView()
}

/**
 * Backups.
 *
 * [status] is readable by anyone signed in, deliberately. §14 calls losing this
 * machine the end of the business's records, so "are we protected" is not a
 * question that should need a permission — the dashboard shows it to whoever is
 * looking. Running and recording backups needs `BACKUP_MANAGE`.
 */
@RestController
@RequestMapping("/api/backups")
class BackupController(private val backups: BackupService) {

    @GetMapping("/status")
    fun status(): BackupStatus = backups.status()

    @GetMapping
    fun history(): List<BackupRunView> = backups.history().map { it.toView() }

    /** Takes a dump now, rather than waiting for 01:00. */
    @PostMapping("/run")
    @PreAuthorize("hasAuthority('BACKUP_MANAGE')")
    @ResponseStatus(HttpStatus.CREATED)
    fun run(): BackupRunView? = backups.runDump()?.toView()

    /**
     * Runs the restore drill against the latest dump.
     *
     * The layer §14 says is usually skipped. Restores into a scratch database
     * and drops it again — never over the live one, which would be the most
     * destructive thing this system could do to itself.
     */
    @PostMapping("/verify")
    @PreAuthorize("hasAuthority('BACKUP_MANAGE')")
    fun verify(): BackupRunView? = backups.verifyLatest()?.toView()

    /**
     * Records a backup this application did not perform.
     *
     * WAL archiving is PostgreSQL's own machinery and the off-site rotation
     * involves somebody carrying a disk home. Recording them makes the register
     * describe the shop's real protection rather than only the part that runs
     * in this process.
     */
    @PostMapping("/record")
    @PreAuthorize("hasAuthority('BACKUP_MANAGE')")
    @ResponseStatus(HttpStatus.CREATED)
    fun record(@Valid @RequestBody body: RecordBackupRequest): BackupRunView =
        backups.record(body.kind, body.destination, body.sizeBytes, body.checksum, body.verified).toView()
}
