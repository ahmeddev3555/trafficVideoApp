package com.trafficwatch.server.videoanalysis.dto

/**
 * Wire response shape from the Python video-analysis service's `POST /v1/analyze` - snake_case
 * JSON, matching the server's global Jackson SNAKE_CASE naming strategy (`application.yml`)
 * with zero extra config needed here, unlike the OSM DTOs which need their own ObjectMapper.
 */
data class VideoAnalysisResponse(
    val vehicles: List<VehicleAnalysisResult> = emptyList(),
    // Source video dimensions - null when talking to an older service version,
    // in which case corridor/flow analysis is skipped entirely.
    val frameWidth: Int? = null,
    val frameHeight: Int? = null,
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
    // Corridor flow facts from app/corridors.py - null from older service
    // versions; ClipFlowAnalyzer treats null as "vehicle not usable for flow".
    val corridorId: Long? = null,
    val corridorCohesion: Double? = null,
    val trackFrameCount: Int? = null,
    val displacementPixels: Double? = null,
)
