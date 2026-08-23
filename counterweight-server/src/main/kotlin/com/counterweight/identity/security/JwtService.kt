package com.counterweight.identity.security

import com.counterweight.identity.domain.AppUser
import io.jsonwebtoken.Claims
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import javax.crypto.SecretKey

@ConfigurationProperties(prefix = "counterweight.auth")
data class AuthProperties(
    /**
     * HMAC signing secret. Must be at least 32 bytes and MUST be supplied by the
     * environment in production — see the startup check in [JwtService].
     */
    var jwtSecret: String = "",
    var accessTokenMinutes: Long = 15,
    var refreshTokenDays: Long = 7,
    var issuer: String = "counterweight",
    var lockoutMaxAttempts: Int = 5,
    var lockoutMinutes: Long = 15,
    var pinMaxAttempts: Int = 3,
    var pinLockoutMinutes: Long = 30,
    var passwordMinLength: Int = 10,
    /*
     * There was a `rateLimitPerIpPerMinute` here and nothing ever read it —
     * the same shape as a permission granted and never checked, and removed
     * for the same reason: a control that exists only in configuration is
     * worse than a missing one, because nobody re-reads a defence they believe
     * is already there. What actually bounds password guessing is the per
     * account lockout above (five attempts, fifteen minutes), which is real and
     * tested. Behind Docker every till also shares one source address, so an
     * IP bucket would have been the wrong unit here anyway.
     */
)

/**
 * Issues and verifies access tokens, and mints refresh tokens.
 *
 * Two separate token types, on purpose:
 *
 *  - The **access token** is a short-lived signed JWT carrying the user id and
 *    permission codes. It is never checked against the database, which is what
 *    keeps the till fast — but it also means it cannot be revoked early, hence
 *    the short lifetime.
 *  - The **refresh token** is opaque random bytes, stored server-side as a
 *    SHA-256 hash. It is the revocable half. Sessions are killed by revoking
 *    these.
 *
 * Permissions are embedded in the access token rather than re-read per request.
 * The trade is that a permission change takes effect at the next refresh rather
 * than instantly; for a shop of a handful of staff that is the right trade, and
 * an urgent removal is handled by revoking the refresh tokens outright.
 */
@Service
class JwtService(private val props: AuthProperties) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val random = SecureRandom()

    private val key: SecretKey by lazy {
        Keys.hmacShaKeyFor(props.jwtSecret.toByteArray(Charsets.UTF_8))
    }

    @PostConstruct
    fun verifySecret() {
        val secret = props.jwtSecret
        require(secret.isNotBlank()) {
            "counterweight.auth.jwt-secret is not set. Generate one with " +
                "`openssl rand -base64 48` and supply it through the environment."
        }
        // HMAC-SHA256 needs >= 256 bits of key. A short secret is brute-forceable
        // offline against any captured token, which forges any user's session.
        require(secret.toByteArray(Charsets.UTF_8).size >= 32) {
            "counterweight.auth.jwt-secret must be at least 32 bytes (256 bits); " +
                "it is ${secret.toByteArray(Charsets.UTF_8).size}."
        }
        require(secret != DEV_PLACEHOLDER) {
            "counterweight.auth.jwt-secret is still the development placeholder. " +
                "Set a real secret before running anywhere but a developer machine."
        }
        log.info("JWT signing key accepted ({} bytes)", secret.toByteArray(Charsets.UTF_8).size)
    }

    /**
     * `pwd` says the holder is still on a temporary password.
     *
     * It rides in the token rather than being read from the database on every
     * request, for the same reason the permissions do — the sale path may not
     * take a database hop to answer "who is this". The cost is that the flag is
     * as stale as the token, which is exactly right here: changing a password
     * revokes every session, so the next token cannot carry it.
     */
    fun issueAccessToken(user: AppUser, now: Instant = Instant.now()): String {
        val expiry = now.plus(Duration.ofMinutes(props.accessTokenMinutes))
        return Jwts.builder()
            .issuer(props.issuer)
            .subject(user.id!!.toString())
            .claim("usr", user.username)
            .claim("brn", user.branchId)
            .claim("rol", user.roles.map { it.code }.sorted())
            .claim("prm", user.permissionCodes().sorted())
            .claim("pwd", user.mustChangePassword)
            .issuedAt(java.util.Date.from(now))
            .expiration(java.util.Date.from(expiry))
            .signWith(key)
            .compact()
    }

    /** Returns the claims, or null if the token is absent, tampered or expired. */
    fun parse(token: String): Claims? = try {
        Jwts.parser()
            .verifyWith(key)
            .requireIssuer(props.issuer)
            .build()
            .parseSignedClaims(token)
            .payload
    } catch (e: JwtException) {
        // Expected during normal operation (expiry, a stale tab). Not an error,
        // and the token itself is never logged.
        log.debug("rejected token: {}", e.javaClass.simpleName)
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    /** 32 bytes of CSPRNG output, URL-safe. Opaque — it carries no claims. */
    fun newRefreshToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * What actually gets stored. SHA-256 without a salt is correct here and
     * deliberate: the input is 256 bits of random, so there is no dictionary to
     * attack, and lookup has to be a single indexed query rather than a scan
     * over every row with a per-row cost. Passwords are the opposite case and
     * use Argon2id.
     */
    fun hashRefreshToken(token: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun accessTokenLifetime(): Duration = Duration.ofMinutes(props.accessTokenMinutes)
    fun refreshTokenLifetime(): Duration = Duration.ofDays(props.refreshTokenDays)

    companion object {
        const val DEV_PLACEHOLDER = "change-me-in-production-this-is-not-a-secret-value-32b"
    }
}
