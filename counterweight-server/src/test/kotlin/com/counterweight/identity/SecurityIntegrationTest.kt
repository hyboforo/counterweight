package com.counterweight.identity

import com.counterweight.identity.repo.AppUserRepository
import com.counterweight.identity.repo.RoleRepository
import com.counterweight.common.ApiException
import com.counterweight.identity.service.SupervisorOverrideService
import com.counterweight.identity.service.UserService
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.io.File

/**
 * Security behaviour, exercised through the real HTTP stack against a real
 * PostgreSQL — filters, Spring Security, validation, services and constraints
 * all in play.
 *
 * Unit-testing the service alone would prove nothing here: most of what makes
 * this safe is the interaction between the filter chain, the method-security
 * annotations and the database triggers.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@DisplayName("Security and access control")
class SecurityIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val pg = PostgreSQLContainer("postgres:16")
            .withDatabaseName("cw").withUsername("cw").withPassword("cw")

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", pg::getJdbcUrl)
            registry.add("spring.datasource.username", pg::getUsername)
            registry.add("spring.datasource.password", pg::getPassword)
            registry.add("counterweight.auth.jwt-secret") { "test-secret-that-is-definitely-long-enough-32b+" }
            registry.add("COUNTERWEIGHT_BOOTSTRAP_PASSWORD") { BOOTSTRAP_PASSWORD }
        }

        const val BOOTSTRAP_PASSWORD = "bootstrap-correct-horse-staple-42"
    }

    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var json: ObjectMapper
    @Autowired private lateinit var users: AppUserRepository
    @Autowired private lateinit var roles: RoleRepository
    @Autowired private lateinit var encoder: PasswordEncoder
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var overrides: SupervisorOverrideService

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun login(username: String, password: String): MvcResult =
        mvc.perform(
            post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"$username","password":"$password"}""")
        ).andReturn()

    private fun tokenOf(result: MvcResult): String =
        json.readTree(result.response.contentAsString).get("accessToken").asText()

    private fun bodyOf(result: MvcResult) = json.readTree(result.response.contentAsString)

    /** Signs in as the bootstrap SYSTEM_ADMIN, clearing its must-change flag first. */
    private fun sysadminToken(): String {
        users.findByUsernameIgnoreCase("sysadmin")!!.let {
            it.mustChangePassword = false
            users.save(it)
        }
        return tokenOf(login("sysadmin", BOOTSTRAP_PASSWORD))
    }

    /** Creates a user directly, bypassing the API, so tests can control roles. */
    private fun seedUser(username: String, password: String, roleCodes: Set<String>): Long {
        val u = com.counterweight.identity.domain.AppUser(
            branchId = 1, username = username, fullName = "Test $username",
            passwordHash = encoder.encode(password),
        ).also { it.roles = roles.findByCodeIn(roleCodes).toMutableSet() }
        return users.save(u).id!!
    }

    // ── Authentication ─────────────────────────────────────────────────────

    @Test
    @DisplayName("an unknown username and a wrong password are indistinguishable")
    fun `login failures do not enumerate users`() {
        val unknown = login("no-such-person", "whatever-long-password")
        seedUser("realuser", "correct-horse-battery-99", setOf("SALES_STAFF"))
        val wrongPassword = login("realuser", "definitely-not-the-password")

        assertThat(unknown.response.status).isEqualTo(401)
        assertThat(wrongPassword.response.status).isEqualTo(401)
        assertThat(bodyOf(unknown).get("message").asText())
            .describedAs("a different message for an unknown user would enumerate accounts")
            .isEqualTo(bodyOf(wrongPassword).get("message").asText())
    }

    @Test
    fun `a valid login returns a token carrying the caller's permissions`() {
        seedUser("sales1", "correct-horse-battery-99", setOf("SALES_STAFF"))
        val body = bodyOf(login("sales1", "correct-horse-battery-99"))

        assertThat(body.get("accessToken").asText()).isNotBlank()
        assertThat(body.get("refreshToken").asText()).isNotBlank()
        val perms = body.get("permissions").map { it.asText() }
        assertThat(perms).contains("SALE_CREATE")
        assertThat(perms)
            .describedAs("sales staff must never be able to see margin")
            .doesNotContain("COST_VIEW")
    }

    @Test
    fun `the account locks after repeated failures and says so`() {
        seedUser("locky", "correct-horse-battery-99", setOf("SALES_STAFF"))
        repeat(5) { login("locky", "wrong-password-here") }

        val locked = login("locky", "correct-horse-battery-99")
        assertThat(locked.response.status)
            .describedAs("the correct password must not work while locked out")
            .isEqualTo(423)
        assertThat(bodyOf(locked).get("code").asText()).isEqualTo("ACCOUNT_LOCKED")
    }

    @Test
    fun `no response ever contains a password hash`() {
        seedUser("hashcheck", "correct-horse-battery-99", setOf("SALES_STAFF"))
        val loginBody = login("hashcheck", "correct-horse-battery-99").response.contentAsString
        val listBody = mvc.perform(
            get("/api/admin/users").header("Authorization", "Bearer ${sysadminToken()}")
        ).andReturn().response.contentAsString

        listOf(loginBody, listBody).forEach { body ->
            assertThat(body).doesNotContain("argon2")
            assertThat(body).doesNotContain("passwordHash")
            assertThat(body).doesNotContain("overridePinHash")
        }
    }

    // ── Session handling ───────────────────────────────────────────────────

    @Test
    @DisplayName("a replayed refresh token kills every session for that user")
    fun `refresh reuse is detected`() {
        seedUser("rotate", "correct-horse-battery-99", setOf("SALES_STAFF"))
        val first = bodyOf(login("rotate", "correct-horse-battery-99"))
        val originalRefresh = first.get("refreshToken").asText()

        // Normal rotation succeeds.
        val rotated = mvc.perform(
            post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("""{"refreshToken":"$originalRefresh"}""")
        ).andReturn()
        assertThat(rotated.response.status).isEqualTo(200)
        val newRefresh = bodyOf(rotated).get("refreshToken").asText()

        // Replaying the original is treated as theft.
        val replay = mvc.perform(
            post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("""{"refreshToken":"$originalRefresh"}""")
        ).andReturn()
        assertThat(replay.response.status).isEqualTo(401)

        // ...and the legitimate holder is signed out too. We cannot tell which
        // party is genuine, so both are stopped.
        val afterPurge = mvc.perform(
            post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("""{"refreshToken":"$newRefresh"}""")
        ).andReturn()
        assertThat(afterPurge.response.status)
            .describedAs("reuse must revoke the whole token family, not just the replayed one")
            .isEqualTo(401)
    }

    // ── Authorization ──────────────────────────────────────────────────────

    @Test
    fun `an unauthenticated request is refused`() {
        assertThat(mvc.perform(get("/api/admin/users")).andReturn().response.status).isEqualTo(401)
    }

    @Test
    fun `sales staff cannot reach user administration`() {
        seedUser("sales2", "correct-horse-battery-99", setOf("SALES_STAFF"))
        val token = tokenOf(login("sales2", "correct-horse-battery-99"))
        val res = mvc.perform(get("/api/admin/users").header("Authorization", "Bearer $token")).andReturn()
        assertThat(res.response.status).isEqualTo(403)
    }

    @Test
    @DisplayName("a tampered token is rejected, not merely ignored")
    fun `forged tokens fail`() {
        seedUser("tamper", "correct-horse-battery-99", setOf("SALES_STAFF"))
        val token = tokenOf(login("tamper", "correct-horse-battery-99"))
        // Flip the payload; the signature no longer matches.
        val parts = token.split(".")
        val forged = parts[0] + "." + parts[1].dropLast(4) + "AAAA." + parts[2]

        assertThat(
            mvc.perform(get("/api/auth/me").header("Authorization", "Bearer $forged")).andReturn().response.status
        ).isEqualTo(401)
    }

    // ── Privilege escalation ───────────────────────────────────────────────

    @Test
    @DisplayName("nobody can grant access they do not hold themselves")
    fun `privilege escalation via user creation is blocked`() {
        // ADMIN holds USER_MANAGE but not CONFIG_MANAGE or BACKUP_MANAGE.
        seedUser("owner1", "correct-horse-battery-99", setOf("ADMIN"))
        val token = tokenOf(login("owner1", "correct-horse-battery-99"))

        val res = mvc.perform(
            post("/api/admin/users").header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"puppet","fullName":"Puppet Account","roles":["SYSTEM_ADMIN"]}""")
        ).andReturn()

        assertThat(res.response.status)
            .describedAs("an owner must not be able to mint a system administrator")
            .isEqualTo(403)
        assertThat(users.findByUsernameIgnoreCase("puppet")).isNull()
    }

    @Test
    fun `a system admin cannot grant itself commercial access`() {
        val token = sysadminToken()
        // SYSTEM_ADMIN holds no commercial permission, so granting ADMIN — which
        // carries COST_VIEW — is an escalation and must be refused.
        val res = mvc.perform(
            post("/api/admin/users").header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"backdoor","fullName":"Back Door","roles":["ADMIN"]}""")
        ).andReturn()

        assertThat(res.response.status).isEqualTo(403)
        assertThat(bodyOf(res).get("message").asText()).contains("COST_VIEW")
    }

    @Test
    @DisplayName("the role catalogue offers exactly what the guard would allow")
    fun `the role catalogue agrees with the guard`() {
        // ADMIN staffs the shop: USER_MANAGE and ROLE_ASSIGN, but nothing of the
        // system administrator's.
        seedUser("owner2", "correct-horse-battery-99", setOf("ADMIN"))
        val token = tokenOf(login("owner2", "correct-horse-battery-99"))

        val catalogue = bodyOf(
            mvc.perform(get("/api/admin/roles").header("Authorization", "Bearer $token")).andReturn()
        )
        assertThat(catalogue.size())
            .describedAs("every seeded role should be listed, grantable or not")
            .isEqualTo(jdbc.queryForObject("SELECT count(*) FROM role", Int::class.java))

        /*
         * The point of the endpoint: a picker built from it must never offer a
         * role the server then refuses, nor hide one it would have allowed.
         * Both halves are checked by actually trying it.
         */
        catalogue.forEachIndexed { i, role ->
            val code = role.get("code").asText()
            val created = mvc.perform(
                post("/api/admin/users").header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"username":"cat$i","fullName":"Catalogue Probe $i","roles":["$code"]}""")
            ).andReturn()

            assertThat(created.response.status == 201)
                .describedAs("%s: the catalogue says grantable=%s, creating one answered %d",
                    code, role.get("grantable").asBoolean(), created.response.status)
                .isEqualTo(role.get("grantable").asBoolean())
        }

        val systemAdmin = catalogue.first { it.get("code").asText() == "SYSTEM_ADMIN" }
        assertThat(systemAdmin.get("grantable").asBoolean())
            .describedAs("an owner must not be offered the system administrator role")
            .isFalse()
        assertThat(systemAdmin.get("withheld").map { it.asText() })
            .describedAs("and it should say what it is that they do not hold")
            .contains("CONFIG_MANAGE")
    }

    @Test
    fun `nobody can change their own roles`() {
        val token = sysadminToken()
        val selfId = users.findByUsernameIgnoreCase("sysadmin")!!.id!!
        val res = mvc.perform(
            put("/api/admin/users/$selfId/roles").header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"roles":["SYSTEM_ADMIN","ADMIN"]}""")
        ).andReturn()

        assertThat(res.response.status).isEqualTo(422)
        assertThat(bodyOf(res).get("code").asText()).isEqualTo("SELF_ROLE_CHANGE")
    }

    @Test
    fun `the last system administrator cannot be deactivated`() {
        val token = sysadminToken()
        val selfId = users.findByUsernameIgnoreCase("sysadmin")!!.id!!
        // Self-deactivation is refused first...
        val selfRes = mvc.perform(
            put("/api/admin/users/$selfId/active").header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content("""{"active":false}""")
        ).andReturn()
        assertThat(selfRes.response.status).isEqualTo(422)
        assertThat(bodyOf(selfRes).get("code").asText()).isEqualTo("SELF_DEACTIVATION")
    }

    // ── The till PIN ───────────────────────────────────────────────────────

    @Test
    @DisplayName("a till PIN is set by its owner alone, and the till accepts what they set")
    fun `the override pin round trip`() {
        val password = "correct-horse-battery-99"
        // A manager: holds SALE_PRICE_OVERRIDE, so a cashier can be sent to them.
        val id = seedUser("pinboss", password, setOf("MANAGER"))
        val token = tokenOf(login("pinboss", password))

        fun setPin(body: String) = mvc.perform(
            post("/api/auth/override-pin").header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andReturn()

        // A till left signed in must not be enough to mint an approval
        // credential of somebody else's choosing.
        assertThat(setPin("""{"currentPassword":"not-the-password","pin":"4917"}""").response.status)
            .isEqualTo(401)

        // 1234 is the first thing anybody standing behind the counter tries.
        assertThat(setPin("""{"currentPassword":"$password","pin":"1234"}""").response.status)
            .describedAs("a run is not a PIN")
            .isEqualTo(400)

        assertThat(setPin("""{"currentPassword":"$password","pin":"4917"}""").response.status)
            .isEqualTo(204)

        /*
         * The half that was missing until this endpoint existed.
         *
         * `override_pin_hash` was read by the override path and written by
         * nothing, so every supervisor in a real shop had none and every
         * approval was refused — in the same words as a wrong PIN. The sale
         * tests passed because they wrote the hash through the repository,
         * which no screen could do. This asserts the two halves meet.
         */
        val approver = overrides.verify("pinboss", "4917", "SALE_PRICE_OVERRIDE", "test override")
        assertThat(approver.username).isEqualTo("pinboss")

        // There is deliberately no way to set somebody else's: the path exists
        // for DELETE only.
        assertThat(
            mvc.perform(
                put("/api/admin/users/$id/override-pin").header("Authorization", "Bearer ${sysadminToken()}")
                    .contentType(MediaType.APPLICATION_JSON).content("""{"pin":"4917"}""")
            ).andReturn().response.status
        )
            .describedAs("a PIN an administrator chose would put somebody's name on an approval they never gave")
            .isEqualTo(405)

        // Taking it away is an administrator's job, and afterwards the till
        // refuses them exactly as it refuses a wrong PIN.
        assertThat(
            mvc.perform(
                delete("/api/admin/users/$id/override-pin")
                    .header("Authorization", "Bearer ${sysadminToken()}")
            ).andReturn().response.status
        ).isEqualTo(204)

        assertThatThrownBy {
            overrides.verify("pinboss", "4917", "SALE_PRICE_OVERRIDE", "test override")
        }.isInstanceOf(ApiException.Unauthenticated::class.java)
    }

    // ── Input validation ───────────────────────────────────────────────────

    @Test
    fun `invalid input is rejected field by field`() {
        val token = sysadminToken()
        val res = mvc.perform(
            post("/api/admin/users").header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"9bad name!","fullName":"","roles":[],"phone":"12","email":"nope"}""")
        ).andReturn()

        assertThat(res.response.status).isEqualTo(400)
        val fields = bodyOf(res).get("details").get("fields")
        assertThat(fields.fieldNames().asSequence().toList())
            .describedAs("every bad field should be reported, not just the first")
            .contains("username", "fullName", "roles", "phone", "email")
    }

    @Test
    fun `an error response never leaks internals`() {
        val res = mvc.perform(
            post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{ not json")
        ).andReturn()

        assertThat(res.response.status).isEqualTo(400)
        val body = res.response.contentAsString
        assertThat(body).doesNotContain("Exception")
        assertThat(body).doesNotContain("com.counterweight")
        assertThat(bodyOf(res).get("traceId").asText())
            .describedAs("a trace id lets support correlate without exposing detail")
            .isNotBlank()
    }

    @Test
    fun `security headers are present on every response`() {
        val res = mvc.perform(get("/api/admin/users")).andReturn().response
        assertThat(res.getHeader("X-Content-Type-Options")).isEqualTo("nosniff")
        assertThat(res.getHeader("X-Frame-Options")).isEqualTo("DENY")
        assertThat(res.getHeader("Content-Security-Policy")).contains("frame-ancestors 'none'")
    }

    // ── Password handling ──────────────────────────────────────────────────

    @Test
    fun `a weak password is refused when changing it`() {
        seedUser("weak", "correct-horse-battery-99", setOf("SALES_STAFF"))
        val token = tokenOf(login("weak", "correct-horse-battery-99"))

        val res = mvc.perform(
            post("/api/auth/change-password").header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"currentPassword":"correct-horse-battery-99","newPassword":"password123"}""")
        ).andReturn()

        assertThat(res.response.status).isEqualTo(400)
        assertThat(bodyOf(res).get("message").asText()).contains("commonly used")
    }

    @Test
    fun `changing a password signs every other device out`() {
        seedUser("rotate2", "correct-horse-battery-99", setOf("SALES_STAFF"))
        val session = bodyOf(login("rotate2", "correct-horse-battery-99"))
        val otherDevice = session.get("refreshToken").asText()
        val token = session.get("accessToken").asText()

        mvc.perform(
            post("/api/auth/change-password").header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"currentPassword":"correct-horse-battery-99","newPassword":"seventeen-purple-lathes"}""")
        ).andReturn().also { assertThat(it.response.status).isEqualTo(204) }

        val stale = mvc.perform(
            post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("""{"refreshToken":"$otherDevice"}""")
        ).andReturn()
        assertThat(stale.response.status).isEqualTo(401)
    }

    @Test
    fun `a created account gets a one-time password and must replace it`() {
        val token = sysadminToken()
        // SYSTEM_ADMIN may only grant roles within its own permission set.
        val res = mvc.perform(
            post("/api/admin/users").header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"newadmin","fullName":"Second Admin","roles":["SYSTEM_ADMIN"]}""")
        ).andReturn()

        assertThat(res.response.status).isEqualTo(201)
        val body = bodyOf(res)
        assertThat(body.get("temporaryPassword").asText()).isNotBlank()
        assertThat(body.get("user").get("mustChangePassword").asBoolean()).isTrue()
    }

    @Test
    @DisplayName("a temporary password is visible to the screen that has to replace it")
    fun `who I am reports a temporary password`() {
        val created = bodyOf(
            mvc.perform(
                post("/api/admin/users").header("Authorization", "Bearer ${sysadminToken()}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"username":"tempcheck","fullName":"Temp Check","roles":["SYSTEM_ADMIN"]}""")
            ).andReturn()
        )
        val token = tokenOf(login("tempcheck", created.get("temporaryPassword").asText()))

        /*
         * A reload has only the stored token to go on — the flag on the login
         * response is gone by then. Without it on /me the app would render a
         * till to somebody every request is refused from, which reads as the
         * system being broken rather than as one thing left to do.
         */
        val me = bodyOf(
            mvc.perform(get("/api/auth/me").header("Authorization", "Bearer $token")).andReturn()
        )
        assertThat(me.get("mustChangePassword").asBoolean()).isTrue()

        val blocked = mvc.perform(
            get("/api/admin/users").header("Authorization", "Bearer $token")
        ).andReturn()
        assertThat(blocked.response.status).isEqualTo(403)
        assertThat(bodyOf(blocked).get("code").asText())
            .describedAs("the code the till turns into the change-password screen")
            .isEqualTo("PASSWORD_CHANGE_REQUIRED")
    }

    @Test
    fun `a fresh install can actually be brought into service`() {
        /*
         * Two correct rules used to meet in a deadlock.
         *
         * SYSTEM_ADMIN deliberately holds nothing commercial, and UserService
         * refuses to grant a permission the granter does not hold — the single
         * most important check in the role model. Together they meant the only
         * account a new install had could never create one that could sell, so
         * the shop could not be opened at all.
         *
         * The bootstrap now creates the owner as well. This pins both halves:
         * the guard still holds, and there is still a way in.
         */
        val sysadmin = users.findByUsernameIgnoreCase("sysadmin")
        val owner = users.findByUsernameIgnoreCase("owner")

        assertThat(sysadmin).describedAs("the system account").isNotNull
        assertThat(owner).describedAs("the shop account — without it nobody can ever sell").isNotNull

        assertThat(owner!!.permissionCodes().toList())
            .describedAs("the owner has to be able to open a till on day one")
            .contains("SALE_CREATE", "PRICE_MANAGE", "STOCK_RECEIVE", "CUSTOMER_MANAGE")

        assertThat(sysadmin!!.permissionCodes().toList())
            .describedAs("and the system account still must not be able to sell or see cost")
            .doesNotContain("SALE_CREATE", "COST_VIEW", "PRICE_MANAGE")

        assertThat(owner.mustChangePassword)
            .describedAs("a seeded credential that never has to be replaced is a shared password")
            .isTrue()
    }

    @Test
    fun `the system account still cannot grant itself the ability to sell`() {
        val token = sysadminToken()
        val res = mvc.perform(
            post("/api/admin/users").header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"sneaky","fullName":"Escalation","roles":["ADMIN"]}""")
        ).andReturn()

        assertThat(res.response.status)
            .describedAs("granting a permission you do not hold is the bypass this check exists for")
            .isEqualTo(403)
    }
    // ── The permission model itself ────────────────────────────────────────

    /**
     * Permissions granted to a role but checked by nothing.
     *
     * Each is a feature that was never built, not a gate that was forgotten —
     * verified one at a time. They are listed rather than deleted because the
     * grants are already right and a later migration would only have to put
     * them back. What is not acceptable is leaving them off a list, because
     * then the next one to appear looks exactly like these.
     */
    private val dormantPermissions = mapOf(
        "STOCK_TRANSFER" to
            "§5.1 — branch transfers wait for branch two",
        "ROLE_MANAGE" to
            "the roles are seeded and there is no endpoint that creates or edits one",
        "SESSION_REVOKE" to
            "sessions are revoked as a consequence of a role change, a password change " +
            "or a reset — each gated by its own permission — and nothing signs a user " +
            "out on request",
    )

    /**
     * A permission nobody reads is a gate that is not there.
     *
     * The same lie `PlatformTest.noSettingLies` catches for configuration, and
     * it had already happened here: `REPORT_EXPORT` was granted to AUDITOR and
     * MANAGER and read by nothing, so every export rode in on the underlying
     * report's `REPORT_VIEW` and the export permission decided precisely
     * nothing. That is worse than an obviously missing check — nobody
     * re-reads a gate they believe exists.
     *
     * Read out of the compiled classes rather than off the `@PreAuthorize`
     * annotations, because not every check is one: `CartService` asks
     * `actor.has("SALE_PRICE_OVERRIDE")` outright. A scan that understood only
     * annotations would report a live gate as dormant, and the fix for that
     * false alarm would be to weaken this test. Both spellings put the code in
     * the constant pool, and comments — which are not compiled — cannot pass
     * for a check.
     */
    @Test
    @DisplayName("every permission on the books is checked somewhere, or listed as dormant")
    fun noPermissionLies() {
        val checked = permissionCodesInCompiledCode()
        val onTheBooks = jdbc.queryForList("SELECT code FROM permission", String::class.java)

        assertThat(onTheBooks)
            .describedAs("the permission table should be populated by the migrations")
            .isNotEmpty

        assertThat(onTheBooks.filter { it !in checked && it !in dormantPermissions })
            .describedAs(
                "granted to somebody and read by nothing: either check it, or add it to " +
                    "dormantPermissions with the reason it does not exist yet"
            )
            .isEmpty()

        assertThat(dormantPermissions.keys.filter { it in checked })
            .describedAs("this is now enforced — take it off the dormant list")
            .isEmpty()

        assertThat(dormantPermissions.keys.filter { it !in onTheBooks })
            .describedAs("this permission no longer exists — take it off the dormant list")
            .isEmpty()
    }

    /**
     * Every permission code the compiled main classes mention.
     *
     * The boundary check matters: `SESSION_REVOKE` is a prefix of the audit
     * action `SESSION_REVOKED_ON_REUSE`, and a plain substring search calls a
     * dormant permission enforced on the strength of a log line.
     */
    private fun permissionCodesInCompiledCode(): Set<String> {
        val classes = File(UserService::class.java.protectionDomain.codeSource.location.toURI())
        assertThat(classes.isDirectory)
            .describedAs("expected the main classes as a directory to scan, got %s", classes)
            .isTrue()

        val pool = classes.walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            // ISO-8859-1 is byte-preserving, which is all this needs: permission
            // codes are ASCII, and so is their encoding in the constant pool.
            .joinToString("\u0000") { it.readBytes().toString(Charsets.ISO_8859_1) }

        return jdbc.queryForList("SELECT code FROM permission", String::class.java)
            .filter { Regex("(?<![A-Z_])" + Regex.escape(it) + "(?![A-Z_])").containsMatchIn(pool) }
            .toSet()
    }
}
