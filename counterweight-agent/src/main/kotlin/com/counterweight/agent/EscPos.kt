package com.counterweight.agent

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * The only place in Counterweight where ESC/POS bytes exist.
 *
 * §10 puts the whole reason here: the server says *what* should appear and this
 * says *how*, so a shop that replaces an 80 mm printer with a 58 mm one changes
 * a config line rather than a template. Everything printer-specific — the code
 * page, the cut command, how a barcode is framed — has to stay on this side of
 * that line or the line is not real.
 *
 * Written against the ESC/POS command set the Syncotek thermal printers
 * implement, which is the common Epson-compatible subset; nothing exotic is
 * used, because "works on the printer that is actually on the counter" is worth
 * more than a feature nobody prints.
 */
object EscPos {

    private const val ESC = 0x1B.toByte()
    private const val GS = 0x1D.toByte()

    /**
     * CP437 is the printer's power-on code page, so the agent stays on it
     * rather than switching: a printer that was power-cycled mid-shift comes
     * back on page 0 and would otherwise print mojibake until someone
     * restarted the agent.
     *
     * `jlink`/`jpackage` must include `jdk.charsets` or this is not there. The
     * fallback is ASCII rather than an exception — a receipt in plain ASCII is
     * readable, and a till that refuses to print because of a character set is
     * not.
     */
    private val CODE_PAGE: Charset =
        runCatching { Charset.forName("IBM437") }.getOrElse { Charsets.US_ASCII }

    /**
     * What the shop's own text throws at a 1980s code page.
     *
     * Not hypothetical: the receipt template prints an em dash for a missing
     * receipt number, and the count sheet instructs "write 0 — a blank line
     * reads as not yet counted". Both arrive here as characters CP437 has never
     * heard of, and an unmapped character prints as `?` in the middle of a
     * sentence somebody has to act on.
     */
    private val TRANSLITERATED = mapOf(
        '—' to "-", '–' to "-", '‒' to "-", '‐' to "-",
        '‘' to "'", '’' to "'", '“' to "\"", '”' to "\"",
        '…' to "...", '·' to "-", '•' to "*", '×' to "x",
        '₵' to "GHS", '§' to "S", ' ' to " ", '−' to "-",
    )

