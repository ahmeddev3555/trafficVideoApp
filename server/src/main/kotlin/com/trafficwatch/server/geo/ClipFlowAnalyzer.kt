package com.trafficwatch.server.geo

import com.trafficwatch.server.reports.AnalysisProperties
import com.trafficwatch.server.videoanalysis.dto.VehicleAnalysisResult
import org.springframework.stereotype.Component
import kotlin.math.hypot
import kotlin.math.min

/** Minimum observed frames for a track to vote in a consensus. */
private const val MIN_TRACK_FRAMES = 3

/** Frame count at which trackQuality's frame factor saturates to 1.0. */
private const val TRACK_FRAMES_SATURATION = 5.0

/**
 * Recording vehicle's own GPS speed threshold (m/s) below which a bbox-scale-derived
 * ("scale"-sourced) bearing is trusted as the OTHER vehicle's genuine approach/recession,
 * rather than the recording vehicle itself closing the distance on a stationary or slower
 * vehicle. Matches the "GPS bearing unreliable below this speed" walking-pace convention
 * already used elsewhere for GPS-derived signals - see the
 * 2026-08-06-approach-recession-bearing-fix design spec's Critical-finding fix.
 */
private const val MAX_RECORDING_SPEED_FOR_SCALE_BEARING_MPS = 1.0

/** A vehicle qualified for flow analysis: absolute bearing + quality facts. */
data class FlowVehicle(
    val vehicle: VehicleAnalysisResult,
    val absoluteBearingDegrees: Double,
    val trackQuality: Double,
    val corridorId: Long,
    val corridorCohesion: Double,
    val orientationSource: OrientationSource? = null,
) {
    val candidateQuality: Double get() = trackQuality * corridorCohesion
}

/** One corridor's flow consensus (computed excluding any evaluated candidate). */
data class CorridorConsensus(
    val corridorId: Long,
    val bearingDegrees: Double,
    val resultantLength: Double,
    val memberCount: Int,
    val meanCohesion: Double,
) {
    /** Spec: clipConfidence = (n/(n+2)) x R x meanCohesion. */
    val clipConfidence: Double
        get() = (memberCount / (memberCount + 2.0)) * resultantLength * meanCohesion
}

/**
 * Pure clip-flow statistics over corridor-annotated vehicles - no I/O, mirrors
 * [BearingMath]'s testability contract. Which corridor a vehicle is in is
 * Python's (frame-space geometry) answer; everything with judgment in it -
 * who qualifies, what counts as consensus, who opposes whom - is decided here.
 */
