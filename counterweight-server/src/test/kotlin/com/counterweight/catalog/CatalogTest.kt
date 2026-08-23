package com.counterweight.catalog

import com.counterweight.catalog.domain.Product
import com.counterweight.catalog.repo.CategoryAttributeRepository
import com.counterweight.catalog.repo.CategoryRepository
import com.counterweight.catalog.service.CategoryService
import com.counterweight.catalog.service.ProductService
import com.counterweight.catalog.service.ProductUomSpec
import com.counterweight.common.ApiException
import com.counterweight.identity.repo.AppUserRepository
import com.counterweight.identity.security.CurrentUser
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicInteger

/**
 * The catalogue module: how the category tree is read, and what a shop is
 * allowed to change about a product that is already selling.
 */
@SpringBootTest
@Testcontainers
@DisplayName("Catalogue")
class CatalogTest {

    companion object {
        @Container
        @JvmStatic
        val pg = PostgreSQLContainer("postgres:16")
            .withDatabaseName("cw").withUsername("cw").withPassword("cw")

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", pg::getJdbcUrl)
            registry.add("spring.datasource.username", pg::getUsername)
            registry.add("spring.datasource.password", pg::getPassword)
            registry.add("counterweight.auth.jwt-secret") { "test-secret-that-is-definitely-long-enough-32b+" }
            registry.add("COUNTERWEIGHT_BOOTSTRAP_PASSWORD") { "bootstrap-correct-horse-staple-42" }
        }

