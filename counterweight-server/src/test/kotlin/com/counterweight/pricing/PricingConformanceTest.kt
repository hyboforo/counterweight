package com.counterweight.pricing

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
import java.time.LocalDate

/**
 * Proves the pricing rules that live in the database rather than in Kotlin.
 *
 * Same reasoning as the ledger suite: the invariants below are partial unique
 * indexes and check constraints, and an in-memory database reproduces neither.
 * The effective-date arithmetic is exercised through real SQL for the same
 * reason — window overlap is decided by the comparison semantics of `date`, not
 * by anything a mock would agree to.
 *
 * The compound-tax cases are the ones worth reading. A single blended
 * percentage is the mistake this structure exists to prevent, and case
 * [vatCompoundsOnLevies] is the arithmetic that shows why.
 */
@Testcontainers
@DisplayName("Pricing conformance")
class PricingConformanceTest {

    companion object {
        private const val UNIQUE_VIOLATION = "23505"
        private const val CHECK_VIOLATION = "23514"

        @Container
        @JvmStatic
        private val postgres = PostgreSQLContainer("postgres:16")
            .withDatabaseName("counterweight")
            .withUsername("counterweight")
            .withPassword("counterweight")

        private lateinit var db: Connection

        @BeforeAll
        @JvmStatic
        fun migrate() {
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .locations("classpath:db/migration")
                .load()
                .migrate()
            db = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        }
    }

    // ── What V4 has to leave behind ────────────────────────────────────────

    @Test
    @DisplayName("the branch can sell: exactly one default price list exists")
    fun defaultPriceListExists() {
        val rs = db.createStatement().executeQuery(
            "SELECT code FROM price_list WHERE is_default"
        )
        val defaults = generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
        assertThat(defaults)
            .describedAs("no default price list means the till cannot price anything")
            .containsExactly("RETAIL")
    }

    @Test
    @DisplayName("a second default in the same branch is refused by the index")
    fun onlyOneDefaultPerBranch() {
        assertThatThrownBy {
            db.createStatement().executeUpdate(
                "UPDATE price_list SET is_default = TRUE WHERE code = 'TRADE'"
            )
        }
            .isInstanceOf(SQLException::class.java)
            .satisfies({ (it as SQLException).sqlState == UNIQUE_VIOLATION })
    }

    @Test
    @DisplayName("the roles that stand at a till have a discount policy, and only those")
    fun discountPoliciesSeeded() {
        val rs = db.createStatement().executeQuery(
            """
            SELECT r.code, dp.max_percent, dp.min_margin_percent
              FROM discount_policy dp JOIN role r ON r.id = dp.role_id
             ORDER BY dp.max_percent
            """
        )
        val policies = buildList {
            while (rs.next()) add(Triple(rs.getString(1), rs.getBigDecimal(2), rs.getBigDecimal(3)))
        }
        assertThat(policies.map { it.first }).containsExactly("SALES_STAFF", "MANAGER", "ADMIN")
        // A storekeeper who could discount would be a segregation-of-duty hole:
        // the same person receives the goods and sets what they leave for.
        assertThat(policies.map { it.first }).doesNotContain("STOREKEEPER", "AUDITOR")
    }

    @Test
    @DisplayName("exactly one tax scheme may be active")
    fun onlyOneActiveTaxScheme() {
        assertThatThrownBy {
            db.createStatement().executeUpdate(
                "UPDATE tax_scheme SET is_active = TRUE WHERE code = 'GHANA_VAT'"
            )
        }
            .isInstanceOf(SQLException::class.java)
            .satisfies({ (it as SQLException).sqlState == UNIQUE_VIOLATION })
    }

    @Test
    @DisplayName("the dormant Ghana scheme carries structure but deliberately no rates")
    fun ghanaSchemeHasNoSeededRates() {
        val rs = db.createStatement().executeQuery(
            """
            SELECT tc.code, tc.computed_on,
                   (SELECT count(*) FROM tax_rate tr WHERE tr.tax_component_id = tc.id) AS rate_count
              FROM tax_component tc JOIN tax_scheme s ON s.id = tc.tax_scheme_id
             WHERE s.code = 'GHANA_VAT'
             ORDER BY tc.sort_order
            """
        )
        val components = buildList {
            while (rs.next()) add(Triple(rs.getString(1), rs.getString(2), rs.getInt(3)))
        }
        assertThat(components.map { it.first }).containsExactly("NHIL", "GETFUND", "COVID", "VAT")
        // VAT compounds; the three levies do not. This is the whole reason the
        // scheme is four rows rather than one blended percentage.
        assertThat(components.single { it.first == "VAT" }.second).isEqualTo("RUNNING_TOTAL")
        assertThat(components.filter { it.first != "VAT" }).allSatisfy {
            assertThat(it.second).isEqualTo("TAXABLE_VALUE")
        }
        // Seeding a rate that goes stale before anyone switches the scheme on
        // would under-collect silently. TaxCalculator stops the till instead.
        assertThat(components.map { it.third }).allSatisfy { assertThat(it).isZero() }
    }

