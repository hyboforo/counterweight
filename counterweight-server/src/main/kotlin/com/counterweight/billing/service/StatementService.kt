package com.counterweight.billing.service

import com.counterweight.common.ApiException
import com.counterweight.parties.service.AgeingBuckets
import com.counterweight.parties.service.CustomerAccountService
import com.counterweight.parties.service.CustomerService
import com.counterweight.pricing.service.Money
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One line of a statement, with the balance as it stood after that entry. */
data class StatementLine(
    val entryId: Long,
    val occurredAt: Instant,
    val entryType: String,
    val reference: String?,
    val dueOn: LocalDate?,
    /** Signed as stored: positive increases the debt. */
    val amount: BigDecimal,
    val runningBalance: BigDecimal,
)

data class Statement(
    val customerId: Long,
    val customerCode: String,
    val customerName: String,
    val from: LocalDate,
    val to: LocalDate,
    val openingBalance: BigDecimal,
    val lines: List<StatementLine>,
    val closingBalance: BigDecimal,
    val creditLimit: BigDecimal?,
    /** Ageing is as of today, not as of [to] — see the note on [StatementService]. */
    val ageing: AgeingBuckets,
)

/**
 * Customer statements: what happened in a period and what is still owed.
 *
 * Composed from what `parties` exposes rather than read out of its tables. The
 * customer ledger belongs to that module; a statement is a document rendered
 * over it, which is why this lives here alongside the invoices it cites.
 *
 * The running balance is computed forward from the opening figure rather than
 * summed per row, so the closing balance on the last line is arithmetically the
 * same number as the account balance — a statement whose final line disagrees
 * with the balance printed beneath it is worse than no statement.
 *
 * One asymmetry worth knowing: the ageing block is **as of today**, not as of
 * the period end. That is what a collections conversation needs — how late the
 * money is now — and reconstructing historical ageing would mean replaying
 * every allocation as it stood on the closing date. If a statement ever has to
 * be reproduced exactly as first issued, that becomes a snapshot on the
 * document rather than a recomputation here.
 */
@Service
class StatementService(
    private val customers: CustomerService,
    private val accounts: CustomerAccountService,
) {

    /**
     * The statement for a period.
     *
     * [to] is inclusive — somebody asking for a statement to the 31st means
     * including the 31st — so the query window runs to the start of the
     * following day.
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('CUSTOMER_MANAGE')")
    fun forCustomer(customerId: Long, from: LocalDate, to: LocalDate): Statement {
        if (to.isBefore(from)) {
            throw ApiException.Validation(
                "The statement period ends before it starts.",
                mapOf("to" to "must not be before the start date"),
            )
        }
        val customer = customers.get(customerId)
        val zone = ZoneId.systemDefault()
        val fromInstant = from.atStartOfDay(zone).toInstant()
        val untilInstant = to.plusDays(1).atStartOfDay(zone).toInstant()

        val opening = accounts.balanceBefore(customerId, fromInstant)
        var running = opening

        val lines = accounts.entriesBetween(customerId, fromInstant, untilInstant).map { entry ->
            running = running.add(entry.amount)
            StatementLine(
                entryId = entry.id!!,
                occurredAt = entry.occurredAt,
                entryType = entry.entryType,
                reference = entry.reference,
                dueOn = entry.dueOn,
                amount = entry.amount,
                runningBalance = Money.round(running),
            )
        }

        return Statement(
            customerId = customerId,
            customerCode = customer.code,
            customerName = customer.name,
            from = from,
            to = to,
            openingBalance = opening,
            lines = lines,
            closingBalance = Money.round(running),
            creditLimit = customer.creditLimit,
            ageing = accounts.ageing(customerId),
        )
    }

    /** The current month to date — the statement somebody asks for by default. */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('CUSTOMER_MANAGE')")
    fun currentMonth(customerId: Long): Statement {
        val today = LocalDate.now()
        return forCustomer(customerId, today.withDayOfMonth(1), today)
    }
}
