package com.counterweight.alerting.schedule

import com.counterweight.alerting.service.ReorderPointService
import com.counterweight.alerting.service.ScheduledAlertEvaluator
import com.counterweight.inventory.service.InventoryService
import com.counterweight.platform.service.BranchRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * The clock-driven half of alerting.
 *
 * Times are staggered rather than all at midnight: the expiry sweep has to
 * write off stock *before* anything reports on what expired, and the reorder
 * recompute has to finish before the morning briefing reads reorder points. A
 * single 00:00 batch would leave the order to chance.
 *
 * Every job iterates branches explicitly. Scheduled work has no signed-in user
 * and therefore no ambient branch, and a job that quietly assumed branch 1
 * would do the wrong thing the day a second one exists.
 *
 * Each job swallows its own failures. One branch's bad night must not stop the
 * others being evaluated, and a scheduler thread that dies takes every later
 * job with it.
 */
@Component
class AlertSchedules(
    private val evaluator: ScheduledAlertEvaluator,
    private val reorderPoints: ReorderPointService,
    private val inventory: InventoryService,
    private val branches: BranchRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 00:05 — write off what expired overnight.
     *
     * First, because everything below reports on the state it leaves. Expired
     * agro-chemical stock has to stop being sellable at midnight rather than
     * when somebody remembers (§2.2).
     */
    @Scheduled(cron = "0 5 0 * * *")
    fun sweepExpiredStock() = eachBranch("expiry sweep") { branchId ->
        // System-posted movements are attributed to the bootstrap admin; there
        // is no signed-in user at five past midnight.
        val systemUserId = 1L
        val written = inventory.sweepExpiredStock(systemUserId)
        if (written > 0) log.warn("branch {}: wrote off {} expired lot(s)", branchId, written)
    }

    /** 00:15 — what expired, what is about to, and whether the ledger balances. */
    @Scheduled(cron = "0 15 0 * * *")
    fun nightlyStockAlerts() = eachBranch("nightly stock alerts") { branchId ->
        evaluator.evaluateExpiredStock(branchId)
        evaluator.evaluateExpiringStock(branchId)
        evaluator.evaluateLedgerDrift(branchId)
    }

    /** 00:30 — reorder points, from the trailing window (§11.2). */
    @Scheduled(cron = "0 30 0 * * *")
    fun recomputeReorderPoints() = eachBranch("reorder recompute") { branchId ->
        val result = reorderPoints.recompute(branchId)
        result.diverged.forEach { log.info("branch {}: reorder divergence — {}", branchId, it) }
    }

    /** 06:00 — who is late paying. Daily, before the shop opens. */
    @Scheduled(cron = "0 0 6 * * *")
    fun dailyReceivablesAlerts() = eachBranch("overdue invoices") { branchId ->
        evaluator.evaluateOverdueInvoices(branchId)
    }

    /**
     * Monday 01:00 — stock that has not moved.
     *
     * Weekly because it is money on a shelf rather than something going wrong,
     * and a daily reminder of it is the definition of noise.
     */
    @Scheduled(cron = "0 0 1 * * MON")
    fun weeklyDeadStock() = eachBranch("dead stock") { branchId ->
        evaluator.evaluateDeadStock(branchId)
    }

    /**
     * Hourly — is the shop actually protected.
     *
     * More often than the other checks because §14 calls this the largest
     * single risk, and because the window it measures against is measured in
     * hours rather than days.
     */
    @Scheduled(cron = "0 30 * * * *")
    fun backupStaleness() = eachBranch("backup staleness") { branchId ->
        evaluator.evaluateBackupStaleness(branchId)
    }

    private inline fun eachBranch(job: String, action: (Long) -> Unit) {
        branches.findAll().filter { it.isActive }.forEach { branch ->
            runCatching { action(branch.id!!) }
                .onFailure { log.error("{} failed for branch {}", job, branch.code, it) }
        }
    }
}
