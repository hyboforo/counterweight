package com.counterweight.inventory.event

/**
 * Published when stock moved, whatever moved it.
 *
 * Carries product ids rather than movements because the questions downstream
 * asks are all about a product's *position* — is it out of stock, is it below
 * its reorder point — and those are answered by re-reading the balance, not by
 * inspecting the movement that changed it. A listener handed movements would
 * have to reconstruct the position anyway, and would get it wrong for a sale
 * line that drew from three lots.
 *
 * Published inside the moving transaction, so a listener sees the balance as it
 * will be committed. Alerting deliberately raises in its own transaction on top
 * of that — see AlertService.raise.
 */
data class StockChanged(
    val branchId: Long,
    val productIds: List<Long>,
    /** What moved the stock: SALE, GRN, ADJUSTMENT, RETURN, WRITE_OFF. */
    val sourceType: String,
)
