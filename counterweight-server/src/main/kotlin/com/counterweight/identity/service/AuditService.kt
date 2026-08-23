package com.counterweight.identity.service

import com.counterweight.identity.domain.AuditLog
import com.counterweight.identity.repo.AuditLogRepository
import com.counterweight.identity.security.Auth
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Writes the audit trail.
 *
 * `REQUIRES_NEW` on purpose: an audit entry must survive the rollback of the
 * thing it describes. A failed privilege escalation is exactly the event worth
 * keeping, and if the write shared the caller's transaction it would vanish
 * along with the attempt.
 *
 * The table itself rejects UPDATE and DELETE at the database (V1), so nothing
 * here — including this service — can rewrite history afterwards.
 */
@Service
class AuditService(private val auditLogs: AuditLogRepository) {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun record(
        actorId: Long,
        action: String,
        subjectType: String,
        subjectId: Long? = null,
        branchId: Long? = null,
        approverId: Long? = null,
        before: String? = null,
        after: String? = null,
        reason: String? = null,
    ) {
        auditLogs.save(
            AuditLog(
                branchId = branchId ?: Auth.currentOrNull()?.branchId ?: 1L,
                actorId = actorId,
                action = action,
                subjectType = subjectType,
            ).also {
                it.subjectId = subjectId
                it.approverId = approverId
                it.beforeValue = before
                it.afterValue = after
                it.reason = reason
            }
        )
    }

    /** Convenience for the common case where the actor is whoever is signed in. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun recordCurrent(
        action: String,
        subjectType: String,
        subjectId: Long? = null,
        before: String? = null,
        after: String? = null,
        reason: String? = null,
    ) {
        val actor = Auth.current()
        record(
            actorId = actor.id,
            action = action,
            subjectType = subjectType,
            subjectId = subjectId,
            branchId = actor.branchId,
            before = before,
            after = after,
            reason = reason,
        )
    }
}
