package com.counterweight.inventory

import com.counterweight.catalog.repo.CategoryRepository
import com.counterweight.catalog.service.ProductService
import com.counterweight.catalog.service.ProductUomSpec
import com.counterweight.common.ApiException
import com.counterweight.identity.repo.AppUserRepository
import com.counterweight.identity.security.CurrentUser
import com.counterweight.inventory.domain.StockTake
import com.counterweight.inventory.repo.GoodsReceiptRepository
import com.counterweight.inventory.repo.StockLotRepository
import com.counterweight.inventory.repo.StockMovementRepository
import com.counterweight.inventory.repo.StockTakeRepository
import com.counterweight.inventory.service.InventoryService
import com.counterweight.inventory.service.ReceiptLine
import com.counterweight.inventory.service.StockTakeService
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
 * Stock takes.
 *
 * Two things carry the weight here and both are controls rather than features:
 * the count is blind, and posting the variance needs an authority the counter
 * does not have. The rest is arithmetic — but the arithmetic has one genuinely
 * subtle case, which is what happens when the shop keeps trading through the
 * count, and that gets its own tests.
 */
@SpringBootTest
@Testcontainers
@DisplayName("Stock takes")
class StockTakeTest {

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

    @Autowired private lateinit var stockTakes: StockTakeService
    @Autowired private lateinit var inventory: InventoryService
    @Autowired private lateinit var productService: ProductService
    @Autowired private lateinit var categories: CategoryRepository
    @Autowired private lateinit var lots: StockLotRepository
    @Autowired private lateinit var movements: StockMovementRepository
    @Autowired private lateinit var receipts: GoodsReceiptRepository
    @Autowired private lateinit var takes: StockTakeRepository
    @Autowired private lateinit var users: AppUserRepository

    private val fullRights = setOf(
        "STOCK_RECEIVE", "STOCK_COUNT", "STOCK_ADJUST", "PRODUCT_MANAGE", "PRICE_MANAGE", "COST_VIEW",
    )

    private var userId: Long = 0

    @BeforeEach
    fun signIn() {
        userId = users.findAll().first().id!!
        authenticate(fullRights)
        /*
         * An open count blocks the next one by design, so a test that left one
         * behind would fail its successor rather than itself. Cleared through
         * the repository — going via cancel() would need a reason and would
         * itself be one of the behaviours under test.
         */
        takes.findByBranchIdOrderByOpenedAtDesc(BRANCH)
            .filter { it.isOpen }
            .forEach { takes.save(it.also { t -> t.status = StockTake.CANCELLED }) }
    }

    private fun authenticate(rights: Set<String>) {
        SecurityContextHolder.getContext().authentication = PreAuthenticatedAuthenticationToken(
            CurrentUser(userId, "counter", BRANCH, setOf("STOREKEEPER"), rights),
            null,
            rights.map { SimpleGrantedAuthority(it) },
        )
    }

    // ── Fixtures ───────────────────────────────────────────────────────────

    /** A hardware product with one lot on hand. Returns product id to lot id. */
    private fun stocked(qty: String = "100", cost: String = "4.00", agro: Boolean = false): Pair<Long, Long> {
        val n = seq.incrementAndGet()
        val product = productService.create(
            sku = "TAKE-$n", name = "Count Product $n",
            categoryId = categories.findByCode(if (agro) "AGRO" else "HARDWARE")!!.id!!,
            units = listOf(ProductUomSpec("PCS", BigDecimal.ONE, isBase = true)),
            // V16 withdrew the agro licence fields, so a product filed under
            // agro carries none by default; the validator refuses a key its
            // category no longer declares.
            attributes = null,
            isBatchTracked = agro,
            pickingRule = if (agro) "FEFO" else "FIFO",
        )
        val uomId = productService.unitsOf(product.id!!).single().id!!
        inventory.receive(
            listOf(
                ReceiptLine(
                    product.id!!, uomId, BigDecimal(qty), BigDecimal(cost),
                    batchCode = if (agro) "BATCH-$n" else null,
                    expiresOn = if (agro) LocalDate.now().plusDays(300) else null,
                )
            ),
            reference = "GRN-$n",
        )
        val lotId = lots.onHandForProduct(BRANCH, product.id!!, "FIFO").single().lotId
        return product.id!! to lotId
    }

