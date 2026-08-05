package com.trafficwatch.server.geo

import com.trafficwatch.server.reports.dto.LocationSampleDto
import com.trafficwatch.server.reports.dto.RotationSampleDto

enum class OrientationSource { ROTATION, LOCATION }

data class ResolvedOrientation(val bearingDegrees: Double, val source: OrientationSource)

/**
 * Continuous per-timestamp camera orientation, fused from a report's
 * location_samples/rotation_samples - pure, no I/O, mirrors BearingMath's testability
 * contract. rotation_samples is always preferred when any exist; location_samples' GPS
 * bearing is used only when a report has zero rotation_samples (never blended - GPS
 * bearing is unreliable at low/zero speed, the exact failure mode that motivated
 * capturing rotation as an independent signal in the first place - see the
 * 2026-08-05 design spec).
 */
class OrientationTimeline(
    private val locationSamples: List<LocationSampleDto>,
    private val rotationSamples: List<RotationSampleDto>,
) {
    // Earliest timestamp across both lists approximates the trimmed clip's frame-0
    // wall-clock time - both lists are already filtered client-side to the trimmed
    // clip's window. Deliberately NOT report.recordedAt (that's the pre-trim raw
    // recording start - see the design spec's correlation-bug note).
    private val anchorEpochMs: Long? = (rotationSamples.map { it.capturedAt } +
        locationSamples.map { it.capturedAt }).minOrNull()

    /** Orientation at [elapsedMs] into the clip, or null if no samples exist at all. */
    fun orientationAt(elapsedMs: Long): ResolvedOrientation? {
        val anchor = anchorEpochMs ?: return null
        val targetEpochMs = anchor + elapsedMs

        if (rotationSamples.isNotEmpty()) {
            return interpolate(rotationSamples.map { it.capturedAt to it.headingDegrees }, targetEpochMs)
                ?.let { ResolvedOrientation(it, OrientationSource.ROTATION) }
        }
        if (locationSamples.isNotEmpty()) {
            return interpolate(locationSamples.map { it.capturedAt to it.bearing }, targetEpochMs)
                ?.let { ResolvedOrientation(it, OrientationSource.LOCATION) }
        }
        return null
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
