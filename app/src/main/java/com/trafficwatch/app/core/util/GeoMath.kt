package com.trafficwatch.app.core.util

import org.osmdroid.util.GeoPoint
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

private const val EARTH_RADIUS_METERS = 6371000.0

/**
 * The point [distanceMeters] from [origin] along initial bearing [bearingDegrees] (clockwise
 * from north), using standard spherical-projection trigonometry. Pure function - no Android
 * dependencies - so it's directly unit-testable.
 */
fun pointAtBearingAndDistance(origin: GeoPoint, bearingDegrees: Double, distanceMeters: Double): GeoPoint {
    val bearingRad = Math.toRadians(bearingDegrees)
    val lat1 = Math.toRadians(origin.latitude)
    val lon1 = Math.toRadians(origin.longitude)
    val angularDistance = distanceMeters / EARTH_RADIUS_METERS

    val lat2 = asin(sin(lat1) * cos(angularDistance) + cos(lat1) * sin(angularDistance) * cos(bearingRad))
    val lon2 = lon1 + atan2(
        sin(bearingRad) * sin(angularDistance) * cos(lat1),
        cos(angularDistance) - sin(lat1) * sin(lat2)
    )

    return GeoPoint(Math.toDegrees(lat2), Math.toDegrees(lon2))
}
