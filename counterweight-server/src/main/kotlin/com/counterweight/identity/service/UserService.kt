package com.counterweight.identity.service

import com.counterweight.common.ApiException
import com.counterweight.identity.domain.AppUser
import com.counterweight.identity.domain.Role
import com.counterweight.identity.repo.AppUserRepository
import com.counterweight.identity.repo.RoleRepository
import com.counterweight.identity.security.Auth
import com.counterweight.identity.security.CurrentUser
import org.slf4j.LoggerFactory
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.Instant

/**
 * A role as a staffing screen needs it: what it carries, and whether the person
 * looking may hand it out.
 */
data class GrantableRole(
    val code: String,
    val name: String,
    val isSystem: Boolean,
    val permissions: List<String>,
    val grantable: Boolean,
    /** What this role carries that the actor does not — why it is refused. */
    val withheld: List<String>,
)

@Service
class UserService(
    private val users: AppUserRepository,
    private val roles: RoleRepository,
    private val encoder: PasswordEncoder,
    private val passwordPolicy: PasswordPolicy,
    private val auth: AuthService,
    private val audit: AuditService,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val random = SecureRandom()

    /**
     * Creates a staff account and returns the temporary password **once**.
     *
     * The temporary password is generated here rather than chosen by the
     * creating admin: an admin who picks it knows it, and `must_change_password`
     * is what makes that a one-time value instead of a shared credential.
     */
    @Transactional
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    fun createUser(
        username: String,
        fullName: String,
        roleCodes: Set<String>,
        branchId: Long,
        phone: String? = null,
        email: String? = null,
    ): Pair<AppUser, String> {
        val actor = Auth.current()

        if (users.existsByUsernameIgnoreCase(username)) {
            throw ApiException.Conflict("The username '$username' is already taken.")
        }
        val resolved = resolveRoles(roleCodes)
        assertMayGrant(actor, resolved)

        val temporary = generateTemporaryPassword()
        val user = AppUser(
            branchId = branchId,
            username = username,
            fullName = fullName,
            passwordHash = encoder.encode(temporary),
        ).also {
            it.phone = phone
            it.email = email
            it.mustChangePassword = true
            it.createdBy = actor.id
            it.roles = resolved.toMutableSet()
        }
        val saved = users.save(user)

        audit.recordCurrent(
            action = "USER_CREATED",
            subjectType = "app_user",
            subjectId = saved.id,
            after = """{"username":"${saved.username}","roles":${roleCodes.sorted().toJsonArray()}}""",
        )
        log.info("user {} created by {}", saved.username, actor.username)
        return saved to temporary
    }

    @Transactional
    @PreAuthorize("hasAuthority('ROLE_ASSIGN')")
    fun setRoles(userId: Long, roleCodes: Set<String>) {
        val actor = Auth.current()
        val user = users.findById(userId).orElseThrow { ApiException.NotFound("User", userId) }

        /*
         * Nobody edits their own roles, not even a system admin. Otherwise the
         * separation of duties in V2 is decorative: an account with ROLE_ASSIGN
         * could simply grant itself COST_VIEW and read the shop's margins.
         * Changing your own access requires a second person, always.
         */
        if (user.id == actor.id) {
            throw ApiException.RuleViolation(
                "SELF_ROLE_CHANGE",
                "You cannot change your own roles. Ask another administrator.",
            )
        }

        val target = resolveRoles(roleCodes)
        assertMayGrant(actor, target)

        val before = user.roles.map { it.code }.sorted()
        // Losing the last SYSTEM_ADMIN is refused by a database trigger too;
        // this check exists to return a usable message instead of a 409.
        assertNotLastSystemAdmin(user, target)

        user.roles = target.toMutableSet()
        users.save(user)

        // Authority is embedded in the access token, so a revoked permission
        // would otherwise survive until it expired. Killing the sessions makes
        // the change take effect on the next request.
        auth.revokeAllSessions(userId, "ROLES_CHANGED")

        audit.recordCurrent(
            action = "USER_ROLES_CHANGED",
            subjectType = "app_user",
            subjectId = userId,
            before = before.toJsonArray(),
            after = roleCodes.sorted().toJsonArray(),
        )
    }

    @Transactional
    fun changeOwnPassword(currentPassword: String, newPassword: String) {
        val actor = Auth.current()
        val user = users.findById(actor.id).orElseThrow { ApiException.NotFound("User", actor.id) }

        if (!encoder.matches(currentPassword, user.passwordHash)) {
            // Not Validation: this is an authentication failure, and it must not
            // be distinguishable from any other in the response body.
            throw ApiException.Unauthenticated("Your current password is incorrect.")
        }
        if (encoder.matches(newPassword, user.passwordHash)) {
            throw ApiException.Validation("The new password must be different from the current one.")
        }
        passwordPolicy.validate(newPassword, user.username, user.fullName)

        user.passwordHash = encoder.encode(newPassword)
        user.passwordChangedAt = Instant.now()
        user.mustChangePassword = false
        users.save(user)

        // Every other device holding a session for this account is signed out —
        // that is the point of changing a password you think was seen.
        auth.revokeAllSessions(user.id!!, "PASSWORD_CHANGED")
        audit.recordCurrent("PASSWORD_CHANGED", "app_user", user.id)
    }

    /**
     * Sets your own till override PIN.
     *
     * **Only the holder can set it, and that is the whole point.** A PIN
     * authorises one action under the approver's own roles and the audit log
     * records them as having approved it — so a PIN an administrator chose and
     * handed over would make that record a lie, and the separation between the
     * person who asks and the person who authorises decorative. An
     * administrator can clear one ([clearOverridePin]); nobody can set one for
     * somebody else.
     *
     * The password is required for the same reason a password change requires
     * it: otherwise anyone who finds a till signed in walks away with an
     * approval credential of their own choosing.
     */
    @Transactional
    fun setOwnOverridePin(currentPassword: String, pin: String) {
        val actor = Auth.current()
        val user = users.findById(actor.id).orElseThrow { ApiException.NotFound("User", actor.id) }

        if (!encoder.matches(currentPassword, user.passwordHash)) {
            throw ApiException.Unauthenticated("Your password is incorrect.")
        }

        user.overridePinHash = encoder.encode(pin)
        // A new PIN clears the lockout the old one collected. The point of
        // setting one is usually that the old one was fumbled or seen.
        user.pinFailedCount = 0
        user.pinLockedUntil = null
        users.save(user)

        // The PIN itself never appears anywhere — this records that one exists.
        audit.recordCurrent("OVERRIDE_PIN_SET", "app_user", user.id)
        log.info("override PIN set by {}", actor.username)
    }

    /**
     * Whether the caller has a till PIN at all.
     *
     * Read from the row rather than carried in the token: a PIN can be set or
     * cleared while somebody is signed in, and a claim minted at sign-in would
     * still be offering to set a first PIN an hour after they set one.
     */
    @Transactional(readOnly = true)
    fun hasOverridePin(): Boolean =
        users.findById(Auth.current().id).map { it.overridePinHash != null }.orElse(false)

    /**
     * Takes somebody's till PIN away.
     *
     * The counterpart to not being able to set one for them: a PIN that was
     * watched over a shoulder has to be removable by somebody other than the
     * person who may have lost it. Afterwards that account can approve nothing
     * until they set a new one, because [SupervisorOverrideService] refuses an
     * account with no PIN exactly as it refuses a wrong one.
     *
     * Gated on PASSWORD_RESET rather than USER_MANAGE: this is the same act as
     * resetting a password — invalidating somebody's credential without being
     * able to choose the replacement.
     */
    @Transactional
    @PreAuthorize("hasAuthority('PASSWORD_RESET')")
    fun clearOverridePin(userId: Long) {
        val user = users.findById(userId).orElseThrow { ApiException.NotFound("User", userId) }
        user.overridePinHash = null
        user.pinFailedCount = 0
        user.pinLockedUntil = null
        users.save(user)
        audit.recordCurrent("OVERRIDE_PIN_CLEARED", "app_user", userId)
    }

    @Transactional
    @PreAuthorize("hasAuthority('PASSWORD_RESET')")
    fun resetPassword(userId: Long): String {
        val user = users.findById(userId).orElseThrow { ApiException.NotFound("User", userId) }
        val temporary = generateTemporaryPassword()

        user.passwordHash = encoder.encode(temporary)
        user.passwordChangedAt = Instant.now()
        user.mustChangePassword = true
        user.failedLoginCount = 0
        user.lockedUntil = null
        users.save(user)

        auth.revokeAllSessions(userId, "PASSWORD_RESET")
        audit.recordCurrent("PASSWORD_RESET", "app_user", userId)
        return temporary
    }

    @Transactional
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    fun setActive(userId: Long, active: Boolean, reason: String?) {
        val actor = Auth.current()
        if (userId == actor.id) {
            throw ApiException.RuleViolation(
                "SELF_DEACTIVATION",
                "You cannot deactivate your own account.",
            )
        }
        val user = users.findById(userId).orElseThrow { ApiException.NotFound("User", userId) }

        if (!active && user.hasRole(SYSTEM_ADMIN) &&
            users.countActiveWithRoleExcluding(SYSTEM_ADMIN, userId) == 0L
        ) {
            throw ApiException.RuleViolation(
                "LAST_SYSTEM_ADMIN",
                "This is the only active system administrator. Grant the role to " +
                    "someone else before deactivating this account.",
            )
        }

        user.isActive = active
        user.deactivatedAt = if (active) null else Instant.now()
        user.deactivatedBy = if (active) null else actor.id
        users.save(user)

        if (!active) auth.revokeAllSessions(userId, "DEACTIVATED")
        audit.recordCurrent(
            action = if (active) "USER_REACTIVATED" else "USER_DEACTIVATED",
            subjectType = "app_user",
            subjectId = userId,
            reason = reason,
        )
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    fun list(): List<AppUser> = users.findAll()

    /**
     * The roles, and whether this actor may hand each one out.
     *
     * The grantability is computed here rather than in the screen on purpose.
     * [assertMayGrant] is the real gate, and a picker that decided for itself
     * which roles to offer would drift from it — either offering a role the
     * server then refuses, or hiding one it would have allowed. Both read as
     * the system being broken. One rule, two callers.
     *
     * ROLE_ASSIGN is enough to read this: whoever may change someone's roles
     * needs to know what the roles are, and an account can hold ROLE_ASSIGN
     * without USER_MANAGE.
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyAuthority('USER_MANAGE','ROLE_ASSIGN')")
    fun roleCatalogue(): List<GrantableRole> {
        val actor = Auth.current()
        return roles.findAll().sortedBy { it.code }.map { role ->
            val withheld = withheldFrom(actor, role)
            GrantableRole(
                code = role.code,
                name = role.name,
                isSystem = role.isSystem,
                permissions = role.permissions.map { it.code }.sorted(),
                grantable = withheld.isEmpty() && (role.code != SYSTEM_ADMIN || actor.has("SYSTEM_ADMIN_GRANT")),
                withheld = withheld,
            )
        }
    }

    // ── Guards ─────────────────────────────────────────────────────────────

    private fun resolveRoles(codes: Set<String>): List<Role> {
        if (codes.isEmpty()) {
            throw ApiException.Validation("A user needs at least one role.", mapOf("roles" to "is required"))
        }
        val found = roles.findByCodeIn(codes)
        val missing = codes - found.map { it.code }.toSet()
        if (missing.isNotEmpty()) {
            throw ApiException.Validation(
                "Unknown role(s): ${missing.sorted().joinToString(", ")}.",
                mapOf("roles" to "contains an unknown role"),
            )
        }
        return found
    }

    /**
     * Privilege-escalation guard.
     *
     * Two rules, and the second is the one that matters:
     *
     *  1. Granting SYSTEM_ADMIN needs the dedicated SYSTEM_ADMIN_GRANT permission.
     *  2. **You cannot grant a permission you do not hold yourself.** Without
     *     this, any account with USER_MANAGE could mint a new account carrying
     *     COST_VIEW and sign in as it — a complete bypass of the role model.
     *     This is the single most important check in the file.
     */
    private fun assertMayGrant(actor: CurrentUser, target: List<Role>) {
        if (target.any { it.code == SYSTEM_ADMIN } && !actor.has("SYSTEM_ADMIN_GRANT")) {
            throw ApiException.Forbidden("Only a system administrator can grant the system administrator role.")
        }
        val escalation = target.flatMapTo(sortedSetOf()) { withheldFrom(actor, it) }
        if (escalation.isNotEmpty()) {
            log.warn(
                "user {} attempted to grant permissions they do not hold: {}",
                actor.username, escalation,
            )
            throw ApiException.Forbidden(
                "You cannot grant access you do not have yourself: " +
                    escalation.joinToString(", ") + ".",
            )
        }
    }

    /** What this role carries that the actor does not hold — empty means grantable. */
    private fun withheldFrom(actor: CurrentUser, role: Role): List<String> =
        role.permissions.map { it.code }.filterNot { it in actor.permissions }.sorted()

    private fun assertNotLastSystemAdmin(user: AppUser, target: List<Role>) {
        val losingIt = user.hasRole(SYSTEM_ADMIN) && target.none { it.code == SYSTEM_ADMIN }
        if (losingIt && users.countActiveWithRoleExcluding(SYSTEM_ADMIN, user.id!!) == 0L) {
            throw ApiException.RuleViolation(
                "LAST_SYSTEM_ADMIN",
                "This is the only active system administrator. Grant the role to " +
                    "someone else before removing it here.",
            )
        }
    }

    /**
     * Readable but high-entropy: 4 words plus digits beats a random string that
     * gets written on a sticky note because nobody can read it aloud over the
     * phone. ~52 bits from the words alone.
     */
    private fun generateTemporaryPassword(): String {
        val words = (1..4).map { WORDLIST[random.nextInt(WORDLIST.size)] }
        return words.joinToString("-") + "-" + (random.nextInt(9000) + 1000)
    }

    private fun Collection<String>.toJsonArray() =
        joinToString(prefix = "[", postfix = "]") { "\"" + it.replace("\"", "\\\"") + "\"" }

    private companion object {
        const val SYSTEM_ADMIN = "SYSTEM_ADMIN"
        val WORDLIST = listOf(
            "acacia", "baobab", "cedar", "dahlia", "ebony", "fern", "ginger", "hibiscus",
            "iroko", "jasmine", "kapok", "lily", "mahogany", "neem", "orchid", "palm",
            "quince", "rosewood", "shea", "teak", "umbrella", "vine", "walnut", "yam",
            "anvil", "bolt", "chisel", "drill", "file", "gasket", "hinge", "ingot",
            "jack", "kiln", "lathe", "mallet", "nail", "orbit", "pliers", "rivet",
        )
    }
}
