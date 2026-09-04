package com.trafficwatch.app.core.domain.model

data class Report(
    val id: String,
    val serverId: String? = null,
    val videoPath: String,             // absolute path to trimmed file on device
    val location: LocationData,
    val recordingStartedAt: Long,      // epoch millis
    val durationMs: Long,
    val fileSizeBytes: Long,
    val status: ReportStatus,
    val licensePlate: String? = null,
    val confidence: Float? = null,
    val analysisMessage: String? = null,
    val hasWrongWayFrame: Boolean = false,
    val wrongWayConfidence: Float? = null,
    val evidenceBreakdownJson: String? = null,
    val locationSamplesJson: String? = null,
    val rotationSamplesJson: String? = null,
    val createdAt: Long,
    val updatedAt: Long
)
