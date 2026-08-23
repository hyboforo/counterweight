package com.counterweight.identity.domain

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

/*
 * JPA entities.
 *
 * Per ADR-001 guardrail 1 these are plain classes with `var` properties, never
 * `data class`. A data class derives equals/hashCode from every property, which
 * breaks the moment Hibernate mutates a field or returns a lazy proxy — two
 * "equal" entities stop being equal mid-transaction. Identity is the database
 * id and nothing else.
 *
 * The kotlin-jpa (noarg) compiler plugin synthesises the no-arg constructor
 * Hibernate needs, so no property has to carry a meaningless default.
 */

@Entity
@Table(name = "app_user")
class AppUser(
    @Column(name = "branch_id", nullable = false)
    var branchId: Long,

    @Column(nullable = false, unique = true)
    var username: String,

    @Column(name = "full_name", nullable = false)
    var fullName: String,

    @Column(name = "password_hash", nullable = false)
    var passwordHash: String,
) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    var phone: String? = null
    var email: String? = null

    /** Argon2id hash of the till override PIN. Null means the user has none. */
    @Column(name = "override_pin_hash")
    var overridePinHash: String? = null

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true

    @Column(name = "last_login_at")
    var lastLoginAt: Instant? = null

    @Column(name = "failed_login_count", nullable = false)
    var failedLoginCount: Short = 0

    @Column(name = "locked_until")
    var lockedUntil: Instant? = null

    @Column(name = "password_changed_at", nullable = false)
    var passwordChangedAt: Instant = Instant.now()

    @Column(name = "must_change_password", nullable = false)
    var mustChangePassword: Boolean = false

    @Column(name = "pin_failed_count", nullable = false)
    var pinFailedCount: Short = 0

    @Column(name = "pin_locked_until")
    var pinLockedUntil: Instant? = null

    @Column(name = "created_by")
    var createdBy: Long? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()

    @Column(name = "deactivated_at")
    var deactivatedAt: Instant? = null

    @Column(name = "deactivated_by")
    var deactivatedBy: Long? = null

    /*
     * EAGER on purpose, and it is the only EAGER association in the codebase.
     * Roles are needed on every single authenticated request to build the
     * authorities; fetching them lazily would mean either an N+1 on the hot
     * path or a detached-proxy failure once the session closes.
     */
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "user_role",
        joinColumns = [JoinColumn(name = "user_id")],
        inverseJoinColumns = [JoinColumn(name = "role_id")],
    )
    var roles: MutableSet<Role> = mutableSetOf()

    fun isLocked(now: Instant = Instant.now()): Boolean =
        lockedUntil?.isAfter(now) == true

    fun isPinLocked(now: Instant = Instant.now()): Boolean =
        pinLockedUntil?.isAfter(now) == true

    /** Flattened permission codes across every assigned role. */
    fun permissionCodes(): Set<String> =
        roles.flatMapTo(mutableSetOf()) { it.permissions.map { p -> p.code } }

    fun hasRole(code: String): Boolean = roles.any { it.code == code }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AppUser) return false
        // An unsaved entity is only equal to itself — never to another unsaved one.
        return id != null && id == other.id
    }

    override fun hashCode(): Int = javaClass.hashCode()

    override fun toString(): String = "AppUser(id=$id, username='$username')"
}

@Entity
@Table(name = "role")
class Role(
    @Column(nullable = false, unique = true)
    var code: String,

    @Column(nullable = false)
    var name: String,
) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    /** System roles are seeded by migration and may not be renamed or deleted. */
    @Column(name = "is_system", nullable = false)
    var isSystem: Boolean = false

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "role_permission",
        joinColumns = [JoinColumn(name = "role_id")],
        inverseJoinColumns = [JoinColumn(name = "permission_code", referencedColumnName = "code")],
    )
    var permissions: MutableSet<Permission> = mutableSetOf()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Role) return false
        return id != null && id == other.id
    }
    override fun hashCode(): Int = javaClass.hashCode()
    override fun toString(): String = "Role($code)"
}

@Entity
@Table(name = "permission")
class Permission(
    @Id
    var code: String,

    @Column(nullable = false)
    var description: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Permission) return false
        return code == other.code
    }
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "Permission($code)"
}

/**
 * A refresh token, stored as a SHA-256 hash.
 *
 * Never the token itself: a leaked database backup must not hand an attacker
 * working sessions. [replacedBy] powers reuse detection — presenting a token
 * that has already been rotated means it was captured, so the whole family is
 * revoked. See AuthService.refresh.
 */
@Entity
@Table(name = "refresh_token")
class RefreshToken(
    @Column(name = "user_id", nullable = false)
    var userId: Long,

    @Column(name = "token_hash", nullable = false, unique = true)
    var tokenHash: String,

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant,
) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "issued_at", nullable = false)
    var issuedAt: Instant = Instant.now()

    @Column(name = "revoked_at")
    var revokedAt: Instant? = null

    @Column(name = "revoked_reason")
    var revokedReason: String? = null

    @Column(name = "replaced_by")
    var replacedBy: Long? = null

    @Column(name = "client_label")
    var clientLabel: String? = null

    fun isUsable(now: Instant = Instant.now()): Boolean =
        revokedAt == null && expiresAt.isAfter(now)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RefreshToken) return false
        return id != null && id == other.id
    }
    override fun hashCode(): Int = javaClass.hashCode()
}

/** Every sign-in attempt, successful or not — rate limiting and forensics. */
@Entity
@Table(name = "login_attempt")
class LoginAttempt(
    @Column(nullable = false)
    var username: String,

    @Column(nullable = false)
    var succeeded: Boolean,
) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "user_id")
    var userId: Long? = null

    @Column(name = "failure_code")
    var failureCode: String? = null

    @Column(name = "client_label")
    var clientLabel: String? = null

    @Column(name = "attempted_at", nullable = false)
    var attemptedAt: Instant = Instant.now()
}

/**
 * Append-only. UPDATE and DELETE are rejected by a database trigger (V1), not
 * by convention — an audit log the application can rewrite is not an audit log.
 */
@Entity
@Table(name = "audit_log")
class AuditLog(
    @Column(name = "branch_id", nullable = false)
    var branchId: Long,

    @Column(name = "actor_id", nullable = false)
    var actorId: Long,

    @Column(nullable = false)
    var action: String,

    @Column(name = "subject_type", nullable = false)
    var subjectType: String,
) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    /** Set when a supervisor authorised an action taken by someone else. */
    @Column(name = "approver_id")
    var approverId: Long? = null

    @Column(name = "subject_id")
    var subjectId: Long? = null

    /*
     * Hibernate 6 binds a String parameter as varchar unless told otherwise,
     * and PostgreSQL will not implicitly cast varchar to jsonb — the insert
     * fails outright. @JdbcTypeCode(JSON) is what makes String -> jsonb work.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_value", columnDefinition = "jsonb")
    var beforeValue: String? = null

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_value", columnDefinition = "jsonb")
    var afterValue: String? = null

    var reason: String? = null

    @Column(name = "occurred_at", nullable = false)
    var occurredAt: Instant = Instant.now()
}
