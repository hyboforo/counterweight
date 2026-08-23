package com.counterweight.inventory.web

import com.counterweight.catalog.repo.ProductUomRepository
import com.counterweight.common.ApiException
import com.counterweight.inventory.repo.CountSheetRow
import com.counterweight.inventory.repo.VarianceRow
import com.counterweight.inventory.service.StockTakePosting
import com.counterweight.inventory.service.StockTakeService
import com.counterweight.inventory.service.StockTakeView
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal

data class OpenCountRequest(
    /** Counts this category and everything under it. Null counts the whole shop. */
    val categoryId: Long? = null,
)

data class CountLineRequest(
    /** Null puts the line back to uncounted. Zero means the shelf was empty. */
    val countedQty: BigDecimal? = null,
    val note: String? = null,
)

data class CountLotRequest(
    val lotId: Long,
    val countedQty: BigDecimal,
    val note: String? = null,
)

data class PostCountRequest(val note: String? = null)

data class CancelCountRequest(@field:NotBlank val reason: String)

/**
 * Stock takes.
 *
 * The split between what a counter can reach and what a supervisor can reach is
 * enforced in the service, not here — but it is visible in the shape of this
 * controller: the count sheet and the variance report are different endpoints
 * because they are for different people. One never carries the expected figure.
 */
@RestController
@RequestMapping("/api/stock-takes")
class StockTakeController(
    private val stockTakes: StockTakeService,
    private val productUoms: ProductUomRepository,
) {

    @GetMapping
    fun list(): List<StockTakeView> = stockTakes.list()

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun open(@RequestBody(required = false) body: OpenCountRequest?): StockTakeView =
        stockTakes.open(body?.categoryId)

    /** The count sheet. Carries no expected quantity — see the service. */
    @GetMapping("/{id}/sheet")
    fun sheet(@PathVariable id: Long): List<CountSheetRow> = stockTakes.sheet(id)

    /**
     * Lines a scanned barcode addresses.
     *
     * Returns every lot line for the product rather than picking one: a
     * batch-tracked product has a line per batch, and attaching a count to the
     * wrong batch attaches it to the wrong expiry date.
     */
    @GetMapping("/{id}/scan/{barcode}")
    fun scan(@PathVariable id: Long, @PathVariable barcode: String): List<CountSheetRow> {
        val unit = productUoms.findByBarcode(barcode.trim())
            ?: throw ApiException.NotFound("Barcode", barcode)
        val found = stockTakes.linesForProduct(id, unit.productId)
        if (found.isEmpty()) {
            throw ApiException.RuleViolation(
                "NOT_ON_THIS_SHEET",
                "That product is not part of this count.",
            )
        }
        return found
    }

    @PutMapping("/{id}/lines/{lineId}")
    fun count(
        @PathVariable id: Long,
        @PathVariable lineId: Long,
        @RequestBody body: CountLineRequest,
    ): Map<String, Any?> {
        val line = stockTakes.count(lineId, body.countedQty, body.note)
        // The variance is a generated column, so it is echoed from the database
        // rather than computed here — two answers to one question is how they
        // start disagreeing.
        return mapOf("lineId" to line.id, "countedQty" to line.countedQty, "note" to line.note)
    }

    /** Counts a lot directly, adding it to the sheet if the books did not expect it. */
    @PostMapping("/{id}/lines")
    fun countLot(@PathVariable id: Long, @RequestBody body: CountLotRequest): Map<String, Any?> {
        val line = stockTakes.countLot(id, body.lotId, body.countedQty, body.note)
        return mapOf("lineId" to line.id, "lotId" to line.lotId, "countedQty" to line.countedQty)
    }

    @PutMapping("/{id}/counted")
    fun markCounted(@PathVariable id: Long): StockTakeView = stockTakes.markCounted(id)

    /** The variance report. Behind STOCK_ADJUST — it carries expected figures and cost. */
    @GetMapping("/{id}/variances")
    fun variances(
        @PathVariable id: Long,
        @RequestParam(defaultValue = "true") onlyVariances: Boolean,
    ): List<VarianceRow> = stockTakes.variances(id, onlyVariances)

    @PostMapping("/{id}/post")
    fun post(
        @PathVariable id: Long,
        @RequestBody(required = false) body: PostCountRequest?,
    ): StockTakePosting = stockTakes.post(id, body?.note)

    @PostMapping("/{id}/cancel")
    fun cancel(@PathVariable id: Long, @RequestBody body: CancelCountRequest): StockTakeView =
        stockTakes.cancel(id, body.reason)
}
