package com.trafficwatch.server.geo

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure geo math, deliberately dependency-free (no JTS/PostGIS - see the plan's "No
 * PostGIS" decision) so it's trivially unit-testable with synthetic coordinates.
 */
object BearingMath {

    private const val EARTH_RADIUS_METERS = 6_371_000.0

    /** Initial great-circle bearing in degrees `[0, 360)` from [from] to [to]. */
    fun initialBearingDegrees(from: GeoPoint, to: GeoPoint): Double {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val deltaLon = Math.toRadians(to.longitude - from.longitude)

        val y = sin(deltaLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(deltaLon)
        val bearing = Math.toDegrees(atan2(y, x))
        return (bearing + 360.0) % 360.0
    }

    /** Shortest angular difference between two bearings, in degrees `[0, 180]`. */
    fun angularDifferenceDegrees(a: Double, b: Double): Double {
        val diff = Math.abs(a - b) % 360.0
        return if (diff > 180.0) 360.0 - diff else diff
    }

    /**
     * Index `i` such that the segment `(nodes[i], nodes[i+1])` is the closest segment of
     * [nodes] to [point]. Null if [nodes] has fewer than two points.
     */
    fun nearestSegmentIndex(point: GeoPoint, nodes: List<GeoPoint>): Int? {
        if (nodes.size < 2) return null

        var bestIndex = 0
        var bestDistance = Double.MAX_VALUE
        for (i in 0 until nodes.size - 1) {
            val distance = distanceToSegmentMeters(point, nodes[i], nodes[i + 1])
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = i
            }
        }
        return bestIndex
    }

    /**
     * Distance in meters from [point] to the line segment `(a, b)`. Uses a local flat-plane
     * (equirectangular) projection centered on [a] rather than exact great-circle segment
     * distance - accurate enough at the scale this is used for (search radii of tens of
     * meters), and far simpler than a full spherical closest-point-on-arc calculation.
     */
    fun distanceToSegmentMeters(point: GeoPoint, a: GeoPoint, b: GeoPoint): Double {
        val (px, py) = toLocalMeters(a, point)
        val (bx, by) = toLocalMeters(a, b)

        val abLenSq = bx * bx + by * by
        val t = if (abLenSq == 0.0) 0.0 else ((px * bx + py * by) / abLenSq).coerceIn(0.0, 1.0)

        val closestX = t * bx
        val closestY = t * by
        val dx = px - closestX
        val dy = py - closestY
        return sqrt(dx * dx + dy * dy)
    }

    /** Local (x=east, y=north) meters offset of [point] relative to [origin]. */
    private fun toLocalMeters(origin: GeoPoint, point: GeoPoint): Pair<Double, Double> {
        val latRad = Math.toRadians(origin.latitude)
        val x = Math.toRadians(point.longitude - origin.longitude) * EARTH_RADIUS_METERS * cos(latRad)
        val y = Math.toRadians(point.latitude - origin.latitude) * EARTH_RADIUS_METERS
        return x to y
    }

    /**
     * Circular mean + mean resultant length over [bearingsDegrees]. R near 1 means the
     * bearings agree tightly; near 0 means dispersed or bimodal (e.g. two opposing
     * traffic streams). Null for empty input - never a fabricated statistic.
     */
    fun circularStats(bearingsDegrees: List<Double>): CircularStats? {
        if (bearingsDegrees.isEmpty()) return null
        val sumSin = bearingsDegrees.sumOf { sin(Math.toRadians(it)) }
        val sumCos = bearingsDegrees.sumOf { cos(Math.toRadians(it)) }
        val n = bearingsDegrees.size.toDouble()
        val mean = (Math.toDegrees(atan2(sumSin, sumCos)) + 360.0) % 360.0
        val resultantLength = sqrt(sumSin * sumSin + sumCos * sumCos) / n
        return CircularStats(mean, resultantLength)
    }

    /** Confidence-weighted circular mean; null when inputs are empty or all weights are zero. */
    fun weightedCircularMeanDegrees(bearingsDegrees: List<Double>, weights: List<Double>): Double? {
        if (bearingsDegrees.isEmpty() || bearingsDegrees.size != weights.size) return null
        var sumSin = 0.0
        var sumCos = 0.0
        for (i in bearingsDegrees.indices) {
            sumSin += weights[i] * sin(Math.toRadians(bearingsDegrees[i]))
            sumCos += weights[i] * cos(Math.toRadians(bearingsDegrees[i]))
        }
        if (sumSin == 0.0 && sumCos == 0.0) return null
        return (Math.toDegrees(atan2(sumSin, sumCos)) + 360.0) % 360.0
    }
}

/** Circular mean bearing and mean resultant length R (1.0 = perfectly aligned, 0 = dispersed). */
data class CircularStats(val meanDegrees: Double, val resultantLength: Double)
