package com.counterweight.parties.web

import com.counterweight.common.GhanaPhone
import com.counterweight.common.SafeText
import com.counterweight.parties.domain.Customer
import com.counterweight.parties.domain.CustomerLedgerEntry
import com.counterweight.parties.service.*
import jakarta.validation.Valid
import jakarta.validation.constraints.*
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

// ── Requests ───────────────────────────────────────────────────────────────

data class CreateCustomerRequest(
    @field:NotBlank(message = "is required")
    @field:Size(max = 120, message = "is too long")
    @field:SafeText
    val name: String,

    @field:GhanaPhone
    val phone: String? = null,

    @field:GhanaPhone
    val altPhone: String? = null,

    @field:Size(max = 250, message = "is too long")
    @field:SafeText
    val address: String? = null,

    @field:Size(max = 1000, message = "is too long")
    @field:SafeText
    val notes: String? = null,

    /** Optional. Left out, the server allocates one from the customer sequence. */
    @field:Pattern(regexp = "^[A-Za-z0-9._-]{1,30}$", message = "may use letters, digits and . _ - only")
    val code: String? = null,

    val priceListId: Long? = null,

    @field:Min(value = 0, message = "must be zero or more")
    @field:Max(value = 365, message = "is longer than any terms the shop gives")
    val paymentTermsDays: Short = 0,
)

data class UpdateCustomerRequest(
    @field:Size(max = 120, message = "is too long")
    @field:SafeText
    val name: String? = null,

    @field:GhanaPhone
    val phone: String? = null,

    @field:GhanaPhone
    val altPhone: String? = null,

    @field:Size(max = 250, message = "is too long")
    @field:SafeText
    val address: String? = null,

    @field:Size(max = 1000, message = "is too long")
    @field:SafeText
    val notes: String? = null,
)

data class CreditTermsRequest(
    /** Null removes the account and puts the customer back on cash only. */
    @field:DecimalMin(value = "0.0", message = "must be zero or more")
    @field:Digits(integer = 12, fraction = 2, message = "has too many digits")
    val creditLimit: BigDecimal? = null,

    @field:Min(value = 0, message = "must be zero or more")
    @field:Max(value = 365, message = "is longer than any terms the shop gives")
    val paymentTermsDays: Short? = null,
)

data class AllocationLine(
    @field:NotNull(message = "is required")
    @field:Positive(message = "must be a valid invoice")
    val invoiceEntryId: Long,

    @field:NotNull(message = "is required")
    @field:DecimalMin(value = "0.01", message = "must be more than zero")
    @field:Digits(integer = 12, fraction = 2, message = "has too many digits")
    val amount: BigDecimal,
)

data class RecordPaymentRequest(
    @field:NotNull(message = "is required")
    @field:DecimalMin(value = "0.01", message = "must be more than zero")
    @field:Digits(integer = 12, fraction = 2, message = "has too many digits")
    val amount: BigDecimal,

    /** Mobile-money reference, cheque number, whatever the payer quoted. */
    @field:Size(max = 120, message = "is too long")
    @field:SafeText
    val reference: String? = null,

    /**
     * Left out, the payment settles the oldest invoices first. Supplied, the
     * payer's own instruction is honoured instead.
     */
    @field:Size(max = 50, message = "is too many allocations")
    @field:Valid
    val allocations: List<AllocationLine>? = null,
)

data class WriteOffRequest(
    @field:NotNull(message = "is required")
    @field:DecimalMin(value = "0.01", message = "must be more than zero")
    @field:Digits(integer = 12, fraction = 2, message = "has too many digits")
    val amount: BigDecimal,

    @field:NotBlank(message = "is required")
    @field:Size(max = 250, message = "is too long")
    @field:SafeText
    val reason: String,

    @field:Size(max = 50, message = "is too many allocations")
    @field:Valid
    val allocations: List<AllocationLine>? = null,
)

