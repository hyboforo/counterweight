package com.counterweight.identity.web

import com.counterweight.common.OverridePin
import com.counterweight.common.SafeText
import com.counterweight.common.Username
import com.counterweight.identity.service.AuthService
import com.counterweight.identity.service.AuthTokens
import com.counterweight.identity.service.UserService
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/*
 * Request DTOs are `data class`es — ADR-001 guardrail 2. They are immutable,
 * validated at the edge, and never touch the persistence layer.
 *
 * Note what is NOT annotated: passwords carry only @NotBlank and a generous
 * @Size. Applying composition rules here would leak the policy to unauthenticated
 * callers and reject legitimate passphrases; PasswordPolicy owns that decision
 * and only applies it where a password is being *set*.
 */

data class LoginRequest(
    @field:NotBlank(message = "is required")
    @field:Size(max = 40, message = "is too long")
    val username: String,

    @field:NotBlank(message = "is required")
    @field:Size(max = 200, message = "is too long")
    val password: String,

    /** Which till this is, for the session list and the audit trail. */
    @field:SafeText
    @field:Size(max = 120, message = "is too long")
    val clientLabel: String? = null,
)

data class RefreshRequest(
    @field:NotBlank(message = "is required")
    @field:Size(max = 200, message = "is not a valid token")
    val refreshToken: String,

    @field:SafeText
    @field:Size(max = 120, message = "is too long")
    val clientLabel: String? = null,
)

data class LogoutRequest(
    @field:NotBlank(message = "is required")
    @field:Size(max = 200, message = "is not a valid token")
    val refreshToken: String,
)

data class ChangePasswordRequest(
    @field:NotBlank(message = "is required")
    @field:Size(max = 200, message = "is too long")
    val currentPassword: String,

    @field:NotBlank(message = "is required")
    @field:Size(max = 200, message = "is too long")
    val newPassword: String,
)

data class SetOverridePinRequest(
    @field:NotBlank(message = "is required")
    @field:Size(max = 200, message = "is too long")
    val currentPassword: String,

    @field:NotBlank(message = "is required")
    @field:OverridePin
    val pin: String,
)

data class AuthResponse(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String = "Bearer",
    val expiresIn: Long,
    val mustChangePassword: Boolean,
    val username: String,
    val roles: Set<String>,
    val permissions: Set<String>,
)

private fun AuthTokens.toResponse() = AuthResponse(
    accessToken = accessToken,
    refreshToken = refreshToken,
    expiresIn = expiresInSeconds,
    mustChangePassword = mustChangePassword,
    username = username,
    roles = roles,
    permissions = permissions,
)

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val authService: AuthService,
    private val userService: UserService,
) {

    @PostMapping("/login")
    fun login(@Valid @RequestBody body: LoginRequest, req: HttpServletRequest): AuthResponse =
        authService.login(body.username, body.password, body.clientLabel ?: req.remoteAddr).toResponse()

    @PostMapping("/refresh")
    fun refresh(@Valid @RequestBody body: RefreshRequest, req: HttpServletRequest): AuthResponse =
        authService.refresh(body.refreshToken, body.clientLabel ?: req.remoteAddr).toResponse()

    @PostMapping("/logout")
    fun logout(@Valid @RequestBody body: LogoutRequest): ResponseEntity<Void> {
        authService.logout(body.refreshToken)
        return ResponseEntity.noContent().build()
    }

    /**
     * Reachable while `mustChangePassword` is set — it is the only thing that
     * is. A user with a temporary password can do exactly one thing: replace it.
     */
    @PostMapping("/change-password")
    fun changePassword(@Valid @RequestBody body: ChangePasswordRequest): ResponseEntity<Void> {
        userService.changeOwnPassword(body.currentPassword, body.newPassword)
        return ResponseEntity.noContent().build()
    }

    /**
     * Sets the caller's own till override PIN.
     *
     * Here rather than under `/api/admin` because it is nobody else's to set.
     * An approval is recorded against the person whose PIN was typed, so a PIN
     * chosen by an administrator would put somebody's name on an authorisation
     * they never gave. Clearing one is an administrator's job; choosing one is
     * not.
     */
    @PostMapping("/override-pin")
    fun setOverridePin(@Valid @RequestBody body: SetOverridePinRequest): ResponseEntity<Void> {
        userService.setOwnOverridePin(body.currentPassword, body.pin)
        return ResponseEntity.noContent().build()
    }

    /**
     * Who am I — used by the till on load to decide what to render.
     *
     * `mustChangePassword` is here because a reload has only the stored token
     * to go on: the flag arrives with the login response, and a till closed at
     * lunch comes back without one. Without it the app would render the selling
     * screen to somebody the [TemporaryPasswordFilter] refuses every request
     * from, which reads as the system being broken rather than as one thing
     * left to do.
     */
    @GetMapping("/me")
    fun me(): Map<String, Any> {
        val me = com.counterweight.identity.security.Auth.current()
        return mapOf(
            "id" to me.id,
            "username" to me.username,
            "branchId" to me.branchId,
            "roles" to me.roles.sorted(),
            "permissions" to me.permissions.sorted(),
            "mustChangePassword" to me.mustChangePassword,
            "hasOverridePin" to userService.hasOverridePin(),
        )
    }
}
