package com.trafficwatch.server.common

/**
 * Uniform error response body used across all controllers, wired up via
 * [GlobalExceptionHandler].
 *
 * @param error   short, machine-readable category code (e.g. "DUPLICATE_EMAIL",
 *                "VALIDATION_ERROR") - stable, intended for client-side branching.
 * @param message human-readable detail, safe to display or log - not guaranteed
 *                stable across releases, so clients should branch on [error], not this.
 */
data class ApiError(
    val error: String,
    val message: String,
)
