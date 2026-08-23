package com.counterweight.identity.service

import com.counterweight.common.ApiException
import com.counterweight.identity.security.AuthProperties
import org.springframework.stereotype.Component

/**
 * Password rules.
 *
 * Follows current NIST guidance rather than the older "one upper, one digit,
 * one symbol" convention: **length and blocklisting beat composition rules**.
 * Composition rules reliably produce `Password1!`, which is worse than a longer
 * passphrase, and they push people towards writing passwords down — which in a
 * shop means on a note stuck to the till.
 *
 * So: a real minimum length, a check against the passwords everyone actually
 * picks, and a check that the password is not simply the username.
 */
@Component
class PasswordPolicy(private val props: AuthProperties) {

    fun validate(password: String, username: String? = null, fullName: String? = null) {
        val problems = mutableListOf<String>()

        if (password.length < props.passwordMinLength) {
            problems += "be at least ${props.passwordMinLength} characters"
        }
        if (password.length > MAX_LENGTH) {
            // Not a strength rule — an unbounded input into a memory-hard hash
            // is a denial-of-service vector.
            problems += "be no longer than $MAX_LENGTH characters"
        }
        if (password.isNotEmpty() && password.trim() != password) {
            problems += "not start or end with a space"
        }
        if (password.lowercase() in COMMON) {
            problems += "not be a commonly used password"
        }
        if (username != null && password.lowercase().contains(username.lowercase()) && username.length >= 3) {
            problems += "not contain your username"
        }
        if (fullName != null) {
            val parts = fullName.lowercase().split(Regex("\\s+")).filter { it.length >= 4 }
            if (parts.any { password.lowercase().contains(it) }) problems += "not contain your name"
        }
        if (password.toSet().size <= 2 && password.isNotEmpty()) {
            problems += "use more than two different characters"
        }

        if (problems.isNotEmpty()) {
            throw ApiException.Validation(
                "The password must " + problems.joinToString("; ") + ".",
                mapOf("password" to problems.first()),
            )
        }
    }

    private companion object {
        const val MAX_LENGTH = 200

        /*
         * Small, deliberately Ghana- and shop-flavoured blocklist on top of the
         * usual suspects. A full HaveIBeenPwned check would be better, but this
         * server has no internet by design (§1), so a bundled list is the
         * option that actually works here. Extend it from real data over time.
         */
        val COMMON = setOf(
            "password", "password1", "password123", "passw0rd", "p@ssword",
            "12345678", "123456789", "1234567890", "qwertyuiop", "qwerty123",
            "letmein", "welcome", "welcome1", "iloveyou", "admin123", "administrator",
            "abc12345", "changeme", "secret123", "trustno1", "sunshine",
            "counterweight", "hardware", "agrochem", "shop1234", "ghana123",
            "accraaccra", "kwame1234", "mypassword",
        )
    }
}
