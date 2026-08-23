package com.counterweight.alerting.service

import com.counterweight.alerting.domain.Alert
import com.counterweight.alerting.domain.AlertRule
import com.counterweight.alerting.domain.NotificationOutbox
import com.counterweight.alerting.repo.AlertRepository
import com.counterweight.alerting.repo.AlertRuleRepository
import com.counterweight.alerting.repo.NotificationOutboxRepository
import com.counterweight.common.ApiException
import com.counterweight.identity.security.Auth
import com.counterweight.identity.service.AuditService
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

/** What an evaluator wants raised. */
data class AlertRequest(
    val ruleType: String,
    val branchId: Long,
    /** Identifies the *condition*, not the occurrence. */
    val dedupeKey: String,
    val title: String,
    val body: String,
    val subjectType: String? = null,
    val subjectId: Long? = null,
)

data class SeverityBadges(val critical: Long, val warning: Long, val info: Long) {
    val total: Long get() = critical + warning + info
}

/**
 * Raising, resolving and quieting alerts.
 *
 * The whole module exists under one constraint: a system that cries daily gets
 * ignored, and then the one that mattered gets ignored with it (§11.1). Three
 * things fight that here, and none of them is optional.
 *
 *  1. **Dedupe.** One open alert per condition, enforced by a partial unique
 *     index. Re-raising while it is still open updates nothing and notifies
 *     nobody.
 *  2. **Auto-resolution.** Restocking above the reorder point closes the
 *     low-stock alert without anyone clicking anything. An alert only a human
 *     can close is one that accumulates until the list is worthless.
 *  3. **Snooze.** "I know, the order comes Thursday" — without disabling the
 *     rule and forgetting to switch it back on.
 */
