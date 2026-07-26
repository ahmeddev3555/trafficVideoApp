package com.trafficwatch.server.reports

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ReportRepository : JpaRepository<Report, UUID> {
    fun findByUserId(userId: UUID): List<Report>
    fun findByUserIdAndStatus(userId: UUID, status: ReportStatus): List<Report>

    // Pageable overloads for GET /reports (Task 10). Spring Data derives these as
    // independent queries from their own method signatures - having both a List-returning
    // and a Page-returning overload of the same derived-query name is supported and does
    // not conflict with the plain List versions above, which existing callers still use.
    fun findByUserId(userId: UUID, pageable: Pageable): Page<Report>
    fun findByUserIdAndStatus(userId: UUID, status: ReportStatus, pageable: Pageable): Page<Report>

    // Per-user scoping guard: later report endpoints use this to ensure a user can only
    // fetch a report by id if it actually belongs to them (returns null otherwise).
    fun findByIdAndUserId(id: UUID, userId: UUID): Report?
}
