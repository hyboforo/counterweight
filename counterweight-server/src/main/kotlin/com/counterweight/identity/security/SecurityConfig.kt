package com.counterweight.identity.security

import com.counterweight.common.ApiErrorResponse
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken
import org.springframework.stereotype.Component
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Instant
import java.util.UUID

/** Who is making this request. Immutable, built fresh from the token each time. */
data class CurrentUser(
    val id: Long,
    val username: String,
    val branchId: Long,
    val roles: Set<String>,
    val permissions: Set<String>,
    /**
     * True while the holder is still on the password somebody else chose for
     * them. Everything but changing it is refused — see
     * [TemporaryPasswordFilter].
     */
    val mustChangePassword: Boolean = false,
) {
    fun has(permission: String) = permission in permissions
    fun hasRole(role: String) = role in roles
}

/** Convenience accessor for services that need the caller. */
object Auth {
    fun currentOrNull(): CurrentUser? =
        SecurityContextHolder.getContext().authentication?.principal as? CurrentUser

    fun current(): CurrentUser =
        currentOrNull() ?: throw IllegalStateException("no authenticated user in context")
}

@Configuration
@EnableWebSecurity
@EnableMethodSecurity   // enables @PreAuthorize on services and controllers
class SecurityConfig(
    private val jwtService: JwtService,
    private val objectMapper: ObjectMapper,
) {

    /**
     * Argon2id, the current OWASP recommendation for password storage.
     *
     * Parameters are Spring Security's defaults for `defaultsForSpringSecurity_v5_8`:
     * 16-byte salt, 32-byte hash, 1 degree of parallelism, 16 MiB memory, 2
     * iterations. Memory-hardness is the point — it is what makes GPU cracking
     * of a stolen `password_hash` column expensive rather than trivial.
     *
     * The shop server is a modest mini-PC, so this is deliberately not tuned
     * higher: a login must still feel instant at the till.
     */
    @Bean
    fun passwordEncoder(): PasswordEncoder =
        Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        jwtFilter: JwtAuthenticationFilter,
        temporaryPasswordFilter: TemporaryPasswordFilter,
    ): SecurityFilterChain {
        http
            // No cookies, no server session, so CSRF has nothing to ride on.
            // The token is sent in an Authorization header the browser does not
            // attach automatically.
            .csrf { it.disable() }
            .cors { it.configurationSource(corsConfigurationSource()) }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .headers { h ->
                h.frameOptions { it.deny() }
                h.contentTypeOptions { }
                h.httpStrictTransportSecurity { hsts ->
                    hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000)
                }
                h.referrerPolicy { it.policy(
                    org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
                        .ReferrerPolicy.SAME_ORIGIN
                ) }
                /*
                 * This server also serves the till itself, so the policy has to
                 * let the app's own bundle run — `default-src 'none'` was right
                 * while the API was all there was and blocks its own scripts the
                 * moment `static/` is populated. Everything still comes from
                 * this origin and nothing else: no CDN, no external font, no
                 * frame. `data:` is there for the images the receipt preview
                 * builds in the browser.
                 */
                h.contentSecurityPolicy {
                    it.policyDirectives(
                        "default-src 'self'; " +
                            "script-src 'self'; " +
                            "style-src 'self' 'unsafe-inline'; " +
                            "img-src 'self' data:; " +
                            "font-src 'self' data:; " +
                            "connect-src 'self'; " +
                            "object-src 'none'; " +
                            "base-uri 'self'; " +
                            "form-action 'self'; " +
                            "frame-ancestors 'none'"
                    )
                }
            }
            .authorizeHttpRequests { reg ->
                reg
                    .requestMatchers(
                        "/api/auth/login",
                        "/api/auth/refresh",
                        "/actuator/health",
                        "/actuator/health/**",
                    ).permitAll()
                    // Swagger is developer tooling. Exposed only where explicitly
                    // enabled; see application.yml springdoc settings.
                    .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                    /*
                     * The till itself, served from this jar.
                     *
                     * The bundle is the sign-in screen — refusing it until you
                     * are signed in is a closed door with the handle behind it.
                     * It carries no data: every figure on it arrives from an
                     * API call that is still authenticated, and the build
                     * deliberately contains no cost or margin field at all.
                     */
                    .requestMatchers("/", "/index.html", "/favicon.ico", "/assets/**").permitAll()
                    .anyRequest().authenticated()
            }
            .exceptionHandling { ex ->
                ex.authenticationEntryPoint { _, res, _ -> writeError(res, HttpStatus.UNAUTHORIZED,
                    "UNAUTHENTICATED", "Sign in to continue.") }
                ex.accessDeniedHandler { _, res, _ -> writeError(res, HttpStatus.FORBIDDEN,
                    "FORBIDDEN", "You do not have permission to do that.") }
            }
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter::class.java)
            // After the JWT filter, so there is a principal to inspect: a
            // temporary password is a state of an authenticated user, not a
            // failure to authenticate.
            .addFilterAfter(temporaryPasswordFilter, JwtAuthenticationFilter::class.java)

        return http.build()
    }

    /**
     * The till is a browser on the shop LAN talking to this server, so the
     * allow-list is narrow and explicit. Wildcards are refused outright: with
     * credentials in play a permissive CORS policy hands any page the user
     * visits the ability to drive the API as them.
     */
    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val config = CorsConfiguration().apply {
            allowedOriginPatterns = listOf(
                "http://localhost:*", "http://127.0.0.1:*",
                "https://localhost:*", "https://127.0.0.1:*",
                // The shop LAN. Narrow this to the real host once deployed.
                "http://192.168.*.*:*", "http://10.*.*.*:*",
            )
            allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            allowedHeaders = listOf(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE, "Idempotency-Key")
            exposedHeaders = listOf("Idempotency-Key")
            allowCredentials = true
            maxAge = 3600
        }
        return UrlBasedCorsConfigurationSource().apply { registerCorsConfiguration("/**", config) }
    }

    private fun writeError(res: HttpServletResponse, status: HttpStatus, code: String, message: String) {
        res.status = status.value()
        res.contentType = MediaType.APPLICATION_JSON_VALUE
        res.characterEncoding = "UTF-8"
        objectMapper.writeValue(
            res.outputStream,
            ApiErrorResponse(code, message, emptyMap(), UUID.randomUUID().toString().take(8), Instant.now()),
        )
    }
}