    private fun onHand(productId: Long): BigDecimal =
        inventory.position(productId).single().qtyBase

    private fun lineFor(takeId: Long, lotId: Long) =
        stockTakes.sheet(takeId).single { it.lotId == lotId }

    // ── Receiving as a document ────────────────────────────────────────────

    /**
     * A RECEIPT movement must be able to name the document that justifies it.
     *
     * Until V12 these carried `source_type = 'GRN'` with a null `source_id` —
     * the ledger asserting a receipt exists while being unable to point at one.
     * Every other movement type sets it, and the printed slip depends on it.
     */
    @Test
    @DisplayName("receiving creates a numbered document its movements point back at")
    fun receiptIsADocument() {
        val (productId, lotId) = stocked(qty = "40")

        val receipt = receipts.findByBranchIdOrderByReceivedOnDescIdDesc(BRANCH).first()
        assertThat(receipt.number).startsWith("GRN-")

        val movement = movements.findByLotIdOrderByOccurredAtAsc(lotId)
            .single { it.movementType == "RECEIPT" }
        assertThat(movement.sourceType).isEqualTo("GRN")
        assertThat(movement.sourceId)
            .describedAs("the ledger has to be able to reach the paper behind the cost")
            .isEqualTo(receipt.id)

        val lines = receipts.linesOf(receipt.id!!)
        assertThat(lines).hasSize(1)
        assertThat(lines.single().qtyReceived).isEqualByComparingTo("40")
        assertThat(lines.single().qtyBase).isEqualByComparingTo("40")
        assertThat(productId).isPositive()
    }

