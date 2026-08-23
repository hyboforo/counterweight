package com.counterweight.printing

import com.counterweight.catalog.repo.CategoryRepository
import com.counterweight.common.ApiException
import com.counterweight.catalog.service.CategoryService
import com.counterweight.catalog.service.ProductService
import com.counterweight.platform.service.BranchService
import com.counterweight.catalog.service.ProductUomSpec
import com.counterweight.identity.repo.AppUserRepository
import com.counterweight.identity.security.CurrentUser
import com.counterweight.inventory.service.InventoryService
import com.counterweight.inventory.service.ReceiptLine
import com.counterweight.parties.service.CustomerAccountService
import com.counterweight.parties.service.CustomerService
import com.counterweight.pricing.service.PriceListService
import com.counterweight.pricing.service.PricingService
import com.counterweight.printing.domain.QueuedPrintJob
import com.counterweight.inventory.service.StockTakeService
import com.counterweight.printing.model.PrintElement
import com.counterweight.printing.service.PrintQueueService
import com.counterweight.printing.service.PrintingService
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
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Printing: what reaches the queue, and what the document says.
 *
 * The element assertions are the point. A receipt is the customer's copy of the
 * batch traceability the ledger keeps, so "does an agro-chemical line carry its
 * batch and expiry" is a safety question, not a formatting one — and it is only
 * answerable by looking at the elements the server actually produced.
 */
@SpringBootTest
@Testcontainers
@DisplayName("Printing")
class PrintingTest {

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

    @Autowired private lateinit var printing: PrintingService
    @Autowired private lateinit var queue: PrintQueueService
    @Autowired private lateinit var cart: CartService
    @Autowired private lateinit var completion: SaleCompletionService
    @Autowired private lateinit var inventory: InventoryService
    @Autowired private lateinit var stockTakes: StockTakeService
    @Autowired private lateinit var productService: ProductService
    @Autowired private lateinit var pricing: PricingService
    @Autowired private lateinit var priceLists: PriceListService
    @Autowired private lateinit var customers: CustomerService
    @Autowired private lateinit var accounts: CustomerAccountService
    @Autowired private lateinit var categories: CategoryRepository
    @Autowired private lateinit var branches: BranchService
    @Autowired private lateinit var catalog: CategoryService
    @Autowired private lateinit var users: AppUserRepository

