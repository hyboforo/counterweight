package com.counterweight.billing

import com.counterweight.billing.domain.SalesDocument
import com.counterweight.billing.service.DocumentService
import com.counterweight.billing.service.QuotationService
import com.counterweight.billing.service.StatementService
import com.counterweight.catalog.repo.CategoryRepository
import com.counterweight.catalog.service.ProductService
import com.counterweight.catalog.service.ProductUomSpec
import com.counterweight.identity.repo.AppUserRepository
import com.counterweight.identity.security.CurrentUser
import com.counterweight.inventory.service.InventoryService
import com.counterweight.inventory.service.ReceiptLine
import com.counterweight.parties.service.CustomerAccountService
import com.counterweight.parties.service.CustomerService
import com.counterweight.pricing.service.PriceListService
import com.counterweight.pricing.service.PricingService
import com.counterweight.sales.service.CartService
import com.counterweight.sales.service.CompleteSaleCommand
import com.counterweight.sales.service.ReturnLineRequest
import com.counterweight.sales.service.SaleCompletionService
import com.counterweight.sales.service.SaleReturnService
import com.counterweight.sales.service.TenderLine
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
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
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Documents, quotations and statements.
 *
 * The document assertions matter most. A register with a gap in it is a
 * question an auditor asks and nobody can answer, and the numbering only holds
 * because allocation happens inside the issuing transaction — which is
 * observable only by rolling one back and looking at what the next one gets.
 */
@SpringBootTest
@Testcontainers
@DisplayName("Billing")
class BillingTest {

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

    @Autowired private lateinit var quotations: QuotationService
    @Autowired private lateinit var documents: DocumentService
    @Autowired private lateinit var statements: StatementService
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
    @Autowired private lateinit var quotationRepo: com.counterweight.billing.repo.QuotationRepository

    private val fullRights = setOf(
        "SALE_CREATE", "SALE_HOLD", "SALE_RETURN", "SALE_VOID", "SALE_DISCOUNT", "SALE_PRICE_OVERRIDE",
        "STOCK_RECEIVE", "PRODUCT_MANAGE", "PRICE_MANAGE", "PRICE_VIEW",
        "CUSTOMER_MANAGE", "CREDIT_APPROVE",
        "REPORT_VIEW",
    )

    @BeforeEach
    fun signIn() {
        val userId = users.findAll().first().id!!
        SecurityContextHolder.getContext().authentication = PreAuthenticatedAuthenticationToken(
            CurrentUser(userId, "test-operator", 1L, setOf("ADMIN"), fullRights),
            null,
            fullRights.map { SimpleGrantedAuthority(it) },
        )
    }

    // ── Fixtures ───────────────────────────────────────────────────────────

    private fun stockedProduct(price: String = "10.00", qty: String = "500"): Pair<Long, Long> {
        val n = seq.incrementAndGet()
        val product = productService.create(
            sku = "BILL-$n", name = "Billing Product $n",
            categoryId = categories.findByCode("HARDWARE")!!.id!!,
            units = listOf(ProductUomSpec("PCS", BigDecimal.ONE, isBase = true)),
        )
        val uomId = productService.unitsOf(product.id!!).single().id!!
        inventory.receive(
            listOf(ReceiptLine(product.id!!, uomId, BigDecimal(qty), BigDecimal("4.00"))),
            reference = "GRN-$n",
        )
        pricing.setPrice(priceLists.default().id!!, product.id!!, uomId, BigDecimal(price), null)
        return product.id!! to uomId
    }

    private fun openTill(): String = "TILL-B${seq.incrementAndGet()}"

    private fun creditCustomer(limit: String = "10000"): Long {
        val customer = customers.create(name = "Billing Customer ${seq.incrementAndGet()}")
        accounts.setCreditTerms(customer.id!!, BigDecimal(limit), 30)
        return customer.id!!
    }

    /** A completed cash sale, returning its id. */
    private fun cashSale(productId: Long, uomId: Long, qty: String, total: String): Long {
        val till = openTill()
        val sale = cart.startSale(null)
        cart.addLine(sale.id!!, productId, uomId, BigDecimal(qty))
        completion.complete(
            CompleteSaleCommand(sale.id!!, listOf(TenderLine("CASH", BigDecimal(total))), tillCode = till),
            UUID.randomUUID(),
        )
        return sale.id!!
    }

    // ── Documents ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("a cash sale produces a receipt document, numbered to match the sale")
    fun cashSaleIssuesAReceipt() {
        val (productId, uomId) = stockedProduct()
        val saleId = cashSale(productId, uomId, "3", "30.00")

        val issued = documents.forSale(saleId)
        assertThat(issued).hasSize(1)
        assertThat(issued.single().docType).isEqualTo(SalesDocument.RECEIPT)
        assertThat(issued.single().number)
            .describedAs("two numbers for one receipt would put the sale and its paper out of step")
            .isEqualTo(cart.get(saleId).number)
        assertThat(issued.single().total).isEqualByComparingTo("30.00")
    }

