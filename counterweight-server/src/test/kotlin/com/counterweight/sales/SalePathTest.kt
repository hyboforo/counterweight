package com.counterweight.sales

import com.counterweight.catalog.repo.CategoryRepository
import com.counterweight.catalog.repo.ProductRepository
import com.counterweight.catalog.service.CategoryService
import com.counterweight.catalog.service.ProductAttributes
import com.counterweight.catalog.service.ProductService
import com.counterweight.catalog.service.ProductUomSpec
import com.counterweight.identity.domain.AppUser
import com.counterweight.identity.repo.AppUserRepository
import com.counterweight.identity.repo.RoleRepository
import com.counterweight.identity.security.CurrentUser
import com.counterweight.inventory.service.InventoryService
import com.counterweight.inventory.service.ReceiptLine
import com.counterweight.parties.service.CustomerAccountService
import com.counterweight.parties.service.CustomerService
import com.counterweight.pricing.service.PriceListService
import com.counterweight.pricing.service.PricingService
import com.counterweight.sales.repo.SaleLineAllocationRepository
import com.counterweight.sales.repo.SalePaymentRepository
import com.counterweight.sales.service.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.password.PasswordEncoder
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
 * The sale path, end to end.
 *
 * Driven through the services against real PostgreSQL, because most of what
 * makes a sale correct lives in the interaction between them — the picking
 * rule choosing a lot, the balance CHECK refusing an oversell, the idempotency
 * key refusing a second deduction, the credit gate refusing an account charge.
 * None of that is observable from a unit test of any one piece.
 */
