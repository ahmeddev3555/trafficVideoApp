package com.trafficwatch.server.videoanalysis.dto

/**
 * Wire response shape from the Python video-analysis service's `POST /v1/analyze` - snake_case
 * JSON, matching the server's global Jackson SNAKE_CASE naming strategy (`application.yml`)
 * with zero extra config needed here, unlike the OSM DTOs which need their own ObjectMapper.
 */
data class VideoAnalysisResponse(
    val vehicles: List<VehicleAnalysisResult> = emptyList(),
)

data class BoundingBox(
    val x1: Double,
    val y1: Double,
    val x2: Double,
    val y2: Double,
)

data class VehicleAnalysisResult(
    val trackId: Long,
    val vehicleType: String,
    val detectionConfidence: Double,
    val bearingDegrees: Double?,
    val plateText: String?,
    val plateConfidence: Double?,
    val boundingBox: BoundingBox? = null,
    val frameJpegBase64: String? = null,
)
