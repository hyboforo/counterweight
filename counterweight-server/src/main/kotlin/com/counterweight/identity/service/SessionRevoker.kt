package com.counterweight.identity.service

import com.counterweight.identity.repo.RefreshTokenRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Revokes sessions in their own transaction.
 *
 * Third instance of the same trap in this module, and the reason it now has a
 * dedicated home: **a security action taken on the way out of a method must not
 * share that method's transaction.**
 *
 * `AuthService.refresh` detects a replayed refresh token, revokes the whole
 * token family, and then throws to reject the caller. Throwing rolls the
 * transaction back — so the revocation was undone and the stolen family stayed
 * alive. The integration test caught it: after reuse was "detected", the other
 * session still refreshed successfully.
 *
 * Anything that must survive a rejection belongs here or in [AuditService].
 */
@Component
class SessionRevoker(private val refreshTokens: RefreshTokenRepository) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun revokeAllForUser(userId: Long, reason: String): Int {
        val revoked = refreshTokens.revokeAllForUser(userId, Instant.now(), reason)
        if (revoked > 0) log.info("revoked {} session(s) for user {} — {}", revoked, userId, reason)
        return revoked
    }
}