@Component
class ClipFlowAnalyzer(
    private val properties: AnalysisProperties,
) {

    /**
     * Vehicles usable for flow analysis: corridor-annotated, with a real bearing,
     * above the quality floor. Requires frame dimensions (null = older Python
     * service = no flow analysis at all, per the graceful-degradation contract).
     */
    fun qualifyVehicles(
        vehicles: List<VehicleAnalysisResult>,
        compassHeadingDegrees: Double?,
        frameWidth: Int?,
        frameHeight: Int?,
        orientationTimeline: OrientationTimeline? = null,
    ): List<FlowVehicle> {
        if (frameWidth == null || frameHeight == null || frameWidth <= 0 || frameHeight <= 0) {
            return emptyList()
        }

        return vehicles.mapNotNull { vehicle ->
            val frameBearing = vehicle.bearingDegrees ?: return@mapNotNull null
            if (vehicle.bearingSource == "scale") {
                val recordingSpeed = vehicle.trackMidpointMs
                    ?.let { orientationTimeline?.recordingSpeedMetersPerSecondAt(it) }
                if (recordingSpeed == null || recordingSpeed > MAX_RECORDING_SPEED_FOR_SCALE_BEARING_MPS) {
                    return@mapNotNull null
                }
            }
            val corridorId = vehicle.corridorId ?: return@mapNotNull null
            val cohesion = vehicle.corridorCohesion ?: return@mapNotNull null
            val frames = vehicle.trackFrameCount ?: return@mapNotNull null
            val displacement = vehicle.displacementPixels ?: return@mapNotNull null
            val bbox = vehicle.boundingBox ?: return@mapNotNull null

            val bboxDiagonal = hypot(bbox.x2 - bbox.x1, bbox.y2 - bbox.y1)
            // Defensive: a zero-diagonal bbox (malformed upstream data - real YOLO boxes
            // are never degenerate) would otherwise make minDisplacement 0.0 and let a
            // zero-displacement vehicle through, then produce NaN in trackQuality's
            // division below (0.0/0.0), which can silently corrupt candidate scoring
            // downstream. Drop it here instead.
            if (bboxDiagonal <= 0.0) return@mapNotNull null
            // Note: vehicle.boundingBox is the track's LARGEST-area frame (see Python's
            // pipeline.py representative_frame selection), not a typical/average size -
            // for a vehicle whose apparent size changes a lot across the clip (e.g.
            // approaching or receding the camera), this makes the effective floor
            // stricter than "15% of typical size" might suggest. Worth knowing if
            // minDisplacementFraction is ever retuned.
            val minDisplacement = properties.minDisplacementFraction * bboxDiagonal

            if (frames < MIN_TRACK_FRAMES || displacement < minDisplacement) return@mapNotNull null

            // Per-vehicle orientation resolution, preferred-to-fallback: (1) the
            // continuous timeline at this vehicle's own observation midpoint, (2) the
            // report-level scalar (today's whole-clip behavior), (3) drop the vehicle -
            // same three-tier graceful degradation as every other optional signal here.
            val resolved = vehicle.trackMidpointMs?.let { orientationTimeline?.orientationAt(it) }
            val orientationDegrees = resolved?.bearingDegrees ?: compassHeadingDegrees ?: return@mapNotNull null

            FlowVehicle(
                vehicle = vehicle,
                absoluteBearingDegrees = (orientationDegrees + frameBearing) % 360.0,
                trackQuality = min(frames / TRACK_FRAMES_SATURATION, 1.0) *
                    min(displacement / minDisplacement, 1.0).coerceAtMost(1.0),
                corridorId = corridorId,
                corridorCohesion = cohesion,
                orientationSource = resolved?.source,
            )
        }
    }

    /**
     * True when [vehicle] passes every [qualifyVehicles] gate EXCEPT orientation resolution
     * (i.e. everything up to, but not including, the per-vehicle orientation-resolution step) -
     * used only to distinguish, for messaging purposes, "this vehicle would have qualified if
     * only its orientation could be resolved" from "this vehicle was never going to qualify
     * regardless of orientation" (bad corridor data, too few frames, insufficient displacement,
     * etc.). Deliberately mirrors [qualifyVehicles]'s own gates rather than sharing code with
     * it, so a change to one is never silently forgotten in the other without a test noticing -
     * see [ReportAnalysisJob]'s REJECTED-message selection for the caller.
     */
    fun qualifiesForFlowExceptOrientation(vehicle: VehicleAnalysisResult, frameWidth: Int?, frameHeight: Int?): Boolean {
        if (frameWidth == null || frameHeight == null || frameWidth <= 0 || frameHeight <= 0) return false
        vehicle.bearingDegrees ?: return false
        vehicle.corridorId ?: return false
        vehicle.corridorCohesion ?: return false
        val frames = vehicle.trackFrameCount ?: return false
        val displacement = vehicle.displacementPixels ?: return false
        val bbox = vehicle.boundingBox ?: return false

        val bboxDiagonal = hypot(bbox.x2 - bbox.x1, bbox.y2 - bbox.y1)
        if (bboxDiagonal <= 0.0) return false
        val minDisplacement = properties.minDisplacementFraction * bboxDiagonal

        return frames >= MIN_TRACK_FRAMES && displacement >= minDisplacement
    }

    /**
     * The consensus of corridor [corridorId]'s members (minus [excluding], the
     * candidate under evaluation - a suspected violator must never vote in the
     * consensus it is judged against, nor ever be ingested from it). Null when
     * no members remain or the bearings are too dispersed/bimodal
     * (R < consensus-min-resultant-length) - a split flow never elects a winner.
     */
    fun corridorConsensus(
        flowVehicles: List<FlowVehicle>,
        corridorId: Long,
        excluding: FlowVehicle?,
    ): CorridorConsensus? {
        val members = flowVehicles.filter { it.corridorId == corridorId && it !== excluding }
        if (members.isEmpty()) return null

        val stats = BearingMath.circularStats(members.map { it.absoluteBearingDegrees }) ?: return null
        if (stats.resultantLength < properties.consensusMinResultantLength) return null

        return CorridorConsensus(
            corridorId = corridorId,
            bearingDegrees = stats.meanDegrees,
            resultantLength = stats.resultantLength,
            memberCount = members.size,
            meanCohesion = members.sumOf { it.corridorCohesion } / members.size,
        )
    }

    /** True when [candidate] flows in the same direction as [consensus] (within agreement tolerance). */
    fun movesWith(candidate: FlowVehicle, consensus: CorridorConsensus): Boolean =
        BearingMath.angularDifferenceDegrees(candidate.absoluteBearingDegrees, consensus.bearingDegrees) <=
            properties.agreementToleranceDegrees

    /**
     * True when at least one OTHER member of [candidate]'s corridor has a bearing within
     * agreement tolerance of [candidate]'s own - i.e. the candidate's specific direction is
     * corroborated by a real peer, not a lone coincidental bearing in an otherwise scattered
     * corridor. Used when the corridor's overall consensus is unavailable (bimodal/dispersed)
     * to decide whether independent evidence (OSM tag, learned history) is still safe to
     * trust for this specific candidate.
     */
    fun hasPeerSupport(flowVehicles: List<FlowVehicle>, candidate: FlowVehicle): Boolean =
        flowVehicles.any {
            it.corridorId == candidate.corridorId &&
                it !== candidate &&
                BearingMath.angularDifferenceDegrees(it.absoluteBearingDegrees, candidate.absoluteBearingDegrees) <=
                    properties.agreementToleranceDegrees
        }
}
