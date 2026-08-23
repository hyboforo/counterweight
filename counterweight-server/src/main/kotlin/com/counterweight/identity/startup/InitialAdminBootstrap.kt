package com.counterweight.identity.startup

import com.counterweight.identity.domain.AppUser
import com.counterweight.identity.repo.AppUserRepository
import com.counterweight.identity.repo.RoleRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.env.Environment
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom

/**
 * Creates the first SYSTEM_ADMIN so the shop can get in at all.
 *
 * Runs only when there are **no users whatsoever**. It is not an "ensure admin
 * exists" reconciler: re-creating a privileged account on every boot would mean
 * that deleting it achieves nothing, and would quietly undo a deliberate
 * removal.
 *
 * The password comes from `COUNTERWEIGHT_BOOTSTRAP_PASSWORD` if set; otherwise
 * one is generated and written to the log **once**. Either way the account is
 * flagged `must_change_password`, so the value is a delivery mechanism and not
 * a standing credential — whoever reads the log cannot keep using it silently,
 * because first sign-in forces a replacement.
 */
@Component
class InitialAdminBootstrap(
    private val users: AppUserRepository,
    private val roles: RoleRepository,
    private val encoder: PasswordEncoder,
    private val env: Environment,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    override fun run(args: ApplicationArguments) {
        if (users.count() > 0L) return

        val systemAdmin = roles.findByCode("SYSTEM_ADMIN")
            ?: error("SYSTEM_ADMIN role is missing — migration V2 has not run.")
        val shopAdmin = roles.findByCode("ADMIN")
            ?: error("ADMIN role is missing — migration V2 has not run.")

        val supplied = env.getProperty("COUNTERWEIGHT_BOOTSTRAP_PASSWORD")?.takeIf { it.isNotBlank() }
        val password = supplied ?: generatePassword()

        /*
         * Who the shop's own administrator is, from configuration.
         *
         * A real person rather than a placeholder: the name goes on every audit
         * row this account writes, and "Shop Owner" in that column tells whoever
         * reads it back nothing. Defaults live in `application.yml`, so an
         * install for a different shop changes one file and no code.
         */
        val ownerUsername = env.getProperty("counterweight.bootstrap.owner-username", OWNER_USERNAME)

        val admin = AppUser(
            branchId = 1,
            username = DEFAULT_USERNAME,
            fullName = "System Administrator",
            passwordHash = encoder.encode(password),
        ).also {
            it.mustChangePassword = true
            it.roles = mutableSetOf(systemAdmin)
        }
        users.save(admin)

        /*
         * The owner is created here too, and that is not a convenience.
         *
         * SYSTEM_ADMIN deliberately holds nothing commercial — no SALE_*, no
         * COST_VIEW, no PRICE_MANAGE (V2 says so in as many words). And
         * UserService refuses to grant a permission the granter does not hold
         * themselves, which is the single most important check in the role
         * model and must not be weakened.
         *
         * Those two correct rules meet in a deadlock: the only account a fresh
         * install has cannot create one that can sell, so the shop can never be
         * brought into service. Creating the owner alongside the system
         * administrator resolves it without touching the guard — the install
         * hands over two accounts, each of which can do its own job and neither
         * of which can do the other's.
         */
        // Neither "-owner" nor "-shop": the policy refuses a password containing
        // the username or the full name, and this account is "owner" / "Shop
        // Owner". Handing out an initial password that the policy would itself
        // reject is a confusing first five minutes.
        val ownerPassword = supplied?.let { "$it-init" } ?: generatePassword()
        users.save(
            AppUser(
                branchId = 1,
                username = ownerUsername,
                fullName = env.getProperty("counterweight.bootstrap.owner-name", "Shop Owner"),
                passwordHash = encoder.encode(ownerPassword),
            ).also {
                it.phone = env.getProperty("counterweight.bootstrap.owner-phone")?.takeIf(String::isNotBlank)
                it.email = env.getProperty("counterweight.bootstrap.owner-email")?.takeIf(String::isNotBlank)
                it.mustChangePassword = true
                it.roles = mutableSetOf(shopAdmin)
            }
        )

        if (supplied == null) {
            // Deliberately the only place a credential is ever logged, and only
            // because the alternative is an unreachable system.
            log.warn(
                """
                |
                |  ────────────────────────────────────────────────────────────
                |   FIRST RUN — initial system administrator created
                |
                |   Two accounts, deliberately separate:
                |
                |     $DEFAULT_USERNAME  /  $password
                |       Runs the system. Users, roles, configuration, backups.
                |       Cannot sell, cannot see cost.
                |
                |     $ownerUsername  /  $ownerPassword
                |       Runs the shop. Selling, pricing, stock, customers.
                |       Cannot change system configuration.
                |
                |   Sign in to each and change the password; neither can do
                |   anything else until you do. This will not be shown again.
                |  ────────────────────────────────────────────────────────────
                """.trimMargin()
            )
        } else {
            log.info(
                "initial accounts '{}' and '{}' created from COUNTERWEIGHT_BOOTSTRAP_PASSWORD " +
                    "(the owner's password has '-init' appended)",
                DEFAULT_USERNAME, ownerUsername,
            )
        }
    }

    private fun generatePassword(): String {
        val random = SecureRandom()
        val words = (1..4).map { WORDS[random.nextInt(WORDS.size)] }
        return words.joinToString("-") + "-" + (random.nextInt(9000) + 1000)
    }

    private companion object {
        const val DEFAULT_USERNAME = "sysadmin"

        /** The shop's own administrator. See the note in [run] for why both exist. */
        const val OWNER_USERNAME = "owner"
        val WORDS = listOf(
            "anvil", "baobab", "cedar", "drill", "ebony", "file", "ginger", "hinge",
            "iroko", "jack", "kapok", "lathe", "mallet", "neem", "orchid", "pliers",
            "quarry", "rivet", "shea", "teak", "umber", "vise", "walnut", "yam",
        )
    }
}