    @Test
    @DisplayName("an on-account sale also produces an invoice, linked to the ledger entry")
    fun onAccountSaleIssuesAnInvoice() {
        val (productId, uomId) = stockedProduct()
        val customerId = creditCustomer()

        val sale = cart.startSale(customerId)
        cart.addLine(sale.id!!, productId, uomId, BigDecimal("5"))
        completion.complete(
            CompleteSaleCommand(sale.id!!, listOf(TenderLine("ON_ACCOUNT", BigDecimal("50.00")))),
            UUID.randomUUID(),
        )

        val issued = documents.forSale(sale.id!!)
        assertThat(issued.map { it.docType })
            .containsExactlyInAnyOrder(SalesDocument.RECEIPT, SalesDocument.INVOICE)

        val invoice = issued.single { it.docType == SalesDocument.INVOICE }
        assertThat(invoice.number).matches("INV-\\d{6}")
        assertThat(invoice.total).isEqualByComparingTo("50.00")

        val entry = accounts.statement(customerId).single { it.entryType == "INVOICE" }
        assertThat(entry.salesDocumentId)
            .describedAs("the statement has to be able to cite the invoice the debt is owed against")
            .isEqualTo(invoice.id)
    }

    @Test
    @DisplayName("a split-tender sale gets both a receipt and an invoice for the account part")
    fun splitTenderIssuesBoth() {
        val (productId, uomId) = stockedProduct()
        val customerId = creditCustomer()

        val sale = cart.startSale(customerId)
        cart.addLine(sale.id!!, productId, uomId, BigDecimal("10"))
        val till = openTill()
        completion.complete(
            CompleteSaleCommand(
                sale.id!!,
                listOf(
                    TenderLine("CASH", BigDecimal("40.00")),
                    TenderLine("ON_ACCOUNT", BigDecimal("60.00")),
                ),
                tillCode = till,
            ),
            UUID.randomUUID(),
        )

        val issued = documents.forSale(sale.id!!)
        assertThat(issued.single { it.docType == SalesDocument.RECEIPT }.total).isEqualByComparingTo("100.00")
        assertThat(issued.single { it.docType == SalesDocument.INVOICE }.total)
            .describedAs("the invoice covers only what is actually owed")
            .isEqualByComparingTo("60.00")
    }

    @Test
    @DisplayName("document numbers are gapless even when a sale rolls back")
    fun numberingIsGapless() {
        val (productId, uomId) = stockedProduct(qty = "10")
        cashSale(productId, uomId, "1", "10.00")
        val firstNumber = documents.forSale(cart.held().firstOrNull()?.id ?: 0L)
        val before = latestReceiptNumber()

        // A completion that fails after the number would have been allocated.
        // Tenders that do not balance abort the transaction.
        val doomed = cart.startSale(null)
        cart.addLine(doomed.id!!, productId, uomId, BigDecimal("1"))
        assertThatThrownBy {
            completion.complete(
                CompleteSaleCommand(doomed.id!!, listOf(TenderLine("CASH", BigDecimal("999.00")))),
                UUID.randomUUID(),
            )
        }.isInstanceOf(Exception::class.java)

        cashSale(productId, uomId, "1", "10.00")
        val after = latestReceiptNumber()

        assertThat(after)
            .describedAs("a failed sale must not consume a receipt number")
            .isEqualTo(before + 1)
        assertThat(firstNumber).isNotNull
    }

    @Test
    @DisplayName("a credit-note refund issues a credit note; a cash refund does not")
    fun creditNoteOnlyWhereOneIsHandedOver() {
        val (productId, uomId) = stockedProduct()
        val customerId = creditCustomer()

        // Cash refund — no document, the return record is the evidence.
        val cashSaleId = cashSale(productId, uomId, "4", "40.00")
        val cashLine = cart.lines(cashSaleId).single()
        val cashReturn = saleReturns.createReturn(
            cashSaleId, listOf(ReturnLineRequest(cashLine.id!!, BigDecimal("1"))), "Wrong size", "CASH",
        )
        assertThat(documents.forReturn(cashReturn.id!!)).isEmpty()

        // Credit-note refund — the customer is handed a numbered document.
        val sale = cart.startSale(customerId)
        cart.addLine(sale.id!!, productId, uomId, BigDecimal("4"))
        completion.complete(
            CompleteSaleCommand(sale.id!!, listOf(TenderLine("ON_ACCOUNT", BigDecimal("40.00")))),
            UUID.randomUUID(),
        )
        val line = cart.lines(sale.id!!).single()
        val creditReturn = saleReturns.createReturn(
            sale.id!!, listOf(ReturnLineRequest(line.id!!, BigDecimal("2"))), "Changed mind", "CREDIT_NOTE",
        )

        val note = documents.forReturn(creditReturn.id!!).single()
        assertThat(note.docType).isEqualTo(SalesDocument.CREDIT_NOTE)
        assertThat(note.number).matches("CRN-\\d{6}")
        assertThat(note.total).isEqualByComparingTo("20.00")
    }

