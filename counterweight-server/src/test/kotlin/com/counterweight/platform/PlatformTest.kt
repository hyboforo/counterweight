package com.counterweight.platform

import com.counterweight.alerting.domain.AlertRule
import com.counterweight.alerting.service.AlertService
import com.counterweight.alerting.service.ScheduledAlertEvaluator
import com.counterweight.identity.repo.AppUserRepository
import com.counterweight.identity.security.CurrentUser
import com.counterweight.platform.domain.BackupRun
import com.counterweight.platform.service.*
import com.counterweight.pricing.service.CashRounding
import com.counterweight.printing.service.PrintQueueService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.time.Instant

/**
 * Platform: the branch, the shop's settings, and the backups.
 *
 * The configuration tests are about one thing — that a setting changed in the
 * database actually changes what the running system does. Before V9 it did not:
 * `app_config` held rows nothing read while the same settings lived in yml, so
 * an owner could change a rounding increment and watch nothing happen.
 *
 * The backup tests are about the distinction §14 turns on: a dump that
 * succeeded is not a backup until something has restored it.
 */
@SpringBootTest
@Testcontainers
@DisplayName("Platform")
class PlatformTest {

    companion object {
        @Container
        @JvmStatic
        val pg = PostgreSQLContainer("postgres:16")
            .withDatabaseName("cw").withUsername("cw").withPassword("cw")

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", pg::getJdbcUrl)
            registry.add("spring.datasource.username", pg::getUsername)
            registry.add("spring.datasource.password", pg::getPassword)
            registry.add("counterweight.auth.jwt-secret") { "test-secret-that-is-definitely-long-enough-32b+" }
            registry.add("COUNTERWEIGHT_BOOTSTRAP_PASSWORD") { "bootstrap-correct-horse-staple-42" }
        }

