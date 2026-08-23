package com.counterweight.printing.template

import com.counterweight.catalog.domain.Product
import com.counterweight.catalog.domain.ProductUom
import com.counterweight.printing.model.Align
import com.counterweight.printing.model.DocumentBuilder
import com.counterweight.printing.model.PrintDocument
import com.counterweight.printing.model.Symbology
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/**
 * Shelf and bin labels.
 *
 * Most stock in this shop has no manufacturer barcode, which is why counter
 * search is fuzzy in the first place. A generated internal barcode on the shelf
 * edge is what turns a scan into an option for the items worth labelling —
 * fast-moving hardware, mostly — without pretending the whole catalogue can be
 * barcoded.
 *
 * The barcode encodes the product's own unit barcode where one exists, and
 * falls back to the SKU. Encoding the database id would make labels useless
 * after any data migration, and a label outlives the row it was printed from.
 */
@Component
class LabelTemplate {

    fun render(
        product: Product,
        unit: ProductUom,
        uomCode: String,
        price: BigDecimal?,
        widthChars: Int,
    ): PrintDocument {
        val doc = DocumentBuilder(widthChars)

        doc.wrapped(product.name, Align.CENTER)
        product.localName?.takeIf { it != product.name }?.let {
            // What customers actually call it, which is what somebody standing
            // at the shelf is matching against.
            doc.centre("($it)")
        }
        doc.line()

        price?.let {
            doc.line("GHS ${DocumentBuilder.money(it)} / $uomCode", Align.CENTER, bold = true, doubleHeight = true)
        }
        doc.line()

        doc.barcode(unit.barcode?.takeIf { it.isNotBlank() } ?: product.sku, Symbology.CODE128)
        doc.centre(unit.barcode?.takeIf { it.isNotBlank() } ?: product.sku)
        doc.cut(feedLines = 1)

        return doc.build()
    }
}

/**
 * The slip a storekeeper signs when goods arrive.
 *
 * Printed at goods receipt so there is a physical record next to the delivery
 * note, signed by whoever actually counted the cartons. §10.2 lists it, and it
 * is the paper trail behind every lot cost the ledger later depends on.
 */
@Component
class GoodsReceiptTemplate {

    /**
     * What one line of the delivery was recorded as.
     *
     * Built from `goods_receipt_line`, never from a request body. The slip is
     * the evidence behind a lot cost, and evidence assembled from whatever the
     * caller happened to send is not evidence.
     */
    data class ReceivedLine(
        val productName: String,
        val sku: String,
        val qty: BigDecimal,
        val uomCode: String,
        val unitCost: BigDecimal,
        /** Set only when the goods arrived in something other than the base unit. */
        val qtyBase: BigDecimal?,
        val baseUomCode: String?,
        val costPerBase: BigDecimal?,
        val batchCode: String?,
        val expiresOn: LocalDate?,
    )

    fun render(
        number: String,
        supplierReference: String?,
        receivedOn: LocalDate,
        lines: List<ReceivedLine>,
        totalValue: BigDecimal,
        widthChars: Int,
        showCosts: Boolean,
    ): PrintDocument {
        val doc = DocumentBuilder(widthChars)

        doc.title("GOODS RECEIVED")
        doc.centre(number, bold = true)
        doc.rule()
        supplierReference?.let { doc.columns("Delivery note", it) }
        doc.columns("Received", DocumentBuilder.format(receivedOn))
        doc.rule()

        lines.forEach { line ->
            doc.wrapped(line.productName)
            val qty = plain(line.qty)
            if (showCosts) {
                doc.columns("  $qty ${line.uomCode}", DocumentBuilder.money(line.unitCost))
            } else {
                // A storekeeper signs for quantities, not for what the shop
                // paid. Cost on this slip is how supplier prices end up known
                // to everyone who handles a carton.
                doc.line("  $qty ${line.uomCode}")
            }
            /*
             * Spell the conversion out when the goods did not arrive in the
             * base unit. This slip is filed against the delivery note, and
             * "5 CARTON = 60 PCS" is what lets somebody check months later
             * that the lot cost was struck on the right basis.
             */
            if (line.qtyBase != null && line.baseUomCode != null && line.baseUomCode != line.uomCode) {
                val conversion = "  = ${plain(line.qtyBase)} ${line.baseUomCode}"
                if (showCosts && line.costPerBase != null) {
                    doc.columns(conversion, "${plain(line.costPerBase)} each")
                } else {
                    doc.line(conversion)
                }
            }
            line.batchCode?.let { doc.line("  Batch: $it") }
            line.expiresOn?.let { doc.line("  Expires: ${DocumentBuilder.format(it)}") }
        }

        doc.rule()
        doc.columns("Lines", lines.size.toString())
        if (showCosts) doc.amount("Value at cost", totalValue.setScale(2, RoundingMode.HALF_UP))
        doc.feed(2)
        doc.line("Received by ............................")
        doc.feed()
        doc.line("Checked by .............................")
        doc.cut()

        return doc.build()
    }

    private fun plain(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()
}
