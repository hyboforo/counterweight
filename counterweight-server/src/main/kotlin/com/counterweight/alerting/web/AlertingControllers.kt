package com.counterweight.alerting.web

import com.counterweight.alerting.domain.Alert
import com.counterweight.alerting.domain.AlertRule
import com.counterweight.alerting.repo.AlertRuleRepository
import com.counterweight.alerting.service.*
import com.counterweight.common.ApiException
import com.counterweight.common.SafeText
import com.counterweight.identity.security.Auth
import jakarta.validation.Valid
import jakarta.validation.constraints.*
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

// ── Requests ───────────────────────────────────────────────────────────────

data class SnoozeRequest(
    @field:Min(value = 1, message = "must be at least an hour")
    @field:Max(value = 336, message = "is longer than two weeks")
    val hours: Long,

    @field:Size(max = 250, message = "is too long")
    @field:SafeText
    val reason: String? = null,
)

data class ResolveRequest(
    @field:Size(max = 250, message = "is too long")
    @field:SafeText
    val reason: String? = null,
)

data class RuleUpdateRequest(
    val enabled: Boolean? = null,

    @field:Pattern(regexp = "^(CRITICAL|WARNING|INFO)$", message = "must be CRITICAL, WARNING or INFO")
    val severity: String? = null,

    @field:Size(max = 3, message = "is too many channels")
    val channels: List<@Pattern(regexp = "^(IN_APP|SMS|EMAIL)$") String>? = null,
)

// ── Responses ──────────────────────────────────────────────────────────────

data class AlertView(
    val id: Long, val severity: String, val title: String, val body: String,
    val subjectType: String?, val subjectId: Long?,
    val raisedAt: Instant, val acknowledgedAt: Instant?, val snoozedUntil: Instant?,
    val dedupeKey: String,
)

data class RuleView(
    val id: Long, val ruleType: String, val severity: String,
    val channels: List<String>, val enabled: Boolean, val branchId: Long?,
)

private fun Alert.toView() = AlertView(
    id!!, severity, title, body, subjectType, subjectId,
    raisedAt, acknowledgedAt, snoozedUntil, dedupeKey,
)

private fun AlertRule.toView() =
    RuleView(id!!, ruleType, severity, channels.toList(), enabled, branchId)

// ── Controllers ────────────────────────────────────────────────────────────

/**
 * The in-app notification centre (§11.3).
 *
 * The primary channel, and the only one that always works. Everything else —
 * badges, the printed briefing, SMS — is a view onto or a copy of what is here.
 */
@RestController
@RequestMapping("/api/alerts")
class AlertController(
    private val alerts: AlertService,
    private val briefing: MorningBriefingService,
) {

    @GetMapping
    fun open(
        @RequestParam(defaultValue = "false") includeSnoozed: Boolean,
    ): List<AlertView> = alerts.open(includeSnoozed).map { it.toView() }

    /** Counts by severity, for the badges on the home screen. */
    @GetMapping("/badges")
    fun badges(): SeverityBadges = alerts.badges()

    @GetMapping("/{id}")
    fun get(@PathVariable id: Long): AlertView = alerts.get(id).toView()

    /**
     * Marks an alert as seen.
     *
     * Does not close it. The stock is still below the reorder point after
     * somebody reads that it is.
     */
    @PutMapping("/{id}/acknowledge")
    fun acknowledge(@PathVariable id: Long): AlertView = alerts.acknowledge(id).toView()

    /** Quiets it for a while without disabling the rule behind it. */
    @PutMapping("/{id}/snooze")
    fun snooze(@PathVariable id: Long, @Valid @RequestBody body: SnoozeRequest): AlertView =
        alerts.snooze(id, Duration.ofHours(body.hours), body.reason).toView()

    /** Closes it by hand, for conditions no evaluator can see the end of. */
    @PutMapping("/{id}/resolve")
    fun resolve(
        @PathVariable id: Long,
        @RequestBody(required = false) body: ResolveRequest?,
    ): AlertView = alerts.resolve(id, body?.reason).toView()

    /** Queues the slip for open of business. */
    @PostMapping("/briefing")
    @ResponseStatus(HttpStatus.CREATED)
    fun printBriefing(
        @RequestParam @Pattern(regexp = "^[A-Za-z0-9._-]+$") tillCode: String,
    ): Map<String, Any?> {
        val job = briefing.printBriefing(tillCode)
        return mapOf("jobId" to job.id, "tillCode" to job.tillCode)
    }
}