    fun encode(job: PrintJob): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(ESC, '@'.code.toByte()))   // reset: known state per job
        job.elements.forEach { element(out, it, job.widthChars) }
        return out.toByteArray()
    }

    private fun element(out: ByteArrayOutputStream, element: PrintElement, width: Int) {
        when (element) {
            is PrintElement.Line -> {
                align(out, element.align)
                if (element.bold) out.write(byteArrayOf(ESC, 'E'.code.toByte(), 1))
                if (element.doubleHeight) out.write(byteArrayOf(GS, '!'.code.toByte(), 0x01))
                out.write(text(element.text))
                out.write('\n'.code)
                // Back to plain immediately. A printer left emphasised is how a
                // receipt ends up entirely bold from one heading onwards.
                if (element.doubleHeight) out.write(byteArrayOf(GS, '!'.code.toByte(), 0x00))
                if (element.bold) out.write(byteArrayOf(ESC, 'E'.code.toByte(), 0))
                align(out, Align.LEFT)
            }

            is PrintElement.Columns -> {
                align(out, Align.LEFT)
                out.write(text(columns(element.left, element.right, width)))
                out.write('\n'.code)
            }

            is PrintElement.Rule -> {
                align(out, Align.LEFT)
                out.write(text("-".repeat(width)))
                out.write('\n'.code)
            }

            is PrintElement.Feed -> out.write(byteArrayOf(ESC, 'd'.code.toByte(), element.lines.coerceIn(0, 16).toByte()))

            is PrintElement.Barcode -> barcode(out, element)

            is PrintElement.QrCode -> qr(out, element.value)

            is PrintElement.Cut -> {
                // Feed first: the blade sits some millimetres past the head, and
                // cutting without it takes the last three lines with it.
                out.write(byteArrayOf(ESC, 'd'.code.toByte(), 4))
                out.write(byteArrayOf(GS, 'V'.code.toByte(), 66, 0))
            }
        }
    }

    /**
     * A left/right pair filled to the paper width.
     *
     * Dots rather than spaces: on a thermal roll that has been in a drawer for
     * a year, a run of spaces between "Total" and a figure is where the eye
     * loses the row. When the pair will not fit, the left side gives way — the
     * money is the part nobody may misread.
     */
    fun columns(left: String, right: String, width: Int): String {
        val r = right.trim()
        val room = (width - r.length - 1).coerceAtLeast(0)
        val l = if (left.length > room) left.take(room) else left
        val fill = (width - l.length - r.length).coerceAtLeast(1)
        return l + ".".repeat(fill) + r
    }

    private fun align(out: ByteArrayOutputStream, align: Align) {
        val n = when (align) {
            Align.LEFT -> 0
            Align.CENTER -> 1
            Align.RIGHT -> 2
        }
        out.write(byteArrayOf(ESC, 'a'.code.toByte(), n.toByte()))
    }

    /** Transliterate, then encode to the printer's code page, `?` for the rest. */
    fun text(value: String): ByteArray {
        val mapped = StringBuilder(value.length)
        value.forEach { ch -> mapped.append(TRANSLITERATED[ch] ?: ch.toString()) }

        val encoder = CODE_PAGE.newEncoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val buffer = encoder.encode(java.nio.CharBuffer.wrap(mapped))
        return ByteArray(buffer.remaining()).also { buffer.get(it) }
    }

    /**
     * Barcodes are printed by the printer, not drawn by us.
     *
     * `GS k` hands it the digits and lets the firmware lay out the bars at the
     * head's real dot pitch. A bitmap generated here would be scaled by the
     * printer and stop scanning, which is the only thing a barcode has to do.
     *
     * The value is checked before it is sent because ESC/POS has no answer for
     * bad input: an EAN-13 with a letter in it makes the printer emit the
     * remaining bytes as text, so the receipt grows a line of `GS k C` garbage
     * and nobody can tell why.
     */
    private fun barcode(out: ByteArrayOutputStream, element: PrintElement.Barcode) {
        val value = element.value.trim()
        val valid = when (element.symbology) {
            Symbology.EAN13 -> value.length in 12..13 && value.all(Char::isDigit)
            Symbology.CODE39 -> value.isNotEmpty() && value.all { it.isLetterOrDigit() || it in "-. $/+%" }
            Symbology.CODE128 -> value.isNotEmpty() && value.all { it.code in 32..126 }
        }
        if (!valid) {
            // Printed as text rather than dropped. Whoever is holding the paper
            // can still read the number and key it in.
            out.write(text(value))
            out.write('\n'.code)
            return
        }

        align(out, Align.CENTER)
        out.write(byteArrayOf(GS, 'h'.code.toByte(), 64))            // height, dots
        out.write(byteArrayOf(GS, 'w'.code.toByte(), 2))             // module width
        out.write(byteArrayOf(GS, 'H'.code.toByte(), 2))             // print the digits below

        val payload = when (element.symbology) {
            // `{B` selects Code Set B, which covers the printable ASCII a SKU
            // uses. Without it the printer picks a set per its own defaults and
            // a code that scans on one machine does not on the next.
            Symbology.CODE128 -> "{B$value"
            else -> value
        }
        val code = when (element.symbology) {
            Symbology.CODE128 -> 73
            Symbology.EAN13 -> 67
            Symbology.CODE39 -> 69
        }
        val bytes = text(payload)
        out.write(byteArrayOf(GS, 'k'.code.toByte(), code.toByte(), bytes.size.toByte()))
        out.write(bytes)
        out.write('\n'.code)
        align(out, Align.LEFT)
    }

    /**
     * QR, model 2, error correction M.
     *
     * M rather than L because these are printed on thermal paper that lives in
     * a shop: it survives a thumbprint and a fold, and the payload here is a
     * receipt reference rather than anything long enough for the size to matter.
     */
    private fun qr(out: ByteArrayOutputStream, value: String) {
        val data = text(value)
        align(out, Align.CENTER)

        fun store(vararg body: Byte) {
            out.write(byteArrayOf(GS, '('.code.toByte(), 'k'.code.toByte()))
            out.write(byteArrayOf(body.size.toByte(), 0))
            out.write(body)
        }

        store(0x31, 0x41, 0x32, 0x00)          // model 2
        store(0x31, 0x43, 0x05)                // module size 5
        store(0x31, 0x45, 0x31)                // error correction M

        // The data store is the one command whose length is the payload's, so
        // it is written out rather than going through `store`.
        val len = data.size + 3
        out.write(byteArrayOf(GS, '('.code.toByte(), 'k'.code.toByte()))
        out.write(byteArrayOf((len and 0xFF).toByte(), ((len shr 8) and 0xFF).toByte()))
        out.write(byteArrayOf(0x31, 0x50, 0x30))
        out.write(data)

        store(0x31, 0x51, 0x30)                // print what is stored
        out.write('\n'.code)
        align(out, Align.LEFT)
    }
}
