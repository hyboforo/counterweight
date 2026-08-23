package com.counterweight.catalog.service

import com.counterweight.catalog.domain.Product
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component

/**
 * Reads values back out of a product's `attributes` document.
 *
 * The counterpart to [AttributeValidator], which is the only thing that puts
 * values in. Having one reader matters for the same reason the validator is a
 * single class: `attributes` is one jsonb column, so nothing about the shape of
 * what comes out is guaranteed by the schema. Two hand-rolled JSON reads drift,
 * and they drift silently — the receipt keeps printing, just without the hazard
 * band.
 *
 * Reading is forgiving where writing is strict. The validator refuses an
 * unknown key; this returns null for one, because a product written before a
 * field was declared is a normal thing to meet and not a reason to fail a sale
 * in front of a customer. Where silence would be dangerous rather than merely
 * unhelpful — the buyer register — the migration that declared the field
 * backfilled every regulated product, so the read never has to guess.
 */
@Component
class ProductAttributes(private val json: ObjectMapper) {

    /** A text value, or null where the product does not carry the field. */
    fun text(attributes: String?, key: String): String? =
        valueOf(attributes, key)?.asText()?.takeIf { it.isNotBlank() }

    /**
     * A flag, false where the product does not carry the field.
     *
     * False is the safe reading only because of the backfill: after V15 every
     * product under `agro` holds an explicit answer, so a missing key means the
     * product is not regulated at all. Were the key ever absent from a
     * regulated product again, this would fail open — which is why the
     * declaration is `required` and the migration filled the gap rather than
     * leaving it to this method to interpret.
     */
    fun flag(attributes: String?, key: String): Boolean =
        valueOf(attributes, key)?.asBoolean(false) ?: false

    /**
     * §6.3: does selling this oblige the counter to write the buyer down?
     *
     * Named here rather than spelled out at each call site so the till and the
     * completion check cannot disagree about what "restricted" means. They
     * would disagree the moment one of them was updated and the other was not,
     * and the visible symptom would be a cashier told to collect details for a
     * line the server then waves through — or worse, the reverse.
     */
    fun requiresBuyerRecord(product: Product): Boolean =
        flag(product.attributes, REQUIRES_BUYER_RECORD)

    private fun valueOf(attributes: String?, key: String) =
        attributes?.let { document ->
            runCatching { json.readTree(document).get(key)?.takeIf { !it.isNull } }.getOrNull()
        }

    companion object {
        /** Declared on the agro root by V15; see that migration for why. */
        const val REQUIRES_BUYER_RECORD = "requires_buyer_record"
    }
}
