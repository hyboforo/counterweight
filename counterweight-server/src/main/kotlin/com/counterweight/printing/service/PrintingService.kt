package com.counterweight.printing.service

import com.counterweight.catalog.repo.ProductRepository
import com.counterweight.catalog.repo.ProductUomRepository
import com.counterweight.catalog.repo.UomRepository
import com.counterweight.common.ApiException
import com.counterweight.identity.security.Auth
import com.counterweight.inventory.repo.GoodsReceiptRepository
import com.counterweight.inventory.service.StockTakeService
import com.counterweight.pricing.service.PricingService
import com.counterweight.printing.domain.QueuedPrintJob
import com.counterweight.printing.template.GoodsReceiptTemplate
import com.counterweight.printing.template.LabelTemplate
import com.counterweight.printing.template.ReceiptTemplate
import com.counterweight.printing.template.StockTakeTemplate
import com.counterweight.sales.service.SaleDetailService
import org.slf4j.LoggerFactory
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Builds the shop's print-outs and puts them on the queue.
 *
 * The split worth noticing: templates turn data into elements and know nothing
 * about queues, [PrintQueueService] owns the queue and knows nothing about
 * receipts, and this joins them. That is what keeps "what a receipt says" a
 * question you can answer by reading one file.
 *
 * Nothing here listens for anything. Every print-out in the shop is queued
 * because a person asked for it, receipts included.
 */