    @Test
    @DisplayName("a document carries the tax configuration it was issued under")
    fun documentsCarryATaxSnapshot() {
        val (productId, uomId) = stockedProduct()
        val saleId = cashSale(productId, uomId, "2", "20.00")

        val snapshot = documents.taxSnapshotOf(documents.forSale(saleId).single().id!!)
        assertThat(snapshot).isNotNull
        // The shop is not VAT-registered, so the live scheme is NONE with no
        // components — but the snapshot still records that, which is what makes
        // a reprint after registration reproduce the original figures.
        assertThat(snapshot!!.schemeCode).isEqualTo("NONE")
        assertThat(snapshot.components).isEmpty()
        assertThat(snapshot.taxTotal).isEqualByComparingTo("0.00")
    }

    @Test
    @DisplayName("a delivery note can only be issued once per sale")
    fun deliveryNoteIsIssuedOnce() {
        val (productId, uomId) = stockedProduct()
        val saleId = cashSale(productId, uomId, "2", "20.00")

        val note = documents.issueDeliveryNote(saleId, BigDecimal("20.00"))
        assertThat(note.number).matches("DN-\\d{6}")

        assertThatThrownBy { documents.issueDeliveryNote(saleId, BigDecimal("20.00")) }
            .hasMessageContaining("already been issued")
    }

    @Test
    @DisplayName("the register lists what was issued, in number order")
    fun registerListsIssuedDocuments() {
        val (productId, uomId) = stockedProduct()
        cashSale(productId, uomId, "1", "10.00")
        cashSale(productId, uomId, "1", "10.00")

        val zone = java.time.ZoneId.systemDefault()
        val today = LocalDate.now()
        val register = documents.register(
            SalesDocument.RECEIPT,
            today.atStartOfDay(zone).toInstant(),
            today.plusDays(1).atStartOfDay(zone).toInstant(),
        )
        assertThat(register.size).isGreaterThanOrEqualTo(2)
        assertThat(register.map { it.number }).isSorted
    }

    // ── Quotations ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("a quotation prices lines and totals them without touching stock")
    fun quotationDoesNotReserveStock() {
        val (productId, uomId) = stockedProduct(price = "10.00", qty = "20")
        val before = inventory.position(productId).single().qtyBase

        val quote = quotations.create(null, LocalDate.now().plusDays(14))
        quotations.addLine(quote.id!!, productId, uomId, BigDecimal("15"))

        assertThat(quotations.get(quote.id!!).total).isEqualByComparingTo("150.00")
        assertThat(inventory.position(productId).single().qtyBase)
            .describedAs("quoting for goods must not make them unsellable to whoever walks in with cash")
            .isEqualByComparingTo(before)
    }

    @Test
    @DisplayName("converting a quotation carries the quoted prices onto the sale")
    fun conversionKeepsQuotedPrices() {
        val (productId, uomId) = stockedProduct(price = "10.00")
        val quote = quotations.create(null, null)
        // A negotiated price, below list.
        quotations.addLine(quote.id!!, productId, uomId, BigDecimal("4"), BigDecimal("7.50"))

        val sale = quotations.convertToSale(quote.id!!)
        val line = cart.lines(sale.id!!).single()

        assertThat(line.unitPrice)
            .describedAs("re-resolving at list would break the promise the quotation made")
            .isEqualByComparingTo("7.50")
        assertThat(cart.get(sale.id!!).grandTotal).isEqualByComparingTo("30.00")
        assertThat(quotations.get(quote.id!!).status).isEqualTo("CONVERTED")
        assertThat(quotations.get(quote.id!!).convertedSaleId).isEqualTo(sale.id)
    }

    @Test
    @DisplayName("a quotation cannot be converted twice")
    fun conversionIsOnce() {
        val (productId, uomId) = stockedProduct()
        val quote = quotations.create(null, null)
        quotations.addLine(quote.id!!, productId, uomId, BigDecimal("1"))
        quotations.convertToSale(quote.id!!)

        assertThatThrownBy { quotations.convertToSale(quote.id!!) }
            .hasMessageContaining("converted")
    }

