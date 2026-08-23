package com.counterweight.common

import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import kotlin.reflect.KClass

/*
 * Input validation for this system.
 *
 * Three layers, and they do different jobs — none of them replaces the others:
 *
 *   1. These annotations reject structurally wrong input at the edge, before
 *      any service sees it.
 *   2. The service layer enforces rules that need state (does this username
 *      already exist, is this discount within the operator's allowance).
 *   3. The database enforces invariants that must hold no matter what wrote
 *      them — the oversell CHECK, the last-SYSTEM_ADMIN trigger.
 *
 * Layer 1 alone is not security. It is a usability and hygiene layer; the
 * guarantees live at layers 2 and 3.
 */

// ── Free text ──────────────────────────────────────────────────────────────

/**
 * Text a human typed: a name, an address, a note.
 *
 * Rejects control characters and Unicode direction overrides. The direction
 * overrides matter — they can make "1000.00" render as "00.0001" in a receipt
 * or an audit entry, which is a real spoofing avenue rather than a theoretical
 * one.
 *
 * This is NOT the XSS defence. Output encoding is, and the API returns JSON
 * that the client escapes on render. Rejecting angle brackets here would break
 * legitimate input ("Pipe 2\" > 1\"") while giving false confidence.
 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [SafeTextValidator::class])
annotation class SafeText(
    val message: String = "contains characters that are not allowed",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

class SafeTextValidator : ConstraintValidator<SafeText, String?> {
    override fun isValid(value: String?, ctx: ConstraintValidatorContext): Boolean {
        if (value.isNullOrEmpty()) return true      // @NotBlank is a separate concern
        return value.none { ch ->
            // C0/C1 controls, except tab and newline which are legitimate in notes.
            (ch.isISOControl() && ch != '\t' && ch != '\n' && ch != '\r') ||
                ch in DIRECTION_OVERRIDES
        }
    }

    private companion object {
        val DIRECTION_OVERRIDES = setOf(
            '‪', '‫', '‬', '‭', '‮',
            '⁦', '⁧', '⁨', '⁩',
        )
    }
}

// ── Ghana phone numbers ────────────────────────────────────────────────────

/**
 * Accepts 0XXXXXXXXX or +233XXXXXXXXX, with spaces, dashes and brackets, since
 * people type them and it is not their job to know we would rather they didn't.
 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [GhanaPhoneValidator::class])
annotation class GhanaPhone(
    val message: String = "should be a Ghana number, like 024 000 0000",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

class GhanaPhoneValidator : ConstraintValidator<GhanaPhone, String?> {
    override fun isValid(value: String?, ctx: ConstraintValidatorContext): Boolean {
        if (value.isNullOrBlank()) return true
        return normalisePhone(value) != null
    }
}

/** Returns the number in local 0XXXXXXXXX form, or null if it is not one. */
fun normalisePhone(raw: String): String? {
    val digits = raw.replace(Regex("[\\s\\-().]"), "")
    if (!Regex("^\\+?\\d+$").matches(digits)) return null
    val local = digits.removePrefix("+233").let { if (it == digits) digits.removePrefix("233") else it }
        .let { if (it.startsWith("0")) it else "0$it" }
    return if (Regex("^0\\d{9}$").matches(local)) local else null
}

// ── Usernames ──────────────────────────────────────────────────────────────

/**
 * Deliberately narrow: letters, digits, dot, underscore, hyphen; must start
 * with a letter. Usernames end up in audit entries and log lines, so anything
 * that could be confused with another account — or forge a log line — is out.
 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [UsernameValidator::class])
annotation class Username(
    val message: String = "may use letters, digits, dot, underscore and hyphen, and must start with a letter",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

class UsernameValidator : ConstraintValidator<Username, String?> {
    override fun isValid(value: String?, ctx: ConstraintValidatorContext): Boolean {
        if (value.isNullOrBlank()) return true
        return PATTERN.matches(value)
    }
    private companion object { val PATTERN = Regex("^[A-Za-z][A-Za-z0-9._-]{2,39}$") }
}

// ── Supervisor override PIN ────────────────────────────────────────────────

@Target(AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [OverridePinValidator::class])
annotation class OverridePin(
    val message: String = "must be 4 to 8 digits and not a run or a repeat",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

class OverridePinValidator : ConstraintValidator<OverridePin, String?> {
    override fun isValid(value: String?, ctx: ConstraintValidatorContext): Boolean {
        if (value.isNullOrBlank()) return true
        if (!Regex("^\\d{4,8}$").matches(value)) return false
        // A PIN is typed in front of customers and shoulder-surfed easily.
        // 1111 and 1234 are the two everybody tries first.
        if (value.toSet().size == 1) return false
        val ascending = value.zipWithNext().all { (a, b) -> b - a == 1 }
        val descending = value.zipWithNext().all { (a, b) -> a - b == 1 }
        return !ascending && !descending
    }
}
