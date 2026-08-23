package com.counterweight.pricing.repo

import com.counterweight.pricing.domain.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDate

@Repository
interface PriceListRepository : JpaRepository<PriceList, Long> {
    fun findByBranchIdOrderByCodeAsc(branchId: Long): List<PriceList>
    fun findByBranchIdAndCode(branchId: Long, code: String): PriceList?
    fun findByBranchIdAndIsDefaultTrue(branchId: Long): PriceList?
    fun existsByBranchIdAndCode(branchId: Long, code: String): Boolean
}

/** A sellable unit with whatever price currently applies to it, if any. */
interface UnitPrice {
    val productUomId: Long
    val uomCode: String
    val factor: BigDecimal
    val unitPrice: BigDecimal?
    val priceListId: Long?
}

@Repository
interface PriceRepository : JpaRepository<Price, Long> {

    /**
     * The price to charge, preferring the customer's list and falling back to
     * the branch default.
     *
     * The fallback is the point: a trade list carries the handful of lines that
     * differ, not a copy of the whole catalogue, so most lookups against it
     * legitimately miss and must land on the default rather than fail.
     *
     * Preference is expressed in the ORDER BY rather than as two round trips,
     * because this runs on the sale path for every line the counter scans.
     */
    @Query(
        value = """
            SELECT p.* FROM price p
             WHERE p.product_id = :productId
               AND p.product_uom_id = :productUomId
               AND p.effective_from <= :onDate
               AND (p.effective_to IS NULL OR p.effective_to >= :onDate)
               AND p.price_list_id IN (:preferredListId, :defaultListId)
             ORDER BY CASE WHEN p.price_list_id = :preferredListId THEN 0 ELSE 1 END,
                      p.effective_from DESC,
                      p.id DESC
             LIMIT 1
        """,
        nativeQuery = true,
    )
    fun findEffective(
        @Param("productId") productId: Long,
        @Param("productUomId") productUomId: Long,
        @Param("preferredListId") preferredListId: Long,
        @Param("defaultListId") defaultListId: Long,
        @Param("onDate") onDate: LocalDate,
    ): Price?

    /**
     * Every sellable unit of a product with its price, priced and unpriced
     * alike.
     *
     * The outer join is deliberate: a unit with no price still has to appear at
     * the counter, greyed out. Omitting it looks like the unit does not exist,
     * and somebody re-creates it.
     */
    @Query(
        value = """
            SELECT pu.id            AS "productUomId",
                   u.code           AS "uomCode",
                   pu.factor        AS "factor",
                   pr.unit_price    AS "unitPrice",
                   pr.price_list_id AS "priceListId"
              FROM product_uom pu
              JOIN uom u ON u.id = pu.uom_id
              LEFT JOIN LATERAL (
                   SELECT p.unit_price, p.price_list_id
                     FROM price p
                    WHERE p.product_uom_id = pu.id
                      AND p.effective_from <= :onDate
                      AND (p.effective_to IS NULL OR p.effective_to >= :onDate)
                      AND p.price_list_id IN (:preferredListId, :defaultListId)
                    ORDER BY CASE WHEN p.price_list_id = :preferredListId THEN 0 ELSE 1 END,
                             p.effective_from DESC, p.id DESC
                    LIMIT 1
              ) pr ON TRUE
             WHERE pu.product_id = :productId
               AND pu.sellable
             ORDER BY pu.factor
        """,
        nativeQuery = true,
    )
    fun findUnitPrices(
        @Param("productId") productId: Long,
        @Param("preferredListId") preferredListId: Long,
        @Param("defaultListId") defaultListId: Long,
        @Param("onDate") onDate: LocalDate,
    ): List<UnitPrice>

    /** The open-ended row, if there is one. `one_open_price` allows at most one. */
    fun findByPriceListIdAndProductIdAndProductUomIdAndEffectiveToIsNull(
        priceListId: Long,
        productId: Long,
        productUomId: Long,
    ): Price?

    fun findByPriceListIdAndProductIdOrderByEffectiveFromDescIdDesc(
        priceListId: Long,
        productId: Long,
    ): List<Price>

