package com.counterweight.reporting.web

import com.counterweight.common.ApiException
import com.counterweight.identity.security.Auth
import com.counterweight.reporting.service.ReportExporter
import com.counterweight.reporting.service.ReportService
import com.counterweight.reporting.service.ReportTable
import com.counterweight.reporting.service.SalesRollupService
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Pattern
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.time.LocalDate

/**
 * Reports, and the same reports as a file.
 *
 * Each report is defined once as a [ReportTable] and served either as JSON for
 * a screen or as a download. Defining the table in one place is what stops the
 * export drifting from what the screen shows — the failure mode being an owner
 * emailing an accountant figures that do not match the ones on the till.
 */
@RestController
@RequestMapping("/api/reports")
class ReportController(
    private val reports: ReportService,
    private val exporter: ReportExporter,
    private val rollups: SalesRollupService,
) {

    // ── Sales ──────────────────────────────────────────────────────────────

    @GetMapping("/sales/daily")
    fun dailyTakings(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate) =
        table("Daily takings", reports.dailyTakings(from, to),
            "Date" to { r -> r.businessDate },
            "Sales" to { r -> r.saleCount },
            "Gross" to { r -> r.gross },
            "Discount" to { r -> r.discount },
            "Tax" to { r -> r.tax },
            "Takings" to { r -> r.net },
            "Cost" to { r -> r.cost },
            "Margin" to { r -> r.margin },
            "Margin %" to { r -> r.marginPercent },
        )

    @GetMapping("/sales/by-cashier")
    fun byCashier(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate) =
        table("Takings by cashier", reports.takingsByCashier(from, to),
            "Cashier" to { r -> r.fullName },
            "Username" to { r -> r.username },
            "Sales" to { r -> r.saleCount },
            "Takings" to { r -> r.net },
            "Discount" to { r -> r.discount },
            "Margin" to { r -> r.margin },
        )

    @GetMapping("/sales/hourly")
    fun hourly(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate) =
        table("Hourly trade", reports.hourlyHeatMap(from, to),
            "Hour" to { r -> r.hour },
            "Sales" to { r -> r.saleCount },
            "Takings" to { r -> r.net },
        )

    @GetMapping("/sales/by-product")
    fun byProduct(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
        @RequestParam(defaultValue = "50") @Min(1) @Max(500) limit: Int,
    ) = table("Sales by product", reports.topProducts(from, to, limit),
        "SKU" to { r -> r.sku },
        "Product" to { r -> r.name },
        "Quantity" to { r -> r.qtyBase },
        "Takings" to { r -> r.net },
        "Cost" to { r -> r.cost },
        "Margin" to { r -> r.margin },
        "Margin %" to { r -> r.marginPercent },
    )

    @GetMapping("/sales/by-category")
    fun byCategory(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate) =
        table("Sales by category", reports.salesByCategory(from, to),
            "Category" to { r -> r.categoryName },
            "Takings" to { r -> r.net },
            "Cost" to { r -> r.cost },
            "Margin" to { r -> r.margin },
        )

    @GetMapping("/sales/by-customer")
    fun byCustomer(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
        @RequestParam(defaultValue = "50") @Min(1) @Max(500) limit: Int,
    ) = table("Sales by customer", reports.topCustomers(from, to, limit),
        "Code" to { r -> r.code },
        "Customer" to { r -> r.name },
        "Sales" to { r -> r.saleCount },
        "Spend" to { r -> r.net },
    )

    // ── Inventory ──────────────────────────────────────────────────────────

    @GetMapping("/inventory/valuation")
    fun valuation(@RequestParam(defaultValue = "true") onlyInStock: Boolean) =
        table("Stock valuation", reports.stockValuation(onlyInStock),
            "SKU" to { r -> r.sku },
            "Product" to { r -> r.name },
            "On hand" to { r -> r.qtyBase },
            "Value at cost" to { r -> r.stockValue },
            "Lots" to { r -> r.lotCount },
            "Nearest expiry" to { r -> r.nearestExpiry },
        )

    @GetMapping("/inventory/abc")
    fun abc(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate) =
        table("ABC analysis", reports.abcAnalysis(from, to),
            "Class" to { r -> r.abcClass },
            "SKU" to { r -> r.sku },
            "Product" to { r -> r.name },
            "Takings" to { r -> r.net },
            "Cumulative %" to { r -> r.cumulativePercent },
        )

    @GetMapping("/inventory/expiry-ageing")
    fun expiryAgeing() =
        table("Expiry ageing", reports.expiryAgeing(),
            "Bucket" to { r -> r.bucket },
            "Lots" to { r -> r.lotCount },
            "Quantity" to { r -> r.qtyBase },
            "Value at cost" to { r -> r.stockValue },
        )

    /** How stale the two inventory views are, so nobody has to guess. */
    @GetMapping("/inventory/refreshed-at")
    fun refreshedAt(): Map<String, Instant?> = mapOf("refreshedAt" to reports.refreshedAt())

    @PostMapping("/inventory/refresh")
    fun refresh(): Map<String, Instant?> {
        reports.refreshNow()
        return mapOf("refreshedAt" to reports.refreshedAt())
    }

    // ── Money ──────────────────────────────────────────────────────────────

    @GetMapping("/money/takings")
    fun takings(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate) =
        table("Takings by tender", reports.takings(from, to),
            "Day" to { r -> r.day },
            "Till" to { r -> r.tillCode },
            "Method" to { r -> r.method },
            "Sales" to { r -> r.saleCount },
            "Total" to { r -> r.total },
        )

    @GetMapping("/money/collections")
    fun collections(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate) =
        table("Collections", reports.collections(from, to),
            "Collection" to { r -> r.number },
            "Collected at" to { r -> r.collectedAt },
            "Covers from" to { r -> r.periodFrom },
            "Collected by" to { r -> r.collectedByName },
            "Method" to { r -> r.method },
            "Expected" to { r -> r.expected },
            "Collected" to { r -> r.collected },
            "Difference" to { r -> r.difference },
            "Note" to { r -> r.note },
        )

    @GetMapping("/money/discounts")
    fun discounts(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate) =
        table("Discount register", reports.discountRegister(from, to),
            "Receipt" to { r -> r.saleNumber },
            "When" to { r -> r.completedAt },
            "Cashier" to { r -> r.cashierName },
            "Product" to { r -> r.productName },
            "Discount" to { r -> r.discountAmount },
            "Line total" to { r -> r.lineTotal },
            "Approved by" to { r -> r.approvedByName },
        )

    // ── Control ────────────────────────────────────────────────────────────

    @GetMapping("/control/{register}")
    fun controlRegister(
        @PathVariable @Pattern(regexp = "^(voids|price-overrides|adjustments|overrides)$") register: String,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
    ): ReportTable {
        val (title, rows) = when (register) {
            "voids" -> "Void and return register" to reports.voidRegister(from, to)
            "price-overrides" -> "Price override log" to reports.priceOverrideLog(from, to)
            "adjustments" -> "Adjustment register" to reports.adjustmentRegister(from, to)
            else -> "Override register" to reports.overrideRegister(from, to)
        }
        return ReportTable.of(
            title, rows,
            "When" to { r -> r.occurredAt },
            "Who" to { r -> r.actorName },
            "Approved by" to { r -> r.approverName },
            "Action" to { r -> r.action },
            "Subject" to { r -> "${r.subjectType} ${r.subjectId ?: ""}".trim() },
            "Reason" to { r -> r.reason },
            "Detail" to { r -> r.detail },
        )
    }

    // ── Compliance ─────────────────────────────────────────────────────────

    @GetMapping("/compliance/restricted-sales")
    fun restrictedSales(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate) =
        table("Restricted sales register", reports.restrictedSalesRegister(from, to),
            "When" to { r -> r.recordedAt },
            "Receipt" to { r -> r.saleNumber },
            "Product" to { r -> r.productName },
            "Quantity" to { r -> r.qty },
            "Buyer" to { r -> r.buyerName },
            "Phone" to { r -> r.buyerPhone },
            "ID type" to { r -> r.buyerIdType },
            "ID number" to { r -> r.buyerIdNumber },
            "Intended use" to { r -> r.intendedUse },
            "Recorded by" to { r -> r.recordedByName },
        )

    /**
     * Batch traceability: who received a batch.
     *
     * By lot code, because that is what is printed on the drum the manufacturer
     * named in the recall notice. A walk-in sale shows with a blank customer —
     * the honest answer, since the shop does not know who bought it, and a
     * recall must be told that rather than handed a shorter list.
     */
    @GetMapping("/compliance/trace")
    fun trace(@RequestParam batch: String) =
        table("Batch traceability: $batch", reports.traceBatch(batch),
            "Receipt" to { r -> r.saleNumber },
            "When" to { r -> r.completedAt },
            "Product" to { r -> r.productName },
            "Batch" to { r -> r.lotCode },
            "Quantity" to { r -> r.qtyBase },
            "Customer" to { r -> r.customerName },
            "Phone" to { r -> r.customerPhone },
        )

    // ── Rebuilds ───────────────────────────────────────────────────────────

    /**
     * Recomputes the sales rollups from the sales themselves.
     *
     * Derived data drifts — a listener that threw, a restore from backup, a
     * sale voided after the fact. Being able to say "recompute from source" is
     * what makes an incremental rollup trustworthy at all.
     *
     * Behind `REPORT_REBUILD` (V14) rather than `REPORT_VIEW`, which reads and
     * this does not: it deletes the range first. AUDITOR holds `REPORT_VIEW`
     * and is meant to read only, so reusing it would have let the auditor
     * rewrite the figures being audited.
     */
    @PreAuthorize("hasAuthority('REPORT_REBUILD')")
    @PostMapping("/sales/rebuild")
    fun rebuild(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate): Map<String, Int> =
        mapOf("rebuilt" to rollups.rebuild(Auth.current().branchId, from, to))

    // ── Export ─────────────────────────────────────────────────────────────

    /**
     * The same reports as a file.
     *
     * Re-runs the report through the very method that serves the screen, rather
     * than exporting a payload the client sends back. A client-supplied table
     * would let anyone download any figures they could compose, permissions
     * checked or not.
     *
     * `REPORT_EXPORT` is checked here and the report's own gate still applies
     * underneath, because re-running goes through the annotated service method.
     * Taking a file off the premises is a different act from reading a figure
     * on a screen the shop controls, which is why the permission exists at all
     * — and until this annotation, it was granted and never read.
     */
    @PreAuthorize("hasAuthority('REPORT_EXPORT')")
    @GetMapping("/export/{format}/**")
    fun export(
        @PathVariable @Pattern(regexp = "^(csv|xlsx)$") format: String,
        @RequestParam report: String,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @RequestParam(required = false) batch: String?,
        @RequestParam(defaultValue = "50") limit: Int,
        @RequestParam(defaultValue = "true") onlyInStock: Boolean,
    ): ResponseEntity<ByteArray> {
        val start = from ?: LocalDate.now()
        val end = to ?: LocalDate.now()

        val data = when (report) {
            "sales/daily" -> dailyTakings(start, end)
            "sales/by-cashier" -> byCashier(start, end)
            "sales/hourly" -> hourly(start, end)
            "sales/by-product" -> byProduct(start, end, limit)
            "sales/by-category" -> byCategory(start, end)
            "sales/by-customer" -> byCustomer(start, end, limit)
            "inventory/valuation" -> valuation(onlyInStock)
            "inventory/abc" -> abc(start, end)
            "inventory/expiry-ageing" -> expiryAgeing()
            "money/takings" -> takings(start, end)
            "money/collections" -> collections(start, end)
            "money/discounts" -> discounts(start, end)
            "compliance/restricted-sales" -> restrictedSales(start, end)
            "compliance/trace" -> trace(
                batch ?: throw ApiException.Validation(
                    "Which batch?", mapOf("batch" to "is required for a traceability export"),
                )
            )
            else -> controlRegister(report.removePrefix("control/"), start, end)
        }

        val (bytes, contentType, extension) = when (format) {
            "csv" -> Triple(exporter.toCsv(data), "text/csv", "csv")
            else -> Triple(
                exporter.toXlsx(data),
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "xlsx",
            )
        }
        val filename = data.title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$filename-$start.$extension\"")
            .contentType(MediaType.parseMediaType(contentType))
            .body(bytes)
    }

    private fun <T> table(title: String, rows: List<T>, vararg columns: Pair<String, (T) -> Any?>) =
        ReportTable.of(title, rows, *columns)
}
