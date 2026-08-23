package com.counterweight.catalog.service

import com.counterweight.catalog.domain.*
import com.counterweight.catalog.repo.*
import com.counterweight.common.ApiException
import com.counterweight.identity.security.Auth
import com.counterweight.identity.service.AuditService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant

@Service
class CategoryService(
    private val categories: CategoryRepository,
    private val categoryAttributes: CategoryAttributeRepository,
    private val products: ProductRepository,
    private val audit: AuditService,
) {

    @Transactional(readOnly = true)
    fun all(): List<Category> = categories.findAll().sortedBy { it.path ?: it.code }

    @Transactional(readOnly = true)
    fun get(id: Long): Category =
        categories.findById(id).orElseThrow { ApiException.NotFound("Category", id) }

    @Transactional(readOnly = true)
    fun attributesFor(categoryId: Long): List<CategoryAttribute> {
        get(categoryId)
        return categoryAttributes.findInheritedForCategory(categoryId)
    }

    @Transactional
    @PreAuthorize("hasAuthority('PRODUCT_MANAGE')")
    fun create(code: String, name: String, parentId: Long?, kind: String): Category {
        val normalised = code.uppercase()
        if (categories.existsByCode(normalised)) {
            throw ApiException.Conflict("A category with the code '$normalised' already exists.")
        }
        parentId?.let { get(it) }   // 404 rather than a foreign-key error

        // Depth is bounded because ltree paths are, and because a ten-deep
        // category tree in a hardware shop is a modelling mistake, not a need.
        parentId?.let {
            val depth = categories.depthOf(it) ?: 0
            if (depth >= MAX_DEPTH) {
                throw ApiException.RuleViolation(
                    "CATEGORY_TOO_DEEP",
                    "Categories can be nested at most $MAX_DEPTH levels deep.",
                )
            }
        }

        /*
         * Kind is inherited, not chosen freely.
         *
         * Everything under the agro root already inherits the EPA number and
         * hazard band it declares, while `ProductService.create` reads the
         * product's *own* category to decide whether batch tracking is
         * compulsory. A GENERAL category filed under agro would therefore ask
         * for an EPA registration and then let the product be sold with no
         * expiry to enforce — the licence fields present, the control they
         * exist for absent.
         *
         * Refused rather than quietly promoted, because the alternative
         * reading is a modelling mistake worth saying out loud: if a knapsack
         * sprayer belongs somewhere, it is not under the chemicals.
         */
        parentId?.let {
            val parentKind = get(it).kind
            if (parentKind == "AGROCHEMICAL" && kind != "AGROCHEMICAL") {
                throw ApiException.Validation(
                    "A category under an agro-chemical one is agro-chemical too — its products " +
                        "inherit the EPA and hazard fields, and batch tracking goes with them.",
                    mapOf("kind" to "must be AGROCHEMICAL under an agro-chemical parent"),
                )
            }
        }

        val saved = categories.save(
            Category(code = normalised, name = name).also {
                it.parentId = parentId
                it.kind = kind
            }
        )
        audit.recordCurrent("CATEGORY_CREATED", "category", saved.id, after = """{"code":"$normalised"}""")
        return saved
    }

    @Transactional
    @PreAuthorize("hasAuthority('PRODUCT_MANAGE')")
    fun addAttribute(
        categoryId: Long,
        key: String,
        label: String,
        dataType: String,
        enumValues: List<String>?,
        unit: String?,
        required: Boolean,
    ): CategoryAttribute {
        get(categoryId)
        if (categoryAttributes.existsByCategoryIdAndKey(categoryId, key)) {
            throw ApiException.Conflict("This category already declares a field called '$key'.")
        }
        if (dataType !in DATA_TYPES) {
            throw ApiException.Validation(
                "Unknown field type '$dataType'.",
                mapOf("dataType" to "must be one of: ${DATA_TYPES.joinToString(", ")}"),
            )
        }
        if (dataType == "ENUM" && enumValues.isNullOrEmpty()) {
            throw ApiException.Validation(
                "A choice field needs at least one option.",
                mapOf("enumValues" to "is required for ENUM fields"),
            )
        }

        /*
         * Adding a required field to a category that already has products would
         * make every one of them retroactively invalid — they would fail
         * validation on the next edit, with no indication why. New fields start
         * optional; make them required once the data is backfilled.
         */
        if (required && products.countByCategoryId(categoryId) > 0) {
            throw ApiException.RuleViolation(
                "REQUIRED_FIELD_ON_POPULATED_CATEGORY",
                "This category already has products, so '$label' cannot be added as " +
                    "required. Add it as optional, fill it in, then make it required.",
            )
        }

        val saved = categoryAttributes.save(
            CategoryAttribute(categoryId = categoryId, key = key, label = label, dataType = dataType).also {
                it.enumValues = enumValues?.toTypedArray()
                it.unit = unit
                it.required = required
            }
        )
        audit.recordCurrent("CATEGORY_ATTRIBUTE_ADDED", "category", categoryId, after = """{"key":"$key"}""")
        return saved
    }

    private companion object {
        const val MAX_DEPTH = 5
        val DATA_TYPES = setOf("TEXT", "NUMBER", "BOOL", "DATE", "ENUM")
    }
}