        private const val BRANCH = 1L
    }

    @Autowired private lateinit var config: ConfigService
    @Autowired private lateinit var branches: BranchService
    @Autowired private lateinit var backups: BackupService
    @Autowired private lateinit var backupRuns: BackupRunRepository
    @Autowired private lateinit var rounding: CashRounding
    @Autowired private lateinit var printQueue: PrintQueueService
    @Autowired private lateinit var alerts: AlertService
    @Autowired private lateinit var evaluator: ScheduledAlertEvaluator
    @Autowired private lateinit var users: AppUserRepository

    private val fullRights = setOf("CONFIG_MANAGE", "BACKUP_MANAGE", "ALERT_MANAGE", "REPORT_VIEW")

    @BeforeEach
    fun signIn() = signInAs(fullRights)

    private fun signInAs(permissions: Set<String>) {
        val userId = users.findAll().first().id!!
        SecurityContextHolder.getContext().authentication = PreAuthenticatedAuthenticationToken(
            CurrentUser(userId, "test-operator", BRANCH, setOf("ADMIN"), permissions),
            null,
            permissions.map { SimpleGrantedAuthority(it) },
        )
    }

    // ── Branch ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the branch is readable for a receipt header")
    fun branchIsReadable() {
        val branch = branches.current()
        assertThat(branch.code).isEqualTo("MAIN")
        // The name printed at the top of every receipt, statement and delivery
        // note. Pinned to the literal on purpose: it is the shop's own name and
        // a migration that changed it by accident would be found here rather
        // than on a customer's copy.
        assertThat(branch.name).isEqualTo("Bofma Ventures")
    }

    // ── Configuration ──────────────────────────────────────────────────────

    @Test
    @DisplayName("every key the code reads has a row to read")
    fun everyKeyIsSeeded() {
        val seeded = config.all().map { it.key }.toSet()
        val read = setOf(
            ConfigKeys.CURRENCY_CODE, ConfigKeys.ROUNDING_INCREMENT, ConfigKeys.RECEIPT_WIDTH,
            ConfigKeys.AGENT_PORT, ConfigKeys.EXPIRY_WARN_DAYS, ConfigKeys.REORDER_SAFETY_FACTOR,
            ConfigKeys.REORDER_WINDOW_DAYS, ConfigKeys.REORDER_LEAD_TIME_DAYS,
            ConfigKeys.DEAD_STOCK_DAYS,
            ConfigKeys.BACKUP_STALE_HOURS, ConfigKeys.BACKUP_DIRECTORY, ConfigKeys.BACKUP_ENABLED,
        )
        assertThat(read)
            .describedAs("a key with no row silently falls back, and the setting screen cannot show it")
            .allMatch { it in seeded }
    }

    @Test
    @DisplayName("every row in app_config is read by code, and nothing else is there")
    fun noSettingLies() {
        val declared = ConfigKeys::class.java.declaredFields
            .filter { it.type == String::class.java }
            .map { it.isAccessible = true; it.get(ConfigKeys) as String }
            .toSet()

        assertThat(config.all().map { it.key })
            .describedAs("a row nothing reads is a setting that lies: changing it does nothing, silently")
            .allMatch { it in declared }
    }

    @Test
    @DisplayName("the duplicate that could disagree with tax_scheme is gone")
    fun taxSchemeConfigKeyRemoved() {
        assertThat(config.all().map { it.key })
            .describedAs("two sources of truth for which tax the shop charges is one too many")
            .doesNotContain("tax.scheme.code")
    }

    @Test
    @DisplayName("changing the rounding increment changes what the till actually rounds to")
    fun configChangeReachesTheSalePath() {
        assertThat(rounding.increment).isEqualByComparingTo("0.05")
        assertThat(rounding.apply(BigDecimal("41.23"))).isEqualByComparingTo("41.25")

        config.set(ConfigKeys.ROUNDING_INCREMENT, "0.01")

        assertThat(rounding.increment)
            .describedAs("before V9 this changed a row nothing read, and the till kept rounding to 5p")
            .isEqualByComparingTo("0.01")
        assertThat(rounding.apply(BigDecimal("41.23"))).isEqualByComparingTo("41.23")

        config.set(ConfigKeys.ROUNDING_INCREMENT, "0.05")
    }

    @Test
    @DisplayName("changing the paper width reaches the print queue")
    fun paperWidthIsConfigurable() {
        assertThat(printQueue.widthChars).isEqualTo(48)

        config.set(ConfigKeys.RECEIPT_WIDTH, "32")
        assertThat(printQueue.widthChars)
            .describedAs("swapping an 80mm printer for a 58mm one is a setting, not a release")
            .isEqualTo(32)

        config.set(ConfigKeys.RECEIPT_WIDTH, "48")
    }

    @Test
    @DisplayName("a value of the wrong type is refused before it is stored")
    fun typesAreValidated() {
        assertThatThrownBy { config.set(ConfigKeys.ROUNDING_INCREMENT, "five pesewas") }
            .hasMessageContaining("not a valid number")

        assertThatThrownBy { config.set(ConfigKeys.EXPIRY_WARN_DAYS, "90, 60") }
            .hasMessageContaining("not a valid json")

        assertThat(config.number(ConfigKeys.ROUNDING_INCREMENT, BigDecimal.ONE))
            .describedAs("a rejected write must not have half-applied")
            .isEqualByComparingTo("0.05")
    }

    @Test
    @DisplayName("a key that does not exist cannot be created from the settings screen")
    fun unknownKeysAreRefused() {
        assertThatThrownBy { config.set("currency.rounding.incremnt", "0.10") }
            .describedAs("typos sitting next to real keys are how a shop thinks it changed something")
            .hasMessageContaining("was not found")
    }

    @Test
    @DisplayName("changing a setting needs CONFIG_MANAGE")
    fun configChangesArePermissioned() {
        signInAs(setOf("REPORT_VIEW"))
        assertThatThrownBy { config.set(ConfigKeys.ROUNDING_INCREMENT, "0.10") }
            .isInstanceOf(AccessDeniedException::class.java)
    }

    @Test
    @DisplayName("a missing key falls back rather than stopping the till")
    fun missingKeysFallBack() {
        assertThat(config.int("nothing.reads.this", 7))
            .describedAs("a shop that cannot sell because a setting is absent is the worse outcome")
            .isEqualTo(7)
    }

    // ── Backups ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("with no backup at all, the shop counts as unprotected")
    fun neverBackedUpIsStale() {
        backupRuns.deleteAll()

        val status = backups.status()
        assertThat(status.lastVerifiedAt).isNull()
        assertThat(status.stale)
            .describedAs("no evidence any backup would restore is not the same as being fine")
            .isTrue()
    }

    @Test
    @DisplayName("a successful dump that was never restored still does not count")
    fun unverifiedBackupsDoNotCount() {
        backupRuns.deleteAll()
        val run = backups.start(BackupRun.DUMP, "/tmp/test.dump")
        backups.succeed(run.id!!, 1024L, "abc123")

        val status = backups.status()
        assertThat(status.lastDumpAt).isNotNull
        assertThat(status.lastVerifiedAt).isNull()
        assertThat(status.stale)
            .describedAs("§14: an unverified backup is a belief, not a backup")
            .isTrue()
    }

    @Test
    @DisplayName("a verified dump is what makes the shop protected")
    fun verifiedBackupsClearStaleness() {
        backupRuns.deleteAll()
        val run = backups.start(BackupRun.DUMP, "/tmp/test.dump")
        backups.succeed(run.id!!, 1024L, "abc123")
        backups.markVerified(run.id!!)

        val status = backups.status()
        assertThat(status.stale).isFalse()
        assertThat(status.hoursSinceVerified).isEqualTo(0)
    }

    @Test
    @DisplayName("a failed restore drill leaves the backup unverified and says why")
    fun failedDrillRecordsWhy() {
        backupRuns.deleteAll()
        val run = backups.start(BackupRun.DUMP, "/tmp/test.dump")
        backups.succeed(run.id!!, 1024L, "abc123")
        backups.failVerification(run.id!!, "checksum has changed since the dump was taken")

        val stored = backups.get(run.id!!)
        assertThat(stored.verifiedAt).isNull()
        assertThat(stored.verifyError).contains("checksum")
        assertThat(stored.isUsable).isFalse()
        assertThat(backups.status().stale).isTrue()
    }

    @Test
    @DisplayName("a backup this application did not perform can still be recorded")
    fun externalBackupsCanBeRecorded() {
        backupRuns.deleteAll()
        val run = backups.record(BackupRun.OFFSITE, "USB-A", 2048L, "def456", verified = true)

        assertThat(run.kind).isEqualTo(BackupRun.OFFSITE)
        assertThat(run.isUsable)
            .describedAs("the off-site rotation is somebody carrying a disk home; the register should still know")
            .isTrue()
    }

    @Test
    @DisplayName("recording a backup needs BACKUP_MANAGE")
    fun recordingIsPermissioned() {
        signInAs(setOf("REPORT_VIEW"))
        assertThatThrownBy { backups.record(BackupRun.DUMP, "/tmp/x", null, null, true) }
            .isInstanceOf(AccessDeniedException::class.java)
    }

    // ── The alert that ties it together ────────────────────────────────────

    @Test
    @DisplayName("no verified backup raises a critical alert, and verifying one clears it")
    fun stalenessRaisesAndClears() {
        backupRuns.deleteAll()
        val key = "${AlertRule.BACKUP_STALE}:$BRANCH"

        assertThat(evaluator.evaluateBackupStaleness(BRANCH)).isTrue()
        val alert = alerts.open(includeSnoozed = true).first { it.dedupeKey == key }
        assertThat(alert.severity).isEqualTo(AlertRule.CRITICAL)
        assertThat(alert.body).contains("has ever been verified")

        val run = backups.start(BackupRun.DUMP, "/tmp/test.dump")
        backups.succeed(run.id!!, 1024L, "abc123")
        backups.markVerified(run.id!!)

        assertThat(evaluator.evaluateBackupStaleness(BRANCH)).isFalse()
        assertThat(alerts.open(includeSnoozed = true).map { it.dedupeKey })
            .describedAs("proving a backup restores is what closes it, and nobody has to click")
            .doesNotContain(key)
    }

    @Test
    @DisplayName("the staleness window is configurable")
    fun stalenessWindowIsConfigurable() {
        backupRuns.deleteAll()
        val run = backups.start(BackupRun.DUMP, "/tmp/test.dump")
        backups.succeed(run.id!!, 1024L, "abc123")
        backups.markVerified(run.id!!)
        assertThat(backups.status().staleAfterHours).isEqualTo(24)

        config.set(ConfigKeys.BACKUP_STALE_HOURS, "1")
        assertThat(backups.status().staleAfterHours).isEqualTo(1)

        config.set(ConfigKeys.BACKUP_STALE_HOURS, "24")
    }

    @Test
    @DisplayName("backup status is readable without a permission, because everyone should see it")
    fun statusIsOpenToAnySignedInUser() {
        signInAs(setOf("SALE_CREATE"))
        assertThat(backups.status())
            .describedAs("'are we protected' should not need a permission to ask")
            .isNotNull
    }
}
