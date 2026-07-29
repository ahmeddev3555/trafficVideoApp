package com.trafficwatch.server.videoanalysis.dto

/**
 * Wire response shape from the Python video-analysis service's `POST /v1/analyze` - snake_case
 * JSON, matching the server's global Jackson SNAKE_CASE naming strategy (`application.yml`)
 * with zero extra config needed here, unlike the OSM DTOs which need their own ObjectMapper.
 */
data class VideoAnalysisResponse(
    val vehicles: List<VehicleAnalysisResult> = emptyList(),
)

data class VehicleAnalysisResult(
    val trackId: Long,
    val vehicleType: String,
    val detectionConfidence: Double,
    val bearingDegrees: Double?,
    val plateText: String?,
    val plateConfidence: Double?,
)
