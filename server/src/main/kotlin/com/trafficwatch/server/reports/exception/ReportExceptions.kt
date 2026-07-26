package com.trafficwatch.server.reports.exception

import java.util.UUID

/**
 * Thrown by `ReportService.getStatus()` when `ReportRepository.findByIdAndUserId` returns
 * null - which happens both when the id belongs to a different user and when it does not
 * exist at all. Deliberately a single, generic exception for both cases - distinguishing
 * them in the response would leak which report ids exist for other users (mirrors
 * `auth.exception.InvalidCredentialsException`'s same rationale for login). Mapped to HTTP
 * 404 by `com.trafficwatch.server.common.GlobalExceptionHandler`.
 */
class ReportNotFoundException(reportId: UUID) : RuntimeException("Report not found: $reportId")
