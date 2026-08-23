package com.counterweight.catalog.web

import com.counterweight.catalog.domain.*
import com.counterweight.catalog.service.*
import com.counterweight.common.ApiException
import com.counterweight.common.SafeText
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.validation.Valid
import jakarta.validation.constraints.*
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal

// ── Requests ───────────────────────────────────────────────────────────────

data class CreateCategoryRequest(
    @field:NotBlank(message = "is required")
    @field:Pattern(
        regexp = "^[A-Za-z0-9_]{1,60}$",
        message = "may use letters, digits and underscore only",
    )
    val code: String,

    @field:NotBlank(message = "is required")
    @field:Size(max = 80, message = "is too long")
    @field:SafeText
    val name: String,

    val parentId: Long? = null,

    @field:Pattern(regexp = "^(GENERAL|AGROCHEMICAL)$", message = "must be GENERAL or AGROCHEMICAL")
    val kind: String = "GENERAL",
)

data class AddAttributeRequest(
    @field:NotBlank(message = "is required")
    @field:Pattern(
        regexp = "^[a-z][a-z0-9_]{1,39}$",
        message = "must be lower_snake_case and start with a letter",
    )
    val key: String,

    @field:NotBlank(message = "is required")
    @field:Size(max = 60, message = "is too long")
    @field:SafeText
    val label: String,

    @field:Pattern(regexp = "^(TEXT|NUMBER|BOOL|DATE|ENUM)$", message = "must be TEXT, NUMBER, BOOL, DATE or ENUM")
    val dataType: String,

    @field:Size(max = 40, message = "is too many options")
    val enumValues: List<@NotBlank @Size(max = 40) String>? = null,

    @field:Size(max = 20, message = "is too long")
    val unit: String? = null,

    val required: Boolean = false,
)

data class ProductUomRequest(
    @field:NotBlank(message = "is required")
    @field:Size(max = 20, message = "is too long")
    val uomCode: String,

    @field:NotNull(message = "is required")
    @field:DecimalMin(value = "0.000001", message = "must be greater than zero")
    @field:Digits(integer = 10, fraction = 6, message = "has too many digits")
    val factor: BigDecimal,

    val isBase: Boolean = false,
    val sellable: Boolean = true,
    val purchasable: Boolean = true,

    @field:Size(max = 60, message = "is too long")
    @field:Pattern(regexp = "^[A-Za-z0-9._-]*$", message = "may use letters, digits, dot, underscore and hyphen only")
    val barcode: String? = null,
)

data class CreateProductRequest(
    @field:NotBlank(message = "is required")
    @field:Size(max = 40, message = "is too long")
    @field:Pattern(regexp = "^[A-Za-z0-9._/-]+$", message = "may use letters, digits and . _ / - only")
    val sku: String,

    @field:NotBlank(message = "is required")
    @field:Size(max = 120, message = "is too long")
    @field:SafeText
    val name: String,

    @field:NotNull(message = "is required")
    @field:Positive(message = "must be a valid category")
    val categoryId: Long,

    @field:NotEmpty(message = "at least one unit is required")
    @field:Size(max = 10, message = "is too many units")
    @field:Valid
    val units: List<ProductUomRequest>,

    @field:Size(max = 120, message = "is too long")
    @field:SafeText
    val localName: String? = null,

    @field:Size(max = 1000, message = "is too long")
    @field:SafeText
    val description: String? = null,

    /** Free-form per-category fields — validated by AttributeValidator. */
    val attributes: Map<String, Any?>? = null,

    val isBatchTracked: Boolean = false,

    @field:Pattern(regexp = "^(FIFO|FEFO|MANUAL)$", message = "must be FIFO, FEFO or MANUAL")
    val pickingRule: String? = null,
)

data class UpdateAttributesRequest(val attributes: Map<String, Any?>? = null)

/**
 * Correcting what a product is called.
 *
 * The SKU is not here on purpose — see `ProductService.updateDetails`.
 * `description` is sent whole rather than patched, so the screen has to load
 * the current one first; `ProductView` carries it for exactly that reason.
 */
data class UpdateProductRequest(
    @field:NotBlank(message = "is required")
    @field:Size(max = 120, message = "is too long")
    @field:SafeText
    val name: String,

    @field:Size(max = 120, message = "is too long")
    @field:SafeText
    val localName: String? = null,

    @field:Size(max = 1000, message = "is too long")
    @field:SafeText
    val description: String? = null,
)