    private val fullRights = setOf(
        "SALE_CREATE", "SALE_HOLD", "SALE_RETURN", "STOCK_RECEIVE", "PRODUCT_MANAGE",
        "PRICE_MANAGE", "PRICE_VIEW", "COST_VIEW", "CUSTOMER_MANAGE", "CREDIT_APPROVE",
        "STOCK_COUNT", "STOCK_ADJUST",
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

    private fun stockedProduct(
        price: String = "10.00",
        qty: String = "200",
        batchTracked: Boolean = false,
    ): Pair<Long, Long> {
        val n = seq.incrementAndGet()
        val product = productService.create(
            sku = "PRT-$n", name = "Print Product $n",
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
                    product.id!!, uomId, BigDecimal(qty), BigDecimal("4.00"),
                    batchCode = if (batchTracked) "LOT-$n" else null,
                    expiresOn = if (batchTracked) LocalDate.now().plusDays(45) else null,
                )
            ),
            reference = "GRN-$n",
        )
        pricing.setPrice(priceLists.default().id!!, product.id!!, uomId, BigDecimal(price), null)
        return product.id!! to uomId
    }

    private fun tillCode(): String = "TILL-P${seq.incrementAndGet()}"

    private fun cashSale(productId: Long, uomId: Long, qty: String, total: String, till: String): Long {
        val sale = cart.startSale(null)
        cart.addLine(sale.id!!, productId, uomId, BigDecimal(qty))
        completion.complete(
            CompleteSaleCommand(sale.id!!, listOf(TenderLine("CASH", BigDecimal(total), BigDecimal(total))), tillCode = till),
            UUID.randomUUID(),
        )
        // Nothing queues a receipt on its own any more, and every test below
        // this line is about what the paper says — so the helper asks for it.
        printing.printReceipt(sale.id!!, till)
        return sale.id!!
    }

    private fun receiptFor(till: String): List<PrintElement> {
        val job = queue.history(till).first { it.template == QueuedPrintJob.RECEIPT }
        return queue.toPayload(job).elements
    }

    private fun linesOf(elements: List<PrintElement>): List<String> =
        elements.filterIsInstance<PrintElement.Line>().map { it.text }

    // ── Queueing off the sale path ─────────────────────────────────────────

    @Test
    @DisplayName("completing a sale queues nothing until somebody asks for the paper")
    fun completingASaleQueuesNothing() {
        val (productId, uomId) = stockedProduct()
        val till = tillCode()

        val sale = cart.startSale(null)
        cart.addLine(sale.id!!, productId, uomId, BigDecimal("3"))
        completion.complete(
            CompleteSaleCommand(
                sale.id!!,
                listOf(TenderLine("CASH", BigDecimal("30.00"), BigDecimal("30.00"))),
                tillCode = till,
            ),
            UUID.randomUUID(),
        )

        assertThat(queue.history(till))
            .describedAs("most customers do not want the paper; printing every sale spools a roll nobody collects")
            .isEmpty()

        val job = printing.printReceipt(sale.id!!, till)
        assertThat(job.template).isEqualTo(QueuedPrintJob.RECEIPT)
        assertThat(job.status).isEqualTo(QueuedPrintJob.QUEUED)
        assertThat(queue.history(till)).hasSize(1)
    }

    @Test
    @DisplayName("a receipt cannot be printed for a sale that was never completed")
    fun draftSalesHaveNoReceipt() {
        val (productId, uomId) = stockedProduct()
        val till = tillCode()
        val sale = cart.startSale(null)
        cart.addLine(sale.id!!, productId, uomId, BigDecimal("2"))

        assertThatThrownBy { printing.printReceipt(sale.id!!, till) }
            .isInstanceOf(ApiException.RuleViolation::class.java)
            .hasMessageContaining("not been completed")
    }

    // ── What the receipt says ──────────────────────────────────────────────

    @Test
    @DisplayName("a receipt carries the shop, the number, the total and a cut")
    fun receiptHasTheEssentials() {
        val (productId, uomId) = stockedProduct(price = "10.00")
        val till = tillCode()
        val saleId = cashSale(productId, uomId, "3", "30.00", till)
        val number = cart.get(saleId).number!!

        val elements = receiptFor(till)
        val text = linesOf(elements)

        // Read from the branch record rather than hardcoded here: the point of
        // the assertion is that the header comes from the shop's own row, and a
        // literal would pass just as well against a name baked into a template.
        assertThat(text).anyMatch { it.contains(branches.current().name) }
        assertThat(text).anyMatch { it.contains("TOTAL") && it.contains("30.00") }
        assertThat(elements.filterIsInstance<PrintElement.Columns>())
            .anyMatch { it.left == "Receipt" && it.right == number }
        assertThat(elements.last())
            .describedAs("the cut is the last thing on the paper")
            .isEqualTo(PrintElement.Cut)
    }

    @Test
    @DisplayName("every figure on a receipt is written the same way — two decimals")
    fun moneyIsWrittenOneWay() {
        val (productId, uomId) = stockedProduct(price = "62.00")
        val till = tillCode()
        cashSale(productId, uomId, "1", "62.00", till)

        val columns = receiptFor(till).filterIsInstance<PrintElement.Columns>()
        val lineEntry = columns.single { it.left.contains(" x ") }

        /*
         * `1 BAG x 62` against `62.00` on the same line, which is what a real
         * receipt printed before `DocumentBuilder.money` existed. The quantity
         * strips its zeros because three pieces are `3`; a price never does,
         * because a customer reading two renderings of one figure has been
         * given a reason to doubt the arithmetic.
         */
        assertThat(lineEntry.left)
            .describedAs("the unit price on the quantity line")
            .endsWith("x 62.00")
        assertThat(lineEntry.right).isEqualTo("62.00")
    }

    @Test
    @DisplayName("the receipt number is barcoded so a return can be scanned")
    fun receiptCarriesABarcode() {
        val (productId, uomId) = stockedProduct()
        val till = tillCode()
        val saleId = cashSale(productId, uomId, "1", "10.00", till)

        val barcode = receiptFor(till).filterIsInstance<PrintElement.Barcode>().single()
        assertThat(barcode.value).isEqualTo(cart.get(saleId).number)
    }

    @Test
    @DisplayName("an agro-chemical line prints its batch, expiry and hazard band")
    fun agroLinesCarryBatchAndExpiry() {
        val (productId, uomId) = stockedProduct(batchTracked = true, qty = "20")

        /*
         * The hazard band is recorded here rather than seeded.
         *
         * V16 withdrew the agro licence declarations, so a shop that wants the
         * WHO band on its receipts declares the field and fills it in — which is
         * exactly what this does, through the same endpoint the catalogue
         * screen uses. `ReceiptTemplate` prints the line only when the product
         * carries a band, so without this the receipt is correct and quieter;
         * with it, the safety line is still there. Both halves matter, and this
         * is the half that would otherwise stop being tested.
         */
        val agro = categories.findByCode("AGRO")!!.id!!
        if (catalog.attributesFor(agro).none { it.key == "hazard_band" }) {
            catalog.addAttribute(
                agro, "hazard_band", "WHO Hazard Band", "ENUM",
                listOf("IA", "IB", "II", "III", "U"), null, required = false,
            )
        }
        productService.updateAttributes(productId, mapOf("hazard_band" to "II"))

        val till = tillCode()
        cashSale(productId, uomId, "1", "10.00", till)

        val text = linesOf(receiptFor(till))

        assertThat(text)
            .describedAs("the receipt is the customer's copy of the recall trail")
            .anyMatch { it.contains("Batch: LOT-") }
        assertThat(text).anyMatch { it.contains("Expires:") }
        assertThat(text).anyMatch { it.contains("WHO hazard band: II") }
        assertThat(text).anyMatch { it.contains("Read the label before use") }
        // Stocked 45 days out, so the days-remaining note should be there.
        assertThat(text).anyMatch { it.contains("days left") }
    }

    @Test
    @DisplayName("a hardware line does not print the auto-generated lot")
    fun hardwareLinesHaveNoBatchNoise() {
        val (productId, uomId) = stockedProduct(batchTracked = false)
        val till = tillCode()
        cashSale(productId, uomId, "2", "20.00", till)

        assertThat(linesOf(receiptFor(till)))
            .describedAs("an AUTO- lot code means nothing to a customer and buries the ones that matter")
            .noneMatch { it.contains("Batch:") }
    }

    @Test
    @DisplayName("a shop charging no tax does not print a zero tax line")
    fun noTaxLineWhenNotRegistered() {
        val (productId, uomId) = stockedProduct()
        val till = tillCode()
        cashSale(productId, uomId, "2", "20.00", till)

        assertThat(receiptFor(till).filterIsInstance<PrintElement.Columns>())
            .noneMatch { it.left == "Tax" }
    }

    @Test
    @DisplayName("a reprint says so on the paper")
    fun reprintIsMarked() {
        val (productId, uomId) = stockedProduct()
        val till = tillCode()
        val saleId = cashSale(productId, uomId, "1", "10.00", till)

        val job = printing.printReceipt(saleId, till, isReprint = true)
        val text = linesOf(queue.toPayload(job).elements)

        assertThat(text)
            .describedAs("otherwise the same purchase can be presented twice")
            .anyMatch { it.contains("REPRINT") }
    }

    // ── The queue ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("claiming hands the job over once and marks it printing")
    fun claimingIsExactlyOnce() {
        val (productId, uomId) = stockedProduct()
        val till = tillCode()
        cashSale(productId, uomId, "1", "10.00", till)

        val first = queue.claimFor(till)
        val second = queue.claimFor(till)

        assertThat(first).hasSize(1)
        assertThat(second)
            .describedAs("two browser tabs polling the same till must not both print it")
            .isEmpty()
        assertThat(queue.get(first.single().id).status).isEqualTo(QueuedPrintJob.PRINTING)
    }

    @Test
    @DisplayName("a failed job returns to the queue until the attempt limit, then stops")
    fun failuresRequeueThenGiveUp() {
        val (productId, uomId) = stockedProduct()
        val till = tillCode()
        cashSale(productId, uomId, "1", "10.00", till)
        val jobId = queue.history(till).single().id

        // Paper out, five times over.
        repeat(5) {
            queue.claimFor(till)
            queue.markFailed(jobId, "paper out")
        }

        val job = queue.get(jobId)
        assertThat(job.status)
            .describedAs("a job retrying for ever against an unplugged printer blocks the queue behind it")
            .isEqualTo(QueuedPrintJob.FAILED)
        assertThat(job.lastError).isEqualTo("paper out")
        assertThat(queue.claimFor(till)).isEmpty()
    }

    @Test
    @DisplayName("retrying a failed job puts it back with a clean slate")
    fun retryResetsAttempts() {
        val (productId, uomId) = stockedProduct()
        val till = tillCode()
        cashSale(productId, uomId, "1", "10.00", till)
        val jobId = queue.history(till).single().id
        repeat(5) { queue.claimFor(till); queue.markFailed(jobId, "cover open") }

        val retried = queue.retry(jobId)

        assertThat(retried.status).isEqualTo(QueuedPrintJob.QUEUED)
        assertThat(retried.attempts).isEqualTo(0)
        assertThat(retried.lastError).isNull()
        assertThat(queue.claimFor(till)).hasSize(1)
    }

    @Test
    @DisplayName("a job the till never reported on can be requeued")
    fun staleJobsCanComeBack() {
        val (productId, uomId) = stockedProduct()
        val till = tillCode()
        cashSale(productId, uomId, "1", "10.00", till)
        queue.claimFor(till)   // taken, then the till is unplugged

        val requeued = queue.requeueStale(till, java.time.Instant.now())

        assertThat(requeued).isEqualTo(1)
        assertThat(queue.claimFor(till)).hasSize(1)
    }

    @Test
    @DisplayName("marking a job done completes it")
    fun doneCompletesTheJob() {
        val (productId, uomId) = stockedProduct()
        val till = tillCode()
        cashSale(productId, uomId, "1", "10.00", till)
        val jobId = queue.claimFor(till).single().id

        queue.markDone(jobId)

        val job = queue.get(jobId)
        assertThat(job.status).isEqualTo(QueuedPrintJob.DONE)
        assertThat(job.completedAt).isNotNull
    }

    @Test
    @DisplayName("reprinting a job queues a fresh one rather than resetting the old")
    fun reprintLeavesTheOriginalRecord() {
        val (productId, uomId) = stockedProduct()
        val till = tillCode()
        cashSale(productId, uomId, "1", "10.00", till)
        val original = queue.claimFor(till).single().id
        queue.markDone(original)

        val copy = queue.reprint(original)

        assertThat(copy.id).isNotEqualTo(original)
        assertThat(queue.get(original).status)
            .describedAs("the register should still show that it printed twice")
            .isEqualTo(QueuedPrintJob.DONE)
        assertThat(copy.status).isEqualTo(QueuedPrintJob.QUEUED)
    }

    // ── Reports and labels ─────────────────────────────────────────────────

    @Test
    @DisplayName("a shelf label carries a scannable code and the price")
    fun shelfLabelHasBarcodeAndPrice() {
        val (productId, uomId) = stockedProduct(price = "12.50")
        val till = tillCode()

        val job = printing.printShelfLabel(productId, uomId, till, copies = 3)
        val elements = queue.toPayload(job).elements

        assertThat(job.copies.toInt()).isEqualTo(3)
        assertThat(elements.filterIsInstance<PrintElement.Barcode>()).hasSize(1)
        assertThat(linesOf(elements)).anyMatch { it.contains("12.5") && it.contains("PCS") }
    }

    @Test
    @DisplayName("the goods-receipt slip shows cost only to someone allowed to see cost")
    fun goodsReceiptHidesCostFromStorekeepers() {
        val till = tillCode()
        val n = seq.incrementAndGet()
        val product = productService.create(
            sku = "GRN-$n", name = "Received Product $n",
            categoryId = categories.findByCode("HARDWARE")!!.id!!,
            units = listOf(ProductUomSpec("BAG", BigDecimal.ONE, isBase = true)),
        )
        val uomId = productService.unitsOf(product.id!!).single().id!!
        val receipt = inventory.receive(
            listOf(ReceiptLine(product.id!!, uomId, BigDecimal("40"), BigDecimal("62.00"))),
            reference = "DN-1",
        )

        val withCost = printing.printGoodsReceipt(receipt.id!!, till)
        val shown = queue.toPayload(withCost)
        assertThat(linesOf(shown.elements) + shown.elements.filterIsInstance<PrintElement.Columns>().map { it.right })
            .anyMatch { it.contains("62") }
        assertThat(linesOf(shown.elements) + shown.elements.filterIsInstance<PrintElement.Columns>().map { it.left })
            .describedAs("the slip carries our own document number, not just the supplier's")
            .anyMatch { it.contains(receipt.number) }

        // A storekeeper counts cartons; what the shop paid is not their business.
        val storekeeperRights = setOf("STOCK_RECEIVE", "PRODUCT_MANAGE")
        SecurityContextHolder.getContext().authentication = PreAuthenticatedAuthenticationToken(
            CurrentUser(users.findAll().first().id!!, "storekeeper", 1L, setOf("STOREKEEPER"), storekeeperRights),
            null,
            storekeeperRights.map { SimpleGrantedAuthority(it) },
        )
        val withoutCost = printing.printGoodsReceipt(receipt.id!!, till)
        val payload = queue.toPayload(withoutCost)
        assertThat(payload.elements.filterIsInstance<PrintElement.Columns>())
            .describedAs("cost on this slip is how supplier prices become common knowledge")
            .noneMatch { it.right.contains("62") }
    }

    /**
     * The point of V12: the slip says what was recorded, not what a caller
     * claims was recorded.
     *
     * Rendering takes the receipt's id and reads `goods_receipt_line`, so there
     * is no request body left that could disagree with the ledger. This asserts
     * the two actually match, quantity for quantity.
     */
    @Test
    @DisplayName("the slip is rendered from the receipt, not from whatever the caller sends")
    fun goodsReceiptSlipComesFromTheDatabase() {
        val till = tillCode()
        val n = seq.incrementAndGet()
        val product = productService.create(
            sku = "GRNDB-$n", name = "Carton Product $n",
            categoryId = categories.findByCode("HARDWARE")!!.id!!,
            units = listOf(
                ProductUomSpec("PCS", BigDecimal.ONE, isBase = true),
                ProductUomSpec("CARTON", BigDecimal("12"), isBase = false, sellable = false),
            ),
        )
        val carton = productService.unitsOf(product.id!!).single { !it.isBase }
        val receipt = inventory.receive(
            listOf(ReceiptLine(product.id!!, carton.id!!, BigDecimal("5"), BigDecimal("220.00"))),
            reference = "DN-$n",
        )

        val elements = queue.toPayload(printing.printGoodsReceipt(receipt.id!!, till)).elements
        val text = (linesOf(elements) +
            elements.filterIsInstance<PrintElement.Columns>().map { "${it.left} ${it.right}" }).joinToString("\n")

        assertThat(text).contains(receipt.number)
        assertThat(text).describedAs("the supplier's own paper is what this is filed against").contains("DN-$n")
        assertThat(text).describedAs("as it was keyed").contains("5 CARTON")
        assertThat(text)
            .describedAs("and as the ledger holds it — the conversion is the whole risk on this document")
            .contains("60 PCS")
        assertThat(text).contains("18.3333")
        assertThat(text)
            .describedAs("a padlock has no batch; the generated lot code is not one")
            .doesNotContain("Batch:")
    }

    /** The other half of that: a real batch does print, with its expiry. */
    @Test
    @DisplayName("a batch-tracked delivery prints the batch and expiry off the pack")
    fun goodsReceiptSlipCarriesRealBatches() {
        val till = tillCode()
        val n = seq.incrementAndGet()
        val product = productService.create(
            sku = "GRNAG-$n", name = "Agro Product $n",
            categoryId = categories.findByCode("AGRO")!!.id!!,
            units = listOf(ProductUomSpec("PCS", BigDecimal.ONE, isBase = true)),
            // V16 withdrew the agro licence fields, so a product filed under
            // agro carries none by default; the validator refuses a key its
            // category no longer declares.
            attributes = null,
            isBatchTracked = true,
            pickingRule = "FEFO",
        )
        val uomId = productService.unitsOf(product.id!!).single().id!!
        val receipt = inventory.receive(
            listOf(
                ReceiptLine(
                    product.id!!, uomId, BigDecimal("24"), BigDecimal("56.00"),
                    batchCode = "BATCH-$n", expiresOn = LocalDate.now().plusDays(400),
                )
            ),
            reference = "DN-AG$n",
        )

        val elements = queue.toPayload(printing.printGoodsReceipt(receipt.id!!, till)).elements
        val text = linesOf(elements).joinToString("\n")

        assertThat(text).contains("Batch: BATCH-$n")
        assertThat(text)
            .describedAs("the expiry is what FEFO picks on and what a recall is traced through")
            .contains("Expires:")
    }

    // ── Stock takes ────────────────────────────────────────────────────────

    /**
     * The blind count is only a control if it survives onto paper, which is the
     * medium the counting is actually done on. A distinctive expected quantity
     * goes in and the whole document is searched for it — the strongest form of
     * this assertion, because it tests the paper rather than the type that
     * produced it.
     */
    @Test
    @DisplayName("the printed count sheet gives the counter nothing to anchor on")
    fun countSheetIsBlindOnPaper() {
        val (productId, _) = stockedProduct(qty = "137")
        val till = tillCode()
        val take = stockTakes.open(null)

        val job = printing.printCountSheet(take.id, till)
        val elements = queue.toPayload(job).elements
        val everything = elements.mapNotNull {
            when (it) {
                is PrintElement.Line -> it.text
                is PrintElement.Columns -> it.left + " " + it.right
                else -> null
            }
        }

        /*
         * As a standalone number, not as a substring. Auto-generated lot codes
         * carry a millisecond timestamp — AUTO-11-1787386137xxx contains "137"
         * and means nothing to a counter. Matching that would fail the test for
         * a reason that has nothing to do with blindness.
         */
        val asAFigure = Regex("(?<!\\d)137(?!\\d)")
        assertThat(asAFigure.containsMatchIn(everything.joinToString("\n")))
            .describedAs("a counter who is shown 137 will find 137")
            .isFalse()
        assertThat(everything)
            .describedAs("but there has to be somewhere to write the figure")
            .anyMatch { it.contains("[") && it.contains("]") }
        assertThat(everything.joinToString(" "))
            .contains(take.reference)

        stockTakes.cancel(take.id, "print test")
        assertThat(productId).isPositive()
    }

    @Test
    @DisplayName("the variance slip carries the figures and somewhere to sign")
    fun varianceSlipCarriesTheNumbers() {
        val (_, _) = stockedProduct(qty = "50")
        val till = tillCode()
        val take = stockTakes.open(null)
        stockTakes.sheet(take.id).forEach { row ->
            // Count everything at what the books said, bar one line short by 3.
            val expected = stockTakes.variances(take.id, false).single { it.lineId == row.lineId }.expectedQty
            stockTakes.count(row.lineId, expected.subtract(BigDecimal("3")).max(BigDecimal.ZERO), null)
        }
        stockTakes.markCounted(take.id)
        stockTakes.post(take.id, null)

        val elements = queue.toPayload(printing.printCountVariances(take.id, till)).elements
        val text = elements.mapNotNull {
            when (it) {
                is PrintElement.Line -> it.text
                is PrintElement.Columns -> it.left + " " + it.right
                else -> null
            }
        }.joinToString("\n")

        assertThat(text).contains("expected")
        assertThat(text).contains("-3")
        assertThat(text)
            .describedAs("unsigned, it is just paper")
            .contains("Posted by")
    }

    @Test
    @DisplayName("the document the agent receives carries the paper width")
    fun payloadCarriesWidth() {
        val (productId, uomId) = stockedProduct()
        val till = tillCode()
        cashSale(productId, uomId, "1", "10.00", till)

        val payload = queue.toPayload(queue.history(till).single())
        assertThat(payload.widthChars).isEqualTo(48)
        assertThat(payload.template).isEqualTo(QueuedPrintJob.RECEIPT)
    }
}
