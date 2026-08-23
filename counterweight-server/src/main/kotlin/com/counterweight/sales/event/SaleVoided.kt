package com.counterweight.sales.event

/**
 * Published when a completed sale is reversed.
 *
 * Carries only the ids: a void does not change what the sale *was*, so any
 * listener that needs the figures reads them, and there is nothing to keep in
 * step between the event and the row.
 *
 * Same contract as the other sale events — synchronous, in the voiding
 * transaction — so a listener that cannot keep up rolls the void back rather
 * than leaving stock returned and the reports still counting the sale.
 */
data class SaleVoided(
    val saleId: Long,
    val branchId: Long,
    val number: String?,
)
