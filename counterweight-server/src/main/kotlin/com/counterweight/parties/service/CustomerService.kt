package com.counterweight.parties.service

import com.counterweight.common.ApiException
import com.counterweight.common.normalisePhone
import com.counterweight.identity.security.Auth
import com.counterweight.identity.service.AuditService
import com.counterweight.parties.domain.Customer
import com.counterweight.parties.repo.CustomerRepository
import com.counterweight.pricing.service.PriceListService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

@Service
class CustomerService(
    private val customers: CustomerRepository,
    private val priceLists: PriceListService,
    private val audit: AuditService,
) {

    // ── Reads ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    fun get(id: Long): Customer {
        val customer = customers.findById(id).orElseThrow { ApiException.NotFound("Customer", id) }
        // Another branch's customer is not visible at all; a 403 would confirm
        // the id exists.
        if (customer.branchId != Auth.current().branchId) throw ApiException.NotFound("Customer", id)
        return customer
    }

    /**
     * Counter search.
     *
     * Forgiving on purpose. Somebody standing at the till knows the customer as
     * "Kwame the plumber" or reads out a phone number, and a search that
     * insists on the exact stored spelling means a new duplicate account gets
     * created instead — which is how a receivables ledger stops being usable.
     */
    @Transactional(readOnly = true)
    fun search(query: String, activeOnly: Boolean, limit: Int): List<Customer> {
        val q = query.trim()
        if (q.length < 2) {
            throw ApiException.Validation(
                "Type at least two characters to search.",
                mapOf("q" to "must be at least 2 characters"),
            )
        }
        // Phone matching is done on digits alone, so 024 000 0000, 0240000000
        // and +233 24 000 0000 all find the same account.
        val digits = q.filter(Char::isDigit)
        return customers.search(
            Auth.current().branchId, q, digits, activeOnly, limit.coerceIn(1, 100),
        )
    }

    // ── Writes ─────────────────────────────────────────────────────────────

    @Transactional
    @PreAuthorize("hasAuthority('CUSTOMER_MANAGE')")
    fun create(
        name: String,
        phone: String? = null,
        altPhone: String? = null,
        address: String? = null,
        notes: String? = null,
        code: String? = null,
        priceListId: Long? = null,
        paymentTermsDays: Short = 0,
    ): Customer {
        val branchId = Auth.current().branchId
        val resolvedCode = code?.trim()?.uppercase()?.takeIf(String::isNotEmpty) ?: generateCode()

        if (customers.existsByBranchIdAndCode(branchId, resolvedCode)) {
            throw ApiException.Conflict("A customer with the code '$resolvedCode' already exists.")
        }
        priceListId?.let { priceLists.get(it) }   // 404 rather than a foreign-key error

        val saved = customers.save(
            Customer(branchId = branchId, code = resolvedCode, name = name.trim()).also {
                it.phone = normalisedPhone(phone, "phone")
                it.altPhone = normalisedPhone(altPhone, "altPhone")
                it.address = address?.trim()
                it.notes = notes?.trim()
                it.priceListId = priceListId
                it.paymentTermsDays = paymentTermsDays
                // Credit is never granted at creation. Opening an account is a
                // separate, permissioned decision — see CreditService.
                it.creditLimit = null
            }
        )
        audit.recordCurrent(
            "CUSTOMER_CREATED", "customer", saved.id,
            after = """{"code":"$resolvedCode","name":${quote(name.trim())}}""",
        )
        return saved
    }

    @Transactional
    @PreAuthorize("hasAuthority('CUSTOMER_MANAGE')")
    fun updateDetails(
        id: Long,
        name: String?,
        phone: String?,
        altPhone: String?,
        address: String?,
        notes: String?,
    ): Customer {
        val customer = get(id)
        val before = """{"name":${quote(customer.name)},"phone":${quote(customer.phone ?: "")}}"""

        name?.trim()?.takeIf(String::isNotEmpty)?.let { customer.name = it }
        phone?.let { customer.phone = normalisedPhone(it, "phone") }
        altPhone?.let { customer.altPhone = normalisedPhone(it, "altPhone") }
        address?.let { customer.address = it.trim().takeIf(String::isNotEmpty) }
        notes?.let { customer.notes = it.trim().takeIf(String::isNotEmpty) }

        val saved = customers.save(customer)
        audit.recordCurrent(
            "CUSTOMER_UPDATED", "customer", id,
            before = before,
            after = """{"name":${quote(saved.name)},"phone":${quote(saved.phone ?: "")}}""",
        )
        return saved
    }

    /** Puts the customer on a price list, or back on the branch default with null. */
    @Transactional
    @PreAuthorize("hasAuthority('PRICE_MANAGE')")
    fun setPriceList(id: Long, priceListId: Long?): Customer {
        val customer = get(id)
        val before = customer.priceListId
        priceListId?.let { priceLists.get(it) }
        customer.priceListId = priceListId
        val saved = customers.save(customer)
        audit.recordCurrent(
            "CUSTOMER_PRICE_LIST_CHANGED", "customer", id,
            before = """{"priceListId":${before ?: "null"}}""",
            after = """{"priceListId":${priceListId ?: "null"}}""",
        )
        return saved
    }

    /**
     * Deactivates or reactivates an account.
     *
     * Deactivating does not clear a balance, and this deliberately does not
     * check for one. A customer who has stopped trading with the shop but still
     * owes money is precisely the account that must stay on the ageing report;
     * hiding it would be the wrong kind of tidy.
     */
    @Transactional
    @PreAuthorize("hasAuthority('CUSTOMER_MANAGE')")
    fun setActive(id: Long, active: Boolean) {
        val customer = get(id)
        customer.isActive = active
        customers.save(customer)
        audit.recordCurrent(
            if (active) "CUSTOMER_ACTIVATED" else "CUSTOMER_DEACTIVATED", "customer", id,
        )
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun generateCode(): String = "CUS-%06d".format(customers.nextCodeNumber())

    /**
     * Stores phone numbers in one shape.
     *
     * The bean-validation annotation on the request already rejects a number
     * that is not Ghanaian; this puts the accepted ones into local 0XXXXXXXXX
     * form so that searching for a customer finds them however the number was
     * typed the day the account was opened.
     */
    private fun normalisedPhone(raw: String?, field: String): String? {
        val trimmed = raw?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return normalisePhone(trimmed)
            ?: throw ApiException.Validation(
                "That does not look like a Ghana phone number.",
                mapOf(field to "should be like 024 000 0000"),
            )
    }

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

/** Convenience for callers that only need the number, e.g. the sale path. */
fun Customer.effectiveCreditLimit(): BigDecimal = creditLimit ?: BigDecimal.ZERO
