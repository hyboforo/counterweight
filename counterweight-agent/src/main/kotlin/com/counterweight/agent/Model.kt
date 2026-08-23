package com.counterweight.agent

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/**
 * The print document, as the server sends it.
 *
 * **Mirrored by hand, not shared.** The agent and the server are separately
 * deployed — a shop updates the server on a Tuesday and the tills whenever
 * somebody walks round with a USB stick — so a compile-time dependency would
 * only be a lie about how these two versions actually meet. What binds them is
 * the `type` discriminator below, which is why the server pins those names
 * explicitly and says so in `PrintElement.kt`.
 *
 * Unknown element types are a real possibility here: a newer server printing to
 * an older agent. Jackson is configured to fail on them rather than skip them,
 * because a receipt silently missing its total is worse than one that did not
 * print — the second gets fixed, the first gets handed to a customer.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = PrintElement.Line::class, name = "line"),
    JsonSubTypes.Type(value = PrintElement.Columns::class, name = "columns"),
    JsonSubTypes.Type(value = PrintElement.Barcode::class, name = "barcode"),
    JsonSubTypes.Type(value = PrintElement.QrCode::class, name = "qr"),
    JsonSubTypes.Type(value = PrintElement.Feed::class, name = "feed"),
    JsonSubTypes.Type(value = PrintElement.Rule::class, name = "rule"),
    JsonSubTypes.Type(value = PrintElement.Cut::class, name = "cut"),
)
sealed interface PrintElement {

    data class Line(
        val text: String = "",
        val align: Align = Align.LEFT,
        val bold: Boolean = false,
        val doubleHeight: Boolean = false,
    ) : PrintElement

    /** A left/right pair. The agent pads it, because only the agent knows the width. */
    data class Columns(val left: String = "", val right: String = "") : PrintElement

    data class Barcode(val value: String, val symbology: Symbology = Symbology.CODE128) : PrintElement

    data class QrCode(val value: String) : PrintElement

    data class Feed(val lines: Int = 1) : PrintElement

    class Rule : PrintElement

    class Cut : PrintElement
}

enum class Align { LEFT, CENTER, RIGHT }

enum class Symbology { CODE128, EAN13, CODE39 }

/**
 * `widthChars` is the server's belief about the paper — 32 for 58 mm, 48 for
 * 80 mm. The agent lays out columns and rules to it rather than to its own
 * configuration, so a mismatch shows up as one wrong-looking receipt with one
 * obvious place to look, instead of two components each sure they are right.
 */
data class PrintJob(
    val id: String,
    val template: String = "unknown",
    val widthChars: Int = 48,
    val elements: List<PrintElement> = emptyList(),
    val copies: Int = 1,
)

enum class JobState { QUEUED, PRINTING, DONE, FAILED }

data class JobStatus(
    val jobId: String,
    val state: JobState,
    val template: String,
    val receivedAt: String,
    val attempts: Int,
    val error: String? = null,
)
