package com.counterweight.printing.model

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A small builder for print documents.
 *
 * Exists so templates read like the paper they produce. A template written as a
 * list literal is hard to check against a printed receipt held next to the
 * screen, and receipts are reviewed exactly that way.
 *
 * Deliberately does no padding or dot-filling — that is the agent's job, and
 * doing it here would bake one paper width into every stored document.
 */
class DocumentBuilder(private val widthChars: Int) {

    private val elements = mutableListOf<PrintElement>()

    fun line(
        text: String = "",
        align: Align = Align.LEFT,
        bold: Boolean = false,
        doubleHeight: Boolean = false,
    ) = apply { elements += PrintElement.Line(text, align, bold, doubleHeight) }

    fun title(text: String) = apply {
        elements += PrintElement.Line(text, Align.CENTER, bold = true, doubleHeight = true)
    }

    fun centre(text: String, bold: Boolean = false) = apply {
        elements += PrintElement.Line(text, Align.CENTER, bold = bold)
    }

    fun columns(left: String, right: String) = apply { elements += PrintElement.Columns(left, right) }

    /** A money row. The amount is plain, never a currency symbol per line. */
    fun amount(label: String, value: BigDecimal, bold: Boolean = false) = apply {
        if (bold) {
            // No bold on Columns in the element model, so a bold total is a
            // rule above it and the label carried in caps — which is how these
            // read on thermal paper anyway.
            elements += PrintElement.Rule
        }
        elements += PrintElement.Columns(label, value.toPlainString())
    }

    fun rule() = apply { elements += PrintElement.Rule }

    fun feed(lines: Int = 1) = apply { elements += PrintElement.Feed(lines) }

    fun barcode(value: String, symbology: Symbology = Symbology.CODE128) = apply {
        elements += PrintElement.Barcode(value, symbology)
    }

    fun qr(value: String) = apply { elements += PrintElement.QrCode(value) }


    /**
     * Ends the document.
     *
     * Feeds before cutting because a cut flush against the last line takes the
     * bottom off it on most thermal heads — the blade sits a few millimetres
     * above the print line.
     */
    fun cut(feedLines: Int = 3) = apply {
        elements += PrintElement.Feed(feedLines)
        elements += PrintElement.Cut
    }

    /** Wraps [text] onto successive lines rather than letting the printer clip it. */
    fun wrapped(text: String, align: Align = Align.LEFT) = apply {
        text.chunkedWords(widthChars).forEach { elements += PrintElement.Line(it, align) }
    }

    fun build(): PrintDocument = PrintDocument(widthChars, elements.toList())

    companion object {
        private val DATE_TIME: DateTimeFormatter =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault())
        private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

        fun format(at: Instant): String = DATE_TIME.format(at)
        fun format(on: LocalDate): String = DATE.format(on)

        /**
         * Money, always to two decimals.
         *
         * `stripTrailingZeros()` is right for a quantity — three pieces should
         * read `3`, not `3.000` — and wrong for a cedi amount, which is what
         * put `1 BAG x 62` on the same line as `62.00`. A customer reading two
         * renderings of the same figure has been given a reason to doubt the
         * arithmetic, which is the one thing a receipt exists to settle.
         */
        fun money(amount: BigDecimal): String = amount.setScale(2, RoundingMode.HALF_UP).toPlainString()
    }
}

/**
 * Splits on word boundaries, falling back to a hard break for a single word
 * longer than the paper.
 *
 * A product name like "GALVANISED-ROOFING-NAIL-100MM" has no spaces to break
 * on, and dropping the tail of it is worse than breaking it mid-word.
 */
internal fun String.chunkedWords(width: Int): List<String> {
    if (width <= 0) return listOf(this)
    val out = mutableListOf<String>()
    var current = StringBuilder()

    trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.forEach { word ->
        when {
            word.length > width -> {
                if (current.isNotEmpty()) { out += current.toString(); current = StringBuilder() }
                word.chunked(width).forEach { out += it }
            }
            current.isEmpty() -> current.append(word)
            current.length + 1 + word.length <= width -> current.append(' ').append(word)
            else -> { out += current.toString(); current = StringBuilder(word) }
        }
    }
    if (current.isNotEmpty()) out += current.toString()
    return if (out.isEmpty()) listOf("") else out
}
