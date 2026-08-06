package com.trafficwatch.server.geo

import com.trafficwatch.server.reports.dto.LocationSampleDto
import com.trafficwatch.server.reports.dto.RotationSampleDto

enum class OrientationSource { ROTATION, LOCATION }

data class ResolvedOrientation(val bearingDegrees: Double, val source: OrientationSource)

/**
 * Minimum GPS speed (m/s) below which Android's `Location.bearing` is unreliable/undefined
 * and must not be trusted as an orientation source. The platform reports exactly `0.0` (not
 * null, not omitted) whenever it cannot determine a device's direction of travel - typically
 * when stationary or moving very slowly - which is indistinguishable on the wire from a
 * genuine "facing due north" reading. 1.0 m/s (~3.6 km/h, roughly walking pace) is
 * comfortably above GPS noise at a standstill while still permissive of a slow-moving
 * vehicle. This is the exact `bearing: 0.0deg, speed: 0.0 m/s` failure mode the 2026-08-05
 * design spec names as the root cause this whole feature exists to fix - this gate exists so
 * the GPS-bearing fallback can never reintroduce it.
 */
const val MIN_SPEED_FOR_RELIABLE_BEARING_MPS = 1.0

/**
 * Continuous per-timestamp camera orientation, fused from a report's
 * location_samples/rotation_samples - pure, no I/O, mirrors BearingMath's testability
 * contract. rotation_samples is always preferred when any exist; location_samples' GPS
 * bearing is used only when a report has zero rotation_samples (never blended - GPS
 * bearing is unreliable at low/zero speed, the exact failure mode that motivated
 * capturing rotation as an independent signal in the first place - see the
 * 2026-08-05 design spec). Within location_samples, only entries at/above
 * [MIN_SPEED_FOR_RELIABLE_BEARING_MPS] are eligible to be used at all - see that constant's
 * doc. If every location sample is filtered out (or the list was empty to begin with), the
 * LOCATION source is treated as wholly unavailable: [orientationAt] returns null rather than
 * resolving a fabricated/unreliable bearing, letting the caller's own fallback chain (e.g.
 * a report-level compass scalar) apply exactly as if location_samples had never been sent.
 */
class OrientationTimeline(
    private val locationSamples: List<LocationSampleDto>,
    private val rotationSamples: List<RotationSampleDto>,
) {
    // Earliest timestamp across both lists approximates the trimmed clip's frame-0
    // wall-clock time - both lists are already filtered client-side to the trimmed
    // clip's window. Deliberately NOT report.recordedAt (that's the pre-trim raw
    // recording start - see the design spec's correlation-bug note). Uses the RAW
    // (unfiltered) locationSamples - the anchor is a wall-clock reference point, not a
    // statement about which samples are reliable enough to interpolate a bearing from.
    private val anchorEpochMs: Long? = (rotationSamples.map { it.capturedAt } +
        locationSamples.map { it.capturedAt }).minOrNull()

    /** See [MIN_SPEED_FOR_RELIABLE_BEARING_MPS] - only these are eligible for LOCATION resolution. */
    private val reliableLocationSamples: List<LocationSampleDto> =
        locationSamples.filter { it.speed >= MIN_SPEED_FOR_RELIABLE_BEARING_MPS }

    /** Orientation at [elapsedMs] into the clip, or null if no samples exist at all. */
    fun orientationAt(elapsedMs: Long): ResolvedOrientation? {
        val anchor = anchorEpochMs ?: return null
        val targetEpochMs = anchor + elapsedMs

        if (rotationSamples.isNotEmpty()) {
            return interpolate(rotationSamples.map { it.capturedAt to it.headingDegrees }, targetEpochMs)
                ?.let { ResolvedOrientation(it, OrientationSource.ROTATION) }
        }
        if (reliableLocationSamples.isNotEmpty()) {
            return interpolate(reliableLocationSamples.map { it.capturedAt to it.bearing }, targetEpochMs)
                ?.let { ResolvedOrientation(it, OrientationSource.LOCATION) }
        }
        return null
    }

    /**
     * The recording device's own GPS speed (m/s) from the location_samples entry nearest
     * [elapsedMs] into the clip, or null if no location_samples exist at all. Used to gate
     * whether a bbox-scale-derived "approaching/receding" bearing (Python's
     * resolve_bearing, source "scale") can be trusted: bbox growth alone cannot distinguish
     * "the other vehicle approached me" from "I (the recording vehicle) caught up to a
     * stationary or slower vehicle" - only when the recording vehicle itself was
     * near-stationary at that moment can bbox growth be safely attributed to the OTHER
     * vehicle's own motion. See ClipFlowAnalyzer.qualifyVehicles for the actual gate.
     */
    fun recordingSpeedMetersPerSecondAt(elapsedMs: Long): Double? {
        if (locationSamples.isEmpty()) return null
        val anchor = anchorEpochMs ?: return null
        val targetEpochMs = anchor + elapsedMs
        return locationSamples.minByOrNull { kotlin.math.abs(it.capturedAt - targetEpochMs) }?.speed
    }

    /**
     * Circular-weighted interpolation between the two samples in [points]
     * (epochMs, bearingDegrees) bracketing [targetEpochMs], weighted by inverse
     * time-distance. At the edges (target before the first or after the last point),
     * returns that single nearest point unweighted. Null only for a genuine circular-mean
     * degeneracy (see BearingMath.weightedCircularMeanDegrees) - never a fabricated value.
     */
    private fun interpolate(points: List<Pair<Long, Double>>, targetEpochMs: Long): Double? {
        if (points.isEmpty()) return null
        val sorted = points.sortedBy { it.first }

        if (targetEpochMs <= sorted.first().first) return sorted.first().second
        if (targetEpochMs >= sorted.last().first) return sorted.last().second

        val after = sorted.first { it.first >= targetEpochMs }
        val before = sorted.last { it.first <= targetEpochMs }
        if (before.first == after.first) return before.second

        val totalSpan = (after.first - before.first).toDouble()
        val weightBefore = (after.first - targetEpochMs) / totalSpan
        val weightAfter = (targetEpochMs - before.first) / totalSpan

        // Detect antipodal case (180° difference) to handle floating-point precision issues
        // in circular mean computation - when bearings are exactly opposite, the mean is undefined.
        if (BearingMath.angularDifferenceDegrees(before.second, after.second) > 179.99) {
            return null
        }

        return BearingMath.weightedCircularMeanDegrees(
            listOf(before.second, after.second),
            listOf(weightBefore, weightAfter),
        )
    }
}
