package com.counterweight.sales

import com.counterweight.catalog.repo.CategoryRepository
import com.counterweight.catalog.service.ProductService
import com.counterweight.catalog.service.ProductUomSpec
import com.counterweight.common.ApiException
import com.counterweight.identity.repo.AppUserRepository
import com.counterweight.identity.security.CurrentUser
import com.counterweight.inventory.service.InventoryService
import com.counterweight.inventory.service.ReceiptLine
import com.counterweight.parties.service.CustomerAccountService
import com.counterweight.parties.service.CustomerService
import com.counterweight.pricing.service.PriceListService
import com.counterweight.pricing.service.PricingService
import com.counterweight.reporting.service.ReportService
import com.counterweight.sales.service.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Collecting the takings.
 *
 * What matters most is that consecutive collections add up: every payment,
 * void and counter refund falls inside exactly one of them, so nothing is
 * counted twice and nothing slips between two. The rest is the owner's count
 * standing against the sales, and nobody but the owner making it.
 *
 * Collections chain per branch and every test here shares one database, so
 * each test starts by collecting whatever an earlier one left behind.
 */
@SpringBootTest
@Testcontainers
@DisplayName("Collections")
class CollectionTest {

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
            registry.add("COUNTERWEIGHT_BOOTSTRAP_PASSWORD") { "bootstrap-correct-horse-staple-42" }
        }

        private val seq = AtomicInteger()
    }

    @Autowired private lateinit var collections: CollectionService
    @Autowired private lateinit var reports: ReportService
    @Autowired private lateinit var cart: CartService
    @Autowired private lateinit var completion: SaleCompletionService
    @Autowired private lateinit var saleReturns: SaleReturnService
    @Autowired private lateinit var inventory: InventoryService
    @Autowired private lateinit var productService: ProductService
    @Autowired private lateinit var pricing: PricingService
    @Autowired private lateinit var priceLists: PriceListService
    @Autowired private lateinit var customers: CustomerService
    @Autowired private lateinit var accounts: CustomerAccountService
    @Autowired private lateinit var categories: CategoryRepository
    @Autowired private lateinit var users: AppUserRepository
    @Autowired private lateinit var jdbc: JdbcTemplate

    private val owner = setOf(
        "SALE_CREATE", "SALE_HOLD", "SALE_RETURN", "SALE_VOID",
        "STOCK_RECEIVE", "PRODUCT_MANAGE", "PRICE_MANAGE", "PRICE_VIEW",
        "CUSTOMER_MANAGE", "CREDIT_APPROVE", "REPORT_VIEW", "SALES_COLLECT",
    )

    @BeforeEach
    fun signIn() = signInAs(owner)

    private fun signInAs(permissions: Set<String>) {
        val userId = users.findAll().first().id!!
        SecurityContextHolder.getContext().authentication = PreAuthenticatedAuthenticationToken(
            CurrentUser(userId, "test-owner", 1L, setOf("ADMIN"), permissions),
            null,
            permissions.map { SimpleGrantedAuthority(it) },
        )
    }

    // ── Fixtures ───────────────────────────────────────────────────────────

    /** A product at 10.00 a piece with plenty in stock. */
    private fun product(): Pair<Long, Long> {
        val n = seq.incrementAndGet()
        val product = productService.create(
            sku = "COL-$n", name = "Collection Product $n",
            categoryId = categories.findByCode("HARDWARE")!!.id!!,
            units = listOf(ProductUomSpec("PCS", BigDecimal.ONE, isBase = true)),
            attributes = null,
            isBatchTracked = false,
            pickingRule = "FIFO",
        )
        val uomId = productService.unitsOf(product.id!!).single().id!!
        inventory.receive(
            listOf(ReceiptLine(product.id!!, uomId, BigDecimal("1000"), BigDecimal("4.00"), null, null)),
            reference = "GRN-COL-$n",
        )
        pricing.setPrice(priceLists.default().id!!, product.id!!, uomId, BigDecimal("10.00"), null)
        return product.id!! to uomId
    }

    private fun momo(amount: String) =
        TenderLine("MOBILE_MONEY", BigDecimal(amount), momoNetwork = "MTN", reference = "MP${seq.incrementAndGet()}")

    private fun cash(amount: String) = TenderLine("CASH", BigDecimal(amount))

    /** Sells `qty` pieces at 10.00, paid as given. Returns the sale and its only line. */
    private fun sell(qty: Int, vararg tenders: TenderLine, customerId: Long? = null): Pair<Long, Long> {
        val (productId, uomId) = product()
        val sale = cart.startSale(customerId)
        val line = cart.addLine(sale.id!!, productId, uomId, BigDecimal(qty))
        completion.complete(CompleteSaleCommand(sale.id!!, tenders.toList(), tillCode = "TILL-C"), UUID.randomUUID())
        return sale.id!! to line.id!!
    }

    /** Collects whatever an earlier test left, so this one starts from nothing. */
    private fun drain() {
        val pending = collections.pending()
        if (pending.tenders.all { it.expected.signum() == 0 }) return
        collections.record(
            pending.until,
            pending.tenders.map { CountedTender(it.method, it.expected, it.expected.max(BigDecimal.ZERO)) },
            "Clearing up after an earlier test",
        )
    }

    private fun PendingCollection.expected(method: String): BigDecimal =
        tenders.single { it.method == method }.expected

    /** Counts exactly what the sales say, which is the ordinary case. */
    private fun exactly(pending: PendingCollection): List<CountedTender> =
        pending.tenders.filter { it.expected.signum() != 0 }.map { CountedTender(it.method, it.expected, it.expected) }

    // ── What a collection covers ───────────────────────────────────────────

    @Test
    @DisplayName("every tender but on-account, net of voids and counter refunds")
    fun coversMoneyTendersNetOfVoidsAndRefunds() {
        drain()
        val (cashSale, cashLine) = sell(5, cash("50.00"))
        sell(3, momo("30.00"))
        val (voided, _) = sell(2, cash("20.00"))
        completion.voidSale(voided, "Rang up twice")
        saleReturns.createReturn(cashSale, listOf(ReturnLineRequest(cashLine, BigDecimal("1"))), "Wrong size", "CASH")

        val customer = customers.create(name = "Collection Credit ${seq.incrementAndGet()}")
        accounts.setCreditTerms(customer.id!!, BigDecimal("1000"), 30)
        sell(4, TenderLine("ON_ACCOUNT", BigDecimal("40.00")), customerId = customer.id)

        val pending = collections.pending()
        assertThat(pending.tenders.map { it.method })
            .describedAs("nothing changed hands on account, so there is nothing to collect")
            .containsExactly("CASH", "MOBILE_MONEY", "BANK_TRANSFER", "CHEQUE", "CARD")
        assertThat(pending.expected("CASH"))
            .describedAs("50 in, the voided 20 in and back out, 10 refunded across the counter")
            .isEqualByComparingTo("40.00")
        assertThat(pending.expected("MOBILE_MONEY")).isEqualByComparingTo("30.00")
        assertThat(pending.expectedTotal).isEqualByComparingTo("70.00")
    }

    @Test
    @DisplayName("consecutive collections neither overlap nor leave a gap")
    fun collectionsChain() {
        drain()
        sell(1, cash("10.00"))
        val first = collections.pending()
        val recorded = collections.record(first.until, exactly(first), null)

        sell(2, cash("20.00"))
        val next = collections.pending()

        assertThat(next.previousNumber).isEqualTo(recorded.header.number)
        assertThat(next.periodFrom).isEqualTo(recorded.header.collectedAt)
        assertThat(next.expected("CASH"))
            .describedAs("the 10.00 already went with ${recorded.header.number}")
            .isEqualByComparingTo("20.00")
    }

    @Test
    @DisplayName("a sale voided after it was collected comes off the next collection")
    fun voidAfterCollection() {
        drain()
        val (sale, _) = sell(4, cash("40.00"))
        val pending = collections.pending()
        collections.record(pending.until, exactly(pending), null)

        completion.voidSale(sale, "Customer brought it all back")
        val next = collections.pending()
        assertThat(next.expected("CASH"))
            .describedAs("the 40.00 was handed back after it had been collected")
            .isEqualByComparingTo("-40.00")

        assertThatThrownBy {
            collections.record(next.until, listOf(CountedTender("CASH", BigDecimal("-40.00"), BigDecimal.ZERO)), null)
        }
            .describedAs("0.00 counted against -40.00 is a difference, and a difference needs a reason")
            .isInstanceOf(ApiException.Validation::class.java)
            .hasMessageContaining("Say why")
        collections.record(
            next.until,
            listOf(CountedTender("CASH", BigDecimal("-40.00"), BigDecimal.ZERO)),
            "Refund paid out of the till",
        )
    }

    @Test
    @DisplayName("a collection dated earlier leaves the sales after it for the next one")
    fun backDatedCollection() {
        drain()
        sell(1, cash("10.00"))
        val takenAt = Instant.now()
        sell(3, cash("30.00"))

        val atTheTime = collections.pending(takenAt)
        assertThat(atTheTime.expected("CASH")).isEqualByComparingTo("10.00")
        val recorded = collections.record(takenAt, exactly(atTheTime), null)
        assertThat(recorded.header.collectedAt).isEqualTo(takenAt.truncatedTo(ChronoUnit.MICROS))
        assertThat(recorded.header.recordedAt).isAfter(recorded.header.collectedAt)

        assertThat(collections.pending().expected("CASH"))
            .describedAs("the 30.00 was rung up after the money was taken")
            .isEqualByComparingTo("30.00")
    }

    // ── The count ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("a count that differs from the sales is recorded as a difference, with a reason")
    fun shortageNeedsAReason() {
        drain()
        sell(10, cash("100.00"))
        val pending = collections.pending()
        val short = listOf(CountedTender("CASH", BigDecimal("100.00"), BigDecimal("95.00")))

        assertThatThrownBy { collections.record(pending.until, short, "  ") }
            .isInstanceOf(ApiException.Validation::class.java)
            .hasMessageContaining("does not match the sales")

        val recorded = collections.record(pending.until, short, "Five cedis short, counted twice")
        val line = recorded.lines.single()
        assertThat(line.expected).isEqualByComparingTo("100.00")
        assertThat(line.collected).isEqualByComparingTo("95.00")
        assertThat(line.difference).isEqualByComparingTo("-5.00")
        assertThat(recorded.header.note).isEqualTo("Five cedis short, counted twice")
    }

    @Test
    @DisplayName("every tender the sales say came in has to be counted")
    fun everyTenderIsCounted() {
        drain()
        sell(1, cash("10.00"))
        sell(3, momo("30.00"))
        val pending = collections.pending()

        assertThatThrownBy {
            collections.record(pending.until, listOf(CountedTender("CASH", BigDecimal("10.00"), BigDecimal("10.00"))), null)
        }
            .describedAs("leaving mobile money out would record it as collected-nothing without anyone saying so")
            .isInstanceOf(ApiException.Validation::class.java)
            .hasMessageContaining("mobile money")
    }

    @Test
    @DisplayName("figures that no longer match the sales are refused rather than stored")
    fun staleFiguresAreRefused() {
        drain()
        sell(1, cash("10.00"))
        val pending = collections.pending()

        assertThatThrownBy {
            collections.record(pending.until, listOf(CountedTender("CASH", BigDecimal("9.00"), BigDecimal("9.00"))), null)
        }
            .describedAs("the owner saw 9.00; storing 10.00 would record a shortage they never saw")
            .isInstanceOf(ApiException.Conflict::class.java)
            .hasMessageContaining("Refresh")
    }

    @Test
    @DisplayName("nothing taken in and nothing counted is not a collection")
    fun nothingToCollect() {
        drain()
        val pending = collections.pending()
        assertThat(pending.expectedTotal).isEqualByComparingTo("0.00")

        assertThatThrownBy {
            collections.record(pending.until, listOf(CountedTender("CASH", BigDecimal.ZERO, BigDecimal.ZERO)), null)
        }
            .isInstanceOf(ApiException.RuleViolation::class.java)
            .hasFieldOrPropertyWithValue("code", "NOTHING_TO_COLLECT")
    }

    @Test
    @DisplayName("a collection is never dated in the future or before the one it follows")
    fun collectionTimeIsBounded() {
        drain()
        sell(1, cash("10.00"))
        val pending = collections.pending()
        val counted = exactly(pending)

        assertThatThrownBy { collections.record(Instant.now().plusSeconds(3600), counted, null) }
            .isInstanceOf(ApiException.Validation::class.java)
            .hasMessageContaining("future")

        val recorded = collections.record(pending.until, counted, null)
        sell(1, cash("10.00"))
        assertThatThrownBy { collections.record(recorded.header.collectedAt.minusSeconds(60), counted, null) }
            .isInstanceOf(ApiException.RuleViolation::class.java)
            .hasFieldOrPropertyWithValue("code", "COLLECTION_OUT_OF_ORDER")
    }

    // ── Who, and what the schema holds ─────────────────────────────────────

    @Test
    @DisplayName("only the owner may collect, and V17 grants it to nobody else")
    fun onlyTheOwnerCollects() {
        signInAs(owner - "SALES_COLLECT")
        assertThatThrownBy { collections.pending() }.isInstanceOf(AccessDeniedException::class.java)
        assertThatThrownBy { collections.recent(10) }.isInstanceOf(AccessDeniedException::class.java)

        val holders = jdbc.queryForList(
            """SELECT r.code FROM role_permission rp JOIN role r ON r.id = rp.role_id
                WHERE rp.permission_code = 'SALES_COLLECT'""",
            String::class.java,
        )
        assertThat(holders)
            .describedAs("not SYSTEM_ADMIN, which holds nothing commercial; not the roles that ring up the sales")
            .containsExactly("ADMIN")
    }

    @Test
    @DisplayName("a recorded collection cannot be edited or deleted")
    fun collectionsAreAppendOnly() {
        drain()
        sell(1, cash("10.00"))
        val pending = collections.pending()
        val id = collections.record(pending.until, exactly(pending), null).header.id

        assertThatThrownBy { jdbc.update("UPDATE sales_collection_line SET collected = 0 WHERE collection_id = ?", id) }
            .isInstanceOf(DataAccessException::class.java)
            .hasMessageContaining("append-only")
        assertThatThrownBy { jdbc.update("DELETE FROM sales_collection WHERE id = ?", id) }
            .isInstanceOf(DataAccessException::class.java)
            .hasMessageContaining("append-only")
    }

    @Test
    @DisplayName("the schema refuses a collection that forks the chain or leaves a gap")
    fun theChainIsHeldByTheSchema() {
        drain()
        sell(1, cash("10.00"))
        val pending = collections.pending()
        val last = collections.record(pending.until, exactly(pending), null).header
        val userId = users.findAll().first().id!!
        val insert = """
            INSERT INTO sales_collection (branch_id, number, previous_id, period_from, collected_at, collected_by, recorded_at)
            VALUES (1, ?, ?, ?, now(), ?, now())
        """

        // A second collection claiming the same predecessor — two admins
        // recording at once. Caught by UNIQUE (previous_id), or for a
        // branch's first collection by the one-first index.
        val sharedPredecessor = jdbc.queryForObject(
            "SELECT previous_id FROM sales_collection WHERE id = ?", Long::class.javaObjectType, last.id,
        )
        assertThatThrownBy {
            jdbc.update(insert, "COL-FORK", sharedPredecessor, last.periodFrom?.let(Timestamp::from), userId)
        }
            .isInstanceOf(DataAccessException::class.java)
            .hasMessageContaining("duplicate key")

        // Starting before the last one ended, so the two would overlap.
        assertThatThrownBy {
            jdbc.update(insert, "COL-OVERLAP", last.id, Timestamp.from(last.collectedAt.minusSeconds(1)), userId)
        }
            .isInstanceOf(DataAccessException::class.java)
            .hasMessageContaining("must start where the previous collection ended")
    }

    // ── Reports ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the collections report shows each tender counted against the sales")
    fun collectionsReport() {
        drain()
        sell(6, cash("60.00"))
        sell(2, momo("20.00"))
        val pending = collections.pending()
        val recorded = collections.record(
            pending.until,
            listOf(
                CountedTender("CASH", BigDecimal("60.00"), BigDecimal("58.50")),
                CountedTender("MOBILE_MONEY", BigDecimal("20.00"), BigDecimal("20.00")),
            ),
            "1.50 in coins missing",
        )

        val rows = reports.collections(LocalDate.now(), LocalDate.now())
            .filter { it.number == recorded.header.number }
        assertThat(rows.map { it.method }).containsExactly("CASH", "MOBILE_MONEY")
        val cash = rows.first()
        assertThat(cash.expected).isEqualByComparingTo("60.00")
        assertThat(cash.collected).isEqualByComparingTo("58.50")
        assertThat(cash.difference).isEqualByComparingTo("-1.50")
        assertThat(cash.note).isEqualTo("1.50 in coins missing")

        assertThat(collections.recent(5).first().header.number).isEqualTo(recorded.header.number)
    }
}
