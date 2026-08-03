package com.trafficwatch.server.reports.dto

/**
 * One GPS fix from the Android client's continuous during-recording sampling (see
 * app-side LocationSampleDto). Plain camelCase properties - the server's global Jackson
 * snake_case naming strategy maps `capturedAt` to JSON key `captured_at` with no extra
 * annotations needed, matching every other DTO in this codebase. Not yet consumed by any
 * direction-analysis logic - stored as-is for a future sub-project to use.
 */
data class LocationSampleDto(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Double,
    val altitude: Double,
    val bearing: Double,
    val speed: Double,
    val capturedAt: Long,
)
