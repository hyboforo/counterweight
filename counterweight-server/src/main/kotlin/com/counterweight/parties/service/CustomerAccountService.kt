package com.counterweight.parties.service

import com.counterweight.common.ApiException
import com.counterweight.identity.security.Auth
import com.counterweight.identity.service.AuditService
import com.counterweight.parties.domain.Customer
import com.counterweight.parties.domain.CustomerLedgerEntry
import com.counterweight.parties.domain.PaymentAllocation
import com.counterweight.parties.repo.AgeingRow
import com.counterweight.parties.repo.CustomerLedgerEntryRepository
import com.counterweight.parties.repo.DebtorRow
import com.counterweight.parties.repo.CustomerRepository
import com.counterweight.parties.repo.OpenInvoice
import com.counterweight.parties.repo.PaymentAllocationRepository
import com.counterweight.pricing.service.Money
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** Receivables split by how late they are. */
data class AgeingBuckets(
    val current: BigDecimal,
    val days1To30: BigDecimal,
    val days31To60: BigDecimal,
    val days61To90: BigDecimal,
    val days90Plus: BigDecimal,
    val total: BigDecimal,
) {
    companion object {
        fun from(row: AgeingRow) = AgeingBuckets(
            row.current, row.days1To30, row.days31To60, row.days61To90, row.days90Plus, row.total,
        )
    }
}

/**
 * Where a customer's account stands right now.
 *
 * [availableCredit] is null when the customer has no limit, which means cash
 * only rather than unlimited — the same distinction the entity keeps, carried
 * through so a caller cannot lose it by reading the wrong field.
 */
data class CreditStanding(
    val customerId: Long,
    val creditLimit: BigDecimal?,
    val balance: BigDecimal,
    val availableCredit: BigDecimal?,
    val overdueAmount: BigDecimal,
    val oldestOverdueDays: Int?,
    val paymentTermsDays: Short,
) {
    val isCashOnly: Boolean get() = creditLimit == null
    val hasOverdue: Boolean get() = overdueAmount > BigDecimal.ZERO
}

enum class CreditOutcome {
    /** Put it on the account. */
    ALLOWED,

    /** Over the limit — a supervisor may still let it through. */
    REQUIRES_APPROVAL,

    /** No account to charge. Not something an override fixes. */
    REFUSED,
}

data class CreditDecision(
    val outcome: CreditOutcome,
    val requestedAmount: BigDecimal,
    val standing: CreditStanding,
    /** What to show the person at the counter. Null when allowed outright. */
    val reason: String?,
)

/** One line of a manual payment allocation, when the payer names the invoices. */
data class AllocationRequest(val invoiceEntryId: Long, val amount: BigDecimal)

/**
 * The customer account: its ledger, its balance and its credit standing.
 *
 * Every entry is signed — positive increases what the customer owes, negative
 * reduces it — and the balance is their sum, never a stored column. The reason
 * is the same one behind the stock ledger (§5): a total kept alongside the
 * entries that justify it is a total that can drift from them, and the drift
 * surfaces during an argument with a customer holding a paper receipt.
 *
 * Entries are never edited or deleted. A mistake is corrected with an
 * ADJUSTMENT that is itself part of the record.
 *
 * `billing` will own invoices, credit notes and statements as documents; it
 * posts them onto the account through [postInvoice] and [postCreditNote] rather
 * than writing to this ledger itself.
 */
