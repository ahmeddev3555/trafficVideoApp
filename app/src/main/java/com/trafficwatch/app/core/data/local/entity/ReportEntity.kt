package com.trafficwatch.app.core.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.trafficwatch.app.core.domain.model.LocationData
import com.trafficwatch.app.core.domain.model.Report
import com.trafficwatch.app.core.domain.model.ReportStatus

@Entity(tableName = "reports")
data class ReportEntity(
    @PrimaryKey val id: String,
    val serverId: String?,
    val videoPath: String,
    // LocationData flattened
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val altitude: Double,
    val bearing: Float,
    val speed: Float,
    val locationCapturedAt: Long,
    // Report metadata
    val recordingStartedAt: Long,
    val durationMs: Long,
    val fileSizeBytes: Long,
    val status: String,            // ReportStatus.name()
    val licensePlate: String?,
    val confidence: Float?,
    val analysisMessage: String?,
    val hasWrongWayFrame: Boolean,
    val wrongWayConfidence: Float?,
    val evidenceBreakdownJson: String?,
    val createdAt: Long,
    val updatedAt: Long
) {
    fun toDomain(): Report = Report(
        id = id,
        serverId = serverId,
        videoPath = videoPath,
        location = LocationData(
            latitude = latitude,
            longitude = longitude,
            accuracy = accuracy,
            altitude = altitude,
            bearing = bearing,
            speed = speed,
            capturedAt = locationCapturedAt
        ),
        recordingStartedAt = recordingStartedAt,
        durationMs = durationMs,
        fileSizeBytes = fileSizeBytes,
        status = ReportStatus.valueOf(status),
        licensePlate = licensePlate,
        confidence = confidence,
        analysisMessage = analysisMessage,
        hasWrongWayFrame = hasWrongWayFrame,
        wrongWayConfidence = wrongWayConfidence,
        evidenceBreakdownJson = evidenceBreakdownJson,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    companion object {
        fun fromDomain(report: Report): ReportEntity = ReportEntity(
            id = report.id,
            serverId = report.serverId,
            videoPath = report.videoPath,
            latitude = report.location.latitude,
            longitude = report.location.longitude,
            accuracy = report.location.accuracy,
            altitude = report.location.altitude,
            bearing = report.location.bearing,
            speed = report.location.speed,
            locationCapturedAt = report.location.capturedAt,
            recordingStartedAt = report.recordingStartedAt,
            durationMs = report.durationMs,
            fileSizeBytes = report.fileSizeBytes,
            status = report.status.name,
            licensePlate = report.licensePlate,
            confidence = report.confidence,
            analysisMessage = report.analysisMessage,
            hasWrongWayFrame = report.hasWrongWayFrame,
            wrongWayConfidence = report.wrongWayConfidence,
            evidenceBreakdownJson = report.evidenceBreakdownJson,
            createdAt = report.createdAt,
            updatedAt = report.updatedAt
        )
    }
}
