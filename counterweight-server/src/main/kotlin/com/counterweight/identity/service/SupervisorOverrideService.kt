package com.counterweight.identity.service

import com.counterweight.common.ApiException
import com.counterweight.identity.repo.AppUserRepository
import com.counterweight.identity.security.AuthProperties
import org.slf4j.LoggerFactory
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

/**
 * Who approved an override, and what they are allowed to approve.
 *
 * Carries the approver's own roles so the caller can re-run its check under
 * their authority rather than trusting a boolean. See [SupervisorOverrideService].
 */
data class Approver(
    val userId: Long,
    val username: String,
    val roles: Set<String>,
    val permissions: Set<String>,
)

/**
 * Verifies a supervisor's till PIN.
 *
 * The PIN exists because the alternative at a counter is the supervisor typing
 * a full password in front of a queue, which in practice means the supervisor
 * tells the cashier the password once and never comes over again. A short PIN
 * that is cheap to use is worth more than a strong secret that gets shared.
 *
 * What keeps it honest is that a PIN authorises **one action, now**: it issues
 * no token, starts no session, and grants nothing beyond the single call that
 * verified it. Lockout is tracked separately from password lockout so that a
 * supervisor fumbling a PIN at the till cannot lock themselves out of signing
 * in, and so that guessing PINs cannot be used to lock a manager out of the
 * system during a shift.
 */
@Service
class SupervisorOverrideService(
    private val users: AppUserRepository,
    private val encoder: PasswordEncoder,
    private val props: AuthProperties,
    private val audit: AuditService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Checks [pin] against [username] and returns who approved.
     *
     * [requiredPermission] is checked here rather than left to the caller so
     * that no call site can forget it — an override verified without one would
     * let any user with a PIN approve anything.
     */
    @Transactional
    fun verify(username: String, pin: String, requiredPermission: String, forAction: String): Approver {
        val user = users.findByUsernameIgnoreCase(username.trim())

        // Same failure for an unknown user, a user with no PIN set and a wrong
        // PIN. Distinguishing them tells whoever is guessing which supervisors
        // exist and which have till authority.
        if (user == null || !user.isActive || user.overridePinHash == null) {
            log.warn("override rejected for unknown or ineligible approver")
            throw ApiException.Unauthenticated("That supervisor PIN was not accepted.")
        }
        if (user.isPinLocked()) {
            val minutes = Duration.between(Instant.now(), user.pinLockedUntil).toMinutes() + 1
            throw ApiException.AccountLocked(minutes)
        }

        if (!encoder.matches(pin, user.overridePinHash)) {
            user.pinFailedCount = (user.pinFailedCount + 1).toShort()
            if (user.pinFailedCount >= props.pinMaxAttempts) {
                user.pinLockedUntil = Instant.now().plus(Duration.ofMinutes(props.pinLockoutMinutes))
                user.pinFailedCount = 0
                log.warn("override PIN locked for user {} after {} attempts", user.id, props.pinMaxAttempts)
            }
            users.save(user)
            // Recorded whether or not it succeeded: a run of failed override
            // attempts at one till is exactly the pattern worth seeing later.
            audit.record(
                actorId = user.id!!, action = "OVERRIDE_PIN_REJECTED", subjectType = "app_user",
                subjectId = user.id, reason = forAction,
            )
            throw ApiException.Unauthenticated("That supervisor PIN was not accepted.")
        }

        if (user.pinFailedCount > 0 || user.pinLockedUntil != null) {
            user.pinFailedCount = 0
            user.pinLockedUntil = null
            users.save(user)
        }

        val permissions = user.permissionCodes()
        if (requiredPermission !in permissions) {
            // The PIN was right, so this is a real supervisor being told they
            // are not the right supervisor. Worth its own message and its own
            // audit entry.
            audit.record(
                actorId = user.id!!, action = "OVERRIDE_REFUSED_NO_PERMISSION", subjectType = "app_user",
                subjectId = user.id, reason = forAction,
            )
            throw ApiException.Forbidden("${user.fullName} is not authorised to approve that.")
        }

        audit.record(
            actorId = user.id!!, action = "OVERRIDE_APPROVED", subjectType = "app_user",
            subjectId = user.id, reason = forAction,
        )
        return Approver(
            userId = user.id!!,
            username = user.username,
            roles = user.roles.map { it.code }.toSet(),
            permissions = permissions,
        )
    }
}
