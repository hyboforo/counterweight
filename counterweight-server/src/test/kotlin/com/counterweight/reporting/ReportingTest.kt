package com.counterweight.reporting

import com.counterweight.catalog.repo.CategoryRepository
import com.counterweight.catalog.service.CategoryService
import com.counterweight.catalog.service.ProductAttributes
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
import com.counterweight.reporting.service.ReportExporter
import com.counterweight.reporting.service.ReportService
import com.counterweight.reporting.service.ReportTable
import com.counterweight.reporting.service.SalesRollupService
import com.counterweight.sales.service.*
import org.apache.poi.xssf.usermodel.XSSFWorkbook
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
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reporting.
 *
 * Two things get the most attention. **Batch traceability**, because §12
 * singles it out — a recall has to be one query, and the whole lot-based design
 * exists to make it one. And **the rollup**, because it is derived data written
 * incrementally: if it drifts from the sales behind it, every figure the owner
 * sees is quietly wrong, and the rebuild is the only remedy.
 */
@SpringBootTest
@Testcontainers
@DisplayName("Reporting")
class ReportingTest {

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

    @Autowired private lateinit var reports: ReportService
    @Autowired private lateinit var rollups: SalesRollupService
    @Autowired private lateinit var exporter: ReportExporter
    @Autowired private lateinit var cart: CartService
    @Autowired private lateinit var completion: SaleCompletionService
    @Autowired private lateinit var inventory: InventoryService
    @Autowired private lateinit var productService: ProductService
    @Autowired private lateinit var catalog: CategoryService
    @Autowired private lateinit var pricing: PricingService
    @Autowired private lateinit var priceLists: PriceListService
    @Autowired private lateinit var customers: CustomerService
    @Autowired private lateinit var accounts: CustomerAccountService
    @Autowired private lateinit var categories: CategoryRepository
    @Autowired private lateinit var lots: StockLotRepository
    @Autowired private lateinit var users: AppUserRepository

    private val fullRights = setOf(
        "SALE_CREATE", "SALE_HOLD", "SALE_RETURN", "SALE_VOID", "SALE_DISCOUNT",
        "STOCK_RECEIVE", "STOCK_ADJUST", "PRODUCT_MANAGE", "PRICE_MANAGE", "PRICE_VIEW",
        "COST_VIEW", "CUSTOMER_MANAGE", "CREDIT_APPROVE",
        "REPORT_VIEW", "AUDIT_VIEW",
    )

    @BeforeEach
    fun signIn() = signInAs(fullRights)

    private fun signInAs(permissions: Set<String>) {
        val userId = users.findAll().first().id!!
        SecurityContextHolder.getContext().authentication = PreAuthenticatedAuthenticationToken(
            CurrentUser(userId, "test-operator", BRANCH, setOf("ADMIN"), permissions),
            null,
            permissions.map { SimpleGrantedAuthority(it) },
        )
    }

    // ── Fixtures ───────────────────────────────────────────────────────────