/**
 * The rules themselves.
 *
 * Rows, not code (§11). Turning a rule off, changing its severity or adding a
 * channel is configuration — the only thing that needs a release is a new
 * *type*, because a type is an evaluator.
 */
@RestController
@RequestMapping("/api/alert-rules")
@PreAuthorize("hasAuthority('ALERT_MANAGE')")
class AlertRuleController(private val rules: AlertRuleRepository) {

    @GetMapping
    fun list(): List<RuleView> = rules.findAll()
        .filter { it.branchId == null || it.branchId == Auth.current().branchId }
        .sortedBy { it.ruleType }
        .map { it.toView() }

    @PutMapping("/{id}")
    fun update(@PathVariable id: Long, @Valid @RequestBody body: RuleUpdateRequest): RuleView {
        val rule = rules.findById(id).orElseThrow { ApiException.NotFound("Alert rule", id) }
        if (rule.branchId != null && rule.branchId != Auth.current().branchId) {
            throw ApiException.NotFound("Alert rule", id)
        }
        body.enabled?.let { rule.enabled = it }
        body.severity?.let { rule.severity = it }
        body.channels?.let {
            if (it.isEmpty()) {
                throw ApiException.Validation(
                    "A rule needs at least one channel. Disable it instead of removing them all.",
                    mapOf("channels" to "must not be empty"),
                )
            }
            rule.channels = it.toTypedArray()
        }
        return rules.save(rule).toView()
    }
}

/** Out-of-band delivery. Queue-and-forward; nothing on the sale path waits on it. */
@RestController
@RequestMapping("/api/notifications")
@PreAuthorize("hasAuthority('ALERT_MANAGE')")
class NotificationOutboxController(private val outbox: NotificationOutboxService) {

    @GetMapping("/outbox")
    fun status(): OutboxStatus = outbox.status()

    /**
     * Attempts every queued message.
     *
     * Triggered rather than scheduled: the shop's connection is intermittent,
     * and a timer would spend most of its life failing against an unreachable
     * gateway. Whatever knows a connection exists calls this.
     */
    @PostMapping("/outbox/drain")
    fun drain(): OutboxStatus = outbox.drain()
}

/**
 * Running the scheduled evaluators by hand.
 *
 * Exists because "why did I not get told" is answered by running the evaluator
 * and looking, not by waiting until tomorrow. Each is idempotent, so running
 * one out of turn changes nothing.
 */
@RestController
@RequestMapping("/api/alerts/evaluate")
@PreAuthorize("hasAuthority('ALERT_MANAGE')")
class AlertEvaluationController(
    private val evaluator: ScheduledAlertEvaluator,
    private val reorderPoints: ReorderPointService,
) {

    @PostMapping("/stock")
    fun stock(): Map<String, Any> {
        val branchId = Auth.current().branchId
        return mapOf(
            "expired" to evaluator.evaluateExpiredStock(branchId),
            "expiring" to evaluator.evaluateExpiringStock(branchId),
            "ledgerDrift" to evaluator.evaluateLedgerDrift(branchId),
        )
    }

    @PostMapping("/receivables")
    fun receivables(): Map<String, Any> =
        mapOf("overdue" to evaluator.evaluateOverdueInvoices(Auth.current().branchId))

    @PostMapping("/dead-stock")
    fun deadStock(): Map<String, Any> =
        mapOf("deadStock" to evaluator.evaluateDeadStock(Auth.current().branchId))

    @PostMapping("/reorder-points")
    fun reorderPoints(): ReorderRecomputeResult = reorderPoints.recompute(Auth.current().branchId)
}
