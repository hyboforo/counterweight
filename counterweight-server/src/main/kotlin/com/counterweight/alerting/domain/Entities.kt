package com.counterweight.alerting.domain

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

/*
 * Alerting entities.
 *
 * The governing constraint is that an alerting system which cries daily gets
 * ignored, and then the one that mattered gets ignored with it (§11.1). Three
 * mechanisms fight that, and all three live in the schema rather than in code:
 * the partial unique index on `dedupe_key`, auto-resolution, and `snoozed_until`.
 * Nothing here may work around them.
 */

/**
 * A rule is a row, not code.
 *
 * [ruleType] selects the evaluator; [scope] narrows what it looks at and
 * [params] tunes it. Both are JSON because the shape differs per type — an
 * expiry rule has horizons, a variance rule has a threshold — and a column per
 * parameter would mean a migration every time somebody wanted a new kind of
 * alert.
 */
@Entity
@Table(name = "alert_rule")
class AlertRule(
    @Column(name = "rule_type", nullable = false)
    var ruleType: String,

    @Column(nullable = false)
    var severity: String,
) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    /** Null means every branch. */
    @Column(name = "branch_id")
    var branchId: Long? = null

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "scope", columnDefinition = "jsonb", nullable = false)
    var scope: String = "{}"

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "params", columnDefinition = "jsonb", nullable = false)
    var params: String = "{}"

    @Column(name = "channels", columnDefinition = "text[]", nullable = false)
    @JdbcTypeCode(SqlTypes.ARRAY)
    var channels: Array<String> = arrayOf("IN_APP")

    @Column(nullable = false)
    var enabled: Boolean = true

    override fun equals(other: Any?) = this === other || (other is AlertRule && id != null && id == other.id)
    override fun hashCode() = javaClass.hashCode()
    override fun toString() = "AlertRule($ruleType)"

    companion object {
        const val CRITICAL = "CRITICAL"
        const val WARNING = "WARNING"
        const val INFO = "INFO"

        // The catalogue from §11. Each maps to one evaluator.
        const val OUT_OF_STOCK = "OUT_OF_STOCK"
        const val BELOW_REORDER_POINT = "BELOW_REORDER_POINT"
        const val EXPIRED_STOCK = "EXPIRED_STOCK"
        const val EXPIRY_APPROACHING = "EXPIRY_APPROACHING"
        const val LEDGER_MISMATCH = "LEDGER_MISMATCH"
        const val SOLD_BELOW_COST = "SOLD_BELOW_COST"
        const val CREDIT_LIMIT_REACHED = "CREDIT_LIMIT_REACHED"
        const val INVOICE_OVERDUE = "INVOICE_OVERDUE"
        const val DEAD_STOCK = "DEAD_STOCK"
        const val BACKUP_STALE = "BACKUP_STALE"

        val TYPES = setOf(
            OUT_OF_STOCK, BELOW_REORDER_POINT, EXPIRED_STOCK, EXPIRY_APPROACHING,
            LEDGER_MISMATCH, SOLD_BELOW_COST, CREDIT_LIMIT_REACHED,
            INVOICE_OVERDUE, DEAD_STOCK, BACKUP_STALE,
        )
        val SEVERITIES = setOf(CRITICAL, WARNING, INFO)
        val CHANNELS = setOf("IN_APP", "SMS", "EMAIL")
    }
}

/**
 * One raised condition.
 *
 * [dedupeKey] identifies the *condition*, not the occurrence — `LOW_STOCK:1:482`
 * is the same key however many times the balance crosses the line. The partial
 * unique index on it (where `resolved_at IS NULL`) means re-raising while it is
 * still open is a no-op rather than a new row and a new notification, which is
 * the single most important thing in this module.
 */
@Entity
@Table(name = "alert")
class Alert(
    @Column(name = "rule_id", nullable = false)
    var ruleId: Long,

    @Column(name = "branch_id", nullable = false)
    var branchId: Long,

    @Column(name = "dedupe_key", nullable = false)
    var dedupeKey: String,

    @Column(nullable = false)
    var severity: String,

    @Column(nullable = false)
    var title: String,

    @Column(nullable = false)
    var body: String,
) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "subject_type")
    var subjectType: String? = null

    @Column(name = "subject_id")
    var subjectId: Long? = null

    @Column(name = "raised_at", nullable = false)
    var raisedAt: Instant = Instant.now()

    @Column(name = "acknowledged_at")
    var acknowledgedAt: Instant? = null

    @Column(name = "acknowledged_by")
    var acknowledgedBy: Long? = null

    /**
     * Set when the condition stops being true.
     *
     * Usually by the evaluator rather than by a person: restocking above the
     * reorder point closes the low-stock alert without anyone clicking
     * anything. An alert that only a human can close is one that accumulates.
     */
    @Column(name = "resolved_at")
    var resolvedAt: Instant? = null

    /** "I know, the order comes Thursday" — without disabling the rule. */
    @Column(name = "snoozed_until")
    var snoozedUntil: Instant? = null

    val isOpen: Boolean get() = resolvedAt == null

    fun isSnoozed(now: Instant = Instant.now()): Boolean = snoozedUntil?.isAfter(now) == true

    override fun equals(other: Any?) = this === other || (other is Alert && id != null && id == other.id)
    override fun hashCode() = javaClass.hashCode()
    override fun toString() = "Alert($dedupeKey)"
}

/**
 * An outbound notification waiting for a connection.
 *
 * Strictly queue-and-forward. The shop has no internet on the sale path, so
 * nothing may ever await delivery — a till that blocks because an SMS gateway
 * is unreachable is a till that has stopped selling, which is a far worse
 * outcome than a late message.
 */
@Entity
@Table(name = "notification_outbox")
class NotificationOutbox(
    @Column(nullable = false)
    var channel: String,

    @Column(nullable = false)
    var destination: String,

    @Column(nullable = false)
    var payload: String,
) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "alert_id")
    var alertId: Long? = null

    @Column(nullable = false)
    var status: String = PENDING

    @Column(nullable = false)
    var attempts: Short = 0

    @Column(name = "last_error")
    var lastError: String? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()

    @Column(name = "sent_at")
    var sentAt: Instant? = null

    override fun equals(other: Any?) = this === other || (other is NotificationOutbox && id != null && id == other.id)
    override fun hashCode() = javaClass.hashCode()

    companion object {
        const val PENDING = "PENDING"
        const val SENT = "SENT"
        const val FAILED = "FAILED"

        /** Given up on. Kept, because a message nobody sent is worth knowing about. */
        const val ABANDONED = "ABANDONED"
    }
}