data class OpeningBalanceRequest(
    @field:NotNull(message = "is required")
    @field:DecimalMin(value = "0.01", message = "must be more than zero")
    @field:Digits(integer = 12, fraction = 2, message = "has too many digits")
    val amount: BigDecimal,

    @field:DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    val dueOn: LocalDate? = null,

    @field:Size(max = 120, message = "is too long")
    @field:SafeText
    val reference: String? = null,
)

data class AdjustmentRequest(
    /** Signed: positive increases the debt, negative reduces it. */
    @field:NotNull(message = "is required")
    @field:Digits(integer = 12, fraction = 2, message = "has too many digits")
    val amount: BigDecimal,

    @field:NotBlank(message = "is required")
    @field:Size(max = 250, message = "is too long")
    @field:SafeText
    val reason: String,
)

// ── Responses ──────────────────────────────────────────────────────────────

data class CustomerView(
    val id: Long, val code: String, val name: String,
    val phone: String?, val altPhone: String?, val address: String?,
    val creditLimit: BigDecimal?, val paymentTermsDays: Short,
    val priceListId: Long?, val isActive: Boolean, val notes: String?,
)

data class LedgerEntryView(
    val id: Long, val entryType: String, val amount: BigDecimal,
    val dueOn: LocalDate?, val reference: String?, val occurredAt: Instant,
    val salesDocumentId: Long?,
)

data class DebtorView(
    val customerId: Long, val code: String, val name: String, val phone: String?,
    val creditLimit: BigDecimal?, val balance: BigDecimal,
    val overdueAmount: BigDecimal, val oldestOverdueDays: Int,
)

data class OpenInvoiceView(
    val entryId: Long, val amount: BigDecimal, val allocated: BigDecimal,
    val outstanding: BigDecimal, val dueOn: LocalDate?, val daysOverdue: Int,
    val reference: String?,
)

private fun Customer.toView() = CustomerView(
    id!!, code, name, phone, altPhone, address,
    creditLimit, paymentTermsDays, priceListId, isActive, notes,
)

private fun CustomerLedgerEntry.toView() =
    LedgerEntryView(id!!, entryType, amount, dueOn, reference, occurredAt, salesDocumentId)

// ── Controllers ────────────────────────────────────────────────────────────

@RestController
@RequestMapping("/api/customers")
class CustomerController(private val customers: CustomerService) {

    /** Counter search across name, code and phone. */
    @GetMapping("/search")
    fun search(
        @RequestParam q: String,
        @RequestParam(defaultValue = "true") activeOnly: Boolean,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) limit: Int,
    ): List<CustomerView> = customers.search(q, activeOnly, limit).map { it.toView() }

    @GetMapping("/{id}")
    fun get(@PathVariable id: Long): CustomerView = customers.get(id).toView()

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody body: CreateCustomerRequest): CustomerView =
        customers.create(
            name = body.name,
            phone = body.phone,
            altPhone = body.altPhone,
            address = body.address,
            notes = body.notes,
            code = body.code,
            priceListId = body.priceListId,
            paymentTermsDays = body.paymentTermsDays,
        ).toView()

    @PutMapping("/{id}")
    fun update(@PathVariable id: Long, @Valid @RequestBody body: UpdateCustomerRequest): CustomerView =
        customers.updateDetails(id, body.name, body.phone, body.altPhone, body.address, body.notes).toView()

    @PutMapping("/{id}/price-list")
    fun setPriceList(
        @PathVariable id: Long,
        @RequestParam(required = false) priceListId: Long?,
    ): CustomerView = customers.setPriceList(id, priceListId).toView()

    @PutMapping("/{id}/active")
    fun setActive(@PathVariable id: Long, @RequestParam active: Boolean) {
        customers.setActive(id, active)
    }
}

/**
 * The customer account.
 *
 * Reads sit behind `CUSTOMER_MANAGE` rather than being open to any signed-in
 * user: a receivables ledger is a list of who in town owes money and how late
 * they are, which is not something every account needs to be able to page
 * through.
 */