/** Barcode, and whether the unit may still be sold or bought. Not the factor. */
data class UpdateUnitRequest(
    @field:Size(max = 60, message = "is too long")
    @field:Pattern(regexp = "^[A-Za-z0-9._-]*$", message = "may use letters, digits, dot, underscore and hyphen only")
    val barcode: String? = null,

    val sellable: Boolean = true,
    val purchasable: Boolean = true,
)

/**
 * What a scan resolves to.
 *
 * Carries the unit, not just the product, because the barcode identifies one —
 * scanning the carton and scanning the piece must not add the same line.
 */
data class ScannedProductView(
    val productId: Long,
    val productUomId: Long,
    val sku: String,
    val name: String,
    val localName: String?,
    val uomId: Long,
    val isBatchTracked: Boolean,
)

// ── Responses ──────────────────────────────────────────────────────────────

data class CategoryView(
    val id: Long, val code: String, val name: String,
    val parentId: Long?, val path: String?, val kind: String, val isActive: Boolean,
)

data class AttributeView(
    val key: String, val label: String, val dataType: String,
    val enumValues: List<String>?, val unit: String?, val required: Boolean,
    /** Which category declared it — a field may be inherited from an ancestor. */
    val declaredOnCategoryId: Long,
)

/**
 * `uomCode` and `decimals` are here because a numeric `uomId` tells a client
 * nothing it can show or validate against. Anywhere a person picks a unit — the
 * goods receipt above all, where carton versus piece is the difference between
 * a correct lot cost and one out by a factor of twenty — needs both.
 */
data class ProductUomView(
    val id: Long, val uomId: Long, val uomCode: String, val uomName: String,
    val decimals: Int, val factor: BigDecimal,
    val isBase: Boolean, val sellable: Boolean, val purchasable: Boolean, val barcode: String?,
)

data class ProductView(
    val id: Long, val sku: String, val name: String, val localName: String?,
    /** Carried so an edit form can send it back unchanged rather than erase it. */
    val description: String?,
    val categoryId: Long, val attributes: Map<String, Any?>,
    val isBatchTracked: Boolean, val pickingRule: String, val isActive: Boolean,
)

/** A unit of measure as the catalogue offers it — what a new unit is built from. */
data class UomView(val id: Long, val code: String, val name: String, val decimals: Int)

private fun Category.toView() = CategoryView(id!!, code, name, parentId, path, kind, isActive)
private fun CategoryAttribute.toView() =
    AttributeView(key, label, dataType, enumValues?.toList(), unit, required, categoryId)
private fun Uom.toView() = UomView(id!!, code, name, decimals.toInt())
private fun ResolvedUnit.toView() = ProductUomView(
    unit.id!!, unit.uomId, uomCode, uomName, decimals, unit.factor,
    unit.isBase, unit.sellable, unit.purchasable, unit.barcode,
)

// ── Controllers ────────────────────────────────────────────────────────────

@RestController
@RequestMapping("/api/categories")
class CategoryController(private val categories: CategoryService) {

    @GetMapping
    fun list(): List<CategoryView> = categories.all().map { it.toView() }

    @GetMapping("/{id}")
    fun get(@PathVariable id: Long): CategoryView = categories.get(id).toView()

    /** Every field a product in this category must or may carry, inherited included. */
    @GetMapping("/{id}/attributes")
    fun attributes(@PathVariable id: Long): List<AttributeView> =
        categories.attributesFor(id).map { it.toView() }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody body: CreateCategoryRequest): CategoryView =
        categories.create(body.code, body.name, body.parentId, body.kind).toView()

    @PostMapping("/{id}/attributes")
    @ResponseStatus(HttpStatus.CREATED)
    fun addAttribute(@PathVariable id: Long, @Valid @RequestBody body: AddAttributeRequest): AttributeView =
        categories.addAttribute(
            id, body.key, body.label, body.dataType, body.enumValues, body.unit, body.required,
        ).toView()
}