    /**
     * The document records the conversion, not just the keyed figures.
     *
     * A carton of twelve booked at 220 is sixty pieces at 18.3333, and the slip
     * has to be able to say both — the keyed figures to check against the
     * delivery note, the converted ones to explain the lot cost. Storing them
     * means a later change to the carton factor cannot rewrite either.
     */
    @Test
    @DisplayName("a receipt line stores the conversion it was struck on")
    fun receiptLineKeepsTheConversion() {
        val n = seq.incrementAndGet()
        val product = productService.create(
            sku = "CTN-$n", name = "Carton Product $n",
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

        val line = receipts.linesOf(receipt.id!!).single()
        assertThat(line.qtyReceived).describedAs("as keyed").isEqualByComparingTo("5")
        assertThat(line.uomCode).isEqualTo("CARTON")
        assertThat(line.unitCost).isEqualByComparingTo("220.00")
        assertThat(line.qtyBase).describedAs("as the ledger holds it").isEqualByComparingTo("60")
        assertThat(line.baseUomCode).isEqualTo("PCS")
        assertThat(line.costPerBase)
            .describedAs("220 over twelve — the number every later margin rests on")
            .isEqualByComparingTo("18.3333")
        assertThat(receipt.supplierReference).isEqualTo("DN-$n")
    }

    /** What the slip totals, summed from the recorded lines rather than a request. */
    @Test
    @DisplayName("a receipt values itself from its own lines")
    fun receiptValuesItself() {
        val n = seq.incrementAndGet()
        val (productId, _) = stocked(qty = "10", cost = "7.50")
        val receipt = receipts.findByBranchIdOrderByReceivedOnDescIdDesc(BRANCH).first()

        assertThat(receipts.valueOf(receipt.id!!)).isEqualByComparingTo("75.00")
        assertThat(productId).isPositive()
        assertThat(n).isPositive()
    }

    // ── The variance is the movement ───────────────────────────────────────

    @Test
    @DisplayName("a short count posts the difference and the balance matches what was counted")
    fun shortCountPostsTheDifference() {
        val (productId, lotId) = stocked(qty = "100")
        val take = stockTakes.open(null)

        stockTakes.count(lineFor(take.id, lotId).lineId, BigDecimal("95"), "shelf recount")
        countEverythingElse(take.id, lotId)
        stockTakes.markCounted(take.id)

        val posting = stockTakes.post(take.id, "monthly count")

        assertThat(onHand(productId))
            .describedAs("the ledger now says what the shelf said")
            .isEqualByComparingTo("95")
        assertThat(posting.shrinkageValue)
            .describedAs("5 units at a cost of 4.00")
            .isEqualByComparingTo("20.00")
        assertThat(posting.netValue).isEqualByComparingTo("-20.00")
    }

    @Test
    @DisplayName("stock found over posts a gain, not a silent correction")
    fun overCountPostsAGain() {
        val (productId, lotId) = stocked(qty = "40")
        val take = stockTakes.open(null)

        stockTakes.count(lineFor(take.id, lotId).lineId, BigDecimal("43"), null)
        countEverythingElse(take.id, lotId)
        stockTakes.markCounted(take.id)
        val posting = stockTakes.post(take.id, null)

        assertThat(onHand(productId)).isEqualByComparingTo("43")
        assertThat(posting.unitsFound).isEqualByComparingTo("3")
        assertThat(posting.foundValue).isEqualByComparingTo("12.00")
    }

    @Test
    @DisplayName("a count of zero writes the lot off; leaving it blank does not")
    fun zeroIsARealCount() {
        val (emptyProduct, emptyLot) = stocked(qty = "12")
        val (untouchedProduct, untouchedLot) = stocked(qty = "30")
        val take = stockTakes.open(null)

        stockTakes.count(lineFor(take.id, emptyLot).lineId, BigDecimal.ZERO, "nothing on the shelf")

        assertThatThrownBy { stockTakes.markCounted(take.id) }
            .describedAs("blank lines must block sign-off — a count nobody made is not a count of zero")
            .isInstanceOf(ApiException.RuleViolation::class.java)
            .hasMessageContaining("not been counted")

        countEverythingElse(take.id, emptyLot)
        stockTakes.markCounted(take.id)
        stockTakes.post(take.id, null)

        assertThat(onHand(emptyProduct))
            .describedAs("counted as zero, so written off in full")
            .isEqualByComparingTo("0")
        assertThat(onHand(untouchedProduct))
            .describedAs("counted at what the books said, so untouched")
            .isEqualByComparingTo("30")
    }

    // ── Trading through the count ──────────────────────────────────────────

    @Test
    @DisplayName("stock that moves during the count is not written off twice")
    fun tradingDuringTheCountIsNotDoubleCounted() {
        val (productId, lotId) = stocked(qty = "100")
        val take = stockTakes.open(null)

        // The shop keeps selling. The ledger already records this.
        inventory.adjust(lotId, BigDecimal("-10"), "SOLD_DURING_COUNT", null)

        stockTakes.count(lineFor(take.id, lotId).lineId, BigDecimal("95"), null)
        countEverythingElse(take.id, lotId)
        stockTakes.markCounted(take.id)
        stockTakes.post(take.id, null)

        /*
         * §5.1: the variance is the movement. The count found 5 fewer than the
         * books said when counting began, so 5 is what posts — the 10 that sold
         * are in the ledger explaining themselves, and re-deriving against the
         * moved balance would post them a second time.
         */
        assertThat(onHand(productId))
            .describedAs("90 on the books minus the 5 discrepancy, not minus 15")
            .isEqualByComparingTo("85")
    }

    @Test
    @DisplayName("a shortage bigger than what is left is refused, not left to the constraint")
    fun shortageBeyondRemainingStockIsRefused() {
        val (productId, lotId) = stocked(qty = "100")
        val take = stockTakes.open(null)

        stockTakes.count(lineFor(take.id, lotId).lineId, BigDecimal("60"), null)
        // Almost all of it sells after being counted.
        inventory.adjust(lotId, BigDecimal("-98"), "SOLD_AFTER_COUNT", null)
        countEverythingElse(take.id, lotId)
        stockTakes.markCounted(take.id)

        assertThatThrownBy { stockTakes.post(take.id, null) }
            .describedAs("a -40 posting against 2 on hand would violate the balance check and take the whole post down")
            .isInstanceOf(ApiException.RuleViolation::class.java)
            .hasMessageContaining("sold after being counted")

        assertThat(onHand(productId))
            .describedAs("nothing was posted")
            .isEqualByComparingTo("2")
        assertThat(takes.findById(take.id).orElseThrow().status)
            .describedAs("and the count is still open for a recount")
            .isEqualTo(StockTake.COUNTED)
    }

    @Test
    @DisplayName("the sheet flags lines the shop traded through")
    fun sheetFlagsLinesThatMoved() {
        val (_, movedLot) = stocked(qty = "50")
        val (_, stillLot) = stocked(qty = "50")
        val take = stockTakes.open(null)

        inventory.adjust(movedLot, BigDecimal("-5"), "SOLD_DURING_COUNT", null)

        assertThat(lineFor(take.id, movedLot).movedSince)
            .describedAs("the counter needs to know this figure is approximate")
            .isEqualTo(1)
        assertThat(lineFor(take.id, stillLot).movedSince).isZero()
    }

    // ── The controls ───────────────────────────────────────────────────────

    @Test
    @DisplayName("counting does not carry the authority to post the variance")
    fun postingNeedsAdjustAuthority() {
        val (_, lotId) = stocked(qty = "20")
        val take = stockTakes.open(null)
        stockTakes.count(lineFor(take.id, lotId).lineId, BigDecimal("14"), null)
        countEverythingElse(take.id, lotId)
        stockTakes.markCounted(take.id)

        // A storekeeper: holds STOCK_COUNT, does not hold STOCK_ADJUST.
        authenticate(setOf("STOCK_COUNT", "STOCK_RECEIVE"))

        assertThatThrownBy { stockTakes.post(take.id, null) }
            .describedAs("the person who counts short must not be the person who signs the shortage away")
            .isInstanceOf(AccessDeniedException::class.java)

        assertThatThrownBy { stockTakes.variances(take.id, true) }
            .describedAs("and the variance report un-blinds the count, so it is gated the same way")
            .isInstanceOf(AccessDeniedException::class.java)

        assertThat(stockTakes.sheet(take.id))
            .describedAs("but the count sheet itself stays reachable")
            .isNotEmpty
    }

    @Test
    @DisplayName("the count sheet never carries what the system expected")
    fun theSheetIsBlind() {
        val (_, lotId) = stocked(qty = "77")
        val take = stockTakes.open(null)

        val row = lineFor(take.id, lotId)
        val exposed = row.javaClass.methods.map { it.name }

        assertThat(exposed)
            .describedAs("a counter who can see the expected figure will find the expected figure")
            .noneMatch { it.contains("xpected") }
        assertThat(stockTakes.variances(take.id, false).single { it.lotId == lotId }.expectedQty)
            .describedAs("the supervisor's view does carry it")
            .isEqualByComparingTo("77")
    }

    @Test
    @DisplayName("two counts covering the same stock cannot both be open")
    fun overlappingCountsAreRefused() {
        stocked(qty = "10")
        stockTakes.open(null)

        assertThatThrownBy { stockTakes.open(null) }
            .describedAs("both would snapshot the same lot and both would post its variance")
            .isInstanceOf(ApiException.RuleViolation::class.java)
            .hasMessageContaining("already open")

        val agro = categories.findByCode("AGRO")!!.id!!
        assertThatThrownBy { stockTakes.open(agro) }
            .describedAs("a section inside a shop-wide count overlaps it")
            .isInstanceOf(ApiException.RuleViolation::class.java)
    }

    @Test
    @DisplayName("a scoped count only snapshots its own section")
    fun scopeLimitsTheSheet() {
        val (hardwareProduct, hardwareLot) = stocked(qty = "10", agro = false)
        val (agroProduct, agroLot) = stocked(qty = "10", agro = true)

        val take = stockTakes.open(categories.findByCode("AGRO")!!.id!!)
        val sheet = stockTakes.sheet(take.id)

        assertThat(sheet.map { it.lotId }).contains(agroLot).doesNotContain(hardwareLot)
        assertThat(take.scopeName).isNotEqualTo("Whole shop")

        stockTakes.count(sheet.single { it.lotId == agroLot }.lineId, BigDecimal("8"), null)
        countEverythingElse(take.id, agroLot)
        stockTakes.markCounted(take.id)
        stockTakes.post(take.id, null)

        assertThat(onHand(agroProduct)).isEqualByComparingTo("8")
        assertThat(onHand(hardwareProduct))
            .describedAs("out of scope, so out of the posting")
            .isEqualByComparingTo("10")
    }

    @Test
    @DisplayName("goods received mid-count cannot be added to the sheet")
    fun stockReceivedDuringTheCountIsRefused() {
        stocked(qty = "10")
        val take = stockTakes.open(null)

        // Arrives after the snapshot: already in the ledger from its receipt.
        val (_, freshLot) = stocked(qty = "25")

        assertThatThrownBy { stockTakes.countLot(take.id, freshLot, BigDecimal("25"), null) }
            .describedAs("counting it would post the same 25 units a second time")
            .isInstanceOf(ApiException.RuleViolation::class.java)
            .hasMessageContaining("received after this count started")
    }

    @Test
    @DisplayName("a re-receipt into an existing batch mid-count is refused too")
    fun reReceiptDuringTheCountIsRefused() {
        stocked(qty = "10")
        val (emptyProduct, emptyLot) = stocked(qty = "5", agro = true)
        inventory.adjust(emptyLot, BigDecimal("-5"), "EMPTIED", null)

        val take = stockTakes.open(null)

        /*
         * The lot is old, so the created-at guard alone would let this through.
         * A supplier splitting a batch over two deliveries re-receives into the
         * same lot, and those units are already in the ledger.
         */
        val uomId = productService.unitsOf(emptyProduct).single().id!!
        val batch = lots.findById(emptyLot).orElseThrow().lotCode
        inventory.receive(
            listOf(
                ReceiptLine(
                    emptyProduct, uomId, BigDecimal("7"), BigDecimal("4.00"),
                    batchCode = batch, expiresOn = LocalDate.now().plusDays(300),
                )
            ),
            reference = "GRN-SPLIT",
        )

        assertThatThrownBy { stockTakes.countLot(take.id, emptyLot, BigDecimal("7"), null) }
            .describedAs("counting these 7 would post the delivery a second time")
            .isInstanceOf(ApiException.RuleViolation::class.java)
            .hasMessageContaining("received after this count started")
    }

    @Test
    @DisplayName("stock found for a lot the books think is empty can be counted back in")
    fun foundStockCanBeAdded() {
        stocked(qty = "10")
        val (emptyProduct, emptyLot) = stocked(qty = "6")
        inventory.adjust(emptyLot, BigDecimal("-6"), "EMPTIED", null)

        val take = stockTakes.open(null)
        assertThat(stockTakes.sheet(take.id).map { it.lotId })
            .describedAs("a lot with nothing on hand is not on the sheet")
            .doesNotContain(emptyLot)

        stockTakes.countLot(take.id, emptyLot, BigDecimal("4"), "found behind the counter")
        countEverythingElse(take.id, emptyLot)
        stockTakes.markCounted(take.id)
        stockTakes.post(take.id, null)

        assertThat(onHand(emptyProduct)).isEqualByComparingTo("4")
    }

    @Test
    @DisplayName("a posted count is finished with")
    fun postedCountsAreTerminal() {
        val (_, lotId) = stocked(qty = "5")
        val take = stockTakes.open(null)
        val lineId = lineFor(take.id, lotId).lineId
        stockTakes.count(lineId, BigDecimal("5"), null)
        countEverythingElse(take.id, lotId)
        stockTakes.markCounted(take.id)
        stockTakes.post(take.id, null)

        assertThatThrownBy { stockTakes.count(lineId, BigDecimal("4"), null) }
            .isInstanceOf(ApiException.RuleViolation::class.java)
            .hasMessageContaining("cannot be changed")
        assertThatThrownBy { stockTakes.post(take.id, null) }
            .isInstanceOf(ApiException.RuleViolation::class.java)
    }

    @Test
    @DisplayName("an abandoned count posts nothing and stays on the record")
    fun cancellingPostsNothing() {
        val (productId, lotId) = stocked(qty = "18")
        val take = stockTakes.open(null)
        stockTakes.count(lineFor(take.id, lotId).lineId, BigDecimal("2"), null)

        stockTakes.cancel(take.id, "wrong shelf")

        assertThat(onHand(productId)).isEqualByComparingTo("18")
        assertThat(takes.findById(take.id).orElseThrow().status).isEqualTo(StockTake.CANCELLED)
        assertThat(stockTakes.list().map { it.reference })
            .describedAs("the sheet stays as evidence the count happened")
            .contains(take.reference)
    }

    /**
     * Counts every line the test is not about at what the books expect, so the
     * take can be signed off without those lines contributing a variance.
     */
    private fun countEverythingElse(takeId: Long, exceptLot: Long) {
        stockTakes.variances(takeId, false)
            .filter { it.lotId != exceptLot && it.countedQty == null }
            .forEach { stockTakes.count(it.lineId, it.expectedQty, null) }
    }
}
