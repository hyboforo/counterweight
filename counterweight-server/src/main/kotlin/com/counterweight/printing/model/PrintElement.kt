package com.counterweight.printing.model

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.util.UUID

enum class Align { LEFT, CENTER, RIGHT }

enum class Symbology { CODE128, EAN13, CODE39 }

/**
 * One thing that should appear on a print-out.
 *
 * **The server never emits ESC/POS bytes** (§10). It says what should appear
 * and the agent on the till turns that into bytes for whatever printer is
 * plugged into it. Swapping an 80 mm Epson for a 58 mm Xprinter is agent
 * configuration and nothing else — which only holds if nothing here ever
 * describes *how* to print, only *what*.
 *
 * Sealed, so adding an element type makes the compiler point at every renderer
 * that has to handle it, the same reasoning as the movement types in the ledger.
 *
 * The `type` discriminator is part of the agent's wire contract. Renaming a
 * subclass renames it on the wire and breaks every agent in the shop until they
 * are updated, so the names are pinned explicitly rather than derived.
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
        val text: String,
        val align: Align = Align.LEFT,
        val bold: Boolean = false,
        val doubleHeight: Boolean = false,
    ) : PrintElement

    /**
     * A left/right pair, dot-filled to the paper width by the agent.
     *
     * Structured rather than pre-padded here, because the padding depends on
     * the font the printer is actually using. A server that padded to 48
     * characters would produce a receipt that looks right on an 80 mm roll and
     * wraps into nonsense on a 58 mm one.
     */
    data class Columns(val left: String, val right: String) : PrintElement

    data class Barcode(val value: String, val symbology: Symbology = Symbology.CODE128) : PrintElement

    data class QrCode(val value: String) : PrintElement

    data class Feed(val lines: Int = 1) : PrintElement

    /** A horizontal separator, drawn to the paper width. */
    data object Rule : PrintElement

    data object Cut : PrintElement
}

/**
 * What the agent receives.
 *
 * §10 calls this the print document. [widthChars] is 32 for 58 mm paper and 48
 * for 80 mm; the server sends the width it believes the till has so the agent
 * can lay out columns and rules, and a mismatch is a configuration problem with
 * one obvious place to look.
 */
data class PrintJob(
    val id: UUID,
    val template: String,
    val widthChars: Int,
    val elements: List<PrintElement>,
    val copies: Int = 1,
)

/** The part that is stored in `print_job.document`; the rest lives in columns. */
data class PrintDocument(
    val widthChars: Int,
    val elements: List<PrintElement>,
)
