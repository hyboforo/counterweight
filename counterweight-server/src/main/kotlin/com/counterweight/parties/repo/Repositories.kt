package com.counterweight.parties.repo

import com.counterweight.parties.domain.Customer
import com.counterweight.parties.domain.CustomerLedgerEntry
import com.counterweight.parties.domain.PaymentAllocation
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.math.BigDecimal

@Repository
interface CustomerRepository : JpaRepository<Customer, Long> {

    fun findByBranchIdAndCode(branchId: Long, code: String): Customer?
    fun existsByBranchIdAndCode(branchId: Long, code: String): Boolean

    /**
     * Counter search across name, code and phone.
     *
     * Phone matches on a suffix, because the number a customer reads out is
     * rarely the form it was typed in — 024, +23324 and 23324 all have to find
     * the same account. The trigram index from V1 backs the name half.
     */
    @Query(
        value = """
            SELECT c.* FROM customer c
             WHERE c.branch_id = :branchId
               AND (:activeOnly = FALSE OR c.is_active)
               AND (
                    c.name ILIKE '%' || :q || '%'
                 OR c.code ILIKE :q || '%'
                 OR c.name % :q
                 OR (:digits <> '' AND (
                        replace(replace(c.phone, ' ', ''), '-', '') LIKE '%' || :digits
                     OR replace(replace(c.alt_phone, ' ', ''), '-', '') LIKE '%' || :digits
                 ))
               )
             ORDER BY similarity(c.name, :q) DESC, c.name
             LIMIT :limit
        """,
        nativeQuery = true,
    )
    fun search(
        @Param("branchId") branchId: Long,
        @Param("q") query: String,
        @Param("digits") digits: String,
        @Param("activeOnly") activeOnly: Boolean,
        @Param("limit") limit: Int,
    ): List<Customer>

    /** The next customer code. A plain sequence — see V5 for why gaps are fine here. */
    @Query(value = "SELECT nextval('customer_code_seq')", nativeQuery = true)
    fun nextCodeNumber(): Long
}

/** An invoice with what is still owed on it. */
interface OpenInvoice {
    val entryId: Long
    val amount: BigDecimal
    val allocated: BigDecimal
    val outstanding: BigDecimal
    val dueOn: java.sql.Date?
    val daysOverdue: Int
    val reference: String?
    val occurredAt: java.sql.Timestamp
}

/** Receivables split by how late they are. */
/**
 * A customer who owes the shop money, for the collections list.
 *
 * `balance` is the account net of everything, including money paid in that has
 * not been matched to an invoice yet. `overdueAmount` counts only invoices past
 * their due date, which is the figure a conversation actually starts from.
 */
interface DebtorRow {
    val customerId: Long
    val code: String
    val name: String
    val phone: String?
    val creditLimit: BigDecimal?
    val balance: BigDecimal
    val overdueAmount: BigDecimal
    val oldestOverdueDays: Int
}

interface AgeingRow {
    val current: BigDecimal
    val days1To30: BigDecimal
    val days31To60: BigDecimal
    val days61To90: BigDecimal
    val days90Plus: BigDecimal
    val total: BigDecimal
}

@Repository
interface CustomerLedgerEntryRepository : JpaRepository<CustomerLedgerEntry, Long> {

    /**
     * What the customer owes: the sum of every entry.
     *
     * Derived on read rather than kept as a column on `customer`. A stored
     * balance is one bad write away from disagreeing with the entries that
     * justify it, and this is the number an argument at the counter turns on.
     */
    @Query(
        value = "SELECT COALESCE(SUM(amount), 0) FROM customer_ledger_entry WHERE customer_id = :customerId",
        nativeQuery = true,
    )
    fun balanceOf(@Param("customerId") customerId: Long): BigDecimal

    fun findByCustomerIdOrderByOccurredAtDescIdDesc(customerId: Long): List<CustomerLedgerEntry>

    /**
     * What was owed at the start of a statement period.
     *
     * Strictly before [from], so an entry posted on the opening day appears in
     * the period's activity rather than being folded into the brought-forward
     * figure and counted twice.
     */
    @Query(
        value = """
            SELECT COALESCE(SUM(amount), 0) FROM customer_ledger_entry
             WHERE customer_id = :customerId AND occurred_at < :from
        """,
        nativeQuery = true,
    )
    fun balanceBefore(
        @Param("customerId") customerId: Long,
        @Param("from") from: java.time.Instant,
    ): BigDecimal

    /** Activity within a statement period, oldest first — the order it reads in. */
    @Query(
        value = """
            SELECT e.* FROM customer_ledger_entry e
             WHERE e.customer_id = :customerId
               AND e.occurred_at >= :from
               AND e.occurred_at < :until
             ORDER BY e.occurred_at, e.id
        """,
        nativeQuery = true,
    )
    fun entriesBetween(
        @Param("customerId") customerId: Long,
        @Param("from") from: java.time.Instant,
        @Param("until") until: java.time.Instant,
    ): List<CustomerLedgerEntry>

    /**
     * Invoices with something still on them, oldest first.
     *
     * Oldest first because that is the default allocation order, and because a
     * collections conversation starts at the top of this list.
     */
    @Query(
        value = """
            SELECT e.id                                        AS "entryId",
                   e.amount                                    AS "amount",
                   COALESCE(a.allocated, 0)                    AS "allocated",
                   e.amount - COALESCE(a.allocated, 0)         AS "outstanding",
                   e.due_on                                    AS "dueOn",
                   GREATEST(CURRENT_DATE - COALESCE(e.due_on, e.occurred_at::date), 0) AS "daysOverdue",
                   e.reference                                 AS "reference",
                   e.occurred_at                               AS "occurredAt"
              FROM customer_ledger_entry e
              LEFT JOIN (
                   SELECT invoice_entry_id, SUM(amount) AS allocated
                     FROM payment_allocation GROUP BY invoice_entry_id
              ) a ON a.invoice_entry_id = e.id
             WHERE e.customer_id = :customerId
               AND e.entry_type IN ('INVOICE', 'OPENING_BALANCE')
               AND e.amount - COALESCE(a.allocated, 0) > 0
             ORDER BY COALESCE(e.due_on, e.occurred_at::date), e.id
        """,
        nativeQuery = true,
    )
    fun openInvoices(@Param("customerId") customerId: Long): List<OpenInvoice>

