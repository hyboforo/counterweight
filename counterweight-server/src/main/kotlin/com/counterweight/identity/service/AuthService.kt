package com.counterweight.identity.service

import com.counterweight.common.ApiException
import com.counterweight.identity.domain.*
import com.counterweight.identity.repo.*
import com.counterweight.identity.security.AuthProperties
import com.counterweight.identity.security.JwtService
import org.slf4j.LoggerFactory
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

data class AuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long,
    val mustChangePassword: Boolean,
    val username: String,
    val roles: Set<String>,
    val permissions: Set<String>,
)

@Service
class AuthService(
    private val users: AppUserRepository,
    private val refreshTokens: RefreshTokenRepository,
    private val loginAttempts: LoginAttemptRepository,
    private val jwt: JwtService,
    private val encoder: PasswordEncoder,
    private val props: AuthProperties,
    private val audit: AuditService,
    private val attemptRecorder: LoginAttemptRecorder,
    private val sessionRevoker: SessionRevoker,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Sign in.
     *
     * Failures are deliberately indistinguishable to the caller — unknown user,
     * wrong password and deactivated account all return the same message. Only
     * a locked account is called out, because the user genuinely needs to know
     * to stop trying and how long to wait.
     *
     * A dummy hash verification runs for unknown usernames so that the response
     * takes the same time either way. Without it, response latency alone
     * enumerates valid usernames.
     */
    @Transactional
    fun login(username: String, password: String, clientLabel: String?): AuthTokens {
        val now = Instant.now()
        val user = users.findByUsernameIgnoreCase(username)

        if (user == null) {
            encoder.matches(password, DUMMY_HASH)      // constant-ish time
            record(username, null, false, "NO_SUCH_USER", clientLabel)
            throw ApiException.Unauthenticated()
        }

        if (user.isLocked(now)) {
            val mins = Duration.between(now, user.lockedUntil).toMinutes() + 1
            record(username, user.id, false, "LOCKED", clientLabel)
            throw ApiException.AccountLocked(mins)
        }

        if (!user.isActive) {
            record(username, user.id, false, "INACTIVE", clientLabel)
            throw ApiException.Unauthenticated()
        }

        if (!encoder.matches(password, user.passwordHash)) {
            // Both of these commit in their own transaction — this method is
            // about to throw, which would otherwise roll them straight back.
            val nowLocked = attemptRecorder.registerFailure(user.id!!, user.username, now)
            record(username, user.id, false, if (nowLocked) "BAD_PASSWORD_LOCKED" else "BAD_PASSWORD", clientLabel)
            throw ApiException.Unauthenticated()
        }

        attemptRecorder.registerSuccess(user.id!!, now)
        record(username, user.id, true, null, clientLabel)

        return issueFor(user, clientLabel, now)
    }

    /**
     * Exchange a refresh token for a new pair, rotating the old one.
     *
     * **Reuse detection.** A refresh token is single-use. If one that has
     * already been rotated is presented again, either the client replayed it or
     * — far more likely — it was captured and is being used by someone else.
     * Since we cannot tell which party is legitimate, every session for that
     * user is revoked and both are forced to sign in again. Failing closed is
     * the only safe choice.
     */
    @Transactional
    fun refresh(rawToken: String, clientLabel: String?): AuthTokens {
        val now = Instant.now()
        val stored = refreshTokens.findByTokenHash(jwt.hashRefreshToken(rawToken))
            ?: throw ApiException.Unauthenticated("Your session has expired. Please sign in again.")

        if (stored.revokedAt != null) {
            if (stored.replacedBy != null) {
                log.warn(
                    "refresh token reuse detected for user {} — revoking all sessions",
                    stored.userId,
                )
                sessionRevoker.revokeAllForUser(stored.userId, "REUSE_DETECTED")
                audit.record(
                    actorId = stored.userId,
                    action = "SESSION_REVOKED_ON_REUSE",
                    subjectType = "app_user",
                    subjectId = stored.userId,
                    reason = "A refresh token was presented after it had already been rotated.",
                )
            }
            throw ApiException.Unauthenticated("Your session has expired. Please sign in again.")
        }

        if (!stored.isUsable(now)) {
            throw ApiException.Unauthenticated("Your session has expired. Please sign in again.")
        }

        val user = users.findById(stored.userId).orElse(null)
            ?: throw ApiException.Unauthenticated("Your session has expired. Please sign in again.")

        // Re-checked on every refresh: this is how a deactivation or a lockout
        // reaches a session that already holds a valid token.
        if (!user.isActive || user.isLocked(now)) {
            sessionRevoker.revokeAllForUser(user.id!!, "USER_NOT_ACTIVE")
            throw ApiException.Unauthenticated("Your session has expired. Please sign in again.")
        }

        val tokens = issueFor(user, clientLabel, now)
        stored.revokedAt = now
        stored.revokedReason = "ROTATED"
        stored.replacedBy = refreshTokens.findByTokenHash(jwt.hashRefreshToken(tokens.refreshToken))?.id
        refreshTokens.save(stored)
        return tokens
    }

    @Transactional
    fun logout(rawToken: String) {
        val stored = refreshTokens.findByTokenHash(jwt.hashRefreshToken(rawToken)) ?: return
        if (stored.revokedAt == null) {
            stored.revokedAt = Instant.now()
            stored.revokedReason = "LOGOUT"
            refreshTokens.save(stored)
        }
    }

    /** Revokes every session for a user — used on password change and by admins. */
    @Transactional
    fun revokeAllSessions(userId: Long, reason: String): Int =
        refreshTokens.revokeAllForUser(userId, Instant.now(), reason)

    private fun issueFor(user: AppUser, clientLabel: String?, now: Instant): AuthTokens {
        val raw = jwt.newRefreshToken()
        // saveAndFlush, not save: rotation immediately reads this row back to
        // record it as the replacement, and a pending insert has no id yet.
        refreshTokens.saveAndFlush(
            RefreshToken(
                userId = user.id!!,
                tokenHash = jwt.hashRefreshToken(raw),
                expiresAt = now.plus(jwt.refreshTokenLifetime()),
            ).also { it.clientLabel = clientLabel?.take(120) }
        )
        return AuthTokens(
            accessToken = jwt.issueAccessToken(user, now),
            refreshToken = raw,
            expiresInSeconds = jwt.accessTokenLifetime().seconds,
            mustChangePassword = user.mustChangePassword,
            username = user.username,
            roles = user.roles.map { it.code }.toSet(),
            permissions = user.permissionCodes(),
        )
    }

    private fun record(
        username: String,
        userId: Long?,
        succeeded: Boolean,
        failureCode: String?,
        clientLabel: String?,
    ) {
        attemptRecorder.record(username, userId, succeeded, failureCode, clientLabel)
    }

    private companion object {
        /*
         * A real Argon2id hash of a value nobody knows. Verifying against this
         * for an unknown username costs the same as a real check, so a caller
         * cannot tell "no such user" from "wrong password" by timing alone.
         */
        const val DUMMY_HASH =
            "\$argon2id\$v=19\$m=16384,t=2,p=1\$c29tZXNhbHR2YWx1ZQ\$xrjE9Y0bVJmqDPqLbeXBRRZjqLPHJqxUyBHwXqLxqzo"
    }
}