@Service
class AlertService(
    private val rules: AlertRuleRepository,
    private val alerts: AlertRepository,
    private val outbox: NotificationOutboxRepository,
    private val audit: AuditService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // ── Raising ────────────────────────────────────────────────────────────

    /**
     * Raises an alert if the condition is not already open.
     *
     * Runs in its own transaction. An alert is a side observation about work
     * that is happening elsewhere — a sale, a stock movement — and it must
     * neither fail that work nor vanish when that work rolls back. A low-stock
     * warning is still true even if the sale that revealed it was abandoned.
     *
     * Returns null when the condition was already open, when no rule of that
     * type is enabled, or when a concurrent evaluator won the race.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun raise(request: AlertRequest): Alert? {
        val rule = rules.activeFor(request.ruleType, request.branchId).firstOrNull()
            ?: return null   // nobody asked to be told about this

        alerts.findByDedupeKeyAndResolvedAtIsNull(request.dedupeKey)?.let { return null }

        val alert = Alert(
            ruleId = rule.id!!,
            branchId = request.branchId,
            dedupeKey = request.dedupeKey,
            severity = rule.severity,
            title = request.title,
            body = request.body,
        ).also {
            it.subjectType = request.subjectType
            it.subjectId = request.subjectId
        }

        return try {
            val saved = alerts.save(alert)
            queueNotifications(rule, saved)
            log.info("raised {} alert {}", rule.severity, request.dedupeKey)
            saved
        } catch (e: DataIntegrityViolationException) {
            /*
             * The partial unique index fired: another evaluator raised the same
             * condition between the check above and this insert. That is the
             * index doing exactly its job, and losing the race is the correct
             * outcome — one alert exists, which is what was wanted.
             */
            log.debug("alert {} was raised concurrently; leaving the existing one", request.dedupeKey)
            null
        }
    }

    /**
     * Closes an alert because the condition stopped being true.
     *
     * Quiet by design — no audit entry and no notification. Auto-resolution
     * happens constantly as stock moves, and recording each one would bury the
     * acknowledgements that reflect an actual decision by an actual person.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun autoResolve(dedupeKey: String): Boolean {
        val alert = alerts.findByDedupeKeyAndResolvedAtIsNull(dedupeKey) ?: return false
        alert.resolvedAt = Instant.now()
        alerts.save(alert)
        log.debug("auto-resolved {}", dedupeKey)
        return true
    }

    /**
     * Closes every open alert of a type whose key is not in [stillTrue].
     *
     * How a scheduled evaluator cleans up after itself: it computes the set of
     * conditions that hold now, and everything else of that type is over. The
     * alternative — leaving them until somebody notices — is how the list stops
     * describing the shop.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun resolveMissing(branchId: Long, ruleType: String, stillTrue: Set<String>): Int {
        val stale = alerts.openOfType(branchId, ruleType).filter { it.dedupeKey !in stillTrue }
        stale.forEach { it.resolvedAt = Instant.now() }
        alerts.saveAll(stale)
        return stale.size
    }

    // ── The notification centre ────────────────────────────────────────────

    @Transactional(readOnly = true)
    fun open(includeSnoozed: Boolean = false): List<Alert> =
        alerts.openFor(Auth.current().branchId, includeSnoozed)

    @Transactional(readOnly = true)
    fun badges(): SeverityBadges {
        val counts = alerts.badgeCounts(Auth.current().branchId).associate { it.severity to it.count }
        return SeverityBadges(
            critical = counts[AlertRule.CRITICAL] ?: 0,
            warning = counts[AlertRule.WARNING] ?: 0,
            info = counts[AlertRule.INFO] ?: 0,
        )
    }

    @Transactional(readOnly = true)
    fun get(id: Long): Alert {
        val alert = alerts.findById(id).orElseThrow { ApiException.NotFound("Alert", id) }
        if (alert.branchId != Auth.current().branchId) throw ApiException.NotFound("Alert", id)
        return alert
    }

    @Transactional(readOnly = true)
    fun raisedSince(since: Instant): List<Alert> = alerts.raisedSince(Auth.current().branchId, since)

    /**
     * Someone has seen it.
     *
     * Acknowledging does not resolve. The stock is still below the reorder
     * point after somebody reads that it is, and an acknowledgement that closed
     * the alert would let a real condition disappear because a person clicked
     * a button.
     */
    @Transactional
    fun acknowledge(id: Long): Alert {
        val alert = get(id)
        if (alert.acknowledgedAt != null) return alert
        alert.acknowledgedAt = Instant.now()
        alert.acknowledgedBy = Auth.current().id
        val saved = alerts.save(alert)
        audit.recordCurrent("ALERT_ACKNOWLEDGED", "alert", id, after = """{"key":"${alert.dedupeKey}"}""")
        return saved
    }

    /** Quiets an alert for a while without disabling the rule behind it. */
    @Transactional
    fun snooze(id: Long, duration: Duration, reason: String?): Alert {
        val alert = get(id)
        if (duration.isNegative || duration.isZero) {
            throw ApiException.Validation(
                "Snooze for how long?",
                mapOf("duration" to "must be more than zero"),
            )
        }
        if (duration > MAX_SNOOZE) {
            // A month-long snooze is a disabled rule wearing a disguise, and
            // the disguise is the problem: nobody remembers to undo it.
            throw ApiException.Validation(
                "Snooze for at most ${MAX_SNOOZE.toDays()} days. To silence it for longer, disable the rule.",
                mapOf("duration" to "is longer than ${MAX_SNOOZE.toDays()} days"),
            )
        }
        alert.snoozedUntil = Instant.now().plus(duration)
        val saved = alerts.save(alert)
        audit.recordCurrent(
            "ALERT_SNOOZED", "alert", id,
            after = """{"until":"${alert.snoozedUntil}"}""", reason = reason,
        )
        return saved
    }

    /** Closes an alert by hand, for conditions no evaluator can see the end of. */
    @Transactional
    @PreAuthorize("hasAuthority('ALERT_MANAGE')")
    fun resolve(id: Long, reason: String?): Alert {
        val alert = get(id)
        alert.resolvedAt = Instant.now()
        val saved = alerts.save(alert)
        audit.recordCurrent("ALERT_RESOLVED", "alert", id, reason = reason)
        return saved
    }

    // ── Channels ───────────────────────────────────────────────────────────

    /**
     * Queues the out-of-band channels a rule asks for.
     *
     * IN_APP needs nothing queued — the alert row *is* the in-app notification,
     * and it is the only channel that always works. SMS and email go to the
     * outbox and drain whenever a connection exists; nothing here waits on
     * either (§11.3).
     */
    private fun queueNotifications(rule: AlertRule, alert: Alert) {
        val destinations = rule.channels.filter { it != "IN_APP" }
        if (destinations.isEmpty()) return

        if (alert.severity != AlertRule.CRITICAL) {
            // Out-of-band channels are for critical severity only. A shop owner
            // who gets a text about every slow-moving product stops reading
            // texts from the shop.
            log.debug("skipping out-of-band channels for {} alert {}", alert.severity, alert.dedupeKey)
            return
        }

        destinations.forEach { channel ->
            outbox.save(
                NotificationOutbox(
                    channel = channel,
                    // The recipient is configuration the platform module will
                    // own; until it does, the row records the intent and the
                    // drain reports it as undeliverable rather than guessing.
                    destination = "",
                    payload = "${alert.title}\n${alert.body}",
                ).also { it.alertId = alert.id }
            )
        }
    }

    private companion object {
        val MAX_SNOOZE: Duration = Duration.ofDays(14)
    }
}