    // ── Effective-dated prices ─────────────────────────────────────────────

    @Test
    @DisplayName("only one open-ended price per list, product and unit")
    fun onlyOneOpenPrice() {
        val (productId, uomId) = seedProduct("PRICE-OPEN")
        val listId = retailListId()

        insertPrice(listId, productId, uomId, "10.00", LocalDate.now().minusDays(30), null)

        assertThatThrownBy {
            insertPrice(listId, productId, uomId, "12.00", LocalDate.now(), null)
        }
            .isInstanceOf(SQLException::class.java)
            .satisfies({ (it as SQLException).sqlState == UNIQUE_VIOLATION })
    }

    @Test
    @DisplayName("closing the old window lets the new one open")
    fun supersedingAPrice() {
        val (productId, uomId) = seedProduct("PRICE-SUPERSEDE")
        val listId = retailListId()
        val today = LocalDate.now()

        insertPrice(listId, productId, uomId, "10.00", today.minusDays(30), null)
        db.createStatement().executeUpdate(
            """
            UPDATE price SET effective_to = DATE '${today.minusDays(1)}'
             WHERE price_list_id = $listId AND product_id = $productId AND effective_to IS NULL
            """
        )
        insertPrice(listId, productId, uomId, "12.00", today, null)

        assertThat(priceOn(listId, productId, uomId, today)).isEqualByComparingTo("12.00")
        assertThat(priceOn(listId, productId, uomId, today.minusDays(5)))
            .describedAs("a reprint of last week's invoice must still find last week's price")
            .isEqualByComparingTo("10.00")
    }

    @Test
    @DisplayName("a window cannot end before it starts")
    fun windowMustBeOrdered() {
        val (productId, uomId) = seedProduct("PRICE-WINDOW")
        val listId = retailListId()
        assertThatThrownBy {
            insertPrice(
                listId, productId, uomId, "10.00",
                LocalDate.now(), LocalDate.now().minusDays(1),
            )
        }
            .isInstanceOf(SQLException::class.java)
            .satisfies({ (it as SQLException).sqlState == CHECK_VIOLATION })
    }

    @Test
    @DisplayName("a negative price is refused")
    fun noNegativePrices() {
        val (productId, uomId) = seedProduct("PRICE-NEGATIVE")
        assertThatThrownBy {
            insertPrice(retailListId(), productId, uomId, "-1.00", LocalDate.now(), null)
        }
            .isInstanceOf(SQLException::class.java)
            .satisfies({ (it as SQLException).sqlState == CHECK_VIOLATION })
    }

    @Test
    @DisplayName("a trade list falls back to retail for lines it does not carry")
    fun tradeListFallsBackToRetail() {
        val (productId, uomId) = seedProduct("PRICE-FALLBACK")
        val retail = retailListId()
        val trade = listId("TRADE")
        val today = LocalDate.now()

        insertPrice(retail, productId, uomId, "10.00", today, null)

        // The trade list carries nothing for this product, which is the normal
        // case — it holds only the lines that differ.
        assertThat(resolvePreferring(trade, retail, productId, uomId, today))
            .isEqualByComparingTo("10.00")

        insertPrice(trade, productId, uomId, "8.50", today, null)
        assertThat(resolvePreferring(trade, retail, productId, uomId, today))
            .describedAs("once the trade list carries the line it must win")
            .isEqualByComparingTo("8.50")
    }

    // ── Compound tax ───────────────────────────────────────────────────────