@SpringBootTest
@Testcontainers
@DisplayName("Sale path")
class SalePathTest {

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
        const val SUPERVISOR_PIN = "4917"
    }

    @Autowired private lateinit var cart: CartService
    @Autowired private lateinit var completion: SaleCompletionService
    @Autowired private lateinit var saleReturns: SaleReturnService
    @Autowired private lateinit var inventory: InventoryService
    @Autowired private lateinit var productService: ProductService
    @Autowired private lateinit var catalog: CategoryService
    @Autowired private lateinit var pricing: PricingService
    @Autowired private lateinit var priceLists: PriceListService
    @Autowired private lateinit var customers: CustomerService
    @Autowired private lateinit var accounts: CustomerAccountService
    @Autowired private lateinit var products: ProductRepository
    @Autowired private lateinit var categories: CategoryRepository
    @Autowired private lateinit var users: AppUserRepository
    @Autowired private lateinit var roles: RoleRepository
    @Autowired private lateinit var payments: SalePaymentRepository
    @Autowired private lateinit var allocations: SaleLineAllocationRepository
    @Autowired private lateinit var encoder: PasswordEncoder

    private val fullRights = setOf(
        "SALE_CREATE", "SALE_HOLD", "SALE_DISCOUNT", "SALE_PRICE_OVERRIDE", "SALE_RETURN", "SALE_VOID",
        "STOCK_RECEIVE", "STOCK_ADJUST", "PRODUCT_MANAGE", "PRICE_MANAGE", "PRICE_VIEW", "COST_VIEW",
        "CUSTOMER_MANAGE", "CREDIT_APPROVE",
    )

    @BeforeEach
    fun signIn() = signInAs(fullRights)

    private fun signInAs(permissions: Set<String>, roleCodes: Set<String> = setOf("ADMIN")) {
        val userId = users.findAll().first().id!!
        SecurityContextHolder.getContext().authentication = PreAuthenticatedAuthenticationToken(
            CurrentUser(userId, "test-operator", 1L, roleCodes, permissions),
            null,
            permissions.map { SimpleGrantedAuthority(it) },
        )
    }

    // ── Fixtures ───────────────────────────────────────────────────────────

    /** A hardware product with PCS as its base unit, priced, with stock on hand. */
    private fun stockedProduct(
        price: String = "10.00",
        cost: String = "6.00",
        qty: String = "100",
        batchTracked: Boolean = false,
    ): Triple<Long, Long, Long> {
        val n = seq.incrementAndGet()
        val categoryCode = if (batchTracked) "AGRO" else "HARDWARE"
        val categoryId = categories.findByCode(categoryCode)!!.id!!

        val product = productService.create(
            sku = "TEST-$n",
            name = "Test Product $n",
            categoryId = categoryId,
            units = listOf(ProductUomSpec("PCS", BigDecimal.ONE, isBase = true)),
            // V16 withdrew the agro licence fields, so a product filed under
            // agro carries none by default; the validator refuses a key its
            // category no longer declares.
            attributes = null,
            isBatchTracked = batchTracked,
            pickingRule = if (batchTracked) "FEFO" else "FIFO",
        )
        val uomId = productService.unitsOf(product.id!!).single().id!!

        inventory.receive(
            listOf(
                ReceiptLine(
                    productId = product.id!!, productUomId = uomId,
                    qty = BigDecimal(qty), unitCost = BigDecimal(cost),
                    batchCode = if (batchTracked) "BATCH-$n" else null,
                    expiresOn = if (batchTracked) LocalDate.now().plusYears(1) else null,
                )
            ),
            reference = "GRN-$n",
        )
        pricing.setPrice(priceLists.default().id!!, product.id!!, uomId, BigDecimal(price), null)
        return Triple(product.id!!, uomId, product.id!!)
    }

    private fun openTill(code: String = "TILL-${seq.incrementAndGet()}"): String = code

    private fun cash(amount: String, tendered: String? = null) =
        TenderLine("CASH", BigDecimal(amount), tendered?.let(::BigDecimal))

    private fun onHand(productId: Long): BigDecimal =
        inventory.position(productId).firstOrNull()?.qtyBase ?: BigDecimal.ZERO

    /** A supervisor whose PIN can authorise overrides. */
    private fun supervisorWithPin(permission: String): AppUser {
        val n = seq.incrementAndGet()
        val role = roles.findAll().first { it.code == "ADMIN" }
        return users.save(
            AppUser(
                branchId = 1L, username = "supervisor$n", fullName = "Supervisor $n",
                passwordHash = encoder.encode("irrelevant-for-this-test"),
            ).also {
                it.overridePinHash = encoder.encode(SUPERVISOR_PIN)
                it.roles = mutableSetOf(role)
            }
        ).also { check(permission in it.permissionCodes()) { "ADMIN should hold $permission" } }
    }

    // ── Completion ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("a cash sale deducts stock, records the payment and issues a receipt number")
    fun completeCashSale() {
        val (productId, uomId, _) = stockedProduct(price = "10.00", qty = "100")
        val till = openTill()
        val before = onHand(productId)

        val sale = cart.startSale(null)
        cart.addLine(sale.id!!, productId, uomId, BigDecimal("3"))

        val result = completion.complete(
            CompleteSaleCommand(sale.id!!, listOf(cash("30.00", "50.00")), tillCode = till),
            UUID.randomUUID(),
        )

        assertThat(result).isInstanceOf(SaleResult.Completed::class.java)
        val completed = (result as SaleResult.Completed).sale
        assertThat(completed.status).isEqualTo("COMPLETED")
        assertThat(completed.number).matches("RCT-\\d{6}")
        assertThat(completed.grandTotal).isEqualByComparingTo("30.00")
        assertThat(onHand(productId)).isEqualByComparingTo(before.subtract(BigDecimal("3")))

        val payment = result.payments.single()
        assertThat(payment.changeGiven)
            .describedAs("change is derived from what was tendered, not taken from the client")
            .isEqualByComparingTo("20.00")
    }

    @Test
    @DisplayName("the allocation trail records which lot the line drew from")
    fun allocationTrailIsWritten() {
        val (productId, uomId, _) = stockedProduct(qty = "50")
        val till = openTill()
        val sale = cart.startSale(null)
        val line = cart.addLine(sale.id!!, productId, uomId, BigDecimal("2"))

        completion.complete(
            CompleteSaleCommand(sale.id!!, listOf(cash("20.00")), tillCode = till),
            UUID.randomUUID(),
        )

        val trail = allocations.findBySaleLineId(line.id!!)
        assertThat(trail).hasSize(1)
        assertThat(trail.single().qtyBase).isEqualByComparingTo("2")
        assertThat(trail.single().unitCost)
            .describedAs("cost comes from the lot, which is what makes margin exact")
            .isEqualByComparingTo("6.00")
    }

    @Test
    @DisplayName("a line spanning two lots is allocated across both, oldest first")
    fun linesSpanLots() {
        val (productId, uomId, _) = stockedProduct(qty = "5", cost = "6.00")
        // A second delivery of the same product at a different cost.
        inventory.receive(
            listOf(ReceiptLine(productId, uomId, BigDecimal("10"), BigDecimal("9.00"))),
            reference = "GRN-second",
        )
        val till = openTill()
        val sale = cart.startSale(null)
        val line = cart.addLine(sale.id!!, productId, uomId, BigDecimal("8"))

        completion.complete(
            CompleteSaleCommand(sale.id!!, listOf(cash("80.00")), tillCode = till),
            UUID.randomUUID(),
        )

        val trail = allocations.findBySaleLineId(line.id!!).sortedBy { it.id }
        assertThat(trail).hasSize(2)
        assertThat(trail[0].qtyBase).isEqualByComparingTo("5")
        assertThat(trail[0].unitCost).isEqualByComparingTo("6.00")
        assertThat(trail[1].qtyBase).isEqualByComparingTo("3")
        assertThat(trail[1].unitCost)
            .describedAs("FIFO takes the older lot out before touching the newer one")
            .isEqualByComparingTo("9.00")
    }

    @Test
    @DisplayName("a retried completion replays instead of deducting stock twice")
    fun completionIsIdempotent() {
        val (productId, uomId, _) = stockedProduct(qty = "100")
        val till = openTill()
        val before = onHand(productId)

        val sale = cart.startSale(null)
        cart.addLine(sale.id!!, productId, uomId, BigDecimal("4"))
        val key = UUID.randomUUID()

        val first = completion.complete(
            CompleteSaleCommand(sale.id!!, listOf(cash("40.00")), tillCode = till), key,
        )
        val second = completion.complete(
            CompleteSaleCommand(sale.id!!, listOf(cash("40.00")), tillCode = till), key,
        )

        assertThat(first).isInstanceOf(SaleResult.Completed::class.java)
        assertThat(second)
            .describedAs("a double click must not ring the sale up again")
            .isInstanceOf(SaleResult.Replayed::class.java)
        assertThat(second.sale.id).isEqualTo(first.sale.id)
        assertThat(onHand(productId)).isEqualByComparingTo(before.subtract(BigDecimal("4")))
    }

    @Test
    @DisplayName("the payments have to add up to the sale exactly")
    fun tendersMustBalance() {
        val (productId, uomId, _) = stockedProduct()
        val till = openTill()
        val sale = cart.startSale(null)
        cart.addLine(sale.id!!, productId, uomId, BigDecimal("3"))

        assertThatThrownBy {
            completion.complete(
                CompleteSaleCommand(sale.id!!, listOf(cash("25.00")), tillCode = till),
                UUID.randomUUID(),
            )
        }.hasMessageContaining("but the sale is")
    }

    @Test
    @DisplayName("split tender across cash and mobile money is normal")
    fun splitTender() {
        val (productId, uomId, _) = stockedProduct()
        val till = openTill()
        val sale = cart.startSale(null)
        cart.addLine(sale.id!!, productId, uomId, BigDecimal("5"))

        val result = completion.complete(
            CompleteSaleCommand(
                sale.id!!,
                listOf(
                    cash("20.00"),
                    TenderLine("MOBILE_MONEY", BigDecimal("30.00"), momoNetwork = "MTN", reference = "MP2408"),
                ),
                tillCode = till,
            ),
            UUID.randomUUID(),
        )
        assertThat(payments.findBySaleId(result.sale.id!!)).hasSize(2)
    }

    @Test
    @DisplayName("mobile money without the reference the customer read out is refused")
    fun momoNeedsAReference() {
        val (productId, uomId, _) = stockedProduct()
        val sale = cart.startSale(null)
        cart.addLine(sale.id!!, productId, uomId, BigDecimal("1"))

        assertThatThrownBy {
            completion.complete(
                CompleteSaleCommand(
                    sale.id!!,
                    listOf(TenderLine("MOBILE_MONEY", BigDecimal("10.00"), momoNetwork = "MTN")),
                ),
                UUID.randomUUID(),
            )
        }.hasMessageContaining("reference")
    }

    @Test
    @DisplayName("selling more than is on hand is refused")
    fun cannotOversell() {
        val (productId, uomId, _) = stockedProduct(qty = "5")
        val sale = cart.startSale(null)

        assertThatThrownBy { cart.addLine(sale.id!!, productId, uomId, BigDecimal("9")) }
            .hasMessageContaining("not enough")
    }

    @Test
    @DisplayName("a cash sale records the till that rang it up, and needs nothing opened first")
    fun cashSaleRecordsItsTill() {
        val (productId, uomId, _) = stockedProduct()
        val sale = cart.startSale(null)
        cart.addLine(sale.id!!, productId, uomId, BigDecimal("1"))

        val result = completion.complete(
            CompleteSaleCommand(sale.id!!, listOf(cash("10.00")), tillCode = "TILL-FRESH"),
            UUID.randomUUID(),
        )

        assertThat(result.sale.tillCode)
            .describedAs("there is no drawer, so nothing has to be opened before the first customer")
            .isEqualTo("TILL-FRESH")
    }

    @Test
    @DisplayName("a sale written up with no till has none recorded rather than an invented one")
    fun aSaleWithNoTillRecordsNone() {
        val (productId, uomId, _) = stockedProduct()
        val sale = cart.startSale(null)
        cart.addLine(sale.id!!, productId, uomId, BigDecimal("1"))

        val result = completion.complete(
            CompleteSaleCommand(sale.id!!, listOf(cash("10.00"))),
            UUID.randomUUID(),
        )

        assertThat(result.sale.tillCode)
            .describedAs("guessing one would print a receipt at whichever counter came first")
            .isNull()
    }

    // ── Credit ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an on-account sale posts an invoice to the customer ledger")
    fun onAccountPostsAnInvoice() {
        val (productId, uomId, _) = stockedProduct(price = "10.00")
        val customer = customers.create(name = "Credit Customer ${seq.incrementAndGet()}")
        accounts.setCreditTerms(customer.id!!, BigDecimal("1000"), 30)

        val sale = cart.startSale(customer.id)
        cart.addLine(sale.id!!, productId, uomId, BigDecimal("5"))
        completion.complete(
            CompleteSaleCommand(sale.id!!, listOf(TenderLine("ON_ACCOUNT", BigDecimal("50.00")))),
            UUID.randomUUID(),
        )

        assertThat(accounts.balance(customer.id!!)).isEqualByComparingTo("50.00")
    }

    @Test
    @DisplayName("going over the credit limit needs a supervisor, and a PIN carries it")
    fun overLimitNeedsApproval() {
        val (productId, uomId, _) = stockedProduct(price = "10.00", qty = "500")
        val customer = customers.create(name = "Tight Limit ${seq.incrementAndGet()}")
        accounts.setCreditTerms(customer.id!!, BigDecimal("20"), 30)
        supervisorWithPin("CREDIT_APPROVE")

        val sale = cart.startSale(customer.id)
        cart.addLine(sale.id!!, productId, uomId, BigDecimal("5"))   // 50.00, limit is 20

        assertThatThrownBy {
            completion.complete(
                CompleteSaleCommand(sale.id!!, listOf(TenderLine("ON_ACCOUNT", BigDecimal("50.00")))),
                UUID.randomUUID(),
            )
        }.hasMessageContaining("over their")

        val supervisor = users.findAll().last { it.username.startsWith("supervisor") }
        val result = completion.complete(
            CompleteSaleCommand(
                sale.id!!,
                listOf(TenderLine("ON_ACCOUNT", BigDecimal("50.00"))),
                creditApproval = OverrideCredentials(supervisor.username, SUPERVISOR_PIN),
            ),
            UUID.randomUUID(),
        )
        assertThat(result.sale.status).isEqualTo("COMPLETED")
        assertThat(accounts.balance(customer.id!!)).isEqualByComparingTo("50.00")
    }

    @Test
    @DisplayName("a wrong supervisor PIN does not authorise anything")
    fun wrongPinIsRejected() {
        val (productId, uomId, _) = stockedProduct(price = "10.00")
        val customer = customers.create(name = "Tight Limit ${seq.incrementAndGet()}")
        accounts.setCreditTerms(customer.id!!, BigDecimal("5"), 30)
        val supervisor = supervisorWithPin("CREDIT_APPROVE")

        val sale = cart.startSale(customer.id)
        cart.addLine(sale.id!!, productId, uomId, BigDecimal("2"))

        assertThatThrownBy {
            completion.complete(
                CompleteSaleCommand(
                    sale.id!!,
                    listOf(TenderLine("ON_ACCOUNT", BigDecimal("20.00"))),
                    creditApproval = OverrideCredentials(supervisor.username, "9999"),
                ),
                UUID.randomUUID(),
            )
        }.hasMessageContaining("PIN was not accepted")
    }

    @Test
    @DisplayName("a cash customer cannot buy on account, override or not")
    fun cashCustomerCannotBuyOnAccount() {
        val (productId, uomId, _) = stockedProduct()
        val customer = customers.create(name = "Walk In ${seq.incrementAndGet()}")

        val sale = cart.startSale(customer.id)
        cart.addLine(sale.id!!, productId, uomId, BigDecimal("1"))

        assertThatThrownBy {
            completion.complete(
                CompleteSaleCommand(sale.id!!, listOf(TenderLine("ON_ACCOUNT", BigDecimal("10.00")))),
                UUID.randomUUID(),
            )
        }.hasMessageContaining("cash customer")
    }

    // ── Held sales ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("a held basket survives on the server and any till can recall it")
    fun holdAndRecall() {
        val (productId, uomId, _) = stockedProduct()
        val sale = cart.startSale(null)
        cart.addLine(sale.id!!, productId, uomId, BigDecimal("2"))

        cart.hold(sale.id!!, "Kwame, gone for MoMo")
        assertThat(cart.held().map { it.id }).contains(sale.id)

        val recalled = cart.recall(sale.id!!)
        assertThat(recalled.status).isEqualTo("DRAFT")
        assertThat(cart.lines(sale.id!!)).hasSize(1)
    }

    // ── Returns ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a return puts stock back in the lot it came from and refunds what was paid")
    fun returnRestocksTheOriginalLot() {
        val (productId, uomId, _) = stockedProduct(price = "10.00", qty = "100")
        val till = openTill()
        val sale = cart.startSale(null)
        val line = cart.addLine(sale.id!!, productId, uomId, BigDecimal("5"))
        completion.complete(
            CompleteSaleCommand(sale.id!!, listOf(cash("50.00")), tillCode = till),
            UUID.randomUUID(),
        )
        val soldLot = allocations.findBySaleLineId(line.id!!).single().lotId
        val afterSale = onHand(productId)

        val saleReturn = saleReturns.createReturn(
            sale.id!!, listOf(ReturnLineRequest(line.id!!, BigDecimal("2"))),
            "Wrong size", "CASH",
        )

        assertThat(saleReturn.total).isEqualByComparingTo("20.00")
        assertThat(onHand(productId)).isEqualByComparingTo(afterSale.add(BigDecimal("2")))
        assertThat(saleReturns.linesOf(saleReturn.id!!).single().lotId)
            .describedAs("returning to a new lot would lose the batch and expiry")
            .isEqualTo(soldLot)
        assertThat(cart.get(sale.id!!).status).isEqualTo("PARTIALLY_RETURNED")
    }

    @Test
    @DisplayName("returning everything marks the sale RETURNED")
    fun fullReturn() {
        val (productId, uomId, _) = stockedProduct(price = "10.00")
        val till = openTill()
        val sale = cart.startSale(null)
        val line = cart.addLine(sale.id!!, productId, uomId, BigDecimal("3"))
        completion.complete(
            CompleteSaleCommand(sale.id!!, listOf(cash("30.00")), tillCode = till),
            UUID.randomUUID(),
        )

        saleReturns.createReturn(
            sale.id!!, listOf(ReturnLineRequest(line.id!!, BigDecimal("3"))), "Changed mind", "CASH",
        )
        assertThat(cart.get(sale.id!!).status).isEqualTo("RETURNED")
    }

    @Test
    @DisplayName("the same goods cannot be returned twice")
    fun cannotReturnMoreThanWasSold() {
        val (productId, uomId, _) = stockedProduct()
        val till = openTill()
        val sale = cart.startSale(null)
        val line = cart.addLine(sale.id!!, productId, uomId, BigDecimal("4"))
        completion.complete(
            CompleteSaleCommand(sale.id!!, listOf(cash("40.00")), tillCode = till),
            UUID.randomUUID(),
        )

        saleReturns.createReturn(sale.id!!, listOf(ReturnLineRequest(line.id!!, BigDecimal("3"))), "x", "CASH")

        assertThatThrownBy {
            saleReturns.createReturn(sale.id!!, listOf(ReturnLineRequest(line.id!!, BigDecimal("2"))), "x", "CASH")
        }.hasMessageContaining("left to return")
    }

    @Test
    @DisplayName("damaged goods are refunded but not put back on the shelf")
    fun damagedGoodsAreNotRestocked() {
        val (productId, uomId, _) = stockedProduct(qty = "50")
        val till = openTill()
        val sale = cart.startSale(null)
        val line = cart.addLine(sale.id!!, productId, uomId, BigDecimal("4"))
        completion.complete(
            CompleteSaleCommand(sale.id!!, listOf(cash("40.00")), tillCode = till),
            UUID.randomUUID(),
        )
        val afterSale = onHand(productId)

        val saleReturn = saleReturns.createReturn(
            sale.id!!, listOf(ReturnLineRequest(line.id!!, BigDecimal("2"), restock = false)),
            "Broken in transit", "CASH",
        )

        assertThat(saleReturn.total).isEqualByComparingTo("20.00")
        assertThat(onHand(productId))
            .describedAs("a refund is owed but the goods are not sellable")
            .isEqualByComparingTo(afterSale)
    }

    @Test
    @DisplayName("a discounted line refunds what was charged, not the list price")
    fun refundFollowsWhatWasCharged() {
        val (productId, uomId, _) = stockedProduct(price = "10.00", cost = "2.00")
        val till = openTill()
        val sale = cart.startSale(null)
        // 10% off four pieces: 40.00 less 4.00.
        val line = cart.addLine(sale.id!!, productId, uomId, BigDecimal("4"), discountPercent = BigDecimal("10"))
        completion.complete(
            CompleteSaleCommand(sale.id!!, listOf(cash("36.00")), tillCode = till),
            UUID.randomUUID(),
        )

        val saleReturn = saleReturns.createReturn(
            sale.id!!, listOf(ReturnLineRequest(line.id!!, BigDecimal("2"))), "Wrong item", "CASH",
        )
        assertThat(saleReturn.total)
            .describedAs("half of what was actually paid, not half the list price")
            .isEqualByComparingTo("18.00")
    }

    // ── Voids ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("voiding a sale puts the stock back and leaves the record standing")
    fun voidReversesStock() {
        val (productId, uomId, _) = stockedProduct(qty = "100")
        val till = openTill()
        val before = onHand(productId)

        val sale = cart.startSale(null)
        cart.addLine(sale.id!!, productId, uomId, BigDecimal("6"))
        completion.complete(
            CompleteSaleCommand(sale.id!!, listOf(cash("60.00")), tillCode = till),
            UUID.randomUUID(),
        )

        val voided = completion.voidSale(sale.id!!, "Rang up on the wrong till")

        assertThat(voided.status).isEqualTo("VOIDED")
        assertThat(voided.number)
            .describedAs("a sale that disappears is indistinguishable from one that was stolen")
            .isNotNull()
        assertThat(onHand(productId)).isEqualByComparingTo(before)
    }

    @Test
    @DisplayName("voiding an on-account sale takes the debt back off the customer")
    fun voidReversesTheAccountCharge() {
        val (productId, uomId, _) = stockedProduct(price = "10.00")
        val customer = customers.create(name = "Void Customer ${seq.incrementAndGet()}")
        accounts.setCreditTerms(customer.id!!, BigDecimal("1000"), 30)

        val sale = cart.startSale(customer.id)
        cart.addLine(sale.id!!, productId, uomId, BigDecimal("4"))
        completion.complete(
            CompleteSaleCommand(sale.id!!, listOf(TenderLine("ON_ACCOUNT", BigDecimal("40.00")))),
            UUID.randomUUID(),
        )
        assertThat(accounts.balance(customer.id!!)).isEqualByComparingTo("40.00")

        completion.voidSale(sale.id!!, "Wrong customer")
        assertThat(accounts.balance(customer.id!!))
            .describedAs("the shop has the goods back, so the customer must not still owe for them")
            .isEqualByComparingTo("0.00")
    }

    @Test
    @DisplayName("a draft cannot be voided and a completed sale cannot be discarded")
    fun voidAndDiscardDoNotOverlap() {
        val (productId, uomId, _) = stockedProduct()
        val till = openTill()
        val draft = cart.startSale(null)
        cart.addLine(draft.id!!, productId, uomId, BigDecimal("1"))

        assertThatThrownBy { completion.voidSale(draft.id!!, "nope") }
            .hasMessageContaining("Only a completed sale")

        completion.complete(
            CompleteSaleCommand(draft.id!!, listOf(cash("10.00")), tillCode = till),
            UUID.randomUUID(),
        )
        assertThatThrownBy { cart.discard(draft.id!!) }.hasMessageContaining("no longer be changed")
    }

    // ── Restricted goods ───────────────────────────────────────────────────

    @Test
    @DisplayName("a restricted agro-chemical cannot be sold without the buyer recorded")
    fun restrictedGoodsNeedABuyerRecord() {
        val (productId, uomId, _) = stockedProduct(batchTracked = true, qty = "20")
        products.findById(productId).get()   // exists
        markRestricted(productId)

        val till = openTill()
        val sale = cart.startSale(null)
        val line = cart.addLine(sale.id!!, productId, uomId, BigDecimal("1"))

        assertThatThrownBy {
            completion.complete(
                CompleteSaleCommand(sale.id!!, listOf(cash("10.00")), tillCode = till),
                UUID.randomUUID(),
            )
        }.hasMessageContaining("record the buyer")

        val result = completion.complete(
            CompleteSaleCommand(
                sale.id!!, listOf(cash("10.00")), tillCode = till,
                buyerRecords = listOf(BuyerRecord(line.id!!, "Ama Mensah", "0240000000", "GHANA_CARD", "GHA-123")),
            ),
            UUID.randomUUID(),
        )
        assertThat(result.sale.status).isEqualTo("COMPLETED")
    }

    /**
     * Turns the §6.3 buyer register on the way a shop has to, and then marks
     * one product with it.
     *
     * V16 withdrew the agro licence declarations, so the register is no longer
     * switched on by default — which makes declaring it part of what this test
     * has to prove. `POST /api/categories/{id}/attributes` is a real endpoint,
     * so this is still the path a shop is on, not a repository write nothing in
     * production performs. That distinction is the whole reason V15 exists.
     *
     * Declared **optional**, because a required field would refuse every
     * existing product the next time anybody saved one. A product without the
     * key reads as not restricted, which is the correct default for a shop that
     * does not operate the register.
     */
    private fun markRestricted(productId: Long) {
        val agro = categories.findByCode("AGRO")!!.id!!
        if (catalog.attributesFor(agro).none { it.key == ProductAttributes.REQUIRES_BUYER_RECORD }) {
            catalog.addAttribute(
                agro, ProductAttributes.REQUIRES_BUYER_RECORD, "Buyer register required",
                "BOOL", null, null, required = false,
            )
        }
        productService.updateAttributes(
            productId,
            mapOf(ProductAttributes.REQUIRES_BUYER_RECORD to true),
        )
    }
}
