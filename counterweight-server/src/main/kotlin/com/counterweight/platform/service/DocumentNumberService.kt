package com.counterweight.platform.service

import com.counterweight.common.ApiException
import com.counterweight.platform.domain.DocumentSequence
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Repository
interface DocumentSequenceRepository : JpaRepository<DocumentSequence, Long> {

    /**
     * Takes the row lock that makes numbering gap-free.
     *
     * `PESSIMISTIC_WRITE` is `SELECT ... FOR UPDATE`: a second till asking for
     * the same document type waits here rather than reading the same next value
     * and issuing a duplicate. Optimistic locking would be wrong — it detects
     * the collision after the fact, and the loser would have to retry with a
     * sale already half-written.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM DocumentSequence s WHERE s.branchId = :branchId AND s.docType = :docType")
    fun lockFor(
        @Param("branchId") branchId: Long,
        @Param("docType") docType: String,
    ): DocumentSequence?
}

/**
 * Issues document numbers: RCT-000001, INV-000042.
 *
 * **Must run inside the caller's transaction**, and the propagation below
 * enforces it. That is the whole design: a number issued for a sale that then
 * fails must roll back with the sale, or the register has a hole in it that
 * nobody can explain a year later. Calling this from a new transaction — the
 * reflex borrowed from audit logging, where the opposite is true — would
 * reintroduce exactly the gap-leaking that §8.3 rejects sequences for.
 */
@Service
class DocumentNumberService(private val sequences: DocumentSequenceRepository) {

    @Transactional(propagation = Propagation.MANDATORY)
    fun next(branchId: Long, docType: String): String {
        val sequence = sequences.lockFor(branchId, docType)
            ?: throw ApiException.RuleViolation(
                "NO_DOCUMENT_SEQUENCE",
                "This branch has no numbering set up for $docType documents.",
            )
        val value = sequence.nextValue
        sequence.nextValue = value + 1
        sequences.save(sequence)

        return "${sequence.prefix}-${value.toString().padStart(sequence.padWidth.toInt(), '0')}"
    }
}
