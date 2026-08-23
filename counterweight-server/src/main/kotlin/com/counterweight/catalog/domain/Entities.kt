package com.counterweight.catalog.domain

import jakarta.persistence.*
import org.hibernate.annotations.Formula
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.math.BigDecimal
import java.time.Instant

/*
 * Catalog entities. Plain classes with id-based equality, per ADR-001.
 *
 * Note the absence of `path` on Category: the ltree column is maintained by the
 * database (V3) and never written from here. Reading it is fine, writing it is
 * not, so it is exposed read-only.
 */

@Entity
@Table(name = "category")
class Category(
    @Column(nullable = false, unique = true)
    var code: String,

    @Column(nullable = false)
    var name: String,
) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "parent_id")
    var parentId: Long? = null

    /**
     * Materialised ltree path, e.g. `agro.herbicide.selective`.
     *
     * Read through a formula rather than mapped as a column, for two reasons
     * that pull the same way. The value is the database's to decide — V3's
     * trigger derives it from parent_id + code — so Hibernate must never bind
     * it; a String parameter would bind as varchar and fail the implicit cast
     * to ltree.
     *
     * And `ltree` reports itself as `Types#OTHER` over JDBC, which
     * `ddl-auto: validate` compares against the varchar a String property
     * implies and refuses to start on. The explicit `::text` makes the read
     * well-typed and takes the property out of schema validation altogether,
     * which is honest: this is a projection of a column, not the column.
     */
    @Formula("path::text")
    var path: String? = null

    @Column(nullable = false)
    var kind: String = "GENERAL"

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true

    override fun equals(other: Any?) = this === other || (other is Category && id != null && id == other.id)
    override fun hashCode() = javaClass.hashCode()
    override fun toString() = "Category($code)"
}

/**
 * One declared field on a category. Products inherit every attribute declared
 * on any ancestor of their category path — see AttributeValidator.
 */
@Entity
@Table(name = "category_attribute")
class CategoryAttribute(
    @Column(name = "category_id", nullable = false)
    var categoryId: Long,

    @Column(name = "key", nullable = false)
    var key: String,

    @Column(nullable = false)
    var label: String,

    @Column(name = "data_type", nullable = false)
    var dataType: String,
) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "enum_values", columnDefinition = "text[]")
    @JdbcTypeCode(SqlTypes.ARRAY)
    var enumValues: Array<String>? = null

    var unit: String? = null

    @Column(nullable = false)
    var required: Boolean = false

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0

    override fun equals(other: Any?) = this === other || (other is CategoryAttribute && id != null && id == other.id)
    override fun hashCode() = javaClass.hashCode()
}

@Entity
@Table(name = "uom")
class Uom(
    @Column(nullable = false, unique = true)
    var code: String,

    @Column(nullable = false)
    var name: String,
) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    /** How many decimals the till accepts. KG allows 3; PCS allows 0. */
    @Column(nullable = false)
    var decimals: Short = 0

    override fun equals(other: Any?) = this === other || (other is Uom && id != null && id == other.id)
    override fun hashCode() = javaClass.hashCode()
    override fun toString() = "Uom($code)"
}

@Entity
@Table(name = "product")
class Product(
    @Column(name = "branch_id", nullable = false)
    var branchId: Long,

    @Column(name = "category_id", nullable = false)
    var categoryId: Long,

    @Column(nullable = false)
    var sku: String,

    @Column(nullable = false)
    var name: String,
) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    /** What customers actually call it at the counter. Searched alongside name. */
    @Column(name = "local_name")
    var localName: String? = null

    var description: String? = null

    /** Per-category fields, validated on write against the resolved declarations. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attributes", columnDefinition = "jsonb", nullable = false)
    var attributes: String = "{}"

    @Column(name = "picking_rule", nullable = false)
    var pickingRule: String = "FIFO"

    @Column(name = "is_batch_tracked", nullable = false)
    var isBatchTracked: Boolean = false

    @Column(name = "reorder_point")
    var reorderPoint: BigDecimal? = null

    @Column(name = "reorder_qty")
    var reorderQty: BigDecimal? = null

    @Column(name = "reorder_is_manual", nullable = false)
    var reorderIsManual: Boolean = false

    @Column(name = "safety_stock", nullable = false)
    var safetyStock: BigDecimal = BigDecimal.ZERO

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()

    override fun equals(other: Any?) = this === other || (other is Product && id != null && id == other.id)
    override fun hashCode() = javaClass.hashCode()
    override fun toString() = "Product($sku)"
}

/**
 * A unit this product can be bought or sold in, and how it converts.
 *
 * [factor] is how many BASE units one of these contains. The base row has 1.0.
 * The ledger only ever speaks base units; conversion happens at the edges.
 */
@Entity
@Table(name = "product_uom")
class ProductUom(
    @Column(name = "product_id", nullable = false)
    var productId: Long,

    @Column(name = "uom_id", nullable = false)
    var uomId: Long,

    @Column(nullable = false)
    var factor: BigDecimal,
) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "is_base", nullable = false)
    var isBase: Boolean = false

    @Column(nullable = false)
    var sellable: Boolean = true

    @Column(nullable = false)
    var purchasable: Boolean = true

    var barcode: String? = null

    override fun equals(other: Any?) = this === other || (other is ProductUom && id != null && id == other.id)
    override fun hashCode() = javaClass.hashCode()
}