@Service
class PrintingService(
    private val queue: PrintQueueService,
    private val receipts: ReceiptTemplate,
    private val labels: LabelTemplate,
    private val goodsReceipts: GoodsReceiptTemplate,
    private val stockTakeSheets: StockTakeTemplate,
    private val stockTakes: StockTakeService,
    private val goodsReceiptRecords: GoodsReceiptRepository,
    private val saleDetails: SaleDetailService,
    private val products: ProductRepository,
    private val productUoms: ProductUomRepository,
    private val uoms: UomRepository,
    private val pricing: PricingService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Prints a sale's receipt, because somebody asked for it.
     *
     * Nothing queues a receipt on its own. A completed sale is the record; the
     * paper is a courtesy the customer either wants or does not, and most do
     * not. Queueing one for every sale spooled a roll of paper nobody collected
     * and made a missing printer look like a fault rather than a shop that
     * prints on request.
     *
     * [isReprint] marks the paper when this is a second copy, which is a
     * different claim from the first one and worth being able to tell apart.
     */
    @Transactional
    @PreAuthorize("hasAuthority('SALE_CREATE')")
    fun printReceipt(saleId: Long, tillCode: String, isReprint: Boolean = false): QueuedPrintJob {
        val detail = saleDetails.of(saleId)
        if (detail.sale.number == null) {
            throw ApiException.RuleViolation(
                "SALE_NOT_COMPLETED",
                "That sale has not been completed, so there is no receipt to print.",
            )
        }
        return queue.enqueue(
            tillCode = tillCode,
            template = QueuedPrintJob.RECEIPT,
            document = receipts.render(detail, queue.widthChars, isReprint = isReprint),
        )
    }

    @Transactional
    @PreAuthorize("hasAuthority('PRODUCT_MANAGE')")
    fun printShelfLabel(productId: Long, productUomId: Long, tillCode: String, copies: Int): QueuedPrintJob {
        val product = products.findById(productId).orElseThrow { ApiException.NotFound("Product", productId) }
        if (product.branchId != Auth.current().branchId) throw ApiException.NotFound("Product", productId)

        val unit = productUoms.findById(productUomId).orElseThrow { ApiException.NotFound("Unit", productUomId) }
        if (unit.productId != productId) {
            throw ApiException.Validation(
                "That unit does not belong to this product.",
                mapOf("productUomId" to "does not belong to product $productId"),
            )
        }
        val uomCode = uoms.findById(unit.uomId).map { it.code }.orElse("")
        // A label with no price is still a useful bin label, so an unpriced
        // unit prints without one rather than refusing.
        val price = runCatching { pricing.resolve(productId, productUomId).unitPrice }.getOrNull()

        return queue.enqueue(
            tillCode = tillCode,
            template = QueuedPrintJob.SHELF_LABEL,
            document = labels.render(product, unit, uomCode, price, queue.widthChars),
            copies = copies,
        )
    }

    /**
     * The slip for a delivery, rendered from what was recorded.
     *
     * Takes the receipt's id and reads `goods_receipt_line`, rather than
     * accepting lines from the caller. This slip is the paper trail behind
     * every lot cost the ledger depends on, and a document assembled from a
     * request body is not evidence of anything — it says whatever the client
     * said. Reprinting it a year later now reproduces the delivery rather than
     * whatever a browser happens to still hold.
     */
    @Transactional
    @PreAuthorize("hasAuthority('STOCK_RECEIVE')")
    fun printGoodsReceipt(receiptId: Long, tillCode: String): QueuedPrintJob {
        val receipt = goodsReceiptRecords.findById(receiptId).orElseThrow {
            ApiException.NotFound("Goods receipt", receiptId)
        }
        if (receipt.branchId != Auth.current().branchId) {
            throw ApiException.NotFound("Goods receipt", receiptId)
        }

        val lines = goodsReceiptRecords.linesOf(receiptId).map { row ->
            GoodsReceiptTemplate.ReceivedLine(
                productName = row.productName,
                sku = row.sku,
                qty = row.qtyReceived,
                uomCode = row.uomCode,
                unitCost = row.unitCost,
                qtyBase = row.qtyBase,
                baseUomCode = row.baseUomCode,
                costPerBase = row.costPerBase,
                batchCode = row.batchCode,
                expiresOn = row.expiresOn?.toLocalDate(),
            )
        }

        return queue.enqueue(
            tillCode = tillCode,
            template = QueuedPrintJob.GOODS_RECEIPT,
            document = goodsReceipts.render(
                number = receipt.number,
                supplierReference = receipt.supplierReference,
                receivedOn = receipt.receivedOn,
                lines = lines,
                totalValue = goodsReceiptRecords.valueOf(receiptId),
                widthChars = queue.widthChars,
                // Cost prints only for someone allowed to see cost. The slip is
                // signed by whoever counted the cartons, and that is often not
                // the same person.
                showCosts = Auth.current().has("COST_VIEW"),
            ),
        )
    }

    // ── Stock takes ────────────────────────────────────────────────────────

    /**
     * The blind count sheet.
     *
     * Nobody counts a shelf holding a laptop, so this is the form the count
     * actually takes. It carries no expected quantities — see
     * [StockTakeTemplate] — which is the same control the screen enforces,
     * surviving onto the medium the work is really done on.
     */
    @Transactional
    @PreAuthorize("hasAuthority('STOCK_COUNT')")
    fun printCountSheet(takeId: Long, tillCode: String): QueuedPrintJob {
        val take = stockTakes.get(takeId)
        val lines = stockTakes.sheet(takeId).map {
            StockTakeTemplate.CountLine(
                name = it.name,
                sku = it.sku,
                lotCode = it.lotCode,
                uomCode = it.uomCode,
                expiresOn = it.expiresOn?.toLocalDate(),
            )
        }
        return queue.enqueue(
            tillCode = tillCode,
            template = QueuedPrintJob.COUNT_SHEET,
            document = stockTakeSheets.renderSheet(
                take.reference, take.scopeName, take.openedAt, lines, queue.widthChars,
            ),
        )
    }

    /**
     * The variance slip.
     *
     * Behind STOCK_ADJUST because it carries the expected figures and the cost
     * of every discrepancy — the same reason the on-screen report is. Two
     * copies: one for whoever signs it, one for the file.
     */
    @Transactional
    @PreAuthorize("hasAuthority('STOCK_ADJUST')")
    fun printCountVariances(takeId: Long, tillCode: String): QueuedPrintJob {
        val take = stockTakes.get(takeId)
        var missing = java.math.BigDecimal.ZERO
        var found = java.math.BigDecimal.ZERO

        val lines = stockTakes.variances(takeId, true).map { row ->
            val variance = row.variance ?: java.math.BigDecimal.ZERO
            val value = variance.abs().multiply(row.unitCost)
                .setScale(2, java.math.RoundingMode.HALF_UP)
            if (variance < java.math.BigDecimal.ZERO) missing += value else found += value
            StockTakeTemplate.VarianceLine(
                name = row.name,
                sku = row.sku,
                lotCode = row.lotCode,
                uomCode = row.uomCode,
                expected = row.expectedQty,
                counted = row.countedQty ?: java.math.BigDecimal.ZERO,
                variance = variance,
                value = value,
            )
        }

        return queue.enqueue(
            tillCode = tillCode,
            template = QueuedPrintJob.COUNT_VARIANCE,
            document = stockTakeSheets.renderVariances(
                take.reference, take.scopeName, take.postedAt ?: java.time.Instant.now(),
                lines, missing, found, queue.widthChars,
            ),
            copies = 2,
        )
    }

    @Transactional
    @PreAuthorize("hasAuthority('SALE_CREATE')")
    fun reprintJob(jobId: UUID, tillCode: String?): QueuedPrintJob = queue.reprint(jobId, tillCode)

}
