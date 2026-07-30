package com.trafficwatch.server.reports.dto

import com.trafficwatch.server.reports.ReportStatus
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Wire response body for POST /reports. Property names are camelCase; the app-wide
 * Jackson SNAKE_CASE naming strategy (see application.yml) maps `reportId` <-> `report_id`
 * on the wire, matching the auth domain's `dto/` convention.
 */
data class SubmitReportResponse(
    val reportId: UUID,
    val status: ReportStatus,
    val message: String,
)

/**
 * Wire response body for both `GET /reports/{reportId}/status` and the per-item shape
 * inside `GET /reports`'s `reports` array. `status` serializes as the [ReportStatus] enum's
 * exact name (`"PENDING"`/`"CONFIRMED"`/`"REJECTED"`) - the Android client does
 * `ReportStatus.valueOf(...)` on this value, so it must never be lowercased/reformatted.
 * `licensePlate`/`confidence`/`message` are nullable and map straight from `Report`'s
 * `licensePlate`/`confidence`/`analysisMessage` - they stay null until a later task's
 * analysis job flips the report to a terminal status.
 */
data class ReportStatusResponse(
    val reportId: UUID,
    val status: ReportStatus,
    val licensePlate: String?,
    val confidence: BigDecimal?,
    val message: String?,
    val updatedAt: OffsetDateTime,
    val streetName: String?,
    val hasWrongWayFrame: Boolean,
    val wrongWayConfidence: BigDecimal?,
)

/**
 * Wire response body for `GET /reports`. `page` echoes back the 1-indexed page the client
 * asked for (not Spring Data's internal 0-indexed `Pageable.pageNumber`) - see
 * `ReportService.listReports`.
 */
data class ReportListResponse(
    val reports: List<ReportStatusResponse>,
    val total: Long,
    val page: Int,
)
