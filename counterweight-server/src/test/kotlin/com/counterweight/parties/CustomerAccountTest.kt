package com.counterweight.parties

import com.counterweight.identity.repo.AppUserRepository
import com.counterweight.identity.security.CurrentUser
import com.counterweight.parties.repo.CustomerLedgerEntryRepository
import com.counterweight.parties.service.AllocationRequest
import com.counterweight.parties.service.CreditOutcome
import com.counterweight.parties.service.CustomerAccountService
import com.counterweight.parties.service.CustomerService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
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
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

/**
 * The customer account, exercised through the services rather than through SQL.
 *
 * Unlike the ledger and pricing suites, what is under test here is application
 * logic — allocation order, the sign convention, what a credit check decides —
 * so the tests drive the real services with a real security context. The
 * database is still PostgreSQL because the ageing and open-invoice queries are
 * native and do the arithmetic themselves.
 */
@SpringBootTest
@Testcontainers
@DisplayName("Customer account")
class CustomerAccountTest {

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

        private val counter = AtomicInteger()
    }

    @Autowired private lateinit var customers: CustomerService
    @Autowired private lateinit var accounts: CustomerAccountService
    @Autowired private lateinit var ledger: CustomerLedgerEntryRepository
    @Autowired private lateinit var users: AppUserRepository

    /** The permissions an owner holds — enough to drive every path here. */
    private val fullRights = setOf(
        "CUSTOMER_MANAGE", "CREDIT_APPROVE", "SALE_CREATE", "SALE_RETURN", "PRICE_MANAGE", "PRICE_VIEW",
    )

    @BeforeEach
    fun signIn() = signInWith(fullRights)

    private fun signInWith(permissions: Set<String>) {
        val userId = users.findAll().first().id!!
        val principal = CurrentUser(userId, "test-operator", 1L, setOf("ADMIN"), permissions)
        SecurityContextHolder.getContext().authentication = PreAuthenticatedAuthenticationToken(
            principal, null, permissions.map { SimpleGrantedAuthority(it) },
        )
    }

    private fun newCustomer(limit: String? = null, terms: Short = 30) =
        customers.create(name = "Test Customer ${counter.incrementAndGet()}", paymentTermsDays = terms)
            .also { c -> limit?.let { accounts.setCreditTerms(c.id!!, BigDecimal(it), terms) } }

    // ── The ledger ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("balance is the sum of the entries, with payments reducing it")
    fun balanceIsTheSumOfEntries() {
        val customer = newCustomer(limit = "5000")

        accounts.postInvoice(customer.id!!, BigDecimal("1200.00"), null, "INV-1")
        assertThat(accounts.balance(customer.id!!)).isEqualByComparingTo("1200.00")

        accounts.recordPayment(customer.id!!, BigDecimal("500.00"), "MoMo 8842")
        assertThat(accounts.balance(customer.id!!))
            .describedAs("a payment that increased the balance would mean the sign convention is inverted")
            .isEqualByComparingTo("700.00")
    }

    @Test
    @DisplayName("a payment is stored negative even though the caller passes a positive amount")
    fun paymentStoredNegative() {
        val customer = newCustomer(limit = "5000")
        accounts.postInvoice(customer.id!!, BigDecimal("300.00"), null, null)
        val payment = accounts.recordPayment(customer.id!!, BigDecimal("300.00"), null)

        assertThat(payment.amount).isEqualByComparingTo("-300.00")
        assertThat(accounts.balance(customer.id!!)).isEqualByComparingTo("0.00")
    }

    // ── Allocation ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("an unallocated payment settles the oldest invoices first")
    fun oldestFirstAllocation() {
        val customer = newCustomer(limit = "5000")
        val first = accounts.postInvoice(customer.id!!, BigDecimal("400.00"), null, "INV-A")
        val second = accounts.postInvoice(customer.id!!, BigDecimal("600.00"), null, "INV-B")
        backdate(first.id!!, LocalDate.now().minusDays(60))
        backdate(second.id!!, LocalDate.now().minusDays(10))

        accounts.recordPayment(customer.id!!, BigDecimal("500.00"), "cash")

        val open = accounts.openInvoices(customer.id!!)
        assertThat(open.single { it.entryId == second.id }.outstanding)
            .describedAs("the newer invoice should only be touched after the older one is cleared")
            .isEqualByComparingTo("500.00")
        assertThat(open.none { it.entryId == first.id })
            .describedAs("the oldest invoice should be fully settled and no longer open")
            .isTrue()
    }

    @Test
    @DisplayName("a payer naming an invoice has that honoured over oldest-first")
    fun manualAllocationWins() {
        val customer = newCustomer(limit = "5000")
        val september = accounts.postInvoice(customer.id!!, BigDecimal("400.00"), null, "INV-SEP")
        val october = accounts.postInvoice(customer.id!!, BigDecimal("600.00"), null, "INV-OCT")
        backdate(september.id!!, LocalDate.now().minusDays(60))
        backdate(october.id!!, LocalDate.now().minusDays(10))

        accounts.recordPayment(
            customer.id!!, BigDecimal("600.00"), "cheque 114",
            listOf(AllocationRequest(october.id!!, BigDecimal("600.00"))),
        )

        val open = accounts.openInvoices(customer.id!!)
        assertThat(open.map { it.entryId })
            .describedAs("the named invoice should be cleared and the older one left alone")
            .containsExactly(september.id)
        assertThat(open.single().outstanding).isEqualByComparingTo("400.00")
    }

    @Test
    @DisplayName("allocating more than an invoice has outstanding is refused")
    fun overAllocationRefused() {
        val customer = newCustomer(limit = "5000")
        val invoice = accounts.postInvoice(customer.id!!, BigDecimal("100.00"), null, null)

        assertThatThrownBy {
            accounts.recordPayment(
                customer.id!!, BigDecimal("500.00"), null,
                listOf(AllocationRequest(invoice.id!!, BigDecimal("400.00"))),
            )
        }.hasMessageContaining("outstanding")
    }

    @Test
    @DisplayName("allocations totalling more than the amount received are refused")
    fun allocationsCannotExceedThePayment() {
        val customer = newCustomer(limit = "5000")
        val a = accounts.postInvoice(customer.id!!, BigDecimal("400.00"), null, null)
        val b = accounts.postInvoice(customer.id!!, BigDecimal("400.00"), null, null)

        assertThatThrownBy {
            accounts.recordPayment(
                customer.id!!, BigDecimal("500.00"), null,
                listOf(
                    AllocationRequest(a.id!!, BigDecimal("400.00")),
                    AllocationRequest(b.id!!, BigDecimal("400.00")),
                ),
            )
        }.hasMessageContaining("more than the amount received")
    }

    @Test
    @DisplayName("money paid in before an invoice exists still reduces the balance")
    fun paymentOnAccountWithNothingToSettle() {
        val customer = newCustomer(limit = "5000")
        accounts.recordPayment(customer.id!!, BigDecimal("250.00"), "deposit")

        assertThat(accounts.balance(customer.id!!))
            .describedAs("a deposit puts the account in credit rather than failing to post")
            .isEqualByComparingTo("-250.00")
        assertThat(accounts.openInvoices(customer.id!!)).isEmpty()
    }

    // ── Ageing ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("open invoices land in the bucket their due date puts them in")
    fun ageingBuckets() {
        val customer = newCustomer(limit = "50000")
        mapOf(
            LocalDate.now().plusDays(10) to "100.00",   // not due yet
            LocalDate.now().minusDays(15) to "200.00",  // 1-30
            LocalDate.now().minusDays(45) to "300.00",  // 31-60
            LocalDate.now().minusDays(75) to "400.00",  // 61-90
            LocalDate.now().minusDays(200) to "500.00", // 90+
        ).forEach { (due, amount) ->
            val entry = accounts.postInvoice(customer.id!!, BigDecimal(amount), null, null)
            backdate(entry.id!!, due)
        }

        val ageing = accounts.ageing(customer.id!!)
        assertThat(ageing.current).isEqualByComparingTo("100.00")
        assertThat(ageing.days1To30).isEqualByComparingTo("200.00")
        assertThat(ageing.days31To60).isEqualByComparingTo("300.00")
        assertThat(ageing.days61To90).isEqualByComparingTo("400.00")
        assertThat(ageing.days90Plus).isEqualByComparingTo("500.00")
        assertThat(ageing.total).isEqualByComparingTo("1500.00")
    }

    @Test
    @DisplayName("ageing counts what is outstanding, not what was invoiced")
    fun ageingNetsOffAllocations() {
        val customer = newCustomer(limit = "5000")
        val invoice = accounts.postInvoice(customer.id!!, BigDecimal("1000.00"), null, null)
        backdate(invoice.id!!, LocalDate.now().minusDays(45))
        accounts.recordPayment(customer.id!!, BigDecimal("750.00"), "part payment")

        assertThat(accounts.ageing(customer.id!!).days31To60).isEqualByComparingTo("250.00")
    }

    // ── Credit standing ────────────────────────────────────────────────────

    @Test
    @DisplayName("a customer with no limit is cash only, and no override changes that")
    fun noLimitMeansCashOnly() {
        val customer = newCustomer(limit = null)
        val decision = accounts.evaluateOnAccount(customer.id!!, BigDecimal("50.00"))

        assertThat(decision.outcome)
            .describedAs("a null limit must never be read as unlimited")
            .isEqualTo(CreditOutcome.REFUSED)
        assertThat(decision.standing.isCashOnly).isTrue()
        assertThat(decision.reason).contains("cash customer")
    }

    @Test
    @DisplayName("within the limit is allowed, over it needs a supervisor")
    fun limitGatesTheSale() {
        val customer = newCustomer(limit = "1000")
        accounts.postInvoice(customer.id!!, BigDecimal("800.00"), null, null)

        assertThat(accounts.evaluateOnAccount(customer.id!!, BigDecimal("150.00")).outcome)
            .isEqualTo(CreditOutcome.ALLOWED)

        val over = accounts.evaluateOnAccount(customer.id!!, BigDecimal("400.00"))
        assertThat(over.outcome).isEqualTo(CreditOutcome.REQUIRES_APPROVAL)
        assertThat(over.reason).contains("over their")
    }

    @Test
    @DisplayName("being overdue is reported but does not block a sale")
    fun overdueWarnsWithoutBlocking() {
        val customer = newCustomer(limit = "5000")
        val invoice = accounts.postInvoice(customer.id!!, BigDecimal("500.00"), null, null)
        backdate(invoice.id!!, LocalDate.now().minusDays(120))

        val standing = accounts.standing(customer.id!!)
        assertThat(standing.hasOverdue).isTrue()
        assertThat(standing.overdueAmount).isEqualByComparingTo("500.00")
        assertThat(standing.oldestOverdueDays).isGreaterThanOrEqualTo(120)

        assertThat(accounts.evaluateOnAccount(customer.id!!, BigDecimal("100.00")).outcome)
            .describedAs("§11 makes lateness an alert, not a gate")
            .isEqualTo(CreditOutcome.ALLOWED)
    }

    @Test
    @DisplayName("available credit never reads as negative once the limit is blown")
    fun availableCreditFloorsAtZero() {
        val customer = newCustomer(limit = "1000")
        accounts.postInvoice(customer.id!!, BigDecimal("800.00"), null, null)
        // Approved over the limit, which the shop does — the standing still has
        // to describe the result sensibly afterwards.
        accounts.postInvoice(customer.id!!, BigDecimal("700.00"), null, null)

        val standing = accounts.standing(customer.id!!)
        assertThat(standing.balance).isEqualByComparingTo("1500.00")
        assertThat(standing.availableCredit).isEqualByComparingTo("0.00")
    }

    @Test
    @DisplayName("a closed account cannot be charged")
    fun closedAccountRefused() {
        val customer = newCustomer(limit = "5000")
        customers.setActive(customer.id!!, false)

        val decision = accounts.evaluateOnAccount(customer.id!!, BigDecimal("50.00"))
        assertThat(decision.outcome).isEqualTo(CreditOutcome.REFUSED)
    }

    @Test
    @DisplayName("removing a limit leaves the debt standing")
    fun removingALimitDoesNotClearTheDebt() {
        val customer = newCustomer(limit = "2000")
        accounts.postInvoice(customer.id!!, BigDecimal("900.00"), null, null)

        accounts.setCreditTerms(customer.id!!, null, null)

        assertThat(accounts.balance(customer.id!!))
            .describedAs("a debt does not vanish because the shop stopped extending credit")
            .isEqualByComparingTo("900.00")
        assertThat(accounts.standing(customer.id!!).isCashOnly).isTrue()
    }

    // ── Permissions ────────────────────────────────────────────────────────

    @Test
    @DisplayName("setting a credit limit needs CREDIT_APPROVE, not CUSTOMER_MANAGE")
    fun creditLimitNeedsItsOwnPermission() {
        val customer = newCustomer(limit = "1000")
        // Sales staff can edit a customer's phone number. Deciding how much the
        // shop will let them owe is a different decision.
        signInWith(setOf("CUSTOMER_MANAGE"))

        assertThatThrownBy { accounts.setCreditTerms(customer.id!!, BigDecimal("999999"), null) }
            .isInstanceOf(AccessDeniedException::class.java)

        assertThatThrownBy { accounts.writeOff(customer.id!!, BigDecimal("10.00"), "bad debt") }
            .isInstanceOf(AccessDeniedException::class.java)
    }

    // ── Customer records ───────────────────────────────────────────────────

    @Test
    @DisplayName("a customer created without a code gets one allocated")
    fun codesAreAllocated() {
        val a = customers.create(name = "Code Test A")
        val b = customers.create(name = "Code Test B")

        assertThat(a.code).matches("CUS-\\d{6}")
        assertThat(a.code).isNotEqualTo(b.code)
    }

    @Test
    @DisplayName("a phone number is stored in one shape however it was typed")
    fun phoneNumbersAreNormalised() {
        val typed = customers.create(name = "Phone Test", phone = "+233 24 000 0000")
        assertThat(typed.phone).isEqualTo("0240000000")

        // And the counter finds them by whatever they read out.
        assertThat(customers.search("24 000 0000", true, 20).map { it.id }).contains(typed.id)
    }

    @Test
    @DisplayName("a new customer starts with no credit account")
    fun creditIsNeverGrantedAtCreation() {
        val customer = customers.create(name = "Fresh Customer")
        assertThat(customer.creditLimit)
            .describedAs("opening an account is a separate, permissioned decision")
            .isNull()
    }

    // ── Fixtures ───────────────────────────────────────────────────────────

    /**
     * Moves an entry's due date into the past.
     *
     * Ledger entries are immutable by design and no service method does this;
     * it exists only so a test can produce an aged receivable without waiting
     * ninety days for one.
     */
    private fun backdate(entryId: Long, dueOn: LocalDate) {
        val entry = ledger.findById(entryId).orElseThrow()
        entry.dueOn = dueOn
        ledger.saveAndFlush(entry)
    }
}