@Service
class CustomerAccountService(
    private val customers: CustomerService,
    private val customerRepo: CustomerRepository,
    private val ledger: CustomerLedgerEntryRepository,
    private val allocations: PaymentAllocationRepository,
    private val audit: AuditService,
) {

    // ── Reads ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    fun balance(customerId: Long): BigDecimal {
        customers.get(customerId)
        return Money.round(ledger.balanceOf(customerId))
    }

    /** Newest first — the statement view. */
    @Transactional(readOnly = true)
    fun statement(customerId: Long): List<CustomerLedgerEntry> {
        customers.get(customerId)
        return ledger.findByCustomerIdOrderByOccurredAtDescIdDesc(customerId)
    }

    /** Oldest first — the collection view. */
    @Transactional(readOnly = true)
    fun openInvoices(customerId: Long): List<OpenInvoice> {
        customers.get(customerId)
        return ledger.openInvoices(customerId)
    }

    /** What was owed immediately before [from]. The brought-forward line. */
    @Transactional(readOnly = true)
    fun balanceBefore(customerId: Long, from: Instant): BigDecimal {
        customers.get(customerId)
        return Money.round(ledger.balanceBefore(customerId, from))
    }

    /** Activity in a period, oldest first. `billing` renders it as a statement. */
    @Transactional(readOnly = true)
    fun entriesBetween(customerId: Long, from: Instant, until: Instant): List<CustomerLedgerEntry> {
        customers.get(customerId)
        return ledger.entriesBetween(customerId, from, until)
    }

    /**
     * Links a ledger entry to the document that produced it.
     *
     * Called by `billing` once the invoice document exists. The entry is posted
     * first — the sale path needs the credit check and the balance before any
     * document is issued — so the reference is filled in afterwards rather than
     * at insert. This is the one field on an entry that may be set later, and
     * only while it is still null.
     */
    @Transactional
    fun attachDocument(entryId: Long, salesDocumentId: Long) {
        val entry = ledger.findById(entryId).orElseThrow { ApiException.NotFound("Ledger entry", entryId) }
        if (entry.salesDocumentId != null && entry.salesDocumentId != salesDocumentId) {
            throw ApiException.RuleViolation(
                "ENTRY_ALREADY_DOCUMENTED",
                "That ledger entry already points at another document.",
            )
        }
        entry.salesDocumentId = salesDocumentId
        ledger.save(entry)
    }

    @Transactional(readOnly = true)
    fun ageing(customerId: Long): AgeingBuckets {
        customers.get(customerId)
        return AgeingBuckets.from(ledger.ageing(customerId))
    }

    @Transactional(readOnly = true)
    fun standing(customerId: Long): CreditStanding = standingFor(customers.get(customerId))

    /**
     * Everyone carrying a balance, worst first.
     *
     * The question a receivables screen opens on. Deliberately not built from
     * `search` — chasing debt is not a lookup, and requiring a name to type
     * would mean only knowing what is owed by people you already suspect.
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('CUSTOMER_MANAGE')")
    fun debtors(): List<DebtorRow> = ledger.debtors(Auth.current().branchId)

    /**
     * Whether [amount] may go on this customer's account.
     *
     * Two outcomes short of yes, and they are different in kind. A customer with
     * no credit limit has no account to charge, so a supervisor override would
     * be authorising something that does not exist — the fix is to open an
     * account, and the message says so. A customer over their limit has an
     * account and a history, which is a judgement call somebody senior can
     * make, so that one is overridable (§8.2).
     *
     * Being overdue does not block. §11 makes lateness an alert, not a gate,
     * and it is reported on the standing so the till can warn without refusing
     * a sale the owner would have waved through.
     */
    @Transactional(readOnly = true)
    fun evaluateOnAccount(customerId: Long, amount: BigDecimal): CreditDecision {
        if (amount <= BigDecimal.ZERO) {
            throw ApiException.Validation(
                "An amount on account must be more than zero.",
                mapOf("amount" to "must be greater than zero"),
            )
        }
        val customer = customers.get(customerId)
        val standing = standingFor(customer)

        if (!customer.isActive) {
            return CreditDecision(
                CreditOutcome.REFUSED, amount, standing,
                "${customer.name}'s account is closed. Take payment another way.",
            )
        }
        val limit = standing.creditLimit
            ?: return CreditDecision(
                CreditOutcome.REFUSED, amount, standing,
                "${customer.name} is a cash customer and has no credit account. " +
                    "Open one first, or take payment another way.",
            )

        if (standing.balance.add(amount) > limit) {
            val over = standing.balance.add(amount).subtract(limit)
            return CreditDecision(
                CreditOutcome.REQUIRES_APPROVAL, amount, standing,
                "This would put ${customer.name} GHS ${Money.round(over).toPlainString()} over " +
                    "their GHS ${limit.toPlainString()} limit. A supervisor can approve it.",
            )
        }
        return CreditDecision(CreditOutcome.ALLOWED, amount, standing, null)
    }

    // ── Credit terms ───────────────────────────────────────────────────────

    /**
     * Sets or removes a customer's credit limit and terms.
     *
     * Separate from ordinary customer edits and behind `CREDIT_APPROVE`,
     * because this is the decision that lets money leave the shop against a
     * promise. §16 lists credit-limit changes among the events the audit log
     * must carry, which is why the before value is recorded rather than just
     * the new one.
     *
     * Passing null removes the account. An outstanding balance survives that —
     * it does not vanish because the shop stopped extending credit, and the
     * debt stays on the ageing report until it is paid or written off.
     */
    @Transactional
    @PreAuthorize("hasAuthority('CREDIT_APPROVE')")
    fun setCreditTerms(customerId: Long, creditLimit: BigDecimal?, paymentTermsDays: Short?): Customer {
        val customer = customers.get(customerId)
        if (creditLimit != null && creditLimit < BigDecimal.ZERO) {
            throw ApiException.Validation(
                "A credit limit cannot be negative.",
                mapOf("creditLimit" to "must be zero or more"),
            )
        }
        if (paymentTermsDays != null && paymentTermsDays < 0) {
            throw ApiException.Validation(
                "Payment terms cannot be negative.",
                mapOf("paymentTermsDays" to "must be zero or more"),
            )
        }
        val before = """{"creditLimit":${customer.creditLimit?.toPlainString() ?: "null"},""" +
            """"paymentTermsDays":${customer.paymentTermsDays}}"""

        customer.creditLimit = creditLimit
        paymentTermsDays?.let { customer.paymentTermsDays = it }
        val saved = customerRepo.save(customer)

        audit.recordCurrent(
            "CUSTOMER_CREDIT_TERMS_CHANGED", "customer", customerId,
            before = before,
            after = """{"creditLimit":${creditLimit?.toPlainString() ?: "null"},""" +
                """"paymentTermsDays":${saved.paymentTermsDays}}""",
        )
        return saved
    }

    // ── Posting to the account ─────────────────────────────────────────────

    /**
     * Posts an invoice onto the account.
     *
     * Called by the sale path when a sale is settled on account, and later by
     * `billing` when it issues an invoice document. The due date comes from the
     * customer's own terms rather than from the caller — terms are a property of
     * the relationship, and letting each call site pass its own is how one
     * customer ends up with four different sets of them.
     */
    @Transactional
    @PreAuthorize("hasAuthority('SALE_CREATE')")
    fun postInvoice(
        customerId: Long,
        amount: BigDecimal,
        salesDocumentId: Long?,
        reference: String?,
    ): CustomerLedgerEntry {
        requirePositive(amount, "amount")
        val customer = customers.get(customerId)
        val entry = save(
            customer, "INVOICE", Money.round(amount), reference,
            dueOn = LocalDate.now().plusDays(customer.paymentTermsDays.toLong()),
            salesDocumentId = salesDocumentId,
        )
        audit.recordCurrent(
            "CUSTOMER_INVOICED", "customer", customerId,
            after = """{"amount":"${Money.round(amount).toPlainString()}","entryId":${entry.id}}""",
        )
        return entry
    }

    /**
     * Records money received against the account.
     *
     * With no [requested] allocation the payment settles the oldest invoices
     * first, which is both the convention and what a customer handing over cash
     * almost always means. A payer who says "this is for the September invoice"
     * gets that honoured instead — the whole reason allocations are stored
     * rather than inferred from a running balance.
     *
     * Money in before there is an invoice to settle is normal: whatever is left
     * over stays unallocated and reduces the balance regardless.
     */
    @Transactional
    @PreAuthorize("hasAuthority('CUSTOMER_MANAGE')")
    fun recordPayment(
        customerId: Long,
        amount: BigDecimal,
        reference: String?,
        requested: List<AllocationRequest>? = null,
    ): CustomerLedgerEntry = postCredit("PAYMENT", customerId, amount, reference, requested, "PAYMENT_RECEIVED")

    /** A return or price correction, posted from `billing`. */
    @Transactional
    @PreAuthorize("hasAuthority('SALE_RETURN')")
    fun postCreditNote(
        customerId: Long,
        amount: BigDecimal,
        reference: String?,
        requested: List<AllocationRequest>? = null,
    ): CustomerLedgerEntry = postCredit("CREDIT_NOTE", customerId, amount, reference, requested, "CREDIT_NOTE_POSTED")

    /**
     * Gives up on a debt.
     *
     * Behind `CREDIT_APPROVE` and always against named invoices where the caller
     * knows them, because "which debt did we abandon" is the question this entry
     * exists to answer.
     */
    @Transactional
    @PreAuthorize("hasAuthority('CREDIT_APPROVE')")
    fun writeOff(
        customerId: Long,
        amount: BigDecimal,
        reason: String,
        requested: List<AllocationRequest>? = null,
    ): CustomerLedgerEntry {
        if (reason.isBlank()) {
            throw ApiException.Validation(
                "Say why the debt is being written off.",
                mapOf("reason" to "is required"),
            )
        }
        return postCredit("WRITE_OFF", customerId, amount, reason, requested, "DEBT_WRITTEN_OFF")
    }

    /**
     * The opening balance for an account that existed before this system did.
     *
     * Posted as a debit that ages like an invoice, so a shop migrating its
     * paper ledger does not start every long-standing debt at "current".
     */
    @Transactional
    @PreAuthorize("hasAuthority('CREDIT_APPROVE')")
    fun postOpeningBalance(
        customerId: Long,
        amount: BigDecimal,
        dueOn: LocalDate?,
        reference: String?,
    ): CustomerLedgerEntry {
        requirePositive(amount, "amount")
        val customer = customers.get(customerId)
        if (ledger.findByCustomerIdOrderByOccurredAtDescIdDesc(customerId).any { it.entryType == "OPENING_BALANCE" }) {
            throw ApiException.RuleViolation(
                "OPENING_BALANCE_ALREADY_SET",
                "${customer.name} already has an opening balance. Post an adjustment instead.",
            )
        }
        val entry = save(customer, "OPENING_BALANCE", Money.round(amount), reference, dueOn = dueOn)
        audit.recordCurrent(
            "CUSTOMER_OPENING_BALANCE_SET", "customer", customerId,
            after = """{"amount":"${Money.round(amount).toPlainString()}"}""",
        )
        return entry
    }

    /**
     * The escape hatch, for corrections nothing else describes.
     *
     * Signed: positive increases the debt, negative reduces it. A reason is
     * mandatory — an unexplained movement on a receivables ledger is the entry
     * a dispute turns on, and the one nobody can reconstruct a year later.
     */
    @Transactional
    @PreAuthorize("hasAuthority('CREDIT_APPROVE')")
    fun postAdjustment(customerId: Long, signedAmount: BigDecimal, reason: String): CustomerLedgerEntry {
        if (signedAmount.compareTo(BigDecimal.ZERO) == 0) {
            throw ApiException.Validation(
                "An adjustment of zero changes nothing.",
                mapOf("amount" to "must not be zero"),
            )
        }
        if (reason.isBlank()) {
            throw ApiException.Validation(
                "Say why the account is being adjusted.",
                mapOf("reason" to "is required"),
            )
        }
        val customer = customers.get(customerId)
        val entry = save(customer, "ADJUSTMENT", Money.round(signedAmount), reason)
        audit.recordCurrent(
            "CUSTOMER_ACCOUNT_ADJUSTED", "customer", customerId,
            after = """{"amount":"${Money.round(signedAmount).toPlainString()}"}""",
            reason = reason,
        )
        return entry
    }

    // ── Internals ──────────────────────────────────────────────────────────

    private fun standingFor(customer: Customer): CreditStanding {
        val balance = Money.round(ledger.balanceOf(customer.id!!))
        val open = ledger.openInvoices(customer.id!!)
        val overdue = open.filter { it.daysOverdue > 0 }

        return CreditStanding(
            customerId = customer.id!!,
            creditLimit = customer.creditLimit,
            balance = balance,
            availableCredit = customer.creditLimit?.subtract(balance)?.max(BigDecimal.ZERO),
            overdueAmount = overdue.fold(Money.ZERO) { acc, i -> acc.add(i.outstanding) },
            oldestOverdueDays = overdue.maxOfOrNull { it.daysOverdue },
            paymentTermsDays = customer.paymentTermsDays,
        )
    }

    /**
     * Posts a credit-side entry and allocates it.
     *
     * [magnitude] is positive on the way in and stored negated, so no caller has
     * to remember the sign convention to reduce a debt — getting that backwards
     * would double a customer's balance instead of clearing it, and the entry
     * cannot be edited afterwards.
     */
    private fun postCredit(
        entryType: String,
        customerId: Long,
        magnitude: BigDecimal,
        reference: String?,
        requested: List<AllocationRequest>?,
        auditAction: String,
    ): CustomerLedgerEntry {
        requirePositive(magnitude, "amount")
        val customer = customers.get(customerId)
        val rounded = Money.round(magnitude)

        val entry = save(customer, entryType, rounded.negate(), reference)
        val unallocated = allocate(entry.id!!, customerId, rounded, requested)

        audit.recordCurrent(
            auditAction, "customer", customerId,
            after = """{"amount":"${rounded.toPlainString()}","entryId":${entry.id},""" +
                """"unallocated":"${unallocated.toPlainString()}"}""",
            reason = reference,
        )
        return entry
    }

    /** Returns whatever could not be placed against an open invoice. */
    private fun allocate(
        creditEntryId: Long,
        customerId: Long,
        available: BigDecimal,
        requested: List<AllocationRequest>?,
    ): BigDecimal {
        var remaining = available
        val open = ledger.openInvoices(customerId)

        if (requested != null) {
            val byId = open.associateBy { it.entryId }
            requested.forEach { req ->
                val invoice = byId[req.invoiceEntryId]
                    ?: throw ApiException.Validation(
                        "Invoice ${req.invoiceEntryId} is not an open invoice on this account.",
                        mapOf("allocations" to "names an invoice that is not open here"),
                    )
                requirePositive(req.amount, "allocations")
                if (req.amount > invoice.outstanding) {
                    throw ApiException.Validation(
                        "Only GHS ${invoice.outstanding.toPlainString()} is outstanding on invoice " +
                            "${req.invoiceEntryId}.",
                        mapOf("allocations" to "allocates more than the invoice has outstanding"),
                    )
                }
                if (req.amount > remaining) {
                    throw ApiException.Validation(
                        "The allocations add up to more than the amount received.",
                        mapOf("allocations" to "exceeds the amount received"),
                    )
                }
                allocations.save(PaymentAllocation(creditEntryId, req.invoiceEntryId, req.amount))
                remaining = remaining.subtract(req.amount)
            }
            return remaining
        }

        // Oldest first. openInvoices already orders by due date.
        open.forEach { invoice ->
            if (remaining <= BigDecimal.ZERO) return@forEach
            val take = remaining.min(invoice.outstanding)
            allocations.save(PaymentAllocation(creditEntryId, invoice.entryId, take))
            remaining = remaining.subtract(take)
        }
        return remaining
    }

    private fun save(
        customer: Customer,
        entryType: String,
        amount: BigDecimal,
        reference: String?,
        dueOn: LocalDate? = null,
        salesDocumentId: Long? = null,
    ): CustomerLedgerEntry {
        require(entryType in CustomerLedgerEntry.ALL_TYPES) { "unsupported entry type $entryType" }
        // Flushed because the allocations written next carry a foreign key to
        // this row.
        return ledger.saveAndFlush(
            CustomerLedgerEntry(
                branchId = customer.branchId,
                customerId = customer.id!!,
                entryType = entryType,
                amount = amount,
                createdBy = Auth.current().id,
            ).also {
                it.reference = reference?.trim()?.takeIf(String::isNotEmpty)
                it.dueOn = dueOn
                it.salesDocumentId = salesDocumentId
            }
        )
    }

    private fun requirePositive(amount: BigDecimal, field: String) {
        if (amount <= BigDecimal.ZERO) {
            throw ApiException.Validation(
                "The amount must be more than zero.",
                mapOf(field to "must be greater than zero"),
            )
        }
    }
}
