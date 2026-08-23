package com.counterweight.common

import org.springframework.http.HttpStatus

/**
 * Every failure the API returns deliberately, as a closed hierarchy.
 *
 * Sealed rather than a bag of RuntimeExceptions so that adding a case makes the
 * compiler point at every `when` that has to handle it — the same reasoning as
 * the movement types in the ledger.
 *
 * The [code] is a stable machine-readable string. The till shows the [message]
 * to whoever is standing at the counter, so it says what to do next rather than
 * what went wrong internally.
 */
sealed class ApiException(
    val status: HttpStatus,
    val code: String,
    override val message: String,
    /** Never populated with anything the caller has not already proved they may see. */
    val details: Map<String, Any?> = emptyMap(),
    cause: Throwable? = null,
) : RuntimeException(message, cause) {

    class NotFound(what: String, id: Any? = null) : ApiException(
        HttpStatus.NOT_FOUND, "NOT_FOUND",
        if (id != null) "$what $id was not found." else "$what was not found.",
    )

    class Validation(message: String, fieldErrors: Map<String, String> = emptyMap()) : ApiException(
        HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message,
        if (fieldErrors.isEmpty()) emptyMap() else mapOf("fields" to fieldErrors),
    )

    class Conflict(message: String) : ApiException(HttpStatus.CONFLICT, "CONFLICT", message)

    /**
     * Authentication failed. The message is deliberately identical for an unknown
     * username and a wrong password — distinguishing them tells an attacker which
     * usernames exist.
     */
    class Unauthenticated(message: String = "Username or password is incorrect.") :
        ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", message)

    class Forbidden(message: String = "You do not have permission to do that.") :
        ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", message)

    class AccountLocked(minutesRemaining: Long) : ApiException(
        HttpStatus.LOCKED, "ACCOUNT_LOCKED",
        "This account is locked for another $minutesRemaining minute(s) after too many failed sign-ins.",
    )

    class PasswordChangeRequired : ApiException(
        HttpStatus.FORBIDDEN, "PASSWORD_CHANGE_REQUIRED",
        "Set a new password before continuing.",
    )

    class RateLimited(retryAfterSeconds: Long) : ApiException(
        HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
        "Too many attempts. Try again in $retryAfterSeconds second(s).",
        mapOf("retryAfterSeconds" to retryAfterSeconds),
    )

    /** A rule the business enforces, e.g. removing the last SYSTEM_ADMIN. */
    class RuleViolation(code: String, message: String) :
        ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message)
}
