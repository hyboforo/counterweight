package com.counterweight.catalog.repo

import com.counterweight.catalog.domain.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface CategoryRepository : JpaRepository<Category, Long> {

    fun findByCode(code: String): Category?
    fun existsByCode(code: String): Boolean
    fun findByParentIdOrderBySortOrderAscNameAsc(parentId: Long?): List<Category>

    /**
     * Every category at or below [categoryId], using the ltree ancestor
     * operator. Native because `<@` has no JPQL equivalent.
     */
    @Query(
        value = """
            SELECT c.* FROM category c
             WHERE c.path <@ (SELECT path FROM category WHERE id = :categoryId)
             ORDER BY c.path
        """,
        nativeQuery = true,
    )
    fun findSubtree(@Param("categoryId") categoryId: Long): List<Category>

    @Query(value = "SELECT nlevel(path) FROM category WHERE id = :id", nativeQuery = true)
    fun depthOf(@Param("id") id: Long): Int?

    /**
     * The category occupying an exact path, for turning a stored scope back
     * into a name somebody recognises. Native and cast explicitly because the
     * property is a formula over `path::text` and cannot be compared to `ltree`
     * without one.
     */
    @Query(value = "SELECT c.* FROM category c WHERE c.path = CAST(:path AS ltree)", nativeQuery = true)
    fun findByPath(@Param("path") path: String): Category?
}

@Repository
interface CategoryAttributeRepository : JpaRepository<CategoryAttribute, Long> {

    fun findByCategoryIdOrderBySortOrderAsc(categoryId: Long): List<CategoryAttribute>

    /**
     * Attributes declared on this category **or any ancestor of it**.
     *
     * `@>` reads "is an ancestor of (or equal to)". This is the inheritance
     * described in §6.1: the EPA number and hazard band are declared once on
     * `agro` and every descendant picks them up without re-declaring anything.
     *
     * Ordered by depth so a child's declaration of the same key wins — see the
     * DISTINCT ON, which keeps the deepest row per key.
     */
    @Query(
        value = """
            SELECT DISTINCT ON (ca.key) ca.*
              FROM category_attribute ca
              JOIN category anc ON anc.id = ca.category_id
             WHERE anc.path @> (SELECT path FROM category WHERE id = :categoryId)
             ORDER BY ca.key, nlevel(anc.path) DESC
        """,
        nativeQuery = true,
    )
    fun findInheritedForCategory(@Param("categoryId") categoryId: Long): List<CategoryAttribute>

    fun existsByCategoryIdAndKey(categoryId: Long, key: String): Boolean
}

@Repository
interface UomRepository : JpaRepository<Uom, Long> {
    fun findByCode(code: String): Uom?
    fun findByCodeIn(codes: Collection<String>): List<Uom>
}

@Repository
interface ProductRepository : JpaRepository<Product, Long> {

    fun findBySkuAndBranchId(sku: String, branchId: Long): Product?
    fun existsBySkuAndBranchId(sku: String, branchId: Long): Boolean

    /**
     * Counter search. Most stock in this shop has no barcode, so this has to be
     * fast and forgiving: trigram similarity across name, local name and SKU,
     * best match first.
     *
     * `%` is the pg_trgm similarity operator; the GIN indexes from V1 back it.
     */
    @Query(
        value = """
            SELECT p.* FROM product p
             WHERE p.branch_id = :branchId
               AND p.is_active
               AND (
                    p.name ILIKE '%' || :q || '%'
                 OR p.local_name ILIKE '%' || :q || '%'
                 OR p.sku ILIKE :q || '%'
                 OR p.name % :q
                 OR p.local_name % :q
               )
             ORDER BY GREATEST(
                        similarity(p.name, :q),
                        similarity(coalesce(p.local_name, ''), :q)
                      ) DESC,
                      p.name
             LIMIT :limit
        """,
        nativeQuery = true,
    )
    fun search(
        @Param("branchId") branchId: Long,
        @Param("q") query: String,
        @Param("limit") limit: Int,
    ): List<Product>

    @Query(
        value = """
            SELECT p.* FROM product p
             WHERE p.branch_id = :branchId
               AND p.category_id IN (
                    SELECT id FROM category
                     WHERE path <@ (SELECT path FROM category WHERE id = :categoryId)
               )
               AND (:activeOnly = false OR p.is_active)
             ORDER BY p.name
        """,
        nativeQuery = true,
    )
    fun findInCategorySubtree(
        @Param("branchId") branchId: Long,
        @Param("categoryId") categoryId: Long,
        @Param("activeOnly") activeOnly: Boolean,
    ): List<Product>

    fun countByCategoryId(categoryId: Long): Long
}

@Repository
interface ProductUomRepository : JpaRepository<ProductUom, Long> {
    fun findByProductId(productId: Long): List<ProductUom>
    fun findByProductIdAndIsBaseTrue(productId: Long): ProductUom?
    fun findByBarcode(barcode: String): ProductUom?
    fun existsByProductIdAndUomId(productId: Long, uomId: Long): Boolean
}
