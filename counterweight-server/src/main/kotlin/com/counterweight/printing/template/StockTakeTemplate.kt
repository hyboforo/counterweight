package com.counterweight.printing.template

import com.counterweight.platform.service.BranchService
import com.counterweight.printing.model.Align
import com.counterweight.printing.model.DocumentBuilder
import com.counterweight.printing.model.PrintDocument
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * The two pieces of paper a stock take produces.
 *
 * They are separate documents rather than one with a section, because they are
 * for different people and only one of them may carry the expected figures. A
 * count sheet with the system's numbers already on it is not a count — the
 * counter reads the number, finds the number, and the exercise measures
 * nothing. The blank box is the control, and it has to survive onto paper or
 * the control only exists on screens nobody carries down the aisle.
 */
@Component
class StockTakeTemplate(private val branches: BranchService) {

    /** A line to be counted. Deliberately has nowhere to put an expected quantity. */
    data class CountLine(
        val name: String,
        val sku: String,
        val lotCode: String,
        val uomCode: String,
        val expiresOn: LocalDate?,
    )

    /** A counted line, for the supervisor's copy. */
    data class VarianceLine(
        val name: String,
        val sku: String,
        val lotCode: String,
        val uomCode: String,
        val expected: BigDecimal,
        val counted: BigDecimal,
        val variance: BigDecimal,
        val value: BigDecimal,
    )

    /**
     * The blind count sheet.
     *
     * Everything needed to find the goods and nothing that hints at what should
     * be there. Batch and expiry print because on an agro-chemical shelf they
     * are how you tell two otherwise identical bottles apart — getting the count
     * onto the wrong batch puts it against the wrong expiry date.
     */
    fun renderSheet(
        reference: String,
        scopeName: String,
        openedAt: Instant,
        lines: List<CountLine>,
        widthChars: Int,
    ): PrintDocument {
        val doc = DocumentBuilder(widthChars)

        doc.title("COUNT SHEET")
        doc.centre(branches.current().name)
        doc.centre(reference, bold = true)
        doc.rule()
        doc.columns("Section", scopeName)
        doc.columns("Opened", DocumentBuilder.format(openedAt))
        doc.columns("Lines", lines.size.toString())
        doc.feed()
        doc.line("Counted by ...........................")
        doc.rule()

        if (lines.isEmpty()) {
            doc.centre("Nothing in scope.")
        }

        lines.forEach { line ->
            doc.wrapped(line.name)
            val tag = buildString {
                append(line.sku)
                if (line.lotCode.isNotBlank()) append("  ").append(line.lotCode)
                line.expiresOn?.let { append("  exp ").append(DocumentBuilder.format(it)) }
            }
            doc.line("  $tag")
            // The box is the whole point of the document.
            doc.columns("  ${line.uomCode}", "[            ]")
            doc.feed()
        }

        doc.rule()
        doc.wrapped(
            "Write the quantity you actually counted. If a line has none on the shelf, " +
                "write 0 — a blank line reads as not yet counted and will stop the sheet being signed off."
        )
        doc.cut()
        return doc.build()
    }

    /**
     * The variance slip, for whoever signs the shortage away.
     *
     * Carries cost value, so it is only ever produced by the posting path,
     * which is gated on STOCK_ADJUST. Lines are in the order the report gives
     * them — biggest money first — because that is the one somebody will
     * actually chase.
     */
    fun renderVariances(
        reference: String,
        scopeName: String,
        postedAt: Instant,
        lines: List<VarianceLine>,
        shrinkageValue: BigDecimal,
        foundValue: BigDecimal,
        widthChars: Int,
    ): PrintDocument {
        val doc = DocumentBuilder(widthChars)

        doc.title("STOCK TAKE VARIANCE")
        doc.centre(branches.current().name)
        doc.centre(reference, bold = true)
        doc.rule()
        doc.columns("Section", scopeName)
        doc.columns("Posted", DocumentBuilder.format(postedAt))
        doc.rule()

        if (lines.isEmpty()) {
            doc.feed()
            doc.centre("NO VARIANCES", bold = true)
            doc.centre("the count agreed with the books")
            doc.feed()
        }

        lines.forEach { line ->
            doc.wrapped(line.name)
            doc.line("  ${line.sku}  ${line.lotCode}")
            doc.columns(
                "  expected ${plain(line.expected)}",
                "counted ${plain(line.counted)}",
            )
            doc.columns(
                "  ${signed(line.variance)} ${line.uomCode}",
                cash(line.value).toPlainString(),
            )
            doc.feed()
        }

        doc.rule()
        doc.amount("Missing, at cost", cash(shrinkageValue))
        doc.amount("Found, at cost", cash(foundValue))
        val net = cash(foundValue.subtract(shrinkageValue))
        doc.line("NET  ${signedCash(net)}", Align.RIGHT, bold = true, doubleHeight = true)
        doc.feed(2)

        // Unsigned, this is just paper.
        doc.line("Counted by .............................")
        doc.feed()
        doc.line("Posted by ..............................")
        doc.cut()
        return doc.build()
    }

    /**
     * Quantities lose their trailing zeros — a count of 2 bags should read "2",
     * not "2.0000". Money keeps them, below.
     */
    private fun plain(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()

    private fun signed(value: BigDecimal): String =
        (if (value > BigDecimal.ZERO) "+" else "") + plain(value)

    /** Money always to two places, so a column of figures lines up. */
    private fun cash(value: BigDecimal): BigDecimal = value.setScale(2, java.math.RoundingMode.HALF_UP)

    private fun signedCash(value: BigDecimal): String =
        (if (value > BigDecimal.ZERO) "+" else "") + value.toPlainString()
}
