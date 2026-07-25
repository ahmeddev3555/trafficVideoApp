package com.trafficwatch.server.reports.dto

import com.trafficwatch.server.reports.ReportStatus
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
