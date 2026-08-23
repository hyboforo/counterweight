package com.counterweight.catalog.service

import com.counterweight.catalog.domain.CategoryAttribute
import com.counterweight.catalog.repo.CategoryAttributeRepository
import com.counterweight.common.ApiException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Validates a product's dynamic attributes against the fields its category
 * declares.
 *
 * This is what makes "add a new category" data entry rather than a migration
 * (§6.1). The cost of that flexibility is that the schema cannot check these
 * values — `attributes` is one jsonb column — so **this class is the only thing
 * standing between the catalog and arbitrary junk**. It is deliberately strict:
 *
 *  - unknown keys are rejected outright rather than silently stored, because a
 *    typo'd key is otherwise invisible until someone notices a filter returning
 *    nothing;
 *  - required fields must be present and non-blank;
 *  - every value is coerced to its declared type, so `"12"` and `12` both end
 *    up as a number and sorting works;
 *  - enum values must be one of the declared options.
 *
 * Attributes are inherited down the category path, so a field declared once on
 * `agro` applies to every descendant.
 */
@Component
class AttributeValidator(
    private val attributes: CategoryAttributeRepository,
    private val json: ObjectMapper,
) {

    /** Every attribute declared on this category or any ancestor of it. */
    fun declarationsFor(categoryId: Long): List<CategoryAttribute> =
        attributes.findInheritedForCategory(categoryId)

    /**
     * Checks [raw] against the declarations for [categoryId] and returns the
     * normalised document to store. Throws [ApiException.Validation] listing
     * every problem at once — correcting one field at a time is miserable when
     * a product form has fifteen of them.
     */
    fun validateAndNormalise(categoryId: Long, raw: Map<String, Any?>?): String {
        val declared = declarationsFor(categoryId)
        val byKey = declared.associateBy { it.key }
        val supplied = raw ?: emptyMap()
        val problems = linkedMapOf<String, String>()
        val normalised: ObjectNode = json.createObjectNode()

        val unknown = supplied.keys - byKey.keys
        unknown.forEach { key ->
            problems["attributes.$key"] =
                "is not a field of this category" +
                    if (byKey.isEmpty()) " (it declares none)"
                    else " (expected one of: ${byKey.keys.sorted().joinToString(", ")})"
        }

        declared.sortedBy { it.sortOrder }.forEach { def ->
            val value = supplied[def.key]
            val blank = value == null || (value is String && value.isBlank())

            if (blank) {
                if (def.required) problems["attributes.${def.key}"] = "${def.label} is required"
                return@forEach
            }
            when (val outcome = coerce(def, value!!)) {
                is Coerced.Ok -> normalised.set<JsonNode>(def.key, outcome.node)
                is Coerced.Bad -> problems["attributes.${def.key}"] = outcome.message
            }
        }

        if (problems.isNotEmpty()) {
            throw ApiException.Validation("Some product details need correcting.", problems)
        }
        return json.writeValueAsString(normalised)
    }

    private sealed interface Coerced {
        class Ok(val node: JsonNode) : Coerced
        class Bad(val message: String) : Coerced
    }

    private fun coerce(def: CategoryAttribute, value: Any): Coerced {
        val text = value.toString().trim()
        return when (def.dataType) {
            "TEXT" -> when {
                text.length > MAX_TEXT -> Coerced.Bad("${def.label} is too long (max $MAX_TEXT characters)")
                // Same reasoning as @SafeText: control characters and direction
                // overrides have no place in catalogue data and can misrender a
                // printed receipt.
                text.any { it.isISOControl() } -> Coerced.Bad("${def.label} contains characters that are not allowed")
                else -> Coerced.Ok(json.nodeFactory.textNode(text))
            }

            "NUMBER" -> {
                val n = text.toBigDecimalOrNull()
                when {
                    n == null -> Coerced.Bad("${def.label} must be a number")
                    n.abs() > MAX_NUMBER -> Coerced.Bad("${def.label} is out of range")
                    else -> Coerced.Ok(json.nodeFactory.numberNode(n))
                }
            }

            "BOOL" -> when (text.lowercase()) {
                "true", "yes", "1" -> Coerced.Ok(json.nodeFactory.booleanNode(true))
                "false", "no", "0" -> Coerced.Ok(json.nodeFactory.booleanNode(false))
                else -> Coerced.Bad("${def.label} must be true or false")
            }

            "DATE" -> try {
                Coerced.Ok(json.nodeFactory.textNode(LocalDate.parse(text).toString()))
            } catch (e: DateTimeParseException) {
                Coerced.Bad("${def.label} must be a date in YYYY-MM-DD form")
            }

            "ENUM" -> {
                val options = def.enumValues?.toList().orEmpty()
                // Case-insensitive in, canonical form out: staff type 'ii', the
                // catalogue stores 'II'.
                val match = options.firstOrNull { it.equals(text, ignoreCase = true) }
                if (match == null) {
                    Coerced.Bad("${def.label} must be one of: ${options.joinToString(", ")}")
                } else {
                    Coerced.Ok(json.nodeFactory.textNode(match))
                }
            }

            // A declaration with an unknown data_type is a seeding error, not a
            // caller error — fail loudly rather than accepting the value.
            else -> Coerced.Bad("${def.label} has an unsupported field type '${def.dataType}'")
        }
    }

    private companion object {
        const val MAX_TEXT = 500
        val MAX_NUMBER: BigDecimal = BigDecimal("1000000000")
    }
}
