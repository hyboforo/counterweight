package com.counterweight.sales.service

import com.counterweight.catalog.service.ProductAttributes
import com.counterweight.catalog.repo.ProductRepository
import com.counterweight.catalog.repo.ProductUomRepository
import com.counterweight.catalog.repo.UomRepository
import com.counterweight.inventory.service.InventoryService
import com.counterweight.parties.service.CustomerService
import com.counterweight.sales.domain.Sale
import com.counterweight.sales.domain.SaleLine
import com.counterweight.sales.domain.SalePayment
import com.counterweight.sales.repo.SaleLineAllocationRepository
import com.counterweight.sales.repo.SaleLineRepository
import com.counterweight.sales.repo.SalePaymentRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/** A batch a line drew from, as it should appear on paper. */
data class BatchDetail(
    val lotCode: String,
    val expiresOn: LocalDate?,
    val hazardBand: String?,
)

data class SaleLineDetail(
    val line: SaleLine,
    val productName: String,
    val uomCode: String,
    /**
     * Populated only for batch-tracked goods. A hardware line drew from an
     * auto-generated lot that means nothing to the customer, and printing it
     * would bury the batch numbers that do matter under ones that do not.
     */
    val batches: List<BatchDetail>,
)

/**
 * Everything about one sale, in one read.
 *
 * Exists because a receipt needs product names, unit codes, tenders and — for
 * agro-chemicals — the batch, expiry and hazard band of the specific lots that
 * left the shop. Assembling that in `printing` would mean a presentation module
 * joining four other modules' tables; assembling it here keeps the reach inside
 * the module that owns the sale, and gives `billing` and any future reporting
 * the same read.
 */
data class SaleDetail(
    val sale: Sale,
    val lines: List<SaleLineDetail>,
    val payments: List<SalePayment>,
    val customerName: String?,
)

@Service
class SaleDetailService(
    private val cart: CartService,
    private val saleLines: SaleLineRepository,
    private val allocations: SaleLineAllocationRepository,
    private val payments: SalePaymentRepository,
    private val products: ProductRepository,
    private val productUoms: ProductUomRepository,
    private val uoms: UomRepository,
    private val productAttributes: ProductAttributes,
    private val inventory: InventoryService,
    private val customers: CustomerService,
) {

    @Transactional(readOnly = true)
    fun of(saleId: Long): SaleDetail {
        val sale = cart.get(saleId)
        val lines = saleLines.findBySaleIdOrderByLineNoAsc(saleId)

        // Resolved in bulk rather than per line: a contractor's basket runs to
        // thirty lines, and a receipt is printed while somebody waits for it.
        val productsById = products.findAllById(lines.map { it.productId }).associateBy { it.id!! }
        val unitsById = productUoms.findAllById(lines.map { it.productUomId }).associateBy { it.id!! }
        val uomsById = uoms.findAllById(unitsById.values.map { it.uomId }).associateBy { it.id!! }

        val allocationsByLine = lines.associate { it.id!! to allocations.findBySaleLineId(it.id!!) }
        val lotsById = inventory.lotsByIds(allocationsByLine.values.flatten().map { it.lotId }.toSet())

        val detailed = lines.map { line ->
            val product = productsById[line.productId]
            val uomCode = unitsById[line.productUomId]?.let { uomsById[it.uomId]?.code } ?: ""

            val hazardBand = productAttributes.text(product?.attributes, "hazard_band")

            val batches = if (product?.isBatchTracked == true) {
                allocationsByLine[line.id!!].orEmpty().mapNotNull { allocation ->
                    lotsById[allocation.lotId]?.let { lot ->
                        BatchDetail(lot.lotCode, lot.expiresOn, hazardBand)
                    }
                }
            } else emptyList()

            SaleLineDetail(
                line = line,
                productName = product?.name ?: "Item ${line.productId}",
                uomCode = uomCode,
                batches = batches,
            )
        }

        return SaleDetail(
            sale = sale,
            lines = detailed,
            payments = payments.findBySaleId(saleId),
            customerName = sale.customerId?.let { runCatching { customers.get(it).name }.getOrNull() },
        )
    }
}
