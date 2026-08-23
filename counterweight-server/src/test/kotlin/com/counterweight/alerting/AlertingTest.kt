package com.counterweight.alerting

import com.counterweight.alerting.domain.AlertRule
import com.counterweight.alerting.repo.AlertRuleRepository
import com.counterweight.alerting.service.AlertRequest
import com.counterweight.alerting.service.AlertService
import com.counterweight.alerting.service.MorningBriefingService
import com.counterweight.alerting.service.ReorderPointService
import com.counterweight.alerting.service.ScheduledAlertEvaluator
import com.counterweight.catalog.repo.CategoryRepository
import com.counterweight.catalog.repo.ProductRepository
import com.counterweight.catalog.service.ProductService
import com.counterweight.catalog.service.ProductUomSpec
import com.counterweight.identity.repo.AppUserRepository
import com.counterweight.identity.security.CurrentUser
import com.counterweight.inventory.repo.StockLotRepository
import com.counterweight.inventory.service.InventoryService
import com.counterweight.inventory.service.ReceiptLine
import com.counterweight.parties.service.CustomerAccountService
import com.counterweight.parties.service.CustomerService
import com.counterweight.pricing.service.PriceListService
import com.counterweight.pricing.service.PricingService
import com.counterweight.printing.model.PrintElement
import com.counterweight.sales.service.CartService
import com.counterweight.sales.service.CompleteSaleCommand
import com.counterweight.sales.service.SaleCompletionService
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
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Alerting, with the anti-fatigue mechanisms as the main subject.
 *
 * §11.1 is explicit that dedupe, auto-resolution and snooze are what stand
 * between a useful notification centre and one nobody opens. Those three get
 * the most attention here, because an alerting module that raises correctly but
 * never stops is worse than none at all.
 */