@RestController
@RequestMapping("/api/products")
class ProductController(
    private val productService: ProductService,
    private val json: ObjectMapper,
) {

    private fun Product.toView(): ProductView {
        @Suppress("UNCHECKED_CAST")
        val attrs = json.readValue(attributes, Map::class.java) as Map<String, Any?>
        return ProductView(
            id!!, sku, name, localName, description, categoryId, attrs,
            isBatchTracked, pickingRule, isActive,
        )
    }

    /** Counter search — forgiving, because most stock here has no barcode. */
    @GetMapping("/search")
    fun search(
        @RequestParam q: String,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) limit: Int,
    ): List<ProductView> = productService.search(q, limit).map { it.toView() }

    @GetMapping("/{id}")
    fun get(@PathVariable id: Long): ProductView = productService.get(id).toView()

    @GetMapping("/{id}/units")
    fun units(@PathVariable id: Long): List<ProductUomView> =
        productService.unitsWithMeasureOf(id).map { it.toView() }

    /**
     * Resolve a scanned barcode.
     *
     * 404 when nothing matches, which the till treats as "fall back to search"
     * rather than as an error — most stock here has no barcode, so a miss is
     * the ordinary case.
     */
    @GetMapping("/by-barcode/{barcode}")
    fun byBarcode(@PathVariable barcode: String): ScannedProductView {
        val (product, unit) = productService.byBarcode(barcode)
            ?: throw ApiException.NotFound("Barcode", barcode)
        return ScannedProductView(
            productId = product.id!!,
            productUomId = unit.id!!,
            sku = product.sku,
            name = product.name,
            localName = product.localName,
            uomId = unit.uomId,
            isBatchTracked = product.isBatchTracked,
        )
    }

    @GetMapping
    fun inCategory(
        @RequestParam categoryId: Long,
        @RequestParam(defaultValue = "true") activeOnly: Boolean,
    ): List<ProductView> = productService.inCategory(categoryId, activeOnly).map { it.toView() }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody body: CreateProductRequest): ProductView =
        productService.create(
            sku = body.sku,
            name = body.name,
            categoryId = body.categoryId,
            units = body.units.map {
                ProductUomSpec(it.uomCode, it.factor, it.isBase, it.sellable, it.purchasable, it.barcode)
            },
            localName = body.localName,
            description = body.description,
            attributes = body.attributes,
            isBatchTracked = body.isBatchTracked,
            pickingRule = body.pickingRule,
        ).toView()

    @PutMapping("/{id}/attributes")
    fun updateAttributes(
        @PathVariable id: Long,
        @Valid @RequestBody body: UpdateAttributesRequest,
    ): ProductView = productService.updateAttributes(id, body.attributes).toView()

    @PutMapping("/{id}/active")
    fun setActive(@PathVariable id: Long, @RequestParam active: Boolean) {
        productService.setActive(id, active)
    }

    /** Correcting a name. See `ProductService.updateDetails` for what is not here. */
    @PutMapping("/{id}")
    fun update(
        @PathVariable id: Long,
        @Valid @RequestBody body: UpdateProductRequest,
    ): ProductView = productService
        .updateDetails(id, body.name, body.localName, body.description)
        .toView()

    /**
     * A unit the product did not have when it was created — the carton that
     * turned up after the piece.
     *
     * Takes the full unit shape rather than one without `isBase`, so a caller
     * that tries to move the base unit is told why it cannot be done instead
     * of having the field quietly dropped.
     */
    @PostMapping("/{id}/units")
    @ResponseStatus(HttpStatus.CREATED)
    fun addUnit(
        @PathVariable id: Long,
        @Valid @RequestBody body: ProductUomRequest,
    ): ProductUomView {
        val added = productService.addUnit(
            id,
            ProductUomSpec(body.uomCode, body.factor, body.isBase, body.sellable, body.purchasable, body.barcode),
        )
        return productService.unitsWithMeasureOf(id).first { it.unit.id == added.id }.toView()
    }

    @PutMapping("/{id}/units/{unitId}")
    fun updateUnit(
        @PathVariable id: Long,
        @PathVariable unitId: Long,
        @Valid @RequestBody body: UpdateUnitRequest,
    ): ProductUomView {
        productService.updateUnit(id, unitId, body.barcode, body.sellable, body.purchasable)
        return productService.unitsWithMeasureOf(id).first { it.unit.id == unitId }.toView()
    }
}

/**
 * The units of measure a product can be built out of.
 *
 * Gated on PRODUCT_MANAGE rather than left open: the only screen that needs
 * the whole list is the one adding a unit to a product, and every other caller
 * asks a product what its own units are.
 */
@RestController
@RequestMapping("/api/uoms")
class UomController(private val productService: ProductService) {

    @GetMapping
    @PreAuthorize("hasAuthority('PRODUCT_MANAGE')")
    fun list(): List<UomView> = productService.unitsOfMeasure().map { it.toView() }
}
