package com.trafficwatch.server.reports.dto

/**
 * One rotation-vector-derived heading reading from the Android client's continuous
 * during-recording sampling (see app-side RotationSampleDto). Plain camelCase properties -
 * the server's global Jackson snake_case naming strategy maps these to `heading_degrees`/
 * `captured_at` with no extra annotations needed, matching every other DTO in this
 * codebase. Not yet consumed by any direction-analysis logic - stored as-is for a future
 * sub-project to use.
 */
data class RotationSampleDto(
    val headingDegrees: Double,
    val capturedAt: Long,
)
