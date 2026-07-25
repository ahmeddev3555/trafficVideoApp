package com.trafficwatch.server.reports

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ReportRepository : JpaRepository<Report, UUID> {
    fun findByUserId(userId: UUID): List<Report>
    fun findByUserIdAndStatus(userId: UUID, status: ReportStatus): List<Report>

    // Per-user scoping guard: later report endpoints use this to ensure a user can only
    // fetch a report by id if it actually belongs to them (returns null otherwise).
    fun findByIdAndUserId(id: UUID, userId: UUID): Report?
}
