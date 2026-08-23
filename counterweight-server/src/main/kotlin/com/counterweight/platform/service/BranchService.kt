package com.counterweight.platform.service

import com.counterweight.common.ApiException
import com.counterweight.identity.security.Auth
import com.counterweight.platform.domain.Branch
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Repository
interface BranchRepository : JpaRepository<Branch, Long> {
    fun findByCode(code: String): Branch?
}

/**
 * The branch the caller is working in.
 *
 * Every module already carries `branchId` on its rows and checks it; this is
 * for the handful of places that need the branch's own details rather than its
 * id — a receipt header, a statement letterhead, the address on an invoice.
 */
@Service
class BranchService(private val branches: BranchRepository) {

    @Transactional(readOnly = true)
    fun current(): Branch = get(Auth.current().branchId)

    @Transactional(readOnly = true)
    fun get(id: Long): Branch =
        branches.findById(id).orElseThrow { ApiException.NotFound("Branch", id) }
}
