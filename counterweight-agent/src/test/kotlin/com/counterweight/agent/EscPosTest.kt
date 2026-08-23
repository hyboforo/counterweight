package com.counterweight.agent

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The encoder, and the wire contract it decodes.
 *
 * No printer is involved and none is needed: ESC/POS is a byte stream, and
 * every failure this suite covers — a receipt that stays bold to the bottom, a
 * cut through the last three lines, an em dash printed as `?` — is visible in
 * the bytes. What a real printer adds is whether the paper feeds, which is what
 * `--test-print` is for.
 */
@DisplayName("ESC/POS")
class EscPosTest {

    private val json = ObjectMapper().registerKotlinModule()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private fun bytes(vararg elements: PrintElement) =
        EscPos.encode(PrintJob(id = "t", widthChars = 32, elements = elements.toList()))

    @Test
    @DisplayName("every job starts by resetting the printer")
    fun everyJobResets() {
        val out = bytes(PrintElement.Line("x"))
        assertThat(out.take(2)).containsExactly(0x1B.toByte(), '@'.code.toByte())
    }

    @Test
    @DisplayName("emphasis is turned off again on the same line that turned it on")
    fun emphasisIsNotLeftOn() {
        val out = bytes(PrintElement.Line("TOTAL", bold = true, doubleHeight = true))

        // ESC E 1 … ESC E 0, and GS ! 1 … GS ! 0. A printer left emphasised is
        // how a receipt goes bold from one heading to the paper cut.
        assertThat(out.toList()).containsSubsequence(
            0x1B.toByte(), 'E'.code.toByte(), 1,
            0x1D.toByte(), '!'.code.toByte(), 0x01,
            0x1D.toByte(), '!'.code.toByte(), 0x00,
            0x1B.toByte(), 'E'.code.toByte(), 0,
        )
    }

    @Test
    @DisplayName("a column pair is dot-filled to the paper width")
    fun columnsFillTheWidth() {
        assertThat(EscPos.columns("Total", "48.00", 32))
            .hasSize(32)
            .startsWith("Total.")
            .endsWith("48.00")
    }

    @Test
    @DisplayName("when a pair will not fit, the label gives way and the figure does not")
    fun theFigureSurvives() {
        val line = EscPos.columns("Sunphosate 480 SL 1L, twelve of them", "1,250.00", 32)

        assertThat(line).hasSize(32)
        assertThat(line).endsWith("1,250.00")
    }

    @Test
    @DisplayName("an em dash prints as a hyphen rather than a question mark")
    fun typographyIsTransliterated() {
        // ReceiptTemplate prints "—" for a sale with no number, and the count
        // sheet says "write 0 — a blank line reads as not yet counted".
        assertThat(String(EscPos.text("Receipt — none"), Charsets.US_ASCII)).isEqualTo("Receipt - none")
    }

    @Test
    @DisplayName("a cut feeds the paper past the head first")
    fun cutFeedsFirst() {
        val out = bytes(PrintElement.Cut()).toList()

        assertThat(out).containsSubsequence(
            0x1B.toByte(), 'd'.code.toByte(), 4,          // feed
            0x1D.toByte(), 'V'.code.toByte(), 66, 0,      // partial cut
        )
    }

    @Test
    @DisplayName("an unprintable barcode is printed as text, not as ESC/POS garbage")
    fun badBarcodeFallsBackToText() {
        val out = bytes(PrintElement.Barcode("not-an-ean", Symbology.EAN13)).toList()

        assertThat(out).doesNotContainSequence(0x1D.toByte(), 'k'.code.toByte(), 67)
        assertThat(String(out.toByteArray(), Charsets.US_ASCII)).contains("not-an-ean")
    }

    @Test
    @DisplayName("CODE128 selects code set B explicitly")
    fun code128SelectsItsCodeSet() {
        val out = bytes(PrintElement.Barcode("PAD-50")).toList()

        // GS k 73 <len> {B PAD-50 — the length counts the selector too.
        assertThat(out).containsSubsequence(
            0x1D.toByte(), 'k'.code.toByte(), 73, 8,
            '{'.code.toByte(), 'B'.code.toByte(), 'P'.code.toByte(),
        )
    }

    /**
     * The wire contract, from the other side.
     *
     * This is a copy of what `PrintElement.kt` on the server emits, kept here
     * on purpose: the two components ship separately, so the only honest test
     * of the contract is one that reads bytes the server could have sent
     * without importing a single class from it. If somebody renames a subclass
     * — the exact thing that server file warns about — this fails here, in the
     * component that would otherwise fail silently in a shop.
     */
    @Test
    @DisplayName("a document in the server's own JSON decodes and prints")
    fun theServersJsonDecodes() {
        val document = """
            {
              "id": "6f1d9b6e-2f26-4f0b-9a3a-9a8f6b0b3c11",
              "template": "receipt",
              "widthChars": 48,
              "copies": 1,
              "elements": [
                {"type":"line","text":"KWABENA HARDWARE","align":"CENTER","bold":true,"doubleHeight":true},
                {"type":"rule"},
                {"type":"columns","left":"Receipt","right":"RCT-000001"},
                {"type":"columns","left":"Total","right":"261.00"},
                {"type":"barcode","value":"RCT-000001","symbology":"CODE128"},
                {"type":"qr","value":"cw://receipt/RCT-000001"},
                {"type":"feed","lines":2},
                {"type":"cut"}
              ]
            }
        """.trimIndent()

        val job = json.readValue(document, PrintJob::class.java)

        assertThat(job.template).isEqualTo("receipt")
        assertThat(job.widthChars).isEqualTo(48)
        assertThat(job.elements).hasSize(8)
        assertThat(job.elements.map { it::class.simpleName })
            .containsExactly("Line", "Rule", "Columns", "Columns", "Barcode", "QrCode", "Feed", "Cut")

        val out = EscPos.encode(job).toList()
        assertThat(String(out.toByteArray(), Charsets.US_ASCII))
            .contains("KWABENA HARDWARE")
            .contains("Total")
            .contains("261.00")
        assertThat(out).containsSubsequence(0x1D.toByte(), 'V'.code.toByte(), 66, 0)
    }

    @Test
    @DisplayName("a QR payload carries its own length, low byte first")
    fun qrLengthIsEncoded() {
        val out = bytes(PrintElement.QrCode("cw://x")).toList()

        // 6 characters of payload + the three command bytes that precede it.
        assertThat(out).containsSubsequence(
            0x1D.toByte(), '('.code.toByte(), 'k'.code.toByte(),
            9, 0, 0x31, 0x50, 0x30,
            'c'.code.toByte(), 'w'.code.toByte(),
        )
    }
}
