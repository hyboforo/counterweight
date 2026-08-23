package com.counterweight.platform.schedule

import com.counterweight.platform.service.BackupService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * The backup cadence of §14.
 *
 * Timed for when the shop is shut, and to finish before the reporting views
 * refresh at 02:00 — a dump competing with a matview rebuild on the same modest
 * mini-PC would slow both.
 *
 * Each job swallows its own failure. A scheduler thread that dies takes every
 * later job with it, and the failure is recorded on the run either way, which
 * is what the staleness alert reads.
 */
@Component
class BackupSchedules(private val backups: BackupService) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 01:00 nightly — pg_dump to a second physical disk. */
    @Scheduled(cron = "0 0 1 * * *")
    fun nightlyDump() {
        runCatching { backups.runDump() }
            .onFailure { log.error("nightly dump could not be started", it) }
    }

    /**
     * Sunday 03:00 — the restore drill.
     *
     * Weekly rather than nightly because it restores a whole database, and
     * after the matview refresh so the two do not compete. This is the layer
     * §14 says is usually skipped; putting it on a schedule is the only way it
     * does not become the thing somebody meant to set up.
     */
    @Scheduled(cron = "0 0 3 * * SUN")
    fun weeklyRestoreDrill() {
        runCatching { backups.verifyLatest() }
            .onFailure { log.error("restore drill could not be started", it) }
    }
}