    @Test
    @DisplayName("an expired quotation cannot be converted, even before the sweep runs")
    fun expiredQuotationsAreRefused() {
        val (productId, uomId) = stockedProduct()
        val quote = quotations.create(null, LocalDate.now().plusDays(1))
        quotations.addLine(quote.id!!, productId, uomId, BigDecimal("1"))

        // Backdate the validity and persist it: the sweep has not run, so the
        // status is still OPEN and only the date says it has lapsed.
        quotationRepo.findById(quote.id!!).get().also {
            it.validUntil = LocalDate.now().minusDays(1)
            quotationRepo.saveAndFlush(it)
        }

        assertThatThrownBy { quotations.convertToSale(quote.id!!) }
            .describedAs("a price the shop no longer honours must not be convertible")
            .hasMessageContaining("only valid until")
    }

    @Test
    @DisplayName("the sweep closes quotations whose validity has run out")
    fun sweepExpiresStaleQuotations() {
        val (productId, uomId) = stockedProduct()
        val quote = quotations.create(null, LocalDate.now().plusDays(2))
        quotations.addLine(quote.id!!, productId, uomId, BigDecimal("1"))

        val closed = quotations.expireStale(LocalDate.now().plusDays(5))

        assertThat(closed).isGreaterThanOrEqualTo(1)
        assertThat(quotations.get(quote.id!!).status).isEqualTo("EXPIRED")
    }

    @Test
    @DisplayName("converting a quotation for stock that has since sold out fails at conversion")
    fun conversionChecksStock() {
        val (productId, uomId) = stockedProduct(qty = "5")
        val quote = quotations.create(null, null)
        quotations.addLine(quote.id!!, productId, uomId, BigDecimal("5"))

        // The stock goes to somebody who walked in with cash.
        cashSale(productId, uomId, "5", "50.00")

        assertThatThrownBy { quotations.convertToSale(quote.id!!) }
            .describedAs("the quotation never reserved anything, so this is the right moment to find out")
            .hasMessageContaining("not enough")
    }

    // ── Statements ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("a statement runs the balance forward and closes on the account balance")
    fun statementRunsTheBalanceForward() {
        val (productId, uomId) = stockedProduct()
        val customerId = creditCustomer()

        val sale = cart.startSale(customerId)
        cart.addLine(sale.id!!, productId, uomId, BigDecimal("10"))
        completion.complete(
            CompleteSaleCommand(sale.id!!, listOf(TenderLine("ON_ACCOUNT", BigDecimal("100.00")))),
            UUID.randomUUID(),
        )
        accounts.recordPayment(customerId, BigDecimal("40.00"), "MoMo 1234")

        val statement = statements.currentMonth(customerId)

        assertThat(statement.lines).hasSize(2)
        assertThat(statement.lines[0].runningBalance).isEqualByComparingTo("100.00")
        assertThat(statement.lines[1].runningBalance).isEqualByComparingTo("60.00")
        assertThat(statement.closingBalance)
            .describedAs("the last line and the balance beneath it have to be the same number")
            .isEqualByComparingTo(accounts.balance(customerId))
    }

    @Test
    @DisplayName("activity before the period lands in the opening balance, not the lines")
    fun openingBalanceExcludesTheperiod() {
        val customerId = creditCustomer()
        accounts.postOpeningBalance(customerId, BigDecimal("250.00"), LocalDate.now().minusDays(60), "carried over")

        // A window that starts tomorrow contains nothing, so everything so far
        // has to be in the brought-forward figure.
        val tomorrow = LocalDate.now().plusDays(1)
        val statement = statements.forCustomer(customerId, tomorrow, tomorrow.plusDays(7))

        assertThat(statement.openingBalance).isEqualByComparingTo("250.00")
        assertThat(statement.lines).isEmpty()
        assertThat(statement.closingBalance).isEqualByComparingTo("250.00")
    }

    @Test
    @DisplayName("a statement period that ends before it starts is refused")
    fun backwardsPeriodRefused() {
        val customerId = creditCustomer()
        assertThatThrownBy {
            statements.forCustomer(customerId, LocalDate.now(), LocalDate.now().minusDays(5))
        }.hasMessageContaining("ends before it starts")
    }

    @Test
    @DisplayName("a statement carries the ageing the collections conversation needs")
    fun statementCarriesAgeing() {
        val customerId = creditCustomer()
        accounts.postOpeningBalance(customerId, BigDecimal("500.00"), LocalDate.now().minusDays(45), "old debt")

        val statement = statements.currentMonth(customerId)
        assertThat(statement.ageing.days31To60).isEqualByComparingTo("500.00")
        assertThat(statement.ageing.total).isEqualByComparingTo("500.00")
        assertThat(statement.creditLimit).isEqualByComparingTo("10000")
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun latestReceiptNumber(): Int {
        val zone = java.time.ZoneId.systemDefault()
        val today = LocalDate.now()
        return documents.register(
            SalesDocument.RECEIPT,
            today.atStartOfDay(zone).toInstant(),
            today.plusDays(1).atStartOfDay(zone).toInstant(),
        ).maxOf { it.number.substringAfter("-").toInt() }
    }
}
