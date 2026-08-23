package com.counterweight.inventory.service

import com.counterweight.catalog.repo.ProductRepository
import com.counterweight.catalog.service.UomConverter
import com.counterweight.common.ApiException
import com.counterweight.identity.security.Auth
import com.counterweight.identity.service.AuditService
import com.counterweight.inventory.domain.GoodsReceipt
import com.counterweight.inventory.domain.GoodsReceiptLine
import com.counterweight.inventory.domain.StockLot
import com.counterweight.inventory.domain.StockMovement
import com.counterweight.inventory.event.StockChanged
import com.counterweight.inventory.repo.*
import com.counterweight.platform.service.DocumentNumberService
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate

/** One lot movement the sale path has already decided on. */
data class StockIssue(val lotId: Long, val qtyBase: BigDecimal, val unitCost: BigDecimal)

data class ReceiptLine(
    val productId: Long,
    val productUomId: Long,
    val qty: BigDecimal,
    /** Cost per **purchase unit**, converted to base cost on the way in. */
    val unitCost: BigDecimal,
    val batchCode: String? = null,
    val expiresOn: LocalDate? = null,
)

@Service
class InventoryService(
    private val lots: StockLotRepository,
    private val movements: StockMovementRepository,
    private val balances: StockBalanceRepository,
    private val products: ProductRepository,
    private val receipts: GoodsReceiptRepository,
    private val receiptLines: GoodsReceiptLineRepository,
    private val numbers: DocumentNumberService,
    private val uomConverter: UomConverter,
    private val audit: AuditService,
    private val events: ApplicationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // ── Receiving ──────────────────────────────────────────────────────────

    /**
     * Receives goods, creating a lot per line and posting a RECEIPT movement.
     *
     * The lot is what carries cost and expiry, so it is created here at the
     * moment the storekeeper reads them off the carton. The balance row appears
     * automatically — a trigger creates it with the lot — which is why the
     * movement trigger can be a pure UPDATE (§5).
     */
    @Transactional
    @PreAuthorize("hasAuthority('STOCK_RECEIVE')")
    fun receive(lines: List<ReceiptLine>, reference: String?): GoodsReceipt {
        if (lines.isEmpty()) {
            throw ApiException.Validation("A goods receipt needs at least one line.", mapOf("lines" to "is required"))
        }
        val actor = Auth.current()

        /*
         * The document comes first, so every movement below can point at it.
         * Until V12 these movements carried source_type 'GRN' and a null
         * source_id — the ledger asserting that a receipt justified the stock
         * while being unable to say which one.
         */
        val receipt = receipts.saveAndFlush(
            GoodsReceipt(
                branchId = actor.branchId,
                number = numbers.next(actor.branchId, "GOODS_RECEIPT"),
                receivedBy = actor.id,
            ).also { it.supplierReference = reference?.trim()?.takeIf(String::isNotEmpty) }
        )

        lines.forEachIndexed { index, line ->
            val product = products.findById(line.productId).orElseThrow {
                ApiException.NotFound("Product", line.productId)
            }
            if (product.branchId != actor.branchId) {
                throw ApiException.NotFound("Product", line.productId)
            }

            uomConverter.assertPositive(line.qty, "lines[$index].qty")
            uomConverter.assertPrecision(line.productUomId, line.qty)
            if (line.unitCost < BigDecimal.ZERO) {
                throw ApiException.Validation(
                    "Cost cannot be negative.",
                    mapOf("lines[$index].unitCost" to "must be zero or more"),
                )
            }

            // A batch-tracked product must arrive with a batch and an expiry, or
            // FEFO has nothing to sort on and the expiry sweep cannot see it.
            if (product.isBatchTracked) {
                if (line.batchCode.isNullOrBlank()) {
                    throw ApiException.Validation(
                        "${product.name} is batch tracked — enter the batch number from the pack.",
                        mapOf("lines[$index].batchCode" to "is required for batch-tracked products"),
                    )
                }
                if (line.expiresOn == null) {
                    throw ApiException.Validation(
                        "${product.name} is batch tracked — enter the expiry date from the pack.",
                        mapOf("lines[$index].expiresOn" to "is required for batch-tracked products"),
                    )
                }
                if (!line.expiresOn.isAfter(LocalDate.now())) {
                    // Receiving already-expired stock is almost always a typo,
                    // and if it is not, it must not become sellable.
                    throw ApiException.Validation(
                        "${product.name}: that batch expires on ${line.expiresOn}, which is not in the future.",
                        mapOf("lines[$index].expiresOn" to "must be a future date"),
                    )
                }
            }

            val qtyBase = uomConverter.toBase(line.productId, line.productUomId, line.qty)
            // Cost is quoted per purchase unit but stored per base unit, or a
            // carton price would be recorded as the price of a single piece.
            val costPerBase = line.unitCost.multiply(line.qty)
                .divide(qtyBase, COST_SCALE, java.math.RoundingMode.HALF_UP)

            val lotCode = line.batchCode?.trim()?.takeIf(String::isNotEmpty)
                ?: autoLotCode(line.productId)

            // Re-receiving the same batch adds to it rather than failing on the
            // unique constraint — a supplier splitting a batch over two
            // deliveries is normal.
            val existing = lots.findByBranchIdAndProductIdAndLotCode(actor.branchId, line.productId, lotCode)
            val lot = existing ?: lots.saveAndFlush(
                StockLot(
                    branchId = actor.branchId,
                    productId = line.productId,
                    lotCode = lotCode,
                    unitCost = costPerBase,
                ).also { it.expiresOn = line.expiresOn }
            )

            movements.save(
                StockMovement(
                    branchId = actor.branchId,
                    lotId = lot.id!!,
                    movementType = "RECEIPT",
                    qtyBase = qtyBase,
                    unitCost = lot.unitCost,
                    sourceType = "GRN",
                    createdBy = actor.id,
                ).also {
                    it.sourceId = receipt.id
                    // The supplier's own number, not ours: this is what somebody
                    // reading the ledger matches against the delivery note.
                    it.reasonCode = reference
                }
            )

            receiptLines.save(
                GoodsReceiptLine(
                    goodsReceiptId = receipt.id!!,
                    lineNo = index + 1,
                    productId = line.productId,
                    productUomId = line.productUomId,
                    qtyReceived = line.qty,
                    unitCost = line.unitCost,
                    qtyBase = qtyBase,
                    costPerBase = costPerBase,
                    lotId = lot.id!!,
                ).also {
                    /*
                     * What the storekeeper read off the pack, which is null for
                     * anything not batch tracked. Deliberately not `lotCode` —
                     * that falls back to a generated AUTO-… code, and printing
                     * it as a batch number tells whoever signs the slip that a
                     * padlock has a batch.
                     */
                    it.batchCode = line.batchCode?.trim()?.takeIf(String::isNotEmpty)
                    it.expiresOn = line.expiresOn
                }
            )
        }

        audit.recordCurrent(
            "GOODS_RECEIVED", "goods_receipt", receipt.id,
            reason = reference,
            after = """{"number":"${receipt.number}","lines":${lines.size}}""",
        )
        announce(actor.branchId, lines.map { it.productId }, "GRN")
        log.info("received {} line(s) on {}", lines.size, receipt.number)
        return receipt
    }

    /** Deliveries booked in at this branch, newest first. */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('STOCK_RECEIVE')")
    fun recentReceipts(): List<GoodsReceipt> =
        receipts.findByBranchIdOrderByReceivedOnDescIdDesc(Auth.current().branchId)

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('STOCK_RECEIVE')")
    fun receipt(id: Long): GoodsReceipt {
        val receipt = receipts.findById(id).orElseThrow { ApiException.NotFound("Goods receipt", id) }
        if (receipt.branchId != Auth.current().branchId) throw ApiException.NotFound("Goods receipt", id)
        return receipt
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('STOCK_RECEIVE')")
    fun receiptLines(id: Long): List<ReceivedLineRow> {
        receipt(id)
        return receipts.linesOf(id)
    }

    // ── Adjustments ────────────────────────────────────────────────────────

    /**
     * Posts a manual correction against a lot.
     *
     * Requires a reason code — the database insists on one for adjustments, and
     * an unexplained stock change is the thing a stock-take dispute turns on.
     * A negative adjustment that would take the lot below zero is refused by
     * the balance constraint, exactly as an oversell is.
     */
    @Transactional
    @PreAuthorize("hasAuthority('STOCK_ADJUST')")
    fun adjust(lotId: Long, qtyBaseDelta: BigDecimal, reasonCode: String, note: String?): BigDecimal {
        val actor = Auth.current()
        if (qtyBaseDelta.compareTo(BigDecimal.ZERO) == 0) {
            throw ApiException.Validation(
                "An adjustment of zero changes nothing.",
                mapOf("qtyBaseDelta" to "must not be zero"),
            )
        }
        if (reasonCode.isBlank()) {
            throw ApiException.Validation(
                "Say why the stock is being adjusted.",
                mapOf("reasonCode" to "is required"),
            )
        }
        val lot = lots.findById(lotId).orElseThrow { ApiException.NotFound("Stock lot", lotId) }
        if (lot.branchId != actor.branchId) throw ApiException.NotFound("Stock lot", lotId)

        movements.save(
            StockMovement(
                branchId = lot.branchId,
                lotId = lotId,
                movementType = if (qtyBaseDelta > BigDecimal.ZERO) "ADJUST_IN" else "ADJUST_OUT",
                qtyBase = qtyBaseDelta,
                unitCost = lot.unitCost,
                sourceType = "ADJUSTMENT",
                createdBy = actor.id,
            ).also { it.reasonCode = reasonCode }
        )

        audit.recordCurrent(
            "STOCK_ADJUSTED", "stock_lot", lotId,
            after = """{"delta":"${qtyBaseDelta.toPlainString()}","reason":"$reasonCode"}""",
            reason = note,
        )
        announce(lot.branchId, listOf(lot.productId), "ADJUSTMENT")
        return balances.totalOnHand(lot.productId)
    }

    /** Writes off everything on hand in a lot — breakage, spillage, expiry. */
    @Transactional
    @PreAuthorize("hasAuthority('STOCK_ADJUST')")
    fun writeOff(lotId: Long, movementType: String, reasonCode: String): BigDecimal {
        require(movementType in setOf("DAMAGE_WRITE_OFF", "EXPIRY_WRITE_OFF")) {
            "unsupported write-off type $movementType"
        }
        val actor = Auth.current()
        val lot = lots.findById(lotId).orElseThrow { ApiException.NotFound("Stock lot", lotId) }
        val onHand = lots.onHandForProduct(lot.branchId, lot.productId, "FIFO")
            .firstOrNull { it.lotId == lotId }?.qtyBase ?: BigDecimal.ZERO

        if (onHand <= BigDecimal.ZERO) {
            throw ApiException.RuleViolation("NOTHING_TO_WRITE_OFF", "That lot has no stock on hand.")
        }

        movements.save(
            StockMovement(
                branchId = lot.branchId,
                lotId = lotId,
                movementType = movementType,
                qtyBase = onHand.negate(),
                unitCost = lot.unitCost,
                sourceType = "WRITE_OFF",
                createdBy = actor.id,
            ).also { it.reasonCode = reasonCode }
        )
        lot.status = if (movementType == "EXPIRY_WRITE_OFF") "EXPIRED" else "WRITTEN_OFF"
        lots.save(lot)

        audit.recordCurrent(
            "STOCK_WRITTEN_OFF", "stock_lot", lotId,
            after = """{"qty":"${onHand.toPlainString()}","type":"$movementType"}""",
            reason = reasonCode,
        )
        announce(lot.branchId, listOf(lot.productId), "WRITE_OFF")
        return onHand
    }

    // ── The sale path ──────────────────────────────────────────────────────

    /**
     * Takes stock out for a completed sale.
     *
     * Deliberately does no availability checking of its own. The balance CHECK
     * is what decides who gets the last bag of cement when two tills ring it up
     * at the same instant: the second transaction violates the constraint and
     * rolls back, so negative stock is impossible by construction (§5) and no
     * application lock is needed. A read-then-write check here would look
     * reassuring and would lose that race.
     *
     * The caller has already picked the lots; this posts the movements the pick
     * implies and nothing else.
     */
    @Transactional
    @PreAuthorize("hasAnyAuthority('SALE_CREATE','SALE_RETURN','SALE_VOID')")
    fun issueForSale(saleId: Long, issues: List<StockIssue>) {
        val actor = Auth.current()
        val touched = lots.findAllById(issues.map { it.lotId }).map { it.productId }
        issues.forEach { issue ->
            movements.save(
                StockMovement(
                    branchId = actor.branchId,
                    lotId = issue.lotId,
                    movementType = "SALE_ISSUE",
                    qtyBase = issue.qtyBase.negate(),
                    unitCost = issue.unitCost,
                    sourceType = "SALE",
                    createdBy = actor.id,
                ).also { it.sourceId = saleId }
            )
        }
        announce(actor.branchId, touched, "SALE")
    }

    /**
     * Puts stock back where it came from.
     *
     * Returns go to the original lot rather than to a fresh one, so a returned
     * agro-chemical keeps its batch and expiry. A new lot would make the goods
     * sellable past their real expiry date and would break the recall trail at
     * exactly the point it matters.
     */
    @Transactional
    @PreAuthorize("hasAnyAuthority('SALE_RETURN','SALE_VOID')")
    fun returnToStock(sourceId: Long, issues: List<StockIssue>, reasonCode: String) {
        val actor = Auth.current()
        issues.forEach { issue ->
            val lot = lots.findById(issue.lotId).orElseThrow { ApiException.NotFound("Stock lot", issue.lotId) }
            /*
             * An expired or written-off lot must not become sellable again
             * because somebody brought goods back. The movement is still posted
             * — the stock physically exists and the ledger has to say so — but
             * the lot's status keeps it out of every picking query.
             */
            movements.save(
                StockMovement(
                    branchId = lot.branchId,
                    lotId = issue.lotId,
                    movementType = "SALE_RETURN",
                    qtyBase = issue.qtyBase,
                    unitCost = issue.unitCost,
                    sourceType = "RETURN",
                    createdBy = actor.id,
                ).also {
                    it.sourceId = sourceId
                    it.reasonCode = reasonCode
                }
            )
        }
        announce(
            actor.branchId,
            lots.findAllById(issues.map { it.lotId }).map { it.productId },
            "RETURN",
        )
    }

    // ── Reads ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    fun onHand(productId: Long): List<LotOnHand> {
        val product = products.findById(productId).orElseThrow { ApiException.NotFound("Product", productId) }
        return lots.onHandForProduct(Auth.current().branchId, productId, product.pickingRule)
    }

    @Transactional(readOnly = true)
    fun position(productId: Long?): List<ProductStock> =
        lots.stockPosition(Auth.current().branchId, productId)

    /**
     * Lots by id, for callers holding an allocation trail.
     *
     * A receipt has to print the batch and expiry of an agro-chemical line, and
     * the line records which lots it drew from rather than the batch codes
     * themselves. Exposed here so nothing outside this module has to join
     * `stock_lot` to find out.
     */
    @Transactional(readOnly = true)
    fun lotsByIds(lotIds: Collection<Long>): Map<Long, StockLot> {
        if (lotIds.isEmpty()) return emptyMap()
        return lots.findAllById(lotIds).associateBy { it.id!! }
    }

    @Transactional(readOnly = true)
    fun ledgerFor(productId: Long, limit: Int): List<StockMovement> =
        movements.recentForProduct(productId, limit.coerceIn(1, 500))

    /**
     * Quarantines everything past its expiry date.
     *
     * Expired agro-chemical stock must stop being sellable at midnight rather
     * than when somebody remembers (§2.2). Returns how many lots were written
     * off, which the alerting module reports on.
     */
    @Transactional
    fun sweepExpiredStock(systemUserId: Long, onDate: LocalDate = LocalDate.now()): Int {
        val expired = lots.expiredWithStock(onDate)
        expired.forEach { lot ->
            val onHandQty = lots.onHandForProduct(lot.branchId, lot.productId, "FEFO")
                .firstOrNull { it.lotId == lot.id }?.qtyBase ?: return@forEach

            movements.save(
                StockMovement(
                    branchId = lot.branchId,
                    lotId = lot.id!!,
                    movementType = "EXPIRY_WRITE_OFF",
                    qtyBase = onHandQty.negate(),
                    unitCost = lot.unitCost,
                    sourceType = "WRITE_OFF",
                    createdBy = systemUserId,
                ).also { it.reasonCode = "EXPIRED_${lot.expiresOn}" }
            )
            lot.status = "EXPIRED"
            lots.save(lot)
        }
        if (expired.isNotEmpty()) log.warn("expiry sweep wrote off {} lot(s)", expired.size)
        return expired.size
    }

    /**
     * Tells anyone listening that a product's position changed.
     *
     * Published rather than acted on here: whether a balance of zero is worth
     * telling somebody about is alerting's judgement, and inventory has no
     * business holding an opinion on it.
     */
    private fun announce(branchId: Long, productIds: List<Long>, sourceType: String) {
        val distinct = productIds.distinct()
        if (distinct.isNotEmpty()) events.publishEvent(StockChanged(branchId, distinct, sourceType))
    }

    private fun autoLotCode(productId: Long): String =
        "AUTO-$productId-${System.currentTimeMillis()}"

    private companion object { const val COST_SCALE = 4 }
}
