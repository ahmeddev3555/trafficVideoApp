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
    // The clip's own dominant traffic direction (deg clockwise from frame-up) and how
    // tightly its moving vehicles agree (mean resultant length R). Null from a service
    // version predating the 2026-09-06 counter-flow signal, or when < 2 vehicles have a
    // resolvable direction.
    val dominantFlowDegrees: Double? = null,
    val flowCoherence: Double? = null,
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
    // Which computation produced bearingDegrees, from the video-analysis service:
    // "centroid" (real lateral pixel motion) or "scale" (bbox-diagonal-derived fallback for
    // near-head-on motion). Null from older video-analysis service versions (no such
    // distinction existed yet), or when bearingDegrees is also null. A "scale" bearing must
    // be corroborated by the recording vehicle's own low GPS speed before ClipFlowAnalyzer
    // trusts it - see OrientationTimeline.recordingSpeedMetersPerSecondAt and its use in
    // ClipFlowAnalyzer.qualifyVehicles.
    val bearingSource: String? = null,
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
    // Elapsed ms from the clip's start to this track's observation midpoint - null from
    // older video-analysis service versions (no FPS lookup existed yet) or when FPS was
    // unavailable for this specific clip. Used to look up this vehicle's own camera
    // orientation from OrientationTimeline instead of applying one static reading to
    // every vehicle in the clip. Snake_case wire key: track_midpoint_ms.
    val trackMidpointMs: Long? = null,
    // Apparent-size trend of the track from the video-analysis service (app/schemas.py):
    // "growing" (approached the camera), "shrinking" (receded), "flat" (neither / too
    // brief). Default "flat" / 0.0 for a response from a service version predating this
    // field. Consumed only by ReportAnalysisJob's stationary-approach detection path.
    val scaleTrend: String = "flat",
    val scaleGrowthFraction: Double = 0.0,
    // This track's frame-space velocity (over its most-distant window) dotted against
    // dominantFlowDegrees: +1 with the flow, -1 straight against it, 0 perpendicular.
    // Null from an older service version or when the track had too little motion.
    // ReportAnalysisJob's counter-flow detection path consumes it.
    val flowAlignment: Double? = null,
)
