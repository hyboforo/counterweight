package com.counterweight.printing.template

import com.counterweight.platform.service.BranchService
import com.counterweight.printing.model.Align
import com.counterweight.printing.model.DocumentBuilder
import com.counterweight.printing.model.PrintDocument
import com.counterweight.printing.model.Symbology
import com.counterweight.sales.service.SaleDetail
import com.counterweight.sales.service.SaleLineDetail
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * The sales receipt.
 *
 * What goes on it is not a styling question. §10.2 requires the batch, expiry
 * and hazard band for agro-chemical lines, and that is a regulatory and safety
 * obligation rather than a nicety: the receipt is the customer's copy of the
 * batch traceability the `sale_line_allocation` table keeps, and the thing a
 * farmer holds when a manufacturer issues a recall.
 */
@Component
class ReceiptTemplate(private val branches: BranchService) {

    fun render(detail: SaleDetail, widthChars: Int, isReprint: Boolean = false): PrintDocument {
        val branch = branches.get(detail.sale.branchId)
        val doc = DocumentBuilder(widthChars)

        // ── Header ──
        doc.title(branch.name)
        branch.address?.let { doc.wrapped(it, Align.CENTER) }
        branch.phone?.let { doc.centre("Tel: $it") }
        doc.rule()

        if (isReprint) {
            // Marked so a customer cannot present the same receipt twice as two
            // separate purchases, and so staff can tell at a glance.
            doc.centre("*** REPRINT ***", bold = true)
        }

        doc.columns("Receipt", detail.sale.number ?: "—")
        detail.sale.completedAt?.let { doc.columns("Date", DocumentBuilder.format(it)) }
        detail.customerName?.let { doc.columns("Customer", it) }
        doc.rule()

        // ── Lines ──
        detail.lines.forEach { renderLine(doc, it) }
        doc.rule()

        // ── Totals ──
        val sale = detail.sale
        doc.columns("Subtotal", sale.subtotal.toPlainString())
        if (sale.discountTotal > BigDecimal.ZERO) {
            doc.columns("Discount", "-${sale.discountTotal.toPlainString()}")
        }
        // A zero tax line is printed only when the shop is actually charging
        // tax. Printing "Tax 0.00" on every receipt from a shop that is not
        // VAT-registered invites the question it cannot answer.
        if (sale.taxTotal > BigDecimal.ZERO) {
            doc.columns("Tax", sale.taxTotal.toPlainString())
        }
        if (sale.roundingAdjustment.compareTo(BigDecimal.ZERO) != 0) {
            doc.columns("Rounding", sale.roundingAdjustment.toPlainString())
        }
        doc.rule()
        doc.line("TOTAL  GHS ${sale.grandTotal.toPlainString()}", Align.RIGHT, bold = true, doubleHeight = true)
        doc.feed()

        // ── Tenders ──
        detail.payments.forEach { payment ->
            doc.columns(methodLabel(payment.method), payment.amount.toPlainString())
            payment.reference?.let { doc.line("  Ref: $it") }
            payment.momoNetwork?.let { doc.line("  Network: $it") }
            payment.changeGiven?.takeIf { it > BigDecimal.ZERO }?.let {
                doc.columns("  Change", it.toPlainString())
            }
        }

        // ── Footer ──
        doc.feed()
        sale.number?.let {
            // The receipt number as a barcode so a return can be found by
            // scanning rather than by keying a number off thermal paper that
            // has been in somebody's pocket for a fortnight.
            doc.barcode(it, Symbology.CODE128)
        }
        doc.centre("Thank you")
        doc.centre("Goods once sold are returnable within 7 days")
        doc.centre("with this receipt")

        doc.cut()

        return doc.build()
    }

    private fun renderLine(doc: DocumentBuilder, detail: SaleLineDetail) {
        val line = detail.line
        doc.wrapped(detail.productName)

        val qty = line.qty.stripTrailingZeros().toPlainString()
        val unitPrice = DocumentBuilder.money(line.unitPrice)
        doc.columns("  $qty ${detail.uomCode} x $unitPrice", line.lineTotal.toPlainString())

        if (line.discountAmount > BigDecimal.ZERO) {
            doc.columns("  Discount", "-${line.discountAmount.toPlainString()}")
        }

        /*
         * Batch, expiry and hazard band for regulated goods (§10.2). The expiry
         * is spelled out with days remaining rather than as a bare date: a
         * farmer reading "expires 12/03/2027" has to do the arithmetic, and the
         * whole reason for FEFO picking is that the shop already knows the
         * answer.
         */
        detail.batches.forEach { batch ->
            doc.line("  Batch: ${batch.lotCode}")
            batch.expiresOn?.let { expiry ->
                val days = ChronoUnit.DAYS.between(LocalDate.now(), expiry)
                val note = when {
                    days < 0 -> "EXPIRED"
                    days <= 90 -> "$days days left"
                    else -> ""
                }
                doc.line("  Expires: ${DocumentBuilder.format(expiry)}${if (note.isEmpty()) "" else "  ($note)"}")
            }
            batch.hazardBand?.let { doc.line("  WHO hazard band: $it", ) }
        }
        if (detail.batches.isNotEmpty()) {
            doc.line("  Read the label before use.")
        }
    }

    private fun methodLabel(method: String) = when (method) {
        "CASH" -> "Cash"
        "MOBILE_MONEY" -> "Mobile money"
        "BANK_TRANSFER" -> "Bank transfer"
        "CHEQUE" -> "Cheque"
        "ON_ACCOUNT" -> "On account"
        "CARD" -> "Card"
        else -> method
    }
}