data class ProductUomSpec(
    val uomCode: String,
    val factor: BigDecimal,
    val isBase: Boolean,
    val sellable: Boolean = true,
    val purchasable: Boolean = true,
    val barcode: String? = null,
)

/** A product's unit with its unit of measure joined back on. */
data class ResolvedUnit(
    val unit: ProductUom,
    val uomCode: String,
    val uomName: String,
    val decimals: Int,
)

@Service
class ProductService(
    private val products: ProductRepository,
    private val productUoms: ProductUomRepository,
    private val uoms: UomRepository,
    private val categories: CategoryRepository,
    private val attributeValidator: AttributeValidator,
    private val audit: AuditService,
) {

    @Transactional(readOnly = true)
    fun get(id: Long): Product =
        products.findById(id).orElseThrow { ApiException.NotFound("Product", id) }

    @Transactional(readOnly = true)
    fun search(query: String, limit: Int): List<Product> {
        val q = query.trim()
        if (q.length < 2) {
            throw ApiException.Validation(
                "Type at least two characters to search.",
                mapOf("q" to "must be at least 2 characters"),
            )
        }
        return products.search(Auth.current().branchId, q, limit.coerceIn(1, 100))
    }

    @Transactional(readOnly = true)
    fun inCategory(categoryId: Long, activeOnly: Boolean): List<Product> =
        products.findInCategorySubtree(Auth.current().branchId, categoryId, activeOnly)

    @Transactional(readOnly = true)
    fun unitsOf(productId: Long): List<ProductUom> = productUoms.findByProductId(productId)

    /**
     * A product's units with the unit of measure resolved.
     *
     * [ProductUom] carries a `uomId` and nothing a person can read, so any
     * caller offering a choice of unit — goods receipt above all, where the
     * difference between a carton and a piece is the difference between a
     * correct lot cost and one out by a factor of twenty — had to join it back
     * itself. `decimals` comes along because it is the rule for how much
     * quantity precision the unit actually allows.
     */
    @Transactional(readOnly = true)
    fun unitsWithMeasureOf(productId: Long): List<ResolvedUnit> {
        val units = productUoms.findByProductId(productId)
        if (units.isEmpty()) return emptyList()
        val measures = uoms.findAllById(units.map { it.uomId }).associateBy { it.id }
        return units.mapNotNull { unit ->
            val uom = measures[unit.uomId] ?: return@mapNotNull null
            ResolvedUnit(unit, uom.code, uom.name, uom.decimals.toInt())
        }
    }

    /**
     * Every unit of measure the shop can build a product out of.
     *
     * Reference data, seeded by V1 and extended by INSERT — OLONKA is in there
     * because customers ask for a tin of it. Whoever adds a product has to
     * pick from this list rather than type a code, since a mistyped one is
     * refused with "unknown unit" and a *near* miss (PACKET where PACK was
     * meant) would quietly become a second way to say the same thing.
     */
    @Transactional(readOnly = true)
    fun unitsOfMeasure(): List<Uom> = uoms.findAll().sortedBy { it.code }

    /**
     * The product and the specific unit a scanned barcode identifies.
     *
     * A barcode belongs to a *unit*, not a product — the carton and the single
     * piece carry different codes, and which one was scanned is exactly what
     * the till needs to know. Returning the product alone would leave it
     * guessing, which for a carton of twenty means selling one.
     *
     * Null when nothing matches. Most stock in this shop has no barcode at all,
     * so a miss is ordinary rather than exceptional, and the till falls back to
     * search.
     */
    @Transactional(readOnly = true)
    fun byBarcode(barcode: String): Pair<Product, ProductUom>? {
        val code = barcode.trim()
        if (code.isEmpty()) return null
        val unit = productUoms.findByBarcode(code) ?: return null
        val product = products.findById(unit.productId).orElse(null) ?: return null
        if (product.branchId != Auth.current().branchId || !product.isActive) return null
        return product to unit
    }

    @Transactional
    @PreAuthorize("hasAuthority('PRODUCT_MANAGE')")
    fun create(
        sku: String,
        name: String,
        categoryId: Long,
        units: List<ProductUomSpec>,
        localName: String? = null,
        description: String? = null,
        attributes: Map<String, Any?>? = null,
        isBatchTracked: Boolean = false,
        pickingRule: String? = null,
    ): Product {
        val branchId = Auth.current().branchId
        val normalisedSku = sku.trim().uppercase()

        if (products.existsBySkuAndBranchId(normalisedSku, branchId)) {
            throw ApiException.Conflict("A product with SKU '$normalisedSku' already exists.")
        }
        val category = categories.findById(categoryId).orElseThrow {
            ApiException.NotFound("Category", categoryId)
        }

        // Dynamic fields are checked against the category's declarations before
        // anything is written. See AttributeValidator.
        val normalisedAttributes = attributeValidator.validateAndNormalise(categoryId, attributes)

        /*
         * A batch-tracked product must be picked FEFO — expiry is the entire
         * reason for tracking it. The database enforces this too
         * (batch_implies_fefo); doing it here turns a 409 into a usable message.
         */
        val rule = pickingRule ?: if (isBatchTracked) "FEFO" else "FIFO"
        if (isBatchTracked && rule == "FIFO") {
            throw ApiException.Validation(
                "A batch-tracked product must be picked by expiry (FEFO), not FIFO.",
                mapOf("pickingRule" to "must be FEFO or MANUAL when batch tracking is on"),
            )
        }
        if (category.kind == "AGROCHEMICAL" && !isBatchTracked) {
            throw ApiException.Validation(
                "Agro-chemical products must be batch tracked so expiry can be enforced.",
                mapOf("isBatchTracked" to "must be true for agro-chemical categories"),
            )
        }

        val saved = products.save(
            Product(branchId = branchId, categoryId = categoryId, sku = normalisedSku, name = name.trim()).also {
                it.localName = localName?.trim()
                it.description = description?.trim()
                it.attributes = normalisedAttributes
                it.isBatchTracked = isBatchTracked
                it.pickingRule = rule
            }
        )
        saveUnits(saved.id!!, units)

        audit.recordCurrent(
            "PRODUCT_CREATED", "product", saved.id,
            after = """{"sku":"$normalisedSku","name":${quote(name)}}""",
        )
        return saved
    }

    @Transactional
    @PreAuthorize("hasAuthority('PRODUCT_MANAGE')")
    fun updateAttributes(productId: Long, attributes: Map<String, Any?>?): Product {
        val product = get(productId)
        val before = product.attributes
        product.attributes = attributeValidator.validateAndNormalise(product.categoryId, attributes)
        product.updatedAt = Instant.now()
        val saved = products.save(product)
        audit.recordCurrent("PRODUCT_ATTRIBUTES_CHANGED", "product", productId, before = before, after = saved.attributes)
        return saved
    }

    @Transactional
    @PreAuthorize("hasAuthority('PRODUCT_MANAGE')")
    fun setActive(productId: Long, active: Boolean) {
        val product = get(productId)
        product.isActive = active
        product.updatedAt = Instant.now()
        products.save(product)
        audit.recordCurrent(if (active) "PRODUCT_ACTIVATED" else "PRODUCT_DEACTIVATED", "product", productId)
    }

    /**
     * Corrects what a product is called.
     *
     * Names come in wrong. A delivery note is typed in as the supplier wrote
     * it, and the counter then spends a month calling the item something else
     * — and the local name is the one that moves most, because it is learned
     * at the counter rather than read off a carton. Search leans on both.
     *
     * The SKU is deliberately not editable here. A name is what people read;
     * the SKU is what shelf labels, order books and every export point at, so
     * re-coding an item is a different operation from correcting its name.
     * Reprints render today's catalogue, as they always have: an old receipt
     * reprinted after a correction shows the corrected name, and only a
     * reissue would carry a frozen one (§8.3).
     */
    @Transactional
    @PreAuthorize("hasAuthority('PRODUCT_MANAGE')")
    fun updateDetails(
        productId: Long,
        name: String,
        localName: String? = null,
        description: String? = null,
    ): Product {
        val product = get(productId)
        if (name.isBlank()) {
            throw ApiException.Validation("A product needs a name.", mapOf("name" to "is required"))
        }
        val before = nameJson(product)
        product.name = name.trim()
        product.localName = localName?.trim()?.takeIf(String::isNotEmpty)
        product.description = description?.trim()?.takeIf(String::isNotEmpty)
        product.updatedAt = Instant.now()
        val saved = products.save(product)
        audit.recordCurrent("PRODUCT_RENAMED", "product", productId, before = before, after = nameJson(saved))
        return saved
    }

    /**
     * Adds a unit to a product that is already selling.
     *
     * The carton arrives after the piece: a supplier changes pack size, or the
     * shop starts breaking bulk on something it used to sell singly. Without
     * this the only way to sell the new pack is to create a second product,
     * and then the same goods are counted twice on the shelf and in the
     * ledger.
     *
     * Never the base unit. The base is what every row in `stock_movement` is
     * counted in, so a second one — or a moved one — would restate every
     * balance in the shop with nothing on screen to show for it.
     */
    @Transactional
    @PreAuthorize("hasAuthority('PRODUCT_MANAGE')")
    fun addUnit(productId: Long, spec: ProductUomSpec): ProductUom {
        val product = get(productId)
        if (spec.isBase) {
            throw ApiException.Validation(
                "The base unit is fixed when the product is created — every quantity " +
                    "in the ledger is counted in it.",
                mapOf("isBase" to "must be false"),
            )
        }
        if (spec.factor <= BigDecimal.ZERO) {
            throw ApiException.Validation(
                "The factor must be greater than zero.",
                mapOf("factor" to "must be greater than zero"),
            )
        }
        val code = spec.uomCode.trim().uppercase()
        val uom = uoms.findByCode(code)
            ?: throw ApiException.Validation(
                "Unknown unit '$code'.",
                mapOf("uomCode" to "is not a unit of measure"),
            )
        if (productUoms.findByProductId(productId).any { it.uomId == uom.id }) {
            throw ApiException.Conflict("${product.name} already has a $code unit.")
        }

        val barcode = spec.barcode?.trim()?.takeIf(String::isNotEmpty)
        barcode?.let { requireBarcodeFree(it, null) }

        val saved = productUoms.save(
            ProductUom(productId = productId, uomId = uom.id!!, factor = spec.factor).also {
                it.sellable = spec.sellable
                it.purchasable = spec.purchasable
                it.barcode = barcode
            }
        )
        audit.recordCurrent(
            "PRODUCT_UNIT_ADDED", "product", productId,
            after = """{"uom":"$code","factor":"${spec.factor.toPlainString()}"}""",
        )
        return saved
    }

    /**
     * What may change about a unit that already exists: its barcode, and
     * whether it can be sold or bought in.
     *
     * The factor may not. Prices are quoted per unit, so re-sizing a carton
     * from 12 to 24 would halve the per-piece price of every price row written
     * against it without altering a single figure anybody could see. A pack
     * that changes size is a new unit: add it, and clear `sellable` on the old
     * one so the till stops offering it while the history still reads.
     *
     * Clearing the barcode is how a code is moved to the unit that now carries
     * it — the index allows one holder, and the message names the other one.
     */
    @Transactional
    @PreAuthorize("hasAuthority('PRODUCT_MANAGE')")
    fun updateUnit(
        productId: Long,
        unitId: Long,
        barcode: String?,
        sellable: Boolean,
        purchasable: Boolean,
    ): ProductUom {
        get(productId)
        val unit = productUoms.findById(unitId).orElseThrow { ApiException.NotFound("Unit", unitId) }
        if (unit.productId != productId) throw ApiException.NotFound("Unit", unitId)

        val code = barcode?.trim()?.takeIf(String::isNotEmpty)
        code?.let { requireBarcodeFree(it, unitId) }

        val before = unitJson(unit)
        unit.barcode = code
        unit.sellable = sellable
        unit.purchasable = purchasable
        val saved = productUoms.save(unit)
        audit.recordCurrent(
            "PRODUCT_UNIT_CHANGED", "product", productId,
            before = before, after = unitJson(saved),
        )
        return saved
    }

    /**
     * A barcode identifies one unit of one product or the scanner sells the
     * wrong thing. The unique index already refuses a second holder; this
     * turns the constraint violation into a sentence naming what holds it.
     */
    private fun requireBarcodeFree(barcode: String, allowUnitId: Long?) {
        val holder = productUoms.findByBarcode(barcode) ?: return
        if (holder.id == allowUnitId) return
        val owner = products.findById(holder.productId).orElse(null)
        throw ApiException.Conflict(
            "That barcode is already on ${owner?.name ?: "another product"}.",
        )
    }

    private fun nameJson(p: Product) =
        """{"name":${quote(p.name)},"localName":${p.localName?.let { quote(it) } ?: "null"}}"""

    private fun unitJson(u: ProductUom) =
        """{"barcode":${u.barcode?.let { quote(it) } ?: "null"},""" +
            """"sellable":${u.sellable},"purchasable":${u.purchasable}}"""

    /**
     * Writes the unit set for a product.
     *
     * Exactly one base unit with factor 1, and every other factor strictly
     * positive. The database enforces both (`one_base_uom_per_product`,
     * `base_factor_is_one`, `factor_positive`); the checks here exist so the
     * caller gets a sentence rather than a constraint name.
     */
    private fun saveUnits(productId: Long, units: List<ProductUomSpec>) {
        if (units.isEmpty()) {
            throw ApiException.Validation(
                "A product needs at least one unit.",
                mapOf("units" to "is required"),
            )
        }
        val bases = units.filter { it.isBase }
        if (bases.size != 1) {
            throw ApiException.Validation(
                "A product needs exactly one base unit — the one the ledger counts in.",
                mapOf("units" to if (bases.isEmpty()) "no base unit was marked" else "${bases.size} base units were marked"),
            )
        }
        if (bases.single().factor.compareTo(BigDecimal.ONE) != 0) {
            throw ApiException.Validation(
                "The base unit's factor must be exactly 1.",
                mapOf("units" to "base unit factor must be 1"),
            )
        }
        val duplicates = units.groupBy { it.uomCode.uppercase() }.filterValues { it.size > 1 }.keys
        if (duplicates.isNotEmpty()) {
            throw ApiException.Validation(
                "Each unit can only be listed once (repeated: ${duplicates.joinToString(", ")}).",
                mapOf("units" to "contains duplicates"),
            )
        }

        val resolved = uoms.findByCodeIn(units.map { it.uomCode.uppercase() }).associateBy { it.code }
        val unknown = units.map { it.uomCode.uppercase() }.toSet() - resolved.keys
        if (unknown.isNotEmpty()) {
            throw ApiException.Validation(
                "Unknown unit(s): ${unknown.sorted().joinToString(", ")}.",
                mapOf("units" to "contains an unknown unit of measure"),
            )
        }

        units.forEach { spec ->
            if (spec.factor <= BigDecimal.ZERO) {
                throw ApiException.Validation(
                    "The factor for ${spec.uomCode} must be greater than zero.",
                    mapOf("units" to "factor must be greater than zero"),
                )
            }
            productUoms.save(
                ProductUom(
                    productId = productId,
                    uomId = resolved.getValue(spec.uomCode.uppercase()).id!!,
                    factor = spec.factor,
                ).also {
                    it.isBase = spec.isBase
                    it.sellable = spec.sellable
                    it.purchasable = spec.purchasable
                    it.barcode = spec.barcode?.trim()?.takeIf(String::isNotEmpty)
                }
            )
        }
    }

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
