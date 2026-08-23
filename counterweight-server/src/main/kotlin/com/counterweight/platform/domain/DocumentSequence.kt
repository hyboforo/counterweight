package com.counterweight.platform.domain

import jakarta.persistence.*

/**
 * A gap-free counter for one document type in one branch.
 *
 * Deliberately a table and not a PostgreSQL SEQUENCE. A sequence hands out its
 * next value outside the caller's transaction and keeps it handed out when that
 * transaction rolls back, which is the correct behaviour for a surrogate key and
 * the wrong behaviour for a receipt register: the missing number is one somebody
 * has to account for later (§8.3).
 *
 * The cost is a row lock per document, held for the length of the transaction
 * that issues one. On a handful of tills that is not a contention problem, and
 * the guarantee is worth more than the throughput.
 */
@Entity
@Table(name = "document_sequence")
class DocumentSequence(
    @Column(name = "branch_id", nullable = false)
    var branchId: Long,

    @Column(name = "doc_type", nullable = false)
    var docType: String,

    @Column(nullable = false)
    var prefix: String,
) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "next_value", nullable = false)
    var nextValue: Long = 1

    @Column(name = "pad_width", nullable = false)
    var padWidth: Short = 6

    override fun equals(other: Any?) = this === other || (other is DocumentSequence && id != null && id == other.id)
    override fun hashCode() = javaClass.hashCode()
    override fun toString() = "DocumentSequence($docType)"
}
