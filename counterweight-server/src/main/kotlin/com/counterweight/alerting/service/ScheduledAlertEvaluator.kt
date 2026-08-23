package com.counterweight.alerting.service

import com.counterweight.alerting.domain.AlertRule
import com.counterweight.alerting.repo.AlertQueryRepository
import com.counterweight.inventory.repo.StockMovementRepository
import com.counterweight.platform.service.ConfigKeys
import com.counterweight.platform.service.BackupService
import com.counterweight.platform.service.ConfigService
import com.counterweight.pricing.service.Money
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit


/**
 * Alerts that depend on the calendar rather than on a movement.
 *
 * Each method here is idempotent and closes its own stale alerts: it computes
 * the set of conditions that hold now and resolves every open alert of that
 * type outside the set. Running one twice in a night changes nothing, which
 * matters because the alternative is a notification centre that grows a
 * duplicate every time somebody restarts the server.
 *
 * Every method takes an explicit branch id. Scheduled work has no signed-in
 * user and therefore no ambient branch, and inferring one would quietly do the
 * wrong thing the day a second branch exists.
 */
@Service
class ScheduledAlertEvaluator(
    private val alerts: AlertService,
    private val queries: AlertQueryRepository,
    private val movements: StockMovementRepository,
    private val config: ConfigService,
    private val backups: BackupService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Stock past its expiry that is still sellable.
     *
     * Critical, because this is stock that must not go over the counter. The
     * nightly sweep in `inventory` writes it off; this is what tells somebody
     * it happened, and catches anything the sweep could not.
     */
    @Transactional(readOnly = true)
    fun evaluateExpiredStock(branchId: Long): Int {
        val expired = queries.lotsExpiringWithin(branchId, 0)
        val keys = expired.map { lot ->
            val key = "${AlertRule.EXPIRED_STOCK}:$branchId:lot${lot.lotId}"
            alerts.raise(
                AlertRequest(
                    ruleType = AlertRule.EXPIRED_STOCK,
                    branchId = branchId,
                    dedupeKey = key,
                    title = "Expired stock: ${lot.productName}",
                    body = "Batch ${lot.lotCode} expired on ${lot.expiresOn} with " +
                        "${lot.qtyBase.stripTrailingZeros().toPlainString()} still on hand. " +
                        "It must not be sold.",
                    subjectType = "stock_lot",
                    subjectId = lot.lotId,
                )
            )
            key
        }.toSet()

        alerts.resolveMissing(branchId, AlertRule.EXPIRED_STOCK, keys)
        return keys.size
    }

    /**
     * Stock approaching expiry, at the configured horizons.
     *
     * Keyed on the lot and the horizon it crossed, so a drum that passes 90,
     * 60, 30 and 7 days raises four alerts over its life rather than one that
     * nobody looks at again after the first week. The horizon is in the key
     * precisely so each crossing is a new condition.
     */
    @Transactional(readOnly = true)
    fun evaluateExpiringStock(branchId: Long): Int {
        val horizons = config.intList(ConfigKeys.EXPIRY_WARN_DAYS, listOf(90, 60, 30, 7)).sorted()
        val widest = horizons.maxOrNull() ?: return 0

        val keys = mutableSetOf<String>()
        queries.lotsExpiringWithin(branchId, widest)
            .filter { it.daysLeft > 0 }
            .forEach { lot ->
                // The tightest horizon the lot has already crossed. Reporting
                // "90 days left" for a drum with 6 days on it would be true and
                // useless.
                val crossed = horizons.filter { lot.daysLeft <= it }.minOrNull() ?: return@forEach
                val key = "${AlertRule.EXPIRY_APPROACHING}:$branchId:lot${lot.lotId}:h$crossed"
                alerts.raise(
                    AlertRequest(
                        ruleType = AlertRule.EXPIRY_APPROACHING,
                        branchId = branchId,
                        dedupeKey = key,
                        title = "Expiring soon: ${lot.productName}",
                        body = "Batch ${lot.lotCode} expires on ${lot.expiresOn} — ${lot.daysLeft} days left, " +
                            "${lot.qtyBase.stripTrailingZeros().toPlainString()} on hand.",
                        subjectType = "stock_lot",
                        subjectId = lot.lotId,
                    )
                )
                keys += key
            }
        // Deliberately no resolveMissing. A lot that sells out stops appearing
        // here, but the warning that it was about to expire was still true when
        // it was raised — and closing it silently would hide the near miss.
        return keys.size
    }

    /**
     * The stored balance disagreeing with the movements behind it.
     *
     * The nightly tripwire on the ledger's central invariant. Anything here
     * means a write bypassed the trigger, which is a correctness failure rather
     * than a business condition — hence critical, and hence no auto-resolution:
     * somebody has to look.
     */
    @Transactional(readOnly = true)
    fun evaluateLedgerDrift(branchId: Long): Boolean {
        val drift = movements.ledgerDriftCount()
        if (drift <= 0) return false

        alerts.raise(
            AlertRequest(
                ruleType = AlertRule.LEDGER_MISMATCH,
                branchId = branchId,
                // Dated, so a drift found tonight is not deduped away by one
                // found last week and never resolved.
                dedupeKey = "${AlertRule.LEDGER_MISMATCH}:$branchId:${LocalDate.now()}",
                title = "Stock ledger does not balance",
                body = "$drift lot balance(s) disagree with their movements. " +
                    "Something wrote to stock_balance without going through the ledger.",
                subjectType = "stock_balance",
            )
        )
        log.error("ledger drift detected on branch {}: {} row(s)", branchId, drift)
        return true
    }

    /** Customers past their agreed terms. */
    @Transactional(readOnly = true)
    fun evaluateOverdueInvoices(branchId: Long): Int {
        val keys = queries.overdueCustomers(branchId, LocalDate.now()).map { row ->
            val key = "${AlertRule.INVOICE_OVERDUE}:$branchId:customer${row.customerId}"
            alerts.raise(
                AlertRequest(
                    ruleType = AlertRule.INVOICE_OVERDUE,
                    branchId = branchId,
                    dedupeKey = key,
                    title = "Overdue: ${row.name}",
                    body = "${Money.round(row.overdueAmount).toPlainString()} overdue, " +
                        "oldest by ${row.daysOverdue} days.",
                    subjectType = "customer",
                    subjectId = row.customerId,
                )
            )
            key
        }.toSet()

        // Paying up closes it without anyone clicking anything.
        alerts.resolveMissing(branchId, AlertRule.INVOICE_OVERDUE, keys)
        return keys.size
    }

    /**
     * Stock that has not sold in a long time.
     *
     * Info severity, and weekly. This is money sitting on a shelf rather than
     * anything going wrong, and treating it with the urgency of an expired
     * pesticide is how a notification centre teaches people to skim.
     */
    @Transactional(readOnly = true)
    fun evaluateDeadStock(branchId: Long): Int {
        val cutoff = Instant.now().minus(config.int(ConfigKeys.DEAD_STOCK_DAYS, 180).toLong(), ChronoUnit.DAYS)
        val keys = queries.deadStock(branchId, cutoff).map { row ->
            val key = "${AlertRule.DEAD_STOCK}:$branchId:product${row.productId}"
            alerts.raise(
                AlertRequest(
                    ruleType = AlertRule.DEAD_STOCK,
                    branchId = branchId,
                    dedupeKey = key,
                    title = "Not moving: ${row.name}",
                    body = "${row.qtyBase.stripTrailingZeros().toPlainString()} on hand, worth " +
                        "${Money.round(row.stockValue).toPlainString()}. " +
                        (row.lastMovedOn?.let { "Last sold $it." } ?: "Never sold."),
                    subjectType = "product",
                    subjectId = row.productId,
                )
            )
            key
        }.toSet()

        alerts.resolveMissing(branchId, AlertRule.DEAD_STOCK, keys)
        return keys.size
    }

    /**
     * No verified backup within the configured window.
     *
     * Critical, and rightly so: §14 calls the loss of this machine the end of
     * the business's records. The check measures from the last *verified* run,
     * not the last successful one — a nightly dump that has never been restored
     * proves the process is alive, not that the file is readable. An unverified
     * backup is a belief.
     *
     * Auto-resolves once a drill passes, so the alert tracks whether the shop
     * is protected right now rather than whether it once was not.
     */
    @Transactional(readOnly = true)
    fun evaluateBackupStaleness(branchId: Long): Boolean {
        val status = backups.status()
        val key = "${AlertRule.BACKUP_STALE}:$branchId"

        if (!status.stale) {
            alerts.autoResolve(key)
            return false
        }
        val detail = status.hoursSinceVerified
            ?.let { "The last verified backup was $it hours ago." }
            ?: "No backup has ever been verified by a restore drill."

        alerts.raise(
            AlertRequest(
                ruleType = AlertRule.BACKUP_STALE,
                branchId = branchId,
                dedupeKey = key,
                title = "Backups are not proven",
                body = listOfNotNull(
                    detail,
                    "The shop's entire records live on this machine.",
                    status.lastError?.let { "Last failure: $it" },
                ).joinToString(" "),
                subjectType = "backup_run",
            )
        )
        log.error("branch {}: no verified backup within {} hours", branchId, status.staleAfterHours)
        return true
    }

}