/**
 * Receivables across the whole book, rather than one account at a time.
 *
 * Separate from [CustomerAccountController] because it is not scoped to a
 * customer: the whole point is to find out who owes money without having to
 * name them first.
 */
@RestController
@RequestMapping("/api/receivables")
@PreAuthorize("hasAuthority('CUSTOMER_MANAGE')")
class ReceivablesController(private val accounts: CustomerAccountService) {

    @GetMapping("/debtors")
    fun debtors(): List<DebtorView> = accounts.debtors().map {
        DebtorView(
            it.customerId, it.code, it.name, it.phone, it.creditLimit,
            it.balance, it.overdueAmount, it.oldestOverdueDays,
        )
    }
}

@RestController
@RequestMapping("/api/customers/{id}/account")
@PreAuthorize("hasAuthority('CUSTOMER_MANAGE')")
class CustomerAccountController(private val accounts: CustomerAccountService) {

    @GetMapping("/standing")
    fun standing(@PathVariable id: Long): CreditStanding = accounts.standing(id)

    @GetMapping("/statement")
    fun statement(@PathVariable id: Long): List<LedgerEntryView> =
        accounts.statement(id).map { it.toView() }

    @GetMapping("/open-invoices")
    fun openInvoices(@PathVariable id: Long): List<OpenInvoiceView> =
        accounts.openInvoices(id).map {
            OpenInvoiceView(
                it.entryId, it.amount, it.allocated, it.outstanding,
                it.dueOn?.toLocalDate(), it.daysOverdue, it.reference,
            )
        }

    @GetMapping("/ageing")
    fun ageing(@PathVariable id: Long): AgeingBuckets = accounts.ageing(id)

    /**
     * Whether an amount may go on account.
     *
     * The till calls this before offering "on account" as a tender, so the
     * operator finds out before the customer has watched a whole basket be
     * rung up. The sale path evaluates it again at completion — this endpoint
     * is for the prompt, never for the decision.
     */
    @GetMapping("/credit-check")
    fun creditCheck(
        @PathVariable id: Long,
        @RequestParam @DecimalMin("0.01") amount: BigDecimal,
    ): CreditDecision = accounts.evaluateOnAccount(id, amount)

    @PutMapping("/credit-terms")
    fun setCreditTerms(
        @PathVariable id: Long,
        @Valid @RequestBody body: CreditTermsRequest,
    ): CustomerView = accounts.setCreditTerms(id, body.creditLimit, body.paymentTermsDays).toView()

    @PostMapping("/payments")
    @ResponseStatus(HttpStatus.CREATED)
    fun recordPayment(
        @PathVariable id: Long,
        @Valid @RequestBody body: RecordPaymentRequest,
    ): LedgerEntryView = accounts.recordPayment(
        id, body.amount, body.reference,
        body.allocations?.map { AllocationRequest(it.invoiceEntryId, it.amount) },
    ).toView()

    @PostMapping("/write-offs")
    @ResponseStatus(HttpStatus.CREATED)
    fun writeOff(
        @PathVariable id: Long,
        @Valid @RequestBody body: WriteOffRequest,
    ): LedgerEntryView = accounts.writeOff(
        id, body.amount, body.reason,
        body.allocations?.map { AllocationRequest(it.invoiceEntryId, it.amount) },
    ).toView()

    @PostMapping("/opening-balance")
    @ResponseStatus(HttpStatus.CREATED)
    fun openingBalance(
        @PathVariable id: Long,
        @Valid @RequestBody body: OpeningBalanceRequest,
    ): LedgerEntryView =
        accounts.postOpeningBalance(id, body.amount, body.dueOn, body.reference).toView()

    @PostMapping("/adjustments")
    @ResponseStatus(HttpStatus.CREATED)
    fun adjust(
        @PathVariable id: Long,
        @Valid @RequestBody body: AdjustmentRequest,
    ): LedgerEntryView = accounts.postAdjustment(id, body.amount, body.reason).toView()
}
