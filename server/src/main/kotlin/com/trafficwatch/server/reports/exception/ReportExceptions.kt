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

/**
 * Thrown by `ReportService.listReports()` when `page` or `page_size` is less than 1 - both
 * are 1-indexed/positive-only on the wire. Without this guard, `page - 1` can go negative and
 * reach `PageRequest.of` directly, which throws a plain `IllegalArgumentException` with no
 * handler in `GlobalExceptionHandler`, falling through to Spring Boot's default `/error` body
 * instead of this API's uniform `ApiError` shape. Mapped to HTTP 400 by
 * `com.trafficwatch.server.common.GlobalExceptionHandler`.
 */
class InvalidPaginationException(message: String) : RuntimeException(message)