    private fun stockedProduct(
        price: String = "10.00",
        cost: String = "4.00",
        qty: String = "500",
        batchTracked: Boolean = false,
    ): Pair<Long, Long> {
        val n = seq.incrementAndGet()
        val product = productService.create(
            sku = "RPT-$n", name = "Report Product $n",
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
                    batchCode = if (batchTracked) "BATCH-$n" else null,
                    expiresOn = if (batchTracked) LocalDate.now().plusDays(120) else null,
                )
            ),
            reference = "GRN-$n",
        )
        pricing.setPrice(priceLists.default().id!!, product.id!!, uomId, BigDecimal(price), null)
        return product.id!! to uomId
    }

    private fun tillCode(): String = "TILL-R${seq.incrementAndGet()}"

    private fun sell(productId: Long, uomId: Long, qty: String, total: String, customerId: Long? = null): Long {
        val till = tillCode()
        val sale = cart.startSale(customerId)
        cart.addLine(sale.id!!, productId, uomId, BigDecimal(qty))
        completion.complete(
            CompleteSaleCommand(sale.id!!, listOf(TenderLine("CASH", BigDecimal(total))), tillCode = till),
            UUID.randomUUID(),
        )
        return sale.id!!
    }

    private fun today() = LocalDate.now()

    // ── The rollup ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("a completed sale lands in the daily rollup straight away")
    fun salesRollUpLive() {
        val (productId, uomId) = stockedProduct(price = "10.00", cost = "4.00")
        sell(productId, uomId, "5", "50.00")

        val takings = reports.dailyTakings(today(), today())
        assertThat(takings).isNotEmpty
        val row = takings.single { it.businessDate == today() }
        assertThat(row.saleCount).isGreaterThanOrEqualTo(1)
        assertThat(row.net)
            .describedAs("takings have to be readable mid-shift, not after a nightly refresh")
            .isGreaterThanOrEqualTo(BigDecimal("50.00"))
    }

    @Test
    @DisplayName("margin comes out of the rollup as takings less tax less cost")
    fun marginIsDerived() {
        val (productId, uomId) = stockedProduct(price = "10.00", cost = "4.00")
        rollups.rebuild(BRANCH, today(), today())
        sell(productId, uomId, "10", "100.00")

        val product = reports.topProducts(today(), today(), 500).single { it.productId == productId }
        assertThat(product.cost).isEqualByComparingTo("40.00")
        assertThat(product.margin).isEqualByComparingTo("60.00")
        assertThat(product.marginPercent).isEqualByComparingTo("60.00")
    }

    @Test
    @DisplayName("rebuilding from source reproduces what the listener wrote")
    fun rebuildMatchesTheIncrementalRollup() {
        val (productId, uomId) = stockedProduct(price = "10.00", cost = "4.00")
        sell(productId, uomId, "7", "70.00")
        sell(productId, uomId, "3", "30.00")

        val before = reports.dailyTakings(today(), today()).single { it.businessDate == today() }
        rollups.rebuild(BRANCH, today(), today())
        val after = reports.dailyTakings(today(), today()).single { it.businessDate == today() }

        assertThat(after.saleCount)
            .describedAs("if these disagree, every figure the owner sees is quietly wrong")
            .isEqualTo(before.saleCount)
        assertThat(after.net).isEqualByComparingTo(before.net)
        assertThat(after.cost).isEqualByComparingTo(before.cost)
    }

    @Test
    @DisplayName("voiding a sale takes it out of the live figures straight away")
    fun voidsDecrementTheLiveRollup() {
        val (productId, uomId) = stockedProduct(price = "10.00")
        val saleId = sell(productId, uomId, "6", "60.00")
        val before = reports.dailyTakings(today(), today()).single().net

        completion.voidSale(saleId, "mis-scan")

        assertThat(reports.dailyTakings(today(), today()).single().net)
            .describedAs("an owner who voids a sale and sees the total not move stops believing the total")
            .isEqualByComparingTo(before.subtract(BigDecimal("60.00")))
    }

    @Test
    @DisplayName("a voided sale drops out of the figures on rebuild")
    fun voidsAreExcludedOnRebuild() {
        val (productId, uomId) = stockedProduct(price = "10.00")
        val saleId = sell(productId, uomId, "8", "80.00")
        val before = reports.dailyTakings(today(), today()).single().net

        completion.voidSale(saleId, "rang up twice")
        rollups.rebuild(BRANCH, today(), today())

        val after = reports.dailyTakings(today(), today()).singleOrNull()?.net ?: BigDecimal.ZERO
        assertThat(after)
            .describedAs("a void is how the day's figures get corrected")
            .isEqualByComparingTo(before.subtract(BigDecimal("80.00")))
    }

    @Test
    @DisplayName("the hourly heat map covers all 24 hours, quiet ones included")
    fun heatMapHasNoHoles() {
        val (productId, uomId) = stockedProduct()
        sell(productId, uomId, "1", "10.00")

        val map = reports.hourlyHeatMap(today(), today())
        assertThat(map).hasSize(24)
        assertThat(map.map { it.hour }).containsExactlyElementsOf(0..23)
        assertThat(map.sumOf { it.saleCount })
            .describedAs("a gap reads as missing data, not as a quiet afternoon")
            .isGreaterThanOrEqualTo(1)
    }

    // ── Batch traceability ─────────────────────────────────────────────────

    @Test
    @DisplayName("a recall finds every customer who received a batch, in one query")
    fun traceabilityFindsRecipients() {
        val (productId, uomId) = stockedProduct(batchTracked = true, qty = "100", price = "10.00")
        val batch = lots.findAll().first { it.productId == productId }.lotCode

        val customer = customers.create(name = "Recall Customer ${seq.incrementAndGet()}")
        accounts.setCreditTerms(customer.id!!, BigDecimal("1000"), 30)

        sell(productId, uomId, "3", "30.00", customerId = customer.id)
        sell(productId, uomId, "2", "20.00")   // a walk-in

        val trace = reports.traceBatch(batch)

        assertThat(trace).hasSize(2)
        assertThat(trace.map { it.customerName })
            .describedAs("a walk-in must show as unknown, not be dropped from the list")
            .containsExactlyInAnyOrder(customer.name, null)
        assertThat(trace.sumOf { it.qtyBase }).isEqualByComparingTo("5")
        assertThat(trace).allSatisfy { assertThat(it.lotCode).isEqualTo(batch) }
    }

    @Test
    @DisplayName("a voided sale is not in the recall list")
    fun traceabilityExcludesVoids() {
        val (productId, uomId) = stockedProduct(batchTracked = true, qty = "50", price = "10.00")
        val batch = lots.findAll().first { it.productId == productId }.lotCode

        val saleId = sell(productId, uomId, "4", "40.00")
        assertThat(reports.traceBatch(batch)).hasSize(1)

        completion.voidSale(saleId, "wrong item")
        assertThat(reports.traceBatch(batch))
            .describedAs("the goods came back, so nobody received them")
            .isEmpty()
    }

    @Test
    @DisplayName("a recall can be run by anyone on the floor, not just an auditor")
    fun traceabilityIsNotBehindAuditView() {
        val (productId, uomId) = stockedProduct(batchTracked = true, qty = "20", price = "10.00")
        val batch = lots.findAll().first { it.productId == productId }.lotCode
        sell(productId, uomId, "1", "10.00")

        signInAs(setOf("REPORT_VIEW"))
        assertThat(reports.traceBatch(batch))
            .describedAs("the one report that must be run in a hurry cannot need the auditor")
            .hasSize(1)
    }

    // ── Inventory, from the materialised views ─────────────────────────────

    @Test
    @DisplayName("stock valuation reads the view, and refreshing picks up new stock")
    fun valuationComesFromTheRefreshedView() {
        val (productId, _) = stockedProduct(qty = "100", cost = "4.00")

        // Before a refresh the view has not seen this product at all.
        reports.refreshNow()

        val row = reports.stockValuation(onlyInStock = true).single { it.productId == productId }
        assertThat(row.qtyBase).isEqualByComparingTo("100")
        assertThat(row.stockValue)
            .describedAs("valued at lot cost, so valuation and margin always agree")
            .isEqualByComparingTo("400.00")
    }

    @Test
    @DisplayName("the view records when it was last rebuilt, so staleness is never a guess")
    fun refreshTimeIsRecorded() {
        val before = reports.refreshedAt()
        Thread.sleep(10)
        reports.refreshNow()

        assertThat(reports.refreshedAt())
            .describedAs("PostgreSQL does not record this anywhere, so the refresh writes it down")
            .isNotNull
            .satisfies({ after -> assertThat(after).isAfterOrEqualTo(before) })
    }

    @Test
    @DisplayName("expiry ageing buckets what is on hand by how long it has left")
    fun expiryAgeingBuckets() {
        stockedProduct(batchTracked = true, qty = "30")   // 120 days out
        reports.refreshNow()

        val buckets = reports.expiryAgeing().associateBy { it.bucket }
        assertThat(buckets).containsKey("BEYOND_90")
        assertThat(buckets.getValue("BEYOND_90").qtyBase).isGreaterThanOrEqualTo(BigDecimal("30"))
    }

    @Test
    @DisplayName("ABC analysis ranks by what sold and classes on cumulative share")
    fun abcClassifiesByTurnover() {
        val (big, bigUom) = stockedProduct(price = "100.00", cost = "10.00", qty = "500")
        val (small, smallUom) = stockedProduct(price = "1.00", cost = "0.50", qty = "500")
        sell(big, bigUom, "50", "5000.00")
        sell(small, smallUom, "5", "5.00")

        val abc = reports.abcAnalysis(today(), today())
        assertThat(abc.first().productId)
            .describedAs("the question is where to spend counting effort, so rank by turnover")
            .isEqualTo(big)
        assertThat(abc.first().abcClass).isEqualTo("A")
        assertThat(abc.last().cumulativePercent).isEqualByComparingTo("100.00")
    }

    // ── Registers ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("the void register reads the audit log, which cannot be rewritten")
    fun voidRegisterReadsTheAuditLog() {
        val (productId, uomId) = stockedProduct(price = "10.00")
        val saleId = sell(productId, uomId, "2", "20.00")
        completion.voidSale(saleId, "customer changed their mind")

        val register = reports.voidRegister(today(), today())
        assertThat(register).anyMatch {
            it.action == "SALE_VOIDED" && it.reason == "customer changed their mind"
        }
    }

    @Test
    @DisplayName("the discount register names who approved each one")
    fun discountRegisterShowsApprovals() {
        val (productId, uomId) = stockedProduct(price = "10.00", cost = "1.00")
        val till = tillCode()
        val sale = cart.startSale(null)
        cart.addLine(sale.id!!, productId, uomId, BigDecimal("4"), discountPercent = BigDecimal("5"))
        completion.complete(
            CompleteSaleCommand(sale.id!!, listOf(TenderLine("CASH", BigDecimal("38.00"))), tillCode = till),
            UUID.randomUUID(),
        )

        val register = reports.discountRegister(today(), today())
        assertThat(register).anyMatch { it.discountAmount.compareTo(BigDecimal("2.00")) == 0 }
    }

    @Test
    @DisplayName("takings are broken out by tender method, from the payments themselves")
    fun takingsSplitByTender() {
        val (productId, uomId) = stockedProduct(qty = "50", price = "10.00")
        val till = tillCode()

        val sale = cart.startSale(null)
        cart.addLine(sale.id!!, productId, uomId, BigDecimal("5"))
        completion.complete(
            CompleteSaleCommand(
                sale.id!!,
                listOf(
                    TenderLine("CASH", BigDecimal("20.00"), BigDecimal("20.00")),
                    TenderLine("MOBILE_MONEY", BigDecimal("30.00"), momoNetwork = "MTN", reference = "MM-1"),
                ),
                tillCode = till,
            ),
            UUID.randomUUID(),
        )

        val rows = reports.takings(today(), today()).filter { it.tillCode == till }
        assertThat(rows.map { it.method }).containsExactlyInAnyOrder("CASH", "MOBILE_MONEY")
        assertThat(rows.single { it.method == "CASH" }.total).isEqualByComparingTo("20.00")
        assertThat(rows.single { it.method == "MOBILE_MONEY" }.total).isEqualByComparingTo("30.00")
    }

    // ── Compliance ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("the restricted sales register lists the buyers recorded at the counter")
    fun restrictedRegisterListsBuyers() {
        val (productId, uomId) = stockedProduct(batchTracked = true, qty = "20", price = "10.00")
        markRestricted(productId)

        val till = tillCode()
        val sale = cart.startSale(null)
        val line = cart.addLine(sale.id!!, productId, uomId, BigDecimal("1"))
        completion.complete(
            CompleteSaleCommand(
                sale.id!!, listOf(TenderLine("CASH", BigDecimal("10.00"))), tillCode = till,
                buyerRecords = listOf(BuyerRecord(line.id!!, "Kofi Boateng", "0244000000", "GHANA_CARD", "GHA-9")),
            ),
            UUID.randomUUID(),
        )

        val register = reports.restrictedSalesRegister(today(), today())
        assertThat(register).anyMatch { it.buyerName == "Kofi Boateng" && it.buyerIdNumber == "GHA-9" }
    }

    // ── Permissions ────────────────────────────────────────────────────────

    @Test
    @DisplayName("a report carrying margin needs COST_VIEW, not just REPORT_VIEW")
    fun marginReportsNeedCostView() {
        // Sales staff hold neither; a manager holds both. Someone with
        // REPORT_VIEW alone can see trade but not what it earned.
        signInAs(setOf("REPORT_VIEW"))

        assertThatThrownBy { reports.dailyTakings(today(), today()) }
            .isInstanceOf(AccessDeniedException::class.java)
        assertThatThrownBy { reports.stockValuation(true) }
            .isInstanceOf(AccessDeniedException::class.java)

        // The heat map carries no cost, so it stays available.
        assertThat(reports.hourlyHeatMap(today(), today())).hasSize(24)
    }

    @Test
    @DisplayName("the control registers need AUDIT_VIEW")
    fun registersNeedAuditView() {
        signInAs(setOf("REPORT_VIEW", "COST_VIEW"))
        assertThatThrownBy { reports.adjustmentRegister(today(), today()) }
            .isInstanceOf(AccessDeniedException::class.java)
    }

    // ── Export ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("CSV quotes every field and leads with a BOM so Excel reads it")
    fun csvIsExcelSafe() {
        val table = ReportTable(
            "Test", listOf("Product", "Price"),
            listOf(listOf("""Pipe 2", 1.5m""", BigDecimal("12.50"))),
        )

        val bytes = exporter.toCsv(table)
        val text = String(bytes, Charsets.UTF_8)

        assertThat(bytes.take(3))
            .describedAs("without a BOM Excel on Windows reads UTF-8 as the system codepage")
            .containsExactly(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        assertThat(text).contains("\"Product\",\"Price\"")
        assertThat(text)
            .describedAs("a product name with a comma and an inch mark must survive the round trip")
            .contains("\"Pipe 2\"\", 1.5m\"")
    }

    @Test
    @DisplayName("XLSX writes numbers as numbers so a spreadsheet can sum them")
    fun xlsxKeepsNumbersNumeric() {
        val table = ReportTable(
            "Takings", listOf("Product", "Takings", "Sales"),
            listOf(listOf("Cement", BigDecimal("1234.56"), 7)),
        )

        val bytes = exporter.toXlsx(table)

        XSSFWorkbook(ByteArrayInputStream(bytes)).use { workbook ->
            val sheet = workbook.getSheetAt(0)
            assertThat(sheet.sheetName).isEqualTo("Takings")
            assertThat(sheet.getRow(0).getCell(0).stringCellValue).isEqualTo("Product")

            val row = sheet.getRow(1)
            assertThat(row.getCell(0).stringCellValue).isEqualTo("Cement")
            assertThat(row.getCell(1).numericCellValue)
                .describedAs("a column of strings that look like figures cannot be summed")
                .isEqualTo(1234.56)
            assertThat(row.getCell(2).numericCellValue).isEqualTo(7.0)
        }
    }

    @Test
    @DisplayName("a sheet name too long for Excel is trimmed rather than breaking the file")
    fun sheetNamesAreTrimmed() {
        val table = ReportTable(
            "Batch traceability: BATCH-WITH-A-VERY-LONG-CODE-INDEED", listOf("A"), listOf(listOf("x")),
        )
        XSSFWorkbook(ByteArrayInputStream(exporter.toXlsx(table))).use {
            assertThat(it.getSheetAt(0).sheetName.length).isLessThanOrEqualTo(31)
        }
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
