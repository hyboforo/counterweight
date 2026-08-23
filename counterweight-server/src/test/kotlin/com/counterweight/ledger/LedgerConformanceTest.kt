package com.counterweight.ledger

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Proves the runtime properties the schema asserts but that no parser can confirm.
 *
 * The stock ledger is the foundation of the whole system — the API, the till and
 * the agent all assume overselling is impossible — so these run against a real
 * PostgreSQL 16, not an in-memory substitute. H2 would not reproduce the trigger
 * semantics, the partial unique indexes, or the constraint-evaluation ordering
 * that [balanceTriggerMustNotUseOnConflict] documents.
 *
 * Mirrors `src/test/sql/ledger_conformance.sql`, which is the psql-runnable
 * version for checking a live shop database in place.
 */
@Testcontainers
@DisplayName("Stock ledger conformance")
class LedgerConformanceTest {

    companion object {
        private const val CHECK_VIOLATION = "23514"
        private const val UNIQUE_VIOLATION = "23505"

        @Container
        @JvmStatic
        private val pg = PostgreSQLContainer("postgres:16")
            .withDatabaseName("counterweight")
            .withUsername("counterweight")
            .withPassword("counterweight")

        @BeforeAll
        @JvmStatic
        fun migrate() {
            Flyway.configure()
                .dataSource(pg.jdbcUrl, pg.username, pg.password)
                .locations("classpath:db/migration")
                .load()
                .migrate()
        }

        private fun conn(): Connection =
            DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password)
    }

    // ── Fixtures ──────────────────────────────────────────────────────────
    //
    // Each test builds its own product and lots. Sharing them across tests
    // would couple the assertions to execution order, and JUnit gives no
    // ordering guarantee.

    private data class Fixture(val userId: Long, val productId: Long, val lotId: Long)

    private fun Connection.scalarLong(sql: String): Long =
        createStatement().executeQuery(sql).use { it.next(); it.getLong(1) }

    private fun Connection.scalarDecimal(sql: String): BigDecimal? =
        createStatement().executeQuery(sql).use { if (it.next()) it.getBigDecimal(1) else null }

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }

    /** Creates a product with a KG base unit and one lot, optionally stocked. */
    private fun Connection.fixture(
        tag: String,
        onHand: BigDecimal = BigDecimal.ZERO,
        expiresInDays: Int? = null,
    ): Fixture {
        val user = scalarLong(
            """INSERT INTO app_user (branch_id, username, full_name, password_hash)
               VALUES (1, 'u_$tag', 'Test $tag', 'x') RETURNING id"""
        )
        val product = scalarLong(
            """INSERT INTO product (branch_id, category_id, sku, name)
               VALUES (1, (SELECT id FROM category WHERE code='HARDWARE'),
                       'SKU_$tag', 'Fixture $tag') RETURNING id"""
        )
        exec(
            """INSERT INTO product_uom (product_id, uom_id, factor, is_base)
               VALUES ($product, (SELECT id FROM uom WHERE code='KG'), 1, TRUE)"""
        )
        val expiry = expiresInDays?.let { "CURRENT_DATE + $it" } ?: "NULL"
        val lot = scalarLong(
            """INSERT INTO stock_lot (branch_id, product_id, lot_code, expires_on, unit_cost)
               VALUES (1, $product, 'LOT_$tag', $expiry, 10.0000) RETURNING id"""
        )
        if (onHand > BigDecimal.ZERO) {
            exec(
                """INSERT INTO stock_movement
                     (branch_id, lot_id, movement_type, qty_base, unit_cost, source_type, created_by)
                   VALUES (1, $lot, 'RECEIPT', $onHand, 10.0000, 'GRN', $user)"""
            )
        }
        return Fixture(user, product, lot)
    }

    private fun Connection.issue(fx: Fixture, qty: BigDecimal) = exec(
        """INSERT INTO stock_movement
             (branch_id, lot_id, movement_type, qty_base, unit_cost, source_type, created_by)
           VALUES (1, ${fx.lotId}, 'SALE_ISSUE', $qty, 10.0000, 'SALE', ${fx.userId})"""
    )

    private fun Connection.balanceOf(fx: Fixture): BigDecimal? =
        scalarDecimal("SELECT qty_base FROM stock_balance WHERE lot_id = ${fx.lotId}")

    private fun sqlStateOf(block: () -> Unit): String? = try {
        block(); null
    } catch (e: SQLException) {
        e.sqlState
    }

    /* JUnit 5 only registers @Test methods that return void/Unit. An
       expression-bodied test — `fun x() = conn().use { ... }` ending in an
       AssertJ call that returns SELF — is silently NOT discovered: the suite
       goes green having run nothing. Sixteen of the tests below were skipped
       exactly this way. This asserts the whole suite is actually wired up. */
    @Test
    fun `every test in this class is actually discovered by JUnit`() {
        val declared = this::class.java.declaredMethods
            .count { it.isAnnotationPresent(Test::class.java) }
        val runnable = this::class.java.declaredMethods
            .count { it.isAnnotationPresent(Test::class.java) &&
                     (it.returnType == Void.TYPE || it.returnType.name == "void") }
        assertThat(runnable)
            .describedAs("%d @Test method(s) do not return Unit and will be silently skipped", declared - runnable)
            .isEqualTo(declared)
    }

    // ── Balance maintenance ───────────────────────────────────────────────

    @Test
    fun `balance row is created with the lot, at zero`(): Unit = conn().use { c ->
        val fx = c.fixture("bal_init")
        assertThat(c.balanceOf(fx))
            .describedAs("a lot with no movements must still have a balance row")
            .isNotNull
            .satisfies({ assertThat(it).isEqualByComparingTo(BigDecimal.ZERO) })
    }

    @Test
    fun `receipts accumulate into the balance`(): Unit = conn().use { c ->
        val fx = c.fixture("bal_add", onHand = BigDecimal("100"))
        assertThat(c.balanceOf(fx)).isEqualByComparingTo(BigDecimal("100"))
    }

    @Test
    @DisplayName("decimal quantities stay exact — 3.5 m off a roll is routine")
    fun `decimal quantities are exact`(): Unit = conn().use { c ->
        val fx = c.fixture("bal_dec", onHand = BigDecimal("100"))
        c.issue(fx, BigDecimal("-3.5"))
        // If qty_base were INTEGER — or a float — this is where it would break.
        assertThat(c.balanceOf(fx)).isEqualByComparingTo(BigDecimal("96.5"))
    }

    /**
     * Regression guard for a bug found by running the schema rather than reading it.
     *
     * The obvious trigger implementation is a single
     * `INSERT ... ON CONFLICT (branch_id, lot_id) DO UPDATE SET qty_base = ... + EXCLUDED.qty_base`.
     * It fails on **every sale**: PostgreSQL evaluates CHECK constraints against
     * the proposed tuple before consulting the arbiter index, so a negative delta
     * trips `qty_never_negative` on the proposed row and never reaches the update.
     *
     * The fix was to create the balance row with the lot so the trigger is a pure
     * UPDATE. This test fails if anyone reverts that.
     */
    @Test
    @DisplayName("a negative movement against a healthy balance succeeds (ON CONFLICT regression)")
    fun balanceTriggerMustNotUseOnConflict(): Unit = conn().use { c ->
        val fx = c.fixture("no_onconflict", onHand = BigDecimal("50"))
        assertThat(sqlStateOf { c.issue(fx, BigDecimal("-1")) })
            .describedAs("a -1 issue against 50 on hand must not raise")
            .isNull()
        assertThat(c.balanceOf(fx)).isEqualByComparingTo(BigDecimal("49"))
    }

    // ── The oversell guard ────────────────────────────────────────────────

    @Test
    fun `overselling is rejected by the database, not the application`(): Unit = conn().use { c ->
        val fx = c.fixture("oversell", onHand = BigDecimal("5"))
        assertThat(sqlStateOf { c.issue(fx, BigDecimal("-1000")) })
            .isEqualTo(CHECK_VIOLATION)
        assertThat(c.balanceOf(fx))
            .describedAs("a rejected oversell must leave the balance untouched")
            .isEqualByComparingTo(BigDecimal("5"))
    }

    @Test
    @DisplayName("selling down to exactly zero is allowed — the check is >= 0, not > 0")
    fun `selling to exactly zero is permitted`(): Unit = conn().use { c ->
        val fx = c.fixture("to_zero", onHand = BigDecimal("10"))
        assertThat(sqlStateOf { c.issue(fx, BigDecimal("-10")) }).isNull()
        assertThat(c.balanceOf(fx)).isEqualByComparingTo(BigDecimal.ZERO)
    }

    /**
     * The claim the whole design rests on: two cashiers selling the last bag of
     * cement at the same instant need no application-level locking.
     *
     * This is the case plain SQL cannot express in a single session, and the
     * reason this suite exists in Kotlin at all.
     */
    @Test
    @DisplayName("two tills racing for the last unit — exactly one wins")
    fun `concurrent oversell is impossible`() {
        val fx = conn().use { it.fixture("race", onHand = BigDecimal.ONE) }

        val tillAHoldsLock = CountDownLatch(1)
        val tillAMayCommit = CountDownLatch(1)
        val outcome = ConcurrentHashMap<String, String>()

        val tillA = thread(name = "till-A") {
            conn().use { c ->
                c.autoCommit = false
                outcome["A"] = try {
                    c.issue(fx, BigDecimal("-1"))   // takes the row lock
                    tillAHoldsLock.countDown()
                    tillAMayCommit.await(10, TimeUnit.SECONDS)
                    c.commit()
                    "COMMITTED"
                } catch (e: SQLException) {
                    tillAHoldsLock.countDown()
                    c.rollback()
                    "REJECTED:${e.sqlState}"
                }
            }
        }

        val tillB = thread(name = "till-B") {
            conn().use { c ->
                c.autoCommit = false
                tillAHoldsLock.await(10, TimeUnit.SECONDS)
                outcome["B"] = try {
                    // Blocks here on A's row lock, then re-reads the decremented
                    // balance and trips the check constraint.
                    c.issue(fx, BigDecimal("-1"))
                    c.commit()
                    "COMMITTED"
                } catch (e: SQLException) {
                    c.rollback()
                    "REJECTED:${e.sqlState}"
                }
            }
        }

        tillAHoldsLock.await(10, TimeUnit.SECONDS)
        Thread.sleep(500)               // let B reach the lock and block on it
        tillAMayCommit.countDown()
        tillA.join(15_000)
        tillB.join(15_000)

        assertThat(outcome.values.toList())
            .describedAs("exactly one till may win; the other must be rejected")
            .containsExactlyInAnyOrder("COMMITTED", "REJECTED:$CHECK_VIOLATION")

        conn().use { c ->
            assertThat(c.balanceOf(fx))
                .describedAs("stock must land on zero, never negative")
                .isEqualByComparingTo(BigDecimal.ZERO)
            assertThat(c.scalarLong(
                "SELECT count(*) FROM stock_movement WHERE lot_id = ${fx.lotId} AND movement_type = 'SALE_ISSUE'"
            ))
                .describedAs("the losing till must leave no movement behind")
                .isEqualTo(1)
        }
    }

    // ── Immutability ──────────────────────────────────────────────────────

    @Test
    fun `stock_movement rejects UPDATE and DELETE`(): Unit = conn().use { c ->
        val fx = c.fixture("immutable", onHand = BigDecimal("10"))
        assertThat(sqlStateOf {
            c.exec("UPDATE stock_movement SET qty_base = 1 WHERE lot_id = ${fx.lotId}")
        }).describedAs("history is corrected by compensating movements, never edited").isNotNull
        assertThat(sqlStateOf {
            c.exec("DELETE FROM stock_movement WHERE lot_id = ${fx.lotId}")
        }).isNotNull
    }

    @Test
    fun `audit_log accepts INSERT but rejects UPDATE`(): Unit = conn().use { c ->
        val fx = c.fixture("audit")
        assertThat(sqlStateOf {
            c.exec(
                """INSERT INTO audit_log (branch_id, actor_id, action, subject_type, subject_id)
                   VALUES (1, ${fx.userId}, 'TEST', 'product', ${fx.productId})"""
            )
        }).isNull()
        assertThat(sqlStateOf {
            c.exec("UPDATE audit_log SET action = 'TAMPERED' WHERE actor_id = ${fx.userId}")
        }).isNotNull
    }

    // ── Reconciliation tripwire ───────────────────────────────────────────

    @Test
    @DisplayName("v_ledger_mismatch is silent when consistent and loud when not")
    fun `reconciliation view detects a bypassed trigger`(): Unit = conn().use { c ->
        val fx = c.fixture("drift", onHand = BigDecimal("10"))
        assertThat(c.scalarLong("SELECT count(*) FROM v_ledger_mismatch WHERE lot_id = ${fx.lotId}"))
            .describedAs("must not produce false positives, or the nightly alert gets ignored")
            .isEqualTo(0)

        // Simulate a code path that somehow bypassed the trigger.
        c.exec("ALTER TABLE stock_movement DISABLE TRIGGER trg_apply_stock_movement")
        c.exec(
            """INSERT INTO stock_movement
                 (branch_id, lot_id, movement_type, qty_base, unit_cost, source_type,
                  reason_code, created_by)
               VALUES (1, ${fx.lotId}, 'ADJUST_IN', 7, 10.0000, 'ADJUSTMENT',
                       'DRIFT_PROBE', ${fx.userId})"""
        )
        c.exec("ALTER TABLE stock_movement ENABLE TRIGGER trg_apply_stock_movement")

        assertThat(c.scalarLong("SELECT count(*) FROM v_ledger_mismatch WHERE lot_id = ${fx.lotId}"))
            .isEqualTo(1)
    }

    // ── Document numbering ────────────────────────────────────────────────

    @Test
    @DisplayName("document numbers are gapless — a SEQUENCE would leak on rollback")
    fun `document numbers are sequential`(): Unit = conn().use { c ->
        val first = c.scalarStringOf("SELECT next_document_number(1, 'QUOTATION')")
        val second = c.scalarStringOf("SELECT next_document_number(1, 'QUOTATION')")
        assertThat(first).isEqualTo("QUO-000001")
        assertThat(second).isEqualTo("QUO-000002")
    }

    @Test
    fun `unknown document type raises rather than returning junk`(): Unit = conn().use { c ->
        assertThat(sqlStateOf { c.exec("SELECT next_document_number(1, 'NOPE')") }).isNotNull
    }

    private fun Connection.scalarStringOf(sql: String): String =
        createStatement().executeQuery(sql).use { it.next(); it.getString(1) }

    // ── Catalog invariants ────────────────────────────────────────────────

    @Test
    fun `a product cannot have two base units`(): Unit = conn().use { c ->
        val fx = c.fixture("two_base")
        assertThat(sqlStateOf {
            c.exec(
                """INSERT INTO product_uom (product_id, uom_id, factor, is_base)
                   VALUES (${fx.productId}, (SELECT id FROM uom WHERE code='G'), 1, TRUE)"""
            )
        }).isEqualTo(UNIQUE_VIOLATION)
    }

    @Test
    @DisplayName("a batch-tracked product cannot be picked FIFO — expiry is why it is tracked")
    fun `batch tracked implies FEFO`(): Unit = conn().use { c ->
        assertThat(sqlStateOf {
            c.exec(
                """INSERT INTO product (branch_id, category_id, sku, name, is_batch_tracked, picking_rule)
                   VALUES (1, (SELECT id FROM category WHERE code='AGRO'),
                           'BAD_FIFO', 'Batch-tracked but FIFO', TRUE, 'FIFO')"""
            )
        }).isEqualTo(CHECK_VIOLATION)
    }

    @Test
    fun `an adjustment without a reason code is rejected`(): Unit = conn().use { c ->
        val fx = c.fixture("no_reason", onHand = BigDecimal("10"))
        assertThat(sqlStateOf {
            c.exec(
                """INSERT INTO stock_movement
                     (branch_id, lot_id, movement_type, qty_base, unit_cost, source_type, created_by)
                   VALUES (1, ${fx.lotId}, 'ADJUST_OUT', -1, 10.0000, 'ADJUSTMENT', ${fx.userId})"""
            )
        }).isEqualTo(CHECK_VIOLATION)
    }

    // ── Alerting ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("one open alert per condition — this is the whole anti-fatigue mechanism")
    fun `alerts deduplicate while open and may re-raise once resolved`(): Unit = conn().use { c ->
        val rule = c.scalarLong(
            "INSERT INTO alert_rule (branch_id, rule_type, severity) VALUES (1,'LOW_STOCK','WARNING') RETURNING id"
        )
        val key = "test:dedupe:$rule"
        fun raise() = c.exec(
            """INSERT INTO alert (rule_id, branch_id, dedupe_key, severity, title, body)
               VALUES ($rule, 1, '$key', 'WARNING', 't', 'b')"""
        )

        assertThat(sqlStateOf { raise() }).isNull()
        assertThat(sqlStateOf { raise() })
            .describedAs("re-raising an already-open condition must be a no-op, not a new notification")
            .isEqualTo(UNIQUE_VIOLATION)

        c.exec("UPDATE alert SET resolved_at = now() WHERE dedupe_key = '$key'")
        assertThat(sqlStateOf { raise() })
            .describedAs("once resolved, the condition may legitimately recur")
            .isNull()
    }

    // ── Picking ───────────────────────────────────────────────────────────

    @Test
    fun `FEFO picks the nearest expiry first, with non-expiring lots last`(): Unit = conn().use { c ->
        val fx = c.fixture("fefo", onHand = BigDecimal("5"), expiresInDays = 400)
        c.exec(
            """INSERT INTO stock_lot (branch_id, product_id, lot_code, expires_on, unit_cost)
               VALUES (1, ${fx.productId}, 'LOT_fefo_near', CURRENT_DATE + 30, 10.0000),
                      (1, ${fx.productId}, 'LOT_fefo_never', NULL, 10.0000)"""
        )
        val order = mutableListOf<String>()
        c.createStatement().executeQuery(
            """SELECT lot_code FROM stock_lot
                WHERE product_id = ${fx.productId} AND status = 'AVAILABLE'
                ORDER BY expires_on NULLS LAST"""
        ).use { while (it.next()) order += it.getString(1) }

        assertThat(order).containsExactly("LOT_fefo_near", "LOT_fefo", "LOT_fefo_never")
    }
}
