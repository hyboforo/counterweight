package com.counterweight.identity.repo

import com.counterweight.identity.domain.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
interface AppUserRepository : JpaRepository<AppUser, Long> {

    /*
     * Case-insensitive on purpose, matching the app_user_username_ci unique
     * index added in V2. If lookup were case-sensitive but the index were not,
     * 'Ama' would authenticate against the row created as 'ama' only by
     * accident of collation — or not at all, while still blocking registration.
     */
    @Query("SELECT u FROM AppUser u WHERE lower(u.username) = lower(:username)")
    fun findByUsernameIgnoreCase(@Param("username") username: String): AppUser?

    @Query("SELECT u FROM AppUser u WHERE lower(u.email) = lower(:email)")
    fun findByEmailIgnoreCase(@Param("email") email: String): AppUser?

    fun existsByUsernameIgnoreCase(username: String): Boolean

    @Modifying
    @Query("UPDATE AppUser u SET u.failedLoginCount = u.failedLoginCount + 1 WHERE u.id = :id")
    fun incrementFailedLogin(@Param("id") id: Long): Int

    @Query("SELECT u.failedLoginCount FROM AppUser u WHERE u.id = :id")
    fun failedLoginCount(@Param("id") id: Long): Short?

    @Modifying
    @Query("UPDATE AppUser u SET u.lockedUntil = :until, u.failedLoginCount = 0 WHERE u.id = :id")
    fun lockUntil(@Param("id") id: Long, @Param("until") until: Instant): Int

    @Modifying
    @Query("UPDATE AppUser u SET u.failedLoginCount = 0, u.lockedUntil = NULL, u.lastLoginAt = :now WHERE u.id = :id")
    fun recordSuccessfulLogin(@Param("id") id: Long, @Param("now") now: Instant): Int

    @Query("""
        SELECT count(u) FROM AppUser u JOIN u.roles r
         WHERE r.code = :roleCode AND u.isActive = true AND u.id <> :excludingUserId
    """)
    fun countActiveWithRoleExcluding(
        @Param("roleCode") roleCode: String,
        @Param("excludingUserId") excludingUserId: Long,
    ): Long

    /**
     * How many sales this account rang up.
     *
     * Asked before an account is deleted, so the refusal can say "she has sold
     * 412 times" rather than reporting a foreign-key violation. Native,
     * because `sales` owns that table and identity may not import its entities.
     */
    @Query(value = "SELECT count(*) FROM sale WHERE cashier_id = :userId", nativeQuery = true)
    fun countSalesBy(@Param("userId") userId: Long): Long
}

@Repository
interface RoleRepository : JpaRepository<Role, Long> {
    fun findByCode(code: String): Role?
    fun findByCodeIn(codes: Collection<String>): List<Role>
}

@Repository
interface PermissionRepository : JpaRepository<Permission, String>

@Repository
interface RefreshTokenRepository : JpaRepository<RefreshToken, Long> {

    fun findByTokenHash(tokenHash: String): RefreshToken?

    @Query("SELECT t FROM RefreshToken t WHERE t.userId = :userId AND t.revokedAt IS NULL")
    fun findActiveForUser(@Param("userId") userId: Long): List<RefreshToken>

    @Modifying
    @Query("""
        UPDATE RefreshToken t SET t.revokedAt = :now, t.revokedReason = :reason
         WHERE t.userId = :userId AND t.revokedAt IS NULL
    """)
    fun revokeAllForUser(
        @Param("userId") userId: Long,
        @Param("now") now: Instant,
        @Param("reason") reason: String,
    ): Int

    /** Housekeeping: expired tokens are dead weight and a needless liability. */
    @Modifying
    @Query("DELETE FROM RefreshToken t WHERE t.expiresAt < :before")
    fun deleteExpiredBefore(@Param("before") before: Instant): Int
}

@Repository
interface LoginAttemptRepository : JpaRepository<LoginAttempt, Long> {

    @Query("""
        SELECT count(a) FROM LoginAttempt a
         WHERE lower(a.username) = lower(:username)
           AND a.succeeded = false
           AND a.attemptedAt > :since
    """)
    fun countRecentFailuresForUsername(
        @Param("username") username: String,
        @Param("since") since: Instant,
    ): Long
}

@Repository
interface AuditLogRepository : JpaRepository<AuditLog, Long>
