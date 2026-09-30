package com.counterweight.sales.repo

import com.counterweight.sales.domain.SalesCollection
import com.counterweight.sales.domain.SalesCollectionLine
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.Instant

/** A collection with the name of whoever made it. */
interface CollectionHeader {
    val id: Long
    val number: String
    val periodFrom: Instant?
    val collectedAt: Instant
    val recordedAt: Instant
    val collectedByName: String
    val note: String?
}

/** Money that moved through one tender over a period, net of what went back out. */
interface TenderMovement {
    val method: String
    val amount: BigDecimal
}

@Repository
interface SalesCollectionRepository : JpaRepository<SalesCollection, Long> {

    /** The end of the chain: where the next collection has to start. */
    fun findFirstByBranchIdOrderByCollectedAtDesc(branchId: Long): SalesCollection?

    @Query(
        value = """
            SELECT c.id           AS "id",
                   c.number       AS "number",
                   c.period_from  AS "periodFrom",
                   c.collected_at AS "collectedAt",
                   c.recorded_at  AS "recordedAt",
                   u.full_name    AS "collectedByName",
                   c.note         AS "note"
              FROM sales_collection c
              JOIN app_user u ON u.id = c.collected_by
             WHERE c.branch_id = :branchId
             ORDER BY c.collected_at DESC
             LIMIT :limit
        """,
        nativeQuery = true,
    )
    fun history(@Param("branchId") branchId: Long, @Param("limit") limit: Int): List<CollectionHeader>

    @Query(
        value = """
            SELECT c.id           AS "id",
                   c.number       AS "number",
                   c.period_from  AS "periodFrom",
                   c.collected_at AS "collectedAt",
                   c.recorded_at  AS "recordedAt",
                   u.full_name    AS "collectedByName",
                   c.note         AS "note"
              FROM sales_collection c
              JOIN app_user u ON u.id = c.collected_by
             WHERE c.id = :id
        """,
        nativeQuery = true,
    )
    fun headerOf(@Param("id") id: Long): CollectionHeader

    /**
     * What should be in hand for each tender over `[from, until)`.
     *
     * Three movements, each counted at the moment it happened rather than at
     * the moment of the sale it belongs to — that is what makes consecutive
     * periods add up, with nothing counted twice and nothing lost at a
     * boundary:
     *
     *  - **in**: every payment, when its sale completed. Voided sales too; the
     *    void is its own movement.
     *  - **out**: a void hands back what was paid, when it was voided. A sale
     *    completed and voided inside one period nets to nothing; one collected
     *    last week and voided today comes off today's.
     *  - **out**: a return refunded in cash or mobile money, when it was
     *    refunded. Credit notes and account refunds move no money.
     *
     * `from` is the epoch for a branch's first collection, which covers
     * everything before it. ON_ACCOUNT falls out at the method filter.
     */
    @Query(
        value = """
            WITH movements AS (
                SELECT p.method AS method, p.amount AS amount
                  FROM sale_payment p
                  JOIN sale s ON s.id = p.sale_id
                 WHERE s.branch_id = :branchId
                   AND s.completed_at >= :from AND s.completed_at < :until
                UNION ALL
                SELECT p.method, -p.amount
                  FROM sale_payment p
                  JOIN sale s ON s.id = p.sale_id
                 WHERE s.branch_id = :branchId
                   AND s.status = 'VOIDED'
                   AND s.voided_at >= :from AND s.voided_at < :until
                UNION ALL
                SELECT r.refund_method, -r.total
                  FROM sale_return r
                 WHERE r.branch_id = :branchId
                   AND r.refund_method IN ('CASH', 'MOBILE_MONEY')
                   AND r.created_at >= :from AND r.created_at < :until
            )
            SELECT m.method AS "method", SUM(m.amount) AS "amount"
              FROM movements m
             WHERE m.method IN (:methods)
             GROUP BY m.method
        """,
        nativeQuery = true,
    )
    fun movements(
        @Param("branchId") branchId: Long,
        @Param("from") from: Instant,
        @Param("until") until: Instant,
        @Param("methods") methods: Collection<String>,
    ): List<TenderMovement>
}

@Repository
interface SalesCollectionLineRepository : JpaRepository<SalesCollectionLine, Long> {
    fun findByCollectionIdIn(collectionIds: Collection<Long>): List<SalesCollectionLine>
}