@SpringBootTest
@Testcontainers
@DisplayName("Alerting")
class AlertingTest {

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
        private const val BRANCH = 1L
    }

    @Autowired private lateinit var alerts: AlertService
    @Autowired private lateinit var evaluator: ScheduledAlertEvaluator
    @Autowired private lateinit var reorderPoints: ReorderPointService
    @Autowired private lateinit var briefing: MorningBriefingService
    @Autowired private lateinit var rules: AlertRuleRepository
    @Autowired private lateinit var inventory: InventoryService
    @Autowired private lateinit var productService: ProductService
    @Autowired private lateinit var products: ProductRepository
    @Autowired private lateinit var pricing: PricingService
    @Autowired private lateinit var priceLists: PriceListService
    @Autowired private lateinit var cart: CartService
    @Autowired private lateinit var completion: SaleCompletionService
    @Autowired private lateinit var customers: CustomerService
    @Autowired private lateinit var accounts: CustomerAccountService
    @Autowired private lateinit var categories: CategoryRepository
    @Autowired private lateinit var lots: StockLotRepository
    @Autowired private lateinit var users: AppUserRepository

    private val fullRights = setOf(
        "SALE_CREATE", "SALE_HOLD", "SALE_RETURN", "STOCK_RECEIVE", "STOCK_ADJUST",
        "PRODUCT_MANAGE", "PRICE_MANAGE", "PRICE_VIEW", "COST_VIEW",
        "CUSTOMER_MANAGE", "CREDIT_APPROVE",
        "ALERT_MANAGE", "REPORT_VIEW",
    )

    @BeforeEach
    fun signIn() {
        val userId = users.findAll().first().id!!
        SecurityContextHolder.getContext().authentication = PreAuthenticatedAuthenticationToken(
            CurrentUser(userId, "test-operator", BRANCH, setOf("ADMIN"), fullRights),
            null,
            fullRights.map { SimpleGrantedAuthority(it) },
        )
    }

    // ── Fixtures ───────────────────────────────────────────────────────────

    private fun stockedProduct(
        price: String = "10.00",
        cost: String = "4.00",
        qty: String = "100",
        batchTracked: Boolean = false,
        expiresInDays: Long = 400,
    ): Pair<Long, Long> {
        val n = seq.incrementAndGet()
        val product = productService.create(
            sku = "ALT-$n", name = "Alert Product $n",
            categoryId = categories.findByCode(if (batchTracked) "AGRO" else "HARDWARE")!!.id!!,
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
                    product.id!!, uomId, BigDecimal(qty), BigDecimal(cost),
                    batchCode = if (batchTracked) "LOT-$n" else null,
                    expiresOn = if (batchTracked) LocalDate.now().plusDays(expiresInDays) else null,
                )
            ),
            reference = "GRN-$n",
        )
        pricing.setPrice(priceLists.default().id!!, product.id!!, uomId, BigDecimal(price), null)
        return product.id!! to uomId
    }

    private fun tillCode(): String = "TILL-A${seq.incrementAndGet()}"

    private fun sellAll(productId: Long, uomId: Long, qty: String, total: String) {
        val till = tillCode()
        val sale = cart.startSale(null)
        cart.addLine(sale.id!!, productId, uomId, BigDecimal(qty))
        completion.complete(
            CompleteSaleCommand(sale.id!!, listOf(TenderLine("CASH", BigDecimal(total))), tillCode = till),
            UUID.randomUUID(),
        )
    }

    private fun openKeys() = alerts.open(includeSnoozed = true).map { it.dedupeKey }

    // ── Dedupe, the anti-fatigue mechanism ─────────────────────────────────

    @Test
    @DisplayName("raising the same condition twice leaves one alert")
    fun dedupeKeepsOneOpenAlert() {
        val key = "OUT_OF_STOCK:$BRANCH:test${seq.incrementAndGet()}"
        val request = AlertRequest(
            AlertRule.OUT_OF_STOCK, BRANCH, key, "Out of stock", "nothing on hand",
        )

        val first = alerts.raise(request)
        val second = alerts.raise(request)

        assertThat(first).isNotNull
        assertThat(second)
            .describedAs("a second row and a second notification is exactly the fatigue §11.1 is about")
            .isNull()
        assertThat(openKeys().count { it == key }).isEqualTo(1)
    }

    @Test
    @DisplayName("a resolved condition can be raised again")
    fun resolvedConditionsCanRecur() {
        val key = "OUT_OF_STOCK:$BRANCH:test${seq.incrementAndGet()}"
        val request = AlertRequest(AlertRule.OUT_OF_STOCK, BRANCH, key, "Out of stock", "nothing")

        alerts.raise(request)
        alerts.autoResolve(key)
        val again = alerts.raise(request)

        assertThat(again)
            .describedAs("the partial index only covers open rows, so recurrence is a new alert")
            .isNotNull
    }

    @Test
    @DisplayName("no enabled rule means nothing is raised")
    fun disabledRulesRaiseNothing() {
        val rule = rules.findByRuleType(AlertRule.DEAD_STOCK).first()
        rule.enabled = false
        rules.saveAndFlush(rule)

        val raised = alerts.raise(
            AlertRequest(AlertRule.DEAD_STOCK, BRANCH, "DEAD_STOCK:$BRANCH:x", "Dead", "stock"),
        )
        assertThat(raised).isNull()

        rule.enabled = true
        rules.saveAndFlush(rule)
    }

    // ── Auto-resolution ────────────────────────────────────────────────────

    @Test
    @DisplayName("selling out raises out-of-stock; restocking closes it with nobody clicking")
    fun outOfStockAutoResolves() {
        val (productId, uomId) = stockedProduct(qty = "5", price = "10.00")
        val key = "${AlertRule.OUT_OF_STOCK}:$BRANCH:$productId"

        sellAll(productId, uomId, "5", "50.00")
        assertThat(openKeys()).contains(key)

        inventory.receive(
            listOf(ReceiptLine(productId, uomId, BigDecimal("20"), BigDecimal("4.00"))),
            reference = "restock",
        )

        assertThat(openKeys())
            .describedAs("an alert only a human can close is one that accumulates")
            .doesNotContain(key)
    }

    @Test
    @DisplayName("falling below the reorder point raises a warning that clears on restock")
    fun reorderPointAlertAutoResolves() {
        val (productId, uomId) = stockedProduct(qty = "100", price = "10.00")
        products.findById(productId).get().also {
            it.reorderPoint = BigDecimal("50")
            it.reorderQty = BigDecimal("100")
            products.saveAndFlush(it)
        }
        val key = "${AlertRule.BELOW_REORDER_POINT}:$BRANCH:$productId"

        sellAll(productId, uomId, "60", "600.00")   // 40 left, below 50
        assertThat(openKeys()).contains(key)

        inventory.receive(
            listOf(ReceiptLine(productId, uomId, BigDecimal("100"), BigDecimal("4.00"))),
            reference = "restock",
        )
        assertThat(openKeys()).doesNotContain(key)
    }

    @Test
    @DisplayName("hitting zero replaces the low-stock warning with the out-of-stock one")
    fun zeroStockReportsOnceNotTwice() {
        val (productId, uomId) = stockedProduct(qty = "10", price = "10.00")
        products.findById(productId).get().also {
            it.reorderPoint = BigDecimal("8")
            products.saveAndFlush(it)
        }

        sellAll(productId, uomId, "10", "100.00")

        val keys = openKeys()
        assertThat(keys).contains("${AlertRule.OUT_OF_STOCK}:$BRANCH:$productId")
        assertThat(keys)
            .describedAs("the same empty shelf reported at two severities is noise")
            .doesNotContain("${AlertRule.BELOW_REORDER_POINT}:$BRANCH:$productId")
    }

    @Test
    @DisplayName("a product with no reorder point set is never reported as low")
    fun noReorderPointMeansNoWarning() {
        val (productId, uomId) = stockedProduct(qty = "100", price = "10.00")
        sellAll(productId, uomId, "90", "900.00")

        assertThat(openKeys())
            .describedAs("treating unset as zero would raise it for the whole catalogue")
            .doesNotContain("${AlertRule.BELOW_REORDER_POINT}:$BRANCH:$productId")
    }

    // ── Snooze ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("snoozing hides an alert without disabling its rule")
    fun snoozeQuietsWithoutDisabling() {
        val (productId, uomId) = stockedProduct(qty = "3", price = "10.00")
        sellAll(productId, uomId, "3", "30.00")

        val alert = alerts.open().first { it.dedupeKey == "${AlertRule.OUT_OF_STOCK}:$BRANCH:$productId" }
        alerts.snooze(alert.id!!, Duration.ofHours(48), "order arrives Thursday")

        assertThat(alerts.open().map { it.id })
            .describedAs("snoozed alerts drop out of the default view")
            .doesNotContain(alert.id)
        assertThat(alerts.open(includeSnoozed = true).map { it.id }).contains(alert.id)
        assertThat(rules.findByRuleType(AlertRule.OUT_OF_STOCK).first().enabled)
            .describedAs("the rule stays on — that is the whole point of snoozing")
            .isTrue()
    }

    @Test
    @DisplayName("an over-long snooze is refused as a disguised disabled rule")
    fun snoozeIsBounded() {
        val (productId, uomId) = stockedProduct(qty = "2", price = "10.00")
        sellAll(productId, uomId, "2", "20.00")
        val alert = alerts.open().first { it.dedupeKey == "${AlertRule.OUT_OF_STOCK}:$BRANCH:$productId" }

        assertThatThrownBy { alerts.snooze(alert.id!!, Duration.ofDays(60), null) }
            .hasMessageContaining("disable the rule")
    }

    @Test
    @DisplayName("acknowledging records who saw it without closing it")
    fun acknowledgingDoesNotResolve() {
        val (productId, uomId) = stockedProduct(qty = "2", price = "10.00")
        sellAll(productId, uomId, "2", "20.00")
        val alert = alerts.open().first { it.dedupeKey == "${AlertRule.OUT_OF_STOCK}:$BRANCH:$productId" }

        val acked = alerts.acknowledge(alert.id!!)

        assertThat(acked.acknowledgedAt).isNotNull
        assertThat(acked.acknowledgedBy).isNotNull
        assertThat(acked.isOpen)
            .describedAs("the shelf is still empty after somebody reads that it is")
            .isTrue()
    }

    // ── Expiry ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("stock inside a horizon raises an expiry warning naming the days left")
    fun expiryHorizonsRaiseWarnings() {
        val (productId, _) = stockedProduct(batchTracked = true, qty = "10", expiresInDays = 20)

        evaluator.evaluateExpiringStock(BRANCH)

        val alert = alerts.open(includeSnoozed = true)
            .first { it.dedupeKey.startsWith(AlertRule.EXPIRY_APPROACHING) && it.subjectType == "stock_lot" }
        assertThat(alert.dedupeKey)
            .describedAs("the horizon is in the key so each crossing is its own condition")
            .endsWith(":h30")
        assertThat(alert.body).contains("20 days left")
    }

    @Test
    @DisplayName("expired stock still on hand is critical")
    fun expiredStockIsCritical() {
        val (productId, _) = stockedProduct(batchTracked = true, qty = "10", expiresInDays = 30)
        // Move the batch past its date. Receiving already-expired stock is
        // refused by design, so the fixture ages it in place.
        lots.findAll().first { it.productId == productId }.also {
            it.expiresOn = LocalDate.now().minusDays(2)
            lots.saveAndFlush(it)
        }

        evaluator.evaluateExpiredStock(BRANCH)

        val alert = alerts.open(includeSnoozed = true).first { it.dedupeKey.startsWith(AlertRule.EXPIRED_STOCK) }
        assertThat(alert.severity).isEqualTo(AlertRule.CRITICAL)
        assertThat(alert.body).contains("must not be sold")
    }

    @Test
    @DisplayName("running an evaluator twice does not duplicate its alerts")
    fun evaluatorsAreIdempotent() {
        stockedProduct(batchTracked = true, qty = "10", expiresInDays = 20)

        evaluator.evaluateExpiringStock(BRANCH)
        val after = openKeys().size
        evaluator.evaluateExpiringStock(BRANCH)

        assertThat(openKeys().size)
            .describedAs("a restart must not grow the notification centre")
            .isEqualTo(after)
    }

    // ── Sale-driven ────────────────────────────────────────────────────────

    @Test
    @DisplayName("selling under what the stock cost raises a critical alert")
    fun sellingBelowCostIsFlagged() {
        // Costs 20, priced at 5 — a shelf price nobody updated.
        val (productId, uomId) = stockedProduct(price = "5.00", cost = "20.00", qty = "10")
        sellAll(productId, uomId, "2", "10.00")

        val alert = alerts.open(includeSnoozed = true).first { it.dedupeKey.startsWith(AlertRule.SOLD_BELOW_COST) }
        assertThat(alert.severity).isEqualTo(AlertRule.CRITICAL)
        assertThat(alert.body).contains("against a cost of")
    }

    @Test
    @DisplayName("a normal margin raises nothing")
    fun healthyMarginIsQuiet() {
        val (productId, uomId) = stockedProduct(price = "10.00", cost = "4.00", qty = "10")
        sellAll(productId, uomId, "2", "20.00")

        assertThat(openKeys().filter { it.startsWith(AlertRule.SOLD_BELOW_COST) })
            .describedAs("an alert that fires on ordinary trade is an alert nobody reads")
            .isEmpty()
    }

    @Test
    @DisplayName("a customer reaching their limit is flagged and clears when they pay")
    fun creditLimitAlertAutoResolves() {
        val (productId, uomId) = stockedProduct(price = "10.00", qty = "100")
        val customer = customers.create(name = "Alert Customer ${seq.incrementAndGet()}")
        accounts.setCreditTerms(customer.id!!, BigDecimal("50"), 30)
        val key = "${AlertRule.CREDIT_LIMIT_REACHED}:$BRANCH:customer${customer.id}"

        val sale = cart.startSale(customer.id)
        cart.addLine(sale.id!!, productId, uomId, BigDecimal("5"))
        completion.complete(
            CompleteSaleCommand(sale.id!!, listOf(TenderLine("ON_ACCOUNT", BigDecimal("50.00")))),
            UUID.randomUUID(),
        )
        assertThat(openKeys()).contains(key)

        accounts.recordPayment(customer.id!!, BigDecimal("30.00"), "MoMo")
        // Re-evaluated on the next sale against that customer.
        val second = cart.startSale(customer.id)
        cart.addLine(second.id!!, productId, uomId, BigDecimal("1"))
        completion.complete(
            CompleteSaleCommand(second.id!!, listOf(TenderLine("ON_ACCOUNT", BigDecimal("10.00")))),
            UUID.randomUUID(),
        )
        assertThat(openKeys()).doesNotContain(key)
    }

    // ── Receivables ────────────────────────────────────────────────────────

    @Test
    @DisplayName("an overdue account is raised and clears when it is paid")
    fun overdueInvoicesAutoResolve() {
        val customer = customers.create(name = "Late Payer ${seq.incrementAndGet()}")
        accounts.setCreditTerms(customer.id!!, BigDecimal("5000"), 30)
        accounts.postOpeningBalance(customer.id!!, BigDecimal("400.00"), LocalDate.now().minusDays(50), "old")
        val key = "${AlertRule.INVOICE_OVERDUE}:$BRANCH:customer${customer.id}"

        evaluator.evaluateOverdueInvoices(BRANCH)
        assertThat(openKeys()).contains(key)

        accounts.recordPayment(customer.id!!, BigDecimal("400.00"), "settled")
        evaluator.evaluateOverdueInvoices(BRANCH)

        assertThat(openKeys())
            .describedAs("paying up closes it without anyone clicking anything")
            .doesNotContain(key)
    }

    // ── Reorder points ─────────────────────────────────────────────────────

    @Test
    @DisplayName("the recompute sets a reorder point from trailing usage")
    fun reorderPointsAreComputed() {
        val (productId, uomId) = stockedProduct(qty = "500", price = "10.00")
        sellAll(productId, uomId, "90", "900.00")

        reorderPoints.recompute(BRANCH)

        assertThat(products.findById(productId).get().reorderPoint)
            .describedAs("90 sold over a 90-day window, 14 days lead, 1.2 safety")
            .isNotNull
    }

    @Test
    @DisplayName("a manual reorder point is never overwritten")
    fun manualReorderPointsSurvive() {
        val (productId, uomId) = stockedProduct(qty = "500", price = "10.00")
        products.findById(productId).get().also {
            it.reorderPoint = BigDecimal("999")
            it.reorderIsManual = true
            products.saveAndFlush(it)
        }
        sellAll(productId, uomId, "90", "900.00")

        val result = reorderPoints.recompute(BRANCH)

        assertThat(products.findById(productId).get().reorderPoint)
            .describedAs("the computed answer is wrong for anything seasonal")
            .isEqualByComparingTo("999")
        assertThat(result.diverged)
            .describedAs("divergence is information, and gets reported rather than corrected")
            .anyMatch { it.contains("ALT-") }
    }

    // ── The briefing ───────────────────────────────────────────────────────

    @Test
    @DisplayName("the morning briefing gathers what happened overnight onto one slip")
    fun briefingCarriesTheNightsNews() {
        val (productId, uomId) = stockedProduct(qty = "4", price = "10.00")
        products.findById(productId).get().also {
            it.reorderPoint = BigDecimal("50")
            products.saveAndFlush(it)
        }
        sellAll(productId, uomId, "4", "40.00")

        val customer = customers.create(name = "Briefing Debtor ${seq.incrementAndGet()}")
        accounts.setCreditTerms(customer.id!!, BigDecimal("5000"), 30)
        accounts.postOpeningBalance(customer.id!!, BigDecimal("250.00"), LocalDate.now().minusDays(40), "old")

        val document = briefing.build(BRANCH)
        val text = document.elements.filterIsInstance<PrintElement.Line>().map { it.text }
        val columns = document.elements.filterIsInstance<PrintElement.Columns>()

        assertThat(text).anyMatch { it.contains("MORNING BRIEFING") }
        assertThat(text).anyMatch { it.contains("NEEDS ATTENTION") }
        assertThat(text).anyMatch { it.contains("TO ORDER") }
        assertThat(text).anyMatch { it.contains("OVERDUE ACCOUNTS") }
        assertThat(columns).anyMatch { it.left.contains("Briefing Debtor") }
        assertThat(document.elements).anyMatch { it is PrintElement.Cut }
    }

    @Test
    @DisplayName("badge counts break the open alerts down by severity")
    fun badgesCountBySeverity() {
        val (productId, uomId) = stockedProduct(qty = "2", price = "10.00")
        sellAll(productId, uomId, "2", "20.00")

        val badges = alerts.badges()
        assertThat(badges.critical).isGreaterThanOrEqualTo(1)
        assertThat(badges.total).isEqualTo(badges.critical + badges.warning + badges.info)
    }

    @Test
    @DisplayName("a snoozed alert does not count towards the badges")
    fun snoozedAlertsDoNotBadge() {
        val (productId, uomId) = stockedProduct(qty = "2", price = "10.00")
        sellAll(productId, uomId, "2", "20.00")
        val alert = alerts.open().first { it.dedupeKey == "${AlertRule.OUT_OF_STOCK}:$BRANCH:$productId" }

        val before = alerts.badges().critical
        alerts.snooze(alert.id!!, Duration.ofHours(24), null)

        assertThat(alerts.badges().critical)
            .describedAs("a badge that counts what you have already dealt with is a badge you stop looking at")
            .isEqualTo(before - 1)
    }

    // ── Rules are rows ─────────────────────────────────────────────────────

    @Test
    @DisplayName("every rule type in the catalogue has a seeded rule")
    fun everyEvaluatorHasARule() {
        val seeded = rules.findAll().map { it.ruleType }.toSet()
        assertThat(AlertRule.TYPES)
            .describedAs("an evaluator with no rule runs, finds nothing enabled and raises nothing — silently")
            .allMatch { it in seeded }
    }

    @Test
    @DisplayName("out-of-band channels are refused for non-critical alerts")
    fun smsIsCriticalOnly() {
        val rule = rules.findByRuleType(AlertRule.DEAD_STOCK).first()
        rule.channels = arrayOf("IN_APP", "SMS")
        rules.saveAndFlush(rule)

        alerts.raise(
            AlertRequest(
                AlertRule.DEAD_STOCK, BRANCH,
                "${AlertRule.DEAD_STOCK}:$BRANCH:sms-test${seq.incrementAndGet()}",
                "Not moving", "sitting there",
            )
        )

        // Nothing queued: an owner who gets a text about every slow-moving
        // product stops reading texts from the shop.
        assertThat(outboxStatus().pending).isEqualTo(0)

        rule.channels = arrayOf("IN_APP")
        rules.saveAndFlush(rule)
    }

    @Autowired private lateinit var outbox: com.counterweight.alerting.service.NotificationOutboxService
    private fun outboxStatus() = outbox.status()
}
