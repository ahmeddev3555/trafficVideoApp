package com.trafficwatch.app.core.util

import org.junit.Assert.assertEquals
import org.junit.Test
import org.osmdroid.util.GeoPoint

class GeoMathTest {

    @Test
    fun `zero distance returns the same point regardless of bearing`() {
        val origin = GeoPoint(31.5204, 74.3587)

        val result = pointAtBearingAndDistance(origin, bearingDegrees = 45.0, distanceMeters = 0.0)

        assertEquals(origin.latitude, result.latitude, 1e-9)
        assertEquals(origin.longitude, result.longitude, 1e-9)
    }

    @Test
    fun `due north movement changes only latitude, by exactly the angular distance`() {
        val origin = GeoPoint(31.5204, 74.3587)
        // angularDistance = distanceMeters / EARTH_RADIUS_METERS = 63710.0 / 6371000.0 = 0.01 rad exactly
        val distanceMeters = 63710.0

        val result = pointAtBearingAndDistance(origin, bearingDegrees = 0.0, distanceMeters = distanceMeters)

        // For bearing 0, lat2 = lat1 + angularDistance (spherical angle-addition identity) -
        // toDegrees(0.01) = 0.5729577951308232
        assertEquals(31.5204 + 0.5729577951308232, result.latitude, 1e-6)
        assertEquals(74.3587, result.longitude, 1e-6)
    }

    @Test
    fun `due east movement at the equator changes only longitude, by exactly the angular distance`() {
        val origin = GeoPoint(0.0, 0.0)
        val distanceMeters = 63710.0 // angularDistance = 0.01 rad exactly

        val result = pointAtBearingAndDistance(origin, bearingDegrees = 90.0, distanceMeters = distanceMeters)

        assertEquals(0.0, result.latitude, 1e-6)
        assertEquals(0.5729577951308232, result.longitude, 1e-6)
    }

    @Test
    fun `due west movement at the equator via a negative bearing changes only longitude, by exactly the negative angular distance`() {
        // Location.distanceBetween's real bearing range is [-180, 180], so negative bearings
        // (unlike the all-positive 0/45/90 used above) are the common case in practice - this
        // mirrors the due-east test exactly (same atan2(sin(d), cos(d)) = d identity), just
        // negated, since bearing -90 is due west.
        val origin = GeoPoint(0.0, 0.0)
        val distanceMeters = 63710.0 // angularDistance = 0.01 rad exactly

        val result = pointAtBearingAndDistance(origin, bearingDegrees = -90.0, distanceMeters = distanceMeters)

        assertEquals(0.0, result.latitude, 1e-6)
        assertEquals(-0.5729577951308232, result.longitude, 1e-6)
    }
}
