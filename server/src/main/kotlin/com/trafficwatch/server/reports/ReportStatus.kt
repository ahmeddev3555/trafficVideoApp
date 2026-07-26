package com.trafficwatch.server.reports

/**
 * Mirrors the `reports.status` CHECK constraint (V2__create_reports_table.sql) and the
 * Android client's `ReportStatus.valueOf(...)` parsing exactly - the three constant names
 * are case-sensitive and must not change without updating both the migration and the client.
 */
enum class ReportStatus {
    PENDING,
    CONFIRMED,
    REJECTED,
}