/**
 * Reads the bearer token and populates the security context.
 *
 * Deliberately does NOT reject bad tokens itself — it simply leaves the context
 * empty and lets the authorization rules decide. That keeps one place
 * responsible for the 401/403 decision.
 */
@org.springframework.stereotype.Component
class JwtAuthenticationFilter(private val jwtService: JwtService) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val header = request.getHeader(HttpHeaders.AUTHORIZATION)
        if (header != null && header.startsWith(BEARER, ignoreCase = true)) {
            val claims = jwtService.parse(header.substring(BEARER.length).trim())
            if (claims != null && SecurityContextHolder.getContext().authentication == null) {
                @Suppress("UNCHECKED_CAST")
                val principal = CurrentUser(
                    id = claims.subject.toLong(),
                    username = claims["usr"] as String,
                    branchId = (claims["brn"] as Number).toLong(),
                    roles = ((claims["rol"] as? List<String>) ?: emptyList()).toSet(),
                    permissions = ((claims["prm"] as? List<String>) ?: emptyList()).toSet(),
                    mustChangePassword = claims["pwd"] == true,
                )
                // Permissions become authorities verbatim, so @PreAuthorize reads
                // hasAuthority('SALE_VOID') — a permission, never a role. Roles
                // are only ever bundles of permissions.
                val authorities = principal.permissions.map { SimpleGrantedAuthority(it) } +
                    principal.roles.map { SimpleGrantedAuthority("ROLE_$it") }

                SecurityContextHolder.getContext().authentication =
                    PreAuthenticatedAuthenticationToken(principal, null, authorities)
            }
        }
        chain.doFilter(request, response)
    }

    private companion object { const val BEARER = "Bearer " }
}

/**
 * A temporary password buys exactly one thing: replacing it.
 *
 * `must_change_password` has been set on every account the system creates
 * since V2 — the bootstrap admin, every enrolled cashier, every reset — and
 * until this filter existed **nothing read it**. The login response carried the
 * flag, the till ignored it, and a cashier handed a temporary password on
 * Monday could still be selling with it in March. That is the same shape as a
 * permission that is granted and never checked, and it is worse here: the
 * password was typed on a piece of paper by somebody else, and whoever walked
 * past the counter read it too.
 *
 * The three routes left open are the ones a person in that state legitimately
 * needs: change the password, ask who they are, and sign out. Everything else
 * — selling, the back office, reports — answers 403 with a code the till turns
 * into the change-password screen rather than an error.
 */
@Component
class TemporaryPasswordFilter(private val objectMapper: com.fasterxml.jackson.databind.ObjectMapper) :
    OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val principal = Auth.currentOrNull()
        if (principal != null && principal.mustChangePassword && !isAllowed(request)) {
            response.status = HttpStatus.FORBIDDEN.value()
            response.contentType = "application/json"
            objectMapper.writeValue(
                response.outputStream,
                mapOf(
                    "code" to "PASSWORD_CHANGE_REQUIRED",
                    "message" to "Set your own password before using the till.",
                    "traceId" to "",
                ),
            )
            return
        }
        chain.doFilter(request, response)
    }

    private fun isAllowed(request: HttpServletRequest): Boolean {
        val path = request.requestURI
        return path == "/api/auth/change-password" ||
            path == "/api/auth/logout" ||
            path == "/api/auth/me" ||
            /*
             * Signing in and refreshing carry their own identity in the body,
             * so a stale Authorization header on either is noise rather than a
             * claim — and refusing them on the strength of it is a trap: the
             * sign-in immediately after a password change presents the token
             * that change just invalidated, and would be refused for saying
             * exactly what it was issued saying. Neither endpoint grants
             * anything the temporary password should not reach; both re-read
             * the flag from the database and reissue it.
             */
            path == "/api/auth/login" ||
            path == "/api/auth/refresh"
    }
}
