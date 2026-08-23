package com.counterweight.parties.domain

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/*
 * Parties entities — the customer and the account they run.
 *
 * Contractors and masons buy on credit and settle later, so receivables are a
 * first-class part of this system rather than a bolt-on (§2.1). The account is
 * a signed ledger for the same reason the stock ledger is: a balance that is
 * stored rather than derived is a balance that can drift from the entries
 * justifying it, and the argument that follows is with a customer holding a
 * paper receipt.
 */

@Entity
@Table(name = "customer")
class Customer(
    @Column(name = "branch_id", nullable = false)
    var branchId: Long,

    @Column(nullable = false)
    var code: String,

    @Column(nullable = false)
    var name: String,
) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    var phone: String? = null

    @Column(name = "alt_phone")
    var altPhone: String? = null

    var address: String? = null

    /**
     * How much this customer may owe at once.
     *
     * **Null means cash only** — not "unlimited". Getting that backwards would
     * hand an unlimited account to every walk-in ever recorded, so nothing in
     * this module treats a null limit as permissive.
     */
    @Column(name = "credit_limit")
    var creditLimit: BigDecimal? = null

    /** Days from invoice to due date. Zero is cash on delivery. */
    @Column(name = "payment_terms_days", nullable = false)
    var paymentTermsDays: Short = 0

    /** Their price list, if they are on one. Null prices at the branch default. */
    @Column(name = "price_list_id")
    var priceListId: Long? = null

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true

    var notes: String? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()

    override fun equals(other: Any?) = this === other || (other is Customer && id != null && id == other.id)
    override fun hashCode() = javaClass.hashCode()
    override fun toString() = "Customer($code)"
}

/**
 * One signed movement on a customer account.
 *
 * Positive increases what the customer owes, negative reduces it, and the
 * balance is the sum. Nothing edits an entry after the fact: a mistake is
 * corrected with an ADJUSTMENT that is itself part of the record, which is the
 * same discipline the stock ledger keeps and for the same reason — the history
 * is the evidence.
 */
@Entity
@Table(name = "customer_ledger_entry")
class CustomerLedgerEntry(
    @Column(name = "branch_id", nullable = false)
    var branchId: Long,

    @Column(name = "customer_id", nullable = false)
    var customerId: Long,

    @Column(name = "entry_type", nullable = false)
    var entryType: String,

    @Column(nullable = false)
    var amount: BigDecimal,

    @Column(name = "created_by", nullable = false)
    var createdBy: Long,
) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    /** The document this entry came from, where one exists. */
    @Column(name = "sales_document_id")
    var salesDocumentId: Long? = null

    /** Set on invoices, from the customer's terms. What ageing measures against. */
    @Column(name = "due_on")
    var dueOn: LocalDate? = null

    /** Mobile-money reference, cheque number, whatever the payer quoted. */
    var reference: String? = null

    @Column(name = "occurred_at", nullable = false)
    var occurredAt: Instant = Instant.now()

    override fun equals(other: Any?) = this === other || (other is CustomerLedgerEntry && id != null && id == other.id)
    override fun hashCode() = javaClass.hashCode()

    companion object {
        /** Entries that increase the debt. */
        val DEBIT_TYPES = setOf("INVOICE", "OPENING_BALANCE")

        /** Entries that reduce it, and so can be allocated against an invoice. */
        val CREDIT_TYPES = setOf("PAYMENT", "CREDIT_NOTE", "WRITE_OFF")

        val ALL_TYPES = DEBIT_TYPES + CREDIT_TYPES + "ADJUSTMENT"
    }
}

/**
 * Which payment settled which invoice.
 *
 * Kept explicitly rather than inferred from a running balance, because ageing
 * needs to know *which* invoice is still open, not merely how much is
 * outstanding overall. A customer who owes GHS 4,000 across four invoices and
 * pays GHS 1,000 has a different collection story depending on which one they
 * meant to clear, and they usually mean the oldest.
 *
 * [amount] is a positive magnitude on both sides — the direction is carried by
 * the entries it joins, not by this row.
 */
@Entity
@Table(name = "payment_allocation")
class PaymentAllocation(
    @Column(name = "payment_entry_id", nullable = false)
    var paymentEntryId: Long,

    @Column(name = "invoice_entry_id", nullable = false)
    var invoiceEntryId: Long,

    @Column(nullable = false)
    var amount: BigDecimal,
) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    override fun equals(other: Any?) = this === other || (other is PaymentAllocation && id != null && id == other.id)
    override fun hashCode() = javaClass.hashCode()
}
