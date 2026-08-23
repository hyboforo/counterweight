package com.counterweight.identity.web

import com.counterweight.common.GhanaPhone
import com.counterweight.common.SafeText
import com.counterweight.common.Username
import com.counterweight.identity.domain.AppUser
import com.counterweight.identity.service.GrantableRole
import com.counterweight.identity.service.UserService
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.time.Instant

data class CreateUserRequest(
    @field:NotBlank(message = "is required")
    @field:Username
    val username: String,

    @field:NotBlank(message = "is required")
    @field:Size(min = 2, max = 80, message = "must be between 2 and 80 characters")
    @field:SafeText
    val fullName: String,

    @field:NotEmpty(message = "at least one role is required")
    @field:Size(max = 8, message = "is too many roles")
    val roles: Set<String>,

    @field:Positive(message = "must be a valid branch")
    val branchId: Long = 1,

    @field:GhanaPhone
    @field:Size(max = 20, message = "is too long")
    val phone: String? = null,

    @field:Email(message = "is not a valid email address")
    @field:Size(max = 120, message = "is too long")
    val email: String? = null,
)

data class SetRolesRequest(
    @field:NotEmpty(message = "at least one role is required")
    @field:Size(max = 8, message = "is too many roles")
    val roles: Set<String>,
)

data class SetActiveRequest(
    val active: Boolean,
    @field:SafeText
    @field:Size(max = 200, message = "is too long")
    val reason: String? = null,
)

data class UserSummary(
    val id: Long,
    val username: String,
    val fullName: String,
    val phone: String?,
    val email: String?,
    val branchId: Long,
    val roles: List<String>,
    val isActive: Boolean,
    val isLocked: Boolean,
    val mustChangePassword: Boolean,
    /**
     * Whether a till override PIN exists — never the PIN, never its hash.
     *
     * Worth reporting because its absence is otherwise invisible: a manager
     * with no PIN is refused at the till in exactly the same words as a wrong
     * one, deliberately, and without this the shop would be working that out
     * at the counter with a customer waiting.
     */
    val hasOverridePin: Boolean,
    val lastLoginAt: Instant?,
    val createdAt: Instant,
)

/*
 * Hand-written mapper as an extension function — ADR-001 guardrail 3, no
 * MapStruct and therefore no kapt.
 *
 * Note what is absent: passwordHash, overridePinHash, failedLoginCount. A
 * response DTO built by hand cannot accidentally serialise a credential the way
 * returning the entity would.
 */
private fun AppUser.toSummary() = UserSummary(
    id = id!!,
    username = username,
    fullName = fullName,
    phone = phone,
    email = email,
    branchId = branchId,
    roles = roles.map { it.code }.sorted(),
    isActive = isActive,
    isLocked = isLocked(),
    mustChangePassword = mustChangePassword,
    hasOverridePin = overridePinHash != null,
    lastLoginAt = lastLoginAt,
    createdAt = createdAt,
)

data class CreatedUserResponse(
    val user: UserSummary,
    /**
     * Shown once and never retrievable again. The account is forced to replace
     * it on first sign-in, so it is a delivery mechanism rather than a
     * credential the admin keeps.
     */
    val temporaryPassword: String,
)

@RestController
@RequestMapping("/api/admin/users")
class UserAdminController(private val userService: UserService) {

    @GetMapping
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    fun list(): List<UserSummary> = userService.list().map { it.toSummary() }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody body: CreateUserRequest): CreatedUserResponse {
        val (user, temporary) = userService.createUser(
            username = body.username,
            fullName = body.fullName,
            roleCodes = body.roles,
            branchId = body.branchId,
            phone = body.phone,
            email = body.email,
        )
        return CreatedUserResponse(user.toSummary(), temporary)
    }

    @PutMapping("/{id}/roles")
    fun setRoles(@PathVariable id: Long, @Valid @RequestBody body: SetRolesRequest): ResponseEntity<Void> {
        userService.setRoles(id, body.roles)
        return ResponseEntity.noContent().build()
    }

    @PutMapping("/{id}/active")
    fun setActive(@PathVariable id: Long, @Valid @RequestBody body: SetActiveRequest): ResponseEntity<Void> {
        userService.setActive(id, body.active, body.reason)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{id}/reset-password")
    fun resetPassword(@PathVariable id: Long): Map<String, String> =
        mapOf("temporaryPassword" to userService.resetPassword(id))

    /**
     * Takes somebody's till PIN away.
     *
     * There is deliberately no matching PUT. A PIN can be removed by an
     * administrator and chosen only by its owner — see
     * `UserService.setOwnOverridePin`.
     */
    @DeleteMapping("/{id}/override-pin")
    fun clearOverridePin(@PathVariable id: Long): ResponseEntity<Void> {
        userService.clearOverridePin(id)
        return ResponseEntity.noContent().build()
    }
}

/**
 * The roles, read-only.
 *
 * Nothing here creates or edits a role, which is why ROLE_MANAGE stays dormant:
 * the six roles are seeded by the migrations and their grants are the shop's
 * separation of duties written down. A screen that let an administrator add a
 * permission to SALES_STAFF would quietly undo the reason SALES_STAFF exists.
 * Changing what a role carries is a migration, deliberately.
 */
@RestController
@RequestMapping("/api/admin/roles")
class RoleAdminController(private val userService: UserService) {

    @GetMapping
    fun list(): List<GrantableRole> = userService.roleCatalogue()
}
