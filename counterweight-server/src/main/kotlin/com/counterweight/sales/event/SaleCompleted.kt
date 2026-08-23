package com.counterweight.sales.event

import java.math.BigDecimal

/**
 * Published when a sale is settled, inside the completing transaction.
 *
 * This is what keeps `sales` and `billing` pointing one way. Billing already
 * reaches into sales to turn a quotation into a basket; if sales called billing
 * back to issue documents, the two modules would be mutually dependent and the
 * boundary test the module map calls for would have nothing left to enforce.
 * Spring would wire it — the bean graph has no cycle — which is exactly why it
 * needs saying out loud rather than being left to fail loudly on its own.
 *
 * The second reason is that billing will not be the only listener. Printing
 * wants completed sales, and so does alerting; a direct call would grow
 * completion a new dependency for each of them.
 *
 * Listeners run **synchronously, in the caller's transaction**. That is
 * deliberate rather than an oversight: a document number allocated for a sale
 * that then fails must roll back with it (§8.3), and a sale whose invoice
 * cannot be issued should not complete. Anything that genuinely wants to happen
 * afterwards — printing, alerting — should listen for the commit rather than
 * making this asynchronous.
 *
 * Carries what a listener needs rather than the sale itself, so no listener is
 * tempted to reach back into the sales tables through a detached entity.
 */
data class SaleCompleted(
    val saleId: Long,
    val branchId: Long,
    val number: String,
    val customerId: Long?,
    val grandTotal: BigDecimal,
    val taxTotal: BigDecimal,
    /** How much of the total went on the customer's account, if any. */
    val onAccountAmount: BigDecimal,
    /**
     * The customer ledger entry the account charge created, so billing can
     * point the invoice document at it once that document exists.
     */
    val ledgerEntryId: Long?,
    val productIds: List<Long>,
)