    @Test
    @DisplayName("VAT compounds on the levies, which do not compound on each other")
    fun vatCompoundsOnLevies() {
        // A scheme of its own rather than GHANA_VAT: setting rates on the seeded
        // one would leave it configured, and whether
        // [ghanaSchemeHasNoSeededRates] still passed would come down to the
        // order JUnit happened to run them in.
        val schemeId = seedSchemeLikeGhana("CALC_BASIS")
        // Rates chosen to make the arithmetic legible rather than to assert what
        // the GRA currently charges — the point under test is the basis, not the
        // number. 2.5 + 2.5 + 1 on the taxable value, then 15% on the running
        // total.
        setRate(schemeId, "NHIL", "2.5")
        setRate(schemeId, "GETFUND", "2.5")
        setRate(schemeId, "COVID", "1.0")
        setRate(schemeId, "VAT", "15.0")

        val taxable = BigDecimal("1000.00")
        val amounts = applyScheme(schemeId, taxable)

        assertThat(amounts["NHIL"]).isEqualByComparingTo("25.00")
        assertThat(amounts["GETFUND"]).isEqualByComparingTo("25.00")
        assertThat(amounts["COVID"]).isEqualByComparingTo("10.00")
        // 15% of 1060.00, not of 1000.00. A blended 21% would have said 210.00
        // for the lot, understating by 9.00 on every thousand cedis.
        assertThat(amounts["VAT"]).isEqualByComparingTo("159.00")
        assertThat(amounts.values.reduce(BigDecimal::add)).isEqualByComparingTo("219.00")
    }

    @Test
    @DisplayName("a rate is found by date, so a reprint computes the old rate")
    fun ratesAreEffectiveDated() {
        val schemeId = seedSchemeLikeGhana("RATE_HISTORY")
        val componentId = componentId(schemeId, "VAT")
        val changeDate = LocalDate.now().minusDays(10)

        db.createStatement().executeUpdate("DELETE FROM tax_rate WHERE tax_component_id = $componentId")
        db.createStatement().executeUpdate(
            """
            INSERT INTO tax_rate (tax_component_id, rate_percent, effective_from, effective_to)
            VALUES ($componentId, 12.5, DATE '${changeDate.minusYears(1)}', DATE '${changeDate.minusDays(1)}'),
                   ($componentId, 15.0, DATE '$changeDate', NULL)
            """
        )

        assertThat(rateOn(componentId, LocalDate.now())).isEqualByComparingTo("15.0")
        assertThat(rateOn(componentId, changeDate.minusDays(2))).isEqualByComparingTo("12.5")
    }

    // ── Fixtures ───────────────────────────────────────────────────────────

    private fun retailListId() = listId("RETAIL")

    private fun listId(code: String): Long = scalarLong("SELECT id FROM price_list WHERE code = '$code'")

    private fun schemeId(code: String): Long = scalarLong("SELECT id FROM tax_scheme WHERE code = '$code'")

    private fun componentId(schemeId: Long, code: String): Long =
        scalarLong("SELECT id FROM tax_component WHERE tax_scheme_id = $schemeId AND code = '$code'")

    /**
     * An inactive scheme with the same component structure V4 gives GHANA_VAT,
     * for tests that need to set rates without leaving the seeded scheme
     * configured behind them.
     */
    private fun seedSchemeLikeGhana(code: String): Long {
        val schemeId = scalarLong(
            "INSERT INTO tax_scheme (code, name, is_active) VALUES ('$code', '$code', FALSE) RETURNING id"
        )
        db.createStatement().executeUpdate(
            """
            INSERT INTO tax_component (tax_scheme_id, code, name, computed_on, is_recoverable, sort_order)
            VALUES ($schemeId, 'NHIL',    'NHIL',    'TAXABLE_VALUE', FALSE, 10),
                   ($schemeId, 'GETFUND', 'GETFund', 'TAXABLE_VALUE', FALSE, 20),
                   ($schemeId, 'COVID',   'COVID',   'TAXABLE_VALUE', FALSE, 30),
                   ($schemeId, 'VAT',     'VAT',     'RUNNING_TOTAL', TRUE,  40)
            """
        )
        return schemeId
    }

    /** A product with one base unit, enough to hang a price on. */
    private fun seedProduct(sku: String): Pair<Long, Long> {
        val branchId = scalarLong("SELECT id FROM branch WHERE code = 'MAIN'")
        val categoryId = scalarLong("SELECT id FROM category ORDER BY id LIMIT 1")
        val uomId = scalarLong("SELECT id FROM uom WHERE code = 'PCS'")
        val productId = scalarLong(
            """
            INSERT INTO product (branch_id, category_id, sku, name)
            VALUES ($branchId, $categoryId, '$sku', '$sku test product')
            RETURNING id
            """
        )
        val productUomId = scalarLong(
            """
            INSERT INTO product_uom (product_id, uom_id, factor, is_base, sellable, purchasable)
            VALUES ($productId, $uomId, 1, TRUE, TRUE, TRUE)
            RETURNING id
            """
        )
        return productId to productUomId
    }

