package com.trafficwatch.server.geo

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

class BearingMathTest {

    @Test
    fun `initialBearingDegrees is 0 (north) for a point due north`() {
        val from = GeoPoint(31.5200, 74.3500)
        val to = GeoPoint(31.5300, 74.3500)

        assertThat(BearingMath.initialBearingDegrees(from, to)).isCloseTo(0.0, within(0.5))
    }

    @Test
    fun `initialBearingDegrees is 90 (east) for a point due east`() {
        val from = GeoPoint(31.5200, 74.3500)
        val to = GeoPoint(31.5200, 74.3600)

        assertThat(BearingMath.initialBearingDegrees(from, to)).isCloseTo(90.0, within(0.5))
    }

    @Test
    fun `initialBearingDegrees is 180 (south) for a point due south`() {
        val from = GeoPoint(31.5300, 74.3500)
        val to = GeoPoint(31.5200, 74.3500)

        assertThat(BearingMath.initialBearingDegrees(from, to)).isCloseTo(180.0, within(0.5))
    }

    @Test
    fun `initialBearingDegrees is 270 (west) for a point due west`() {
        val from = GeoPoint(31.5200, 74.3600)
        val to = GeoPoint(31.5200, 74.3500)

        assertThat(BearingMath.initialBearingDegrees(from, to)).isCloseTo(270.0, within(0.5))
    }

    @Test
    fun `initialBearingDegrees always returns a value between 0 inclusive and 360 exclusive`() {
        val from = GeoPoint(31.5200, 74.3500)
        val to = GeoPoint(31.5100, 74.3400)

        val bearing = BearingMath.initialBearingDegrees(from, to)
        assertThat(bearing).isGreaterThanOrEqualTo(0.0).isLessThan(360.0)
    }

    @Test
    fun `angularDifferenceDegrees between identical bearings is 0`() {
        assertThat(BearingMath.angularDifferenceDegrees(45.0, 45.0)).isEqualTo(0.0)
    }

    @Test
    fun `angularDifferenceDegrees between opposite bearings is 180`() {
        assertThat(BearingMath.angularDifferenceDegrees(0.0, 180.0)).isEqualTo(180.0)
        assertThat(BearingMath.angularDifferenceDegrees(90.0, 270.0)).isEqualTo(180.0)
    }

    @Test
    fun `angularDifferenceDegrees takes the shorter way around the compass`() {
        // 350 vs 10 are 20 degrees apart going through 0, not 340 the long way around
        assertThat(BearingMath.angularDifferenceDegrees(350.0, 10.0)).isCloseTo(20.0, within(0.001))
    }

    @Test
    fun `nearestSegmentIndex returns null for fewer than two nodes`() {
        assertThat(BearingMath.nearestSegmentIndex(GeoPoint(31.52, 74.35), emptyList())).isNull()
        assertThat(BearingMath.nearestSegmentIndex(GeoPoint(31.52, 74.35), listOf(GeoPoint(31.52, 74.35)))).isNull()
    }

    @Test
    fun `nearestSegmentIndex picks the closer of two segments`() {
        // A three-node way running north; the query point sits right next to the second
        // segment (between nodes 1 and 2), far from the first (between nodes 0 and 1).
        val nodes = listOf(
            GeoPoint(31.5000, 74.3500),
            GeoPoint(31.5100, 74.3500),
            GeoPoint(31.5200, 74.3500),
        )
        val point = GeoPoint(31.5150, 74.3501)

        assertThat(BearingMath.nearestSegmentIndex(point, nodes)).isEqualTo(1)
    }

    @Test
    fun `distanceToSegmentMeters is near zero for a point on the segment`() {
        val a = GeoPoint(31.5200, 74.3500)
        val b = GeoPoint(31.5300, 74.3500)
        val midpoint = GeoPoint(31.5250, 74.3500)

        assertThat(BearingMath.distanceToSegmentMeters(midpoint, a, b)).isCloseTo(0.0, within(1.0))
    }

    @Test
    fun `distanceToSegmentMeters for a point off the segment is roughly the perpendicular offset`() {
        // ~0.0009 degrees longitude at this latitude is roughly 85m east of the segment.
        val a = GeoPoint(31.5200, 74.3500)
        val b = GeoPoint(31.5300, 74.3500)
        val offsetPoint = GeoPoint(31.5250, 74.3509)

        val distance = BearingMath.distanceToSegmentMeters(offsetPoint, a, b)
        assertThat(distance).isCloseTo(85.0, within(15.0))
    }
}
