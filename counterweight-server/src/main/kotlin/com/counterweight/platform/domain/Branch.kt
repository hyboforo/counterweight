package com.counterweight.platform.domain

import jakarta.persistence.*
import java.time.Instant

/**
 * The shop.
 *
 * Singular today and modelled as a table anyway, because `branch_id` is on
 * every transactional row from day one (§1). A second branch is then data
 * rather than a migration against live sales history — which is the whole
 * reason the column exists before there is anything to put in it.
 */
@Entity
@Table(name = "branch")
class Branch(
    @Column(nullable = false, unique = true)
    var code: String,

    @Column(nullable = false)
    var name: String,
) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    var address: String? = null
    var phone: String? = null

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()

    override fun equals(other: Any?) = this === other || (other is Branch && id != null && id == other.id)
    override fun hashCode() = javaClass.hashCode()
    override fun toString() = "Branch($code)"
}