    /**
     * Everyone with a balance, worst first.
     *
     * Ordered by overdue amount before total balance, because that is the order
     * somebody working through a phone list needs: a large account inside its
     * terms is business as usual, and a small one sixty days late is not.
     *
     * Customers in credit — money paid in ahead of an invoice — are included
     * too. A negative balance is a real state that somebody has to notice, and
     * filtering to positives only would hide the shop owing a refund.
     */
    @Query(
        value = """
            SELECT c.id                        AS "customerId",
                   c.code                      AS "code",
                   c.name                      AS "name",
                   c.phone                     AS "phone",
                   c.credit_limit              AS "creditLimit",
                   COALESCE(bal.balance, 0)    AS "balance",
                   COALESCE(od.overdue, 0)     AS "overdueAmount",
                   COALESCE(od.oldest, 0)      AS "oldestOverdueDays"
              FROM customer c
              LEFT JOIN (
                   SELECT customer_id, SUM(amount) AS balance
                     FROM customer_ledger_entry
                    GROUP BY customer_id
              ) bal ON bal.customer_id = c.id
              LEFT JOIN (
                   SELECT e.customer_id,
                          SUM(e.amount - COALESCE(a.allocated, 0)) AS overdue,
                          MAX(CURRENT_DATE - COALESCE(e.due_on, e.occurred_at::date)) AS oldest
                     FROM customer_ledger_entry e
                     LEFT JOIN (
                          SELECT invoice_entry_id, SUM(amount) AS allocated
                            FROM payment_allocation GROUP BY invoice_entry_id
                     ) a ON a.invoice_entry_id = e.id
                    WHERE e.entry_type IN ('INVOICE', 'OPENING_BALANCE')
                      AND e.amount - COALESCE(a.allocated, 0) > 0
                      AND COALESCE(e.due_on, e.occurred_at::date) < CURRENT_DATE
                    GROUP BY e.customer_id
              ) od ON od.customer_id = c.id
             WHERE c.branch_id = :branchId
               AND COALESCE(bal.balance, 0) <> 0
             ORDER BY COALESCE(od.overdue, 0) DESC, COALESCE(bal.balance, 0) DESC
        """,
        nativeQuery = true,
    )
    fun debtors(@Param("branchId") branchId: Long): List<DebtorRow>

    /**
     * Ageing buckets over the open invoices.
     *
     * §11 words the buckets as "current / 30 / 60 / 90+", which names four but
     * implies the standard five — nothing reaches a "90+" label without a
     * 61–90 bucket ahead of it. Five are computed; a caller wanting four can
     * fold the last two together, which is not possible in the other direction.
     */
    @Query(
        value = """
            SELECT COALESCE(SUM(o.outstanding) FILTER (WHERE o.days_overdue <= 0), 0)               AS "current",
                   COALESCE(SUM(o.outstanding) FILTER (WHERE o.days_overdue BETWEEN 1 AND 30), 0)   AS "days1To30",
                   COALESCE(SUM(o.outstanding) FILTER (WHERE o.days_overdue BETWEEN 31 AND 60), 0)  AS "days31To60",
                   COALESCE(SUM(o.outstanding) FILTER (WHERE o.days_overdue BETWEEN 61 AND 90), 0)  AS "days61To90",
                   COALESCE(SUM(o.outstanding) FILTER (WHERE o.days_overdue > 90), 0)               AS "days90Plus",
                   COALESCE(SUM(o.outstanding), 0)                                                  AS "total"
              FROM (
                   SELECT e.amount - COALESCE(a.allocated, 0) AS outstanding,
                          CURRENT_DATE - COALESCE(e.due_on, e.occurred_at::date) AS days_overdue
                     FROM customer_ledger_entry e
                     LEFT JOIN (
                          SELECT invoice_entry_id, SUM(amount) AS allocated
                            FROM payment_allocation GROUP BY invoice_entry_id
                     ) a ON a.invoice_entry_id = e.id
                    WHERE e.customer_id = :customerId
                      AND e.entry_type IN ('INVOICE', 'OPENING_BALANCE')
                      AND e.amount - COALESCE(a.allocated, 0) > 0
              ) o
        """,
        nativeQuery = true,
    )
    fun ageing(@Param("customerId") customerId: Long): AgeingRow

    /**
     * How much of a credit entry has not been allocated to an invoice yet.
     *
     * Payments on account — money in before an invoice exists — are normal, so
     * an unallocated remainder is a state to report rather than an error.
     */
    @Query(
        value = """
            SELECT ABS(e.amount) - COALESCE(
                     (SELECT SUM(pa.amount) FROM payment_allocation pa WHERE pa.payment_entry_id = e.id), 0)
              FROM customer_ledger_entry e WHERE e.id = :entryId
        """,
        nativeQuery = true,
    )
    fun unallocatedOn(@Param("entryId") entryId: Long): BigDecimal
}

@Repository
interface PaymentAllocationRepository : JpaRepository<PaymentAllocation, Long> {
    fun findByPaymentEntryId(paymentEntryId: Long): List<PaymentAllocation>
    fun findByInvoiceEntryId(invoiceEntryId: Long): List<PaymentAllocation>
}
