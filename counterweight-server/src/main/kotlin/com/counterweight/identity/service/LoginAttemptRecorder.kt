package com.counterweight.identity.service

import com.counterweight.identity.domain.LoginAttempt
import com.counterweight.identity.repo.AppUserRepository
import com.counterweight.identity.repo.LoginAttemptRepository
import com.counterweight.identity.security.AuthProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

/**
 * Bookkeeping for sign-in attempts, in its own transaction.
 *
 * `REQUIRES_NEW` is the entire point of this class, and it fixes a real bug.
 *
 * A failed login ends by throwing, and throwing a RuntimeException out of a
 * `@Transactional` method rolls that transaction back. When the counter
 * increment and the attempt row were written inside `AuthService.login`, both
 * were discarded along with the exception — so the failure count never rose,
 * **the account never locked**, and the forensic trail of failed attempts was
 * empty. The integration test caught it: the correct password still worked
 * after five deliberate failures.
 *
 * Committing in a separate transaction is what makes the record survive the
 * rejection it describes. Same reasoning as [AuditService].
 */
@Component
class LoginAttemptRecorder(
    private val users: AppUserRepository,
    private val attempts: LoginAttemptRepository,
    private val props: AuthProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun record(
        username: String,
        userId: Long?,
        succeeded: Boolean,
        failureCode: String?,
        clientLabel: String?,
    ) {
        attempts.save(
            LoginAttempt(username = username.take(80), succeeded = succeeded).also {
                it.userId = userId
                it.failureCode = failureCode
                it.clientLabel = clientLabel?.take(120)
            }
        )
    }

    /**
     * Increments the failure counter and locks the account once it crosses the
     * threshold. Returns true if this attempt caused the lock.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun registerFailure(userId: Long, username: String, now: Instant = Instant.now()): Boolean {
        users.incrementFailedLogin(userId)
        val count = users.failedLoginCount(userId) ?: return false

        if (count >= props.lockoutMaxAttempts) {
            users.lockUntil(userId, now.plus(Duration.ofMinutes(props.lockoutMinutes)))
            log.warn(
                "account '{}' locked for {} minutes after {} failed attempts",
                username, props.lockoutMinutes, props.lockoutMaxAttempts,
            )
            return true
        }
        return false
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun registerSuccess(userId: Long, now: Instant = Instant.now()) {
        users.recordSuccessfulLogin(userId, now)
    }
}