        private val seq = AtomicInteger()
    }

    @Autowired private lateinit var categories: CategoryRepository
    @Autowired private lateinit var categoryAttributes: CategoryAttributeRepository
    @Autowired private lateinit var categoryService: CategoryService
    @Autowired private lateinit var productService: ProductService
    @Autowired private lateinit var users: AppUserRepository

    /**
     * Catalogue work is gated on PRODUCT_MANAGE, so the tests hold it and
     * nothing else — a storekeeper's rights, not an owner's. That is the
     * account the shop actually files new stock under.
     */
    @BeforeEach
    fun signIn() {
        val permissions = setOf("PRODUCT_MANAGE")
        SecurityContextHolder.getContext().authentication = PreAuthenticatedAuthenticationToken(
            CurrentUser(users.findAll().first().id!!, "test-storekeeper", 1L, setOf("STOREKEEPER"), permissions),
            null,
            permissions.map { SimpleGrantedAuthority(it) },
        )
    }

    // ── The ltree path ─────────────────────────────────────────────────────
    //
    // `Category.path` is a `@Formula("path::text")` rather than a mapped
    // column, because `ltree` reports as `Types#OTHER` over JDBC and
    // `ddl-auto: validate` refuses to start against a String property. That fix
    // has a failure mode worth pinning down: a formula is a SQL fragment
    // Hibernate splices into the SELECT it builds, and the repository also
    // reads categories through **native** queries where it cannot do that. If
    // formulas and native queries did not get along, `path` would come back
    // null from exactly the subtree queries the catalogue depends on — and
    // nothing else would fail.
    //
    // So both read paths are exercised here, and both assert on the value.

    @Test
    @DisplayName("a path is readable through the JPA select")
    fun pathReadableThroughJpa() {
        val roots = categories.findByParentIdOrderBySortOrderAscNameAsc(null)
        assertThat(roots).isNotEmpty
        assertThat(roots).allSatisfy { assertThat(it.path).isNotBlank() }
        // V3 re-derives the seeded roots through the trigger, so the path is the
        // lower-cased code rather than anything hand-written.
        assertThat(roots.map { it.path }).contains(roots.first().code.lowercase())
    }

    @Test
    @DisplayName("a path is readable through the native subtree query too")
    fun pathReadableThroughNativeQuery() {
        val root = categories.findByParentIdOrderBySortOrderAscNameAsc(null).first()
        val child = categories.save(
            com.counterweight.catalog.domain.Category(code = "PATHTEST", name = "Path test")
                .also { it.parentId = root.id }
        )
        categories.flush()

        val subtree = categories.findSubtree(root.id!!)

        assertThat(subtree.map { it.id }).contains(child.id)
        assertThat(subtree)
            .describedAs("a null path here means the formula did not survive the native query")
            .allSatisfy { assertThat(it.path).isNotBlank() }
        assertThat(subtree.single { it.id == child.id }.path)
            .isEqualTo("${root.path}.pathtest")
    }

    @Test
    @DisplayName("attributes are inherited down the path")
    fun attributesInheritDownThePath() {
        val root = categories.findByParentIdOrderBySortOrderAscNameAsc(null)
            .first { categoryAttributes.findByCategoryIdOrderBySortOrderAsc(it.id!!).isNotEmpty() }
        val declaredOnRoot = categoryAttributes.findByCategoryIdOrderBySortOrderAsc(root.id!!)

        val child = categories.save(
            com.counterweight.catalog.domain.Category(code = "INHERITTEST", name = "Inherit test")
                .also { it.parentId = root.id }
        )
        categories.flush()

        val inherited = categoryAttributes.findInheritedForCategory(child.id!!)
        assertThat(inherited.map { it.key })
            .describedAs("the ancestor operator is what makes adding a category data entry, not a migration")
            .containsAll(declaredOnRoot.map { it.key })
    }

    @Test
    @DisplayName("depth is reported from the path, for the nesting guard")
    fun depthComesFromThePath() {
        val root = categories.findByParentIdOrderBySortOrderAscNameAsc(null).first()
        assertThat(categories.depthOf(root.id!!)).isEqualTo(1)
    }

    // ── Maintenance ────────────────────────────────────────────────────────
    //
    // A product is created once and corrected for years. What can be corrected
    // and what cannot is the whole subject: a name is a label people read, a
    // factor is arithmetic every price written against it depends on.

    @Test
    @DisplayName("a name can be corrected; the SKU it is known by does not move")
    fun detailsAreCorrectable() {
        val product = hardwareProduct("Padlok 50m")

        val corrected = productService.updateDetails(
            product.id!!, "Padlock 50mm", localName = "Kanda", description = "Brass shackle",
        )

        assertThat(corrected.name).isEqualTo("Padlock 50mm")
        assertThat(corrected.localName).isEqualTo("Kanda")
        assertThat(corrected.sku)
            .describedAs("shelf labels and order books point at the SKU; correcting a name must not move it")
            .isEqualTo(product.sku)
    }

    @Test
    @DisplayName("a blank local name clears rather than stores an empty string")
    fun blankLocalNameClears() {
        val product = hardwareProduct(localName = "Kanda")

        val corrected = productService.updateDetails(product.id!!, product.name, localName = "  ")

        assertThat(corrected.localName)
            .describedAs("search treats null as absent; an empty string is a value that matches nothing")
            .isNull()
    }

    @Test
    @DisplayName("the carton that turned up later can be added to a selling product")
    fun aUnitCanBeAddedAfterwards() {
        val product = hardwareProduct()

        productService.addUnit(product.id!!, ProductUomSpec("CARTON", BigDecimal("12"), isBase = false))

        val units = productService.unitsWithMeasureOf(product.id!!)
        assertThat(units.map { it.uomCode }).containsExactlyInAnyOrder("PCS", "CARTON")
        assertThat(units.single { it.uomCode == "CARTON" }.unit.factor).isEqualByComparingTo("12")
        assertThat(units.single { it.unit.isBase }.uomCode)
            .describedAs("the ledger still counts in what it always counted in")
            .isEqualTo("PCS")
    }

    @Test
    @DisplayName("a second base unit is refused — every balance is counted in the first")
    fun theBaseUnitCannotBeMoved() {
        val product = hardwareProduct()

        assertThatThrownBy {
            productService.addUnit(product.id!!, ProductUomSpec("CARTON", BigDecimal.ONE, isBase = true))
        }
            .isInstanceOf(ApiException::class.java)
            .hasMessageContaining("base unit is fixed")
    }

    @Test
    @DisplayName("the same unit of measure cannot be added twice")
    fun aUnitOfMeasureIsAddedOnce() {
        val product = hardwareProduct()
        productService.addUnit(product.id!!, ProductUomSpec("CARTON", BigDecimal("12"), isBase = false))

        assertThatThrownBy {
            productService.addUnit(product.id!!, ProductUomSpec("CARTON", BigDecimal("24"), isBase = false))
        }
            .isInstanceOf(ApiException.Conflict::class.java)
            .describedAs("a re-sized pack is a new unit, not a second row for the same one")
            .hasMessageContaining("already has a CARTON unit")
    }

    @Test
    @DisplayName("a barcode is on one unit of one product, and the refusal names the other")
    fun aBarcodeHasOneHolder() {
        val first = hardwareProduct("Torch battery")
        val second = hardwareProduct("Wall socket")
        val firstUnit = baseUnitId(first.id!!)
        productService.updateUnit(first.id!!, firstUnit, "6009800000000", sellable = true, purchasable = true)

        assertThatThrownBy {
            productService.updateUnit(
                second.id!!, baseUnitId(second.id!!),
                "6009800000000", sellable = true, purchasable = true,
            )
        }
            .isInstanceOf(ApiException.Conflict::class.java)
            .hasMessageContaining("Torch battery")
    }

    @Test
    @DisplayName("clearing a barcode frees it for the unit that now carries it")
    fun aBarcodeCanBeMoved() {
        val first = hardwareProduct()
        val second = hardwareProduct()
        val firstUnit = baseUnitId(first.id!!)
        productService.updateUnit(first.id!!, firstUnit, "6009811111111", sellable = true, purchasable = true)

        productService.updateUnit(first.id!!, firstUnit, null, sellable = true, purchasable = true)
        productService.updateUnit(
            second.id!!, baseUnitId(second.id!!),
            "6009811111111", sellable = true, purchasable = true,
        )

        assertThat(productService.byBarcode("6009811111111")?.first?.id).isEqualTo(second.id)
    }

    @Test
    @DisplayName("retiring a pack leaves its factor alone")
    fun maintenanceNeverTouchesTheFactor() {
        val product = hardwareProduct()
        productService.addUnit(product.id!!, ProductUomSpec("CARTON", BigDecimal("12"), isBase = false))
        val carton = productService.unitsWithMeasureOf(product.id!!).single { it.uomCode == "CARTON" }

        productService.updateUnit(product.id!!, carton.unit.id!!, null, sellable = false, purchasable = true)

        val after = productService.unitsWithMeasureOf(product.id!!).single { it.uomCode == "CARTON" }
        assertThat(after.unit.sellable).isFalse()
        assertThat(after.unit.factor)
            .describedAs("every price quoted per carton is arithmetic on this number")
            .isEqualByComparingTo("12")
    }

    @Test
    @DisplayName("a unit belonging to another product is not found")
    fun unitsAreScopedToTheirProduct() {
        val first = hardwareProduct()
        val second = hardwareProduct()

        assertThatThrownBy {
            productService.updateUnit(
                second.id!!, baseUnitId(first.id!!),
                null, sellable = true, purchasable = true,
            )
        }.isInstanceOf(ApiException.NotFound::class.java)
    }

    // ── Fixtures ───────────────────────────────────────────────────────────

    private fun hardwareProduct(name: String = "Test item", localName: String? = null): Product {
        val n = seq.incrementAndGet()
        return productService.create(
            sku = "CAT-$n",
            name = name,
            categoryId = categories.findByCode("HARDWARE")!!.id!!,
            units = listOf(ProductUomSpec("PCS", BigDecimal.ONE, isBase = true)),
            localName = localName,
        )
    }

    /** The id of the fixture's only unit — every product here starts with the base alone. */
    private fun baseUnitId(productId: Long): Long =
        productService.unitsWithMeasureOf(productId).single { it.unit.isBase }.unit.id!!

    /**
     * The kind of a category is inherited, not offered as a free choice.
     *
     * Attributes already inherit down the path, so a GENERAL category under
     * the agro root would ask its products for an EPA registration and a
     * hazard band and then let them be sold with no expiry to enforce —
     * `ProductService.create` reads the product's own category to decide that.
     * The licence fields present and the control they exist for absent is the
     * same shape as a permission granted and never read.
     */
    @Test
    @DisplayName("a category under an agro-chemical one cannot be general")
    fun kindIsInheritedUnderAgro() {
        val agro = categories.findByCode("AGRO")!!
        val n = seq.incrementAndGet()

        assertThatThrownBy {
            categoryService.create("SPRAYERS_$n", "Sprayers", agro.id, "GENERAL")
        }
            .isInstanceOf(ApiException.Validation::class.java)
            .hasMessageContaining("agro-chemical")

        // The rule is about what a category inherits, not about the word: the
        // same category under the hardware root is exactly what it says.
        val hardware = categories.findByCode("HARDWARE")!!
        assertThat(categoryService.create("SPRAYERS_${n}_B", "Sprayers", hardware.id, "GENERAL").kind)
            .isEqualTo("GENERAL")
    }
}
