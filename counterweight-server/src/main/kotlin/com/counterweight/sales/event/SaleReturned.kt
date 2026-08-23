package com.counterweight.sales.event

import java.math.BigDecimal

/**
 * Published when goods come back, inside the transaction that records the
 * return.
 *
 * Same contract as [SaleCompleted]: listeners run synchronously in the caller's
 * transaction, so a credit note that cannot be issued fails the return rather
 * than leaving the customer refunded with no document to show for it.
 *
 * [taxPortion] is apportioned from what the original sale actually charged, not
 * recomputed at today's rate. A credit note raised after a rate change has to
 * reverse the tax that was collected, not the tax that would be collected now.
 */
data class SaleReturned(
    val saleReturnId: Long,
    val branchId: Long,
    val saleId: Long,
    val customerId: Long?,
    val refundMethod: String,
    val total: BigDecimal,
    val taxPortion: BigDecimal,
    val reference: String,
)