    private fun insertPrice(
        listId: Long,
        productId: Long,
        productUomId: Long,
        price: String,
        from: LocalDate,
        to: LocalDate?,
    ) {
        db.createStatement().executeUpdate(
            """
            INSERT INTO price (price_list_id, product_id, product_uom_id, unit_price, effective_from, effective_to)
            VALUES ($listId, $productId, $productUomId, $price, DATE '$from',
                    ${if (to == null) "NULL" else "DATE '$to'"})
            """
        )
    }

    private fun priceOn(listId: Long, productId: Long, productUomId: Long, onDate: LocalDate): BigDecimal =
        scalarDecimal(
            """
            SELECT unit_price FROM price
             WHERE price_list_id = $listId AND product_id = $productId AND product_uom_id = $productUomId
               AND effective_from <= DATE '$onDate'
               AND (effective_to IS NULL OR effective_to >= DATE '$onDate')
            """
        )

    /** Mirrors PriceRepository.findEffective, including the fallback preference. */
    private fun resolvePreferring(
        preferredListId: Long,
        defaultListId: Long,
        productId: Long,
        productUomId: Long,
        onDate: LocalDate,
    ): BigDecimal = scalarDecimal(
        """
        SELECT p.unit_price FROM price p
         WHERE p.product_id = $productId
           AND p.product_uom_id = $productUomId
           AND p.effective_from <= DATE '$onDate'
           AND (p.effective_to IS NULL OR p.effective_to >= DATE '$onDate')
           AND p.price_list_id IN ($preferredListId, $defaultListId)
         ORDER BY CASE WHEN p.price_list_id = $preferredListId THEN 0 ELSE 1 END,
                  p.effective_from DESC, p.id DESC
         LIMIT 1
        """
    )

    private fun setRate(schemeId: Long, code: String, percent: String) {
        val componentId = componentId(schemeId, code)
        db.createStatement().executeUpdate("DELETE FROM tax_rate WHERE tax_component_id = $componentId")
        db.createStatement().executeUpdate(
            """
            INSERT INTO tax_rate (tax_component_id, rate_percent, effective_from)
            VALUES ($componentId, $percent, DATE '${LocalDate.now().minusYears(1)}')
            """
        )
    }

    private fun rateOn(componentId: Long, onDate: LocalDate): BigDecimal = scalarDecimal(
        """
        SELECT rate_percent FROM tax_rate
         WHERE tax_component_id = $componentId
           AND effective_from <= DATE '$onDate'
           AND (effective_to IS NULL OR effective_to >= DATE '$onDate')
         ORDER BY effective_from DESC LIMIT 1
        """
    )

    /**
     * The calculation TaxCalculator performs, in SQL, so the test proves the
     * arithmetic rather than agreeing with the implementation about it.
     */
    private fun applyScheme(schemeId: Long, taxableValue: BigDecimal): Map<String, BigDecimal> {
        val rs = db.createStatement().executeQuery(
            """
            SELECT tc.code, tc.computed_on, tr.rate_percent
              FROM tax_component tc
              JOIN tax_rate tr ON tr.tax_component_id = tc.id
             WHERE tc.tax_scheme_id = $schemeId
               AND tr.effective_from <= CURRENT_DATE
               AND (tr.effective_to IS NULL OR tr.effective_to >= CURRENT_DATE)
             ORDER BY tc.sort_order
            """
        )
        var running = taxableValue
        val amounts = linkedMapOf<String, BigDecimal>()
        while (rs.next()) {
            val code = rs.getString(1)
            val base = if (rs.getString(2) == "RUNNING_TOTAL") running else taxableValue
            val amount = base.multiply(rs.getBigDecimal(3))
                .divide(BigDecimal("100"), 2, java.math.RoundingMode.HALF_UP)
            amounts[code] = amount
            running = running.add(amount)
        }
        return amounts
    }

    private fun scalarLong(sql: String): Long =
        db.createStatement().executeQuery(sql).use { it.next(); it.getLong(1) }

    private fun scalarDecimal(sql: String): BigDecimal =
        db.createStatement().executeQuery(sql).use { it.next(); it.getBigDecimal(1) }
}