    /**
     * Rows whose window would still be open on or after [from].
     *
     * The database constrains the open row and the window's own ordering, but
     * nothing stops two closed windows overlapping. Without this check a
     * back-dated price could leave two rows valid on the same day, and which
     * one won would come down to an ORDER BY tie-break.
     */
    @Query(
        value = """
            SELECT p.* FROM price p
             WHERE p.price_list_id = :priceListId
               AND p.product_id = :productId
               AND p.product_uom_id = :productUomId
               AND (p.effective_to IS NULL OR p.effective_to >= :from)
             ORDER BY p.effective_from
        """,
        nativeQuery = true,
    )
    fun findOpenOnOrAfter(
        @Param("priceListId") priceListId: Long,
        @Param("productId") productId: Long,
        @Param("productUomId") productUomId: Long,
        @Param("from") from: LocalDate,
    ): List<Price>
}

@Repository
interface DiscountPolicyRepository : JpaRepository<DiscountPolicy, Long> {

    /**
     * The policies attached to a set of role codes.
     *
     * Keyed by code rather than id because the code is what the token carries;
     * the alternative is a role lookup on every discount check at the till.
     */
    @Query(
        value = """
            SELECT dp.* FROM discount_policy dp
              JOIN role r ON r.id = dp.role_id
             WHERE r.code IN (:roleCodes)
        """,
        nativeQuery = true,
    )
    fun findByRoleCodes(@Param("roleCodes") roleCodes: Collection<String>): List<DiscountPolicy>

    fun findByRoleId(roleId: Long): DiscountPolicy?
}

/** A component and the rate in force on the date asked about. */
interface EffectiveTaxComponent {
    val componentId: Long
    val code: String
    val name: String
    val computedOn: String
    val isRecoverable: Boolean
    val sortOrder: Int
    val ratePercent: BigDecimal
}

@Repository
interface TaxSchemeRepository : JpaRepository<TaxScheme, Long> {
    fun findByIsActiveTrue(): List<TaxScheme>
    fun findByCode(code: String): TaxScheme?
}

@Repository
interface TaxComponentRepository : JpaRepository<TaxComponent, Long> {
    fun findByTaxSchemeIdOrderBySortOrderAscIdAsc(taxSchemeId: Long): List<TaxComponent>
}

@Repository
interface TaxRateRepository : JpaRepository<TaxRate, Long> {

    /**
     * Every component of a scheme with its rate on [onDate], in application
     * order.
     *
     * DISTINCT ON keeps the latest window per component, which forces the inner
     * query to order by component id first; the outer one restores the
     * sort_order the calculation actually depends on.
     *
     * A component with no rate covering the date drops out here rather than
     * defaulting to zero. That is a configuration gap, and TaxCalculator says
     * so instead of quietly under-charging.
     */
    @Query(
        value = """
            SELECT x.* FROM (
                SELECT DISTINCT ON (tc.id)
                       tc.id             AS "componentId",
                       tc.code           AS "code",
                       tc.name           AS "name",
                       tc.computed_on    AS "computedOn",
                       tc.is_recoverable AS "isRecoverable",
                       tc.sort_order     AS "sortOrder",
                       tr.rate_percent   AS "ratePercent"
                  FROM tax_component tc
                  JOIN tax_rate tr ON tr.tax_component_id = tc.id
                 WHERE tc.tax_scheme_id = :schemeId
                   AND tr.effective_from <= :onDate
                   AND (tr.effective_to IS NULL OR tr.effective_to >= :onDate)
                 ORDER BY tc.id, tr.effective_from DESC, tr.id DESC
            ) x
             ORDER BY x."sortOrder", x."componentId"
        """,
        nativeQuery = true,
    )
    fun findEffectiveComponents(
        @Param("schemeId") schemeId: Long,
        @Param("onDate") onDate: LocalDate,
    ): List<EffectiveTaxComponent>

    fun findByTaxComponentIdAndEffectiveToIsNull(taxComponentId: Long): TaxRate?
}
