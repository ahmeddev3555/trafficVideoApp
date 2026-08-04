package com.trafficwatch.app.core.domain.model

/** One rotation-vector-derived, declination-corrected heading reading captured during active recording. */
data class RotationSample(
    val capturedAt: Long,
    val headingDegrees: Float,
)
