package com.trafficwatch.server.geo

import com.trafficwatch.server.reports.dto.LocationSampleDto
import com.trafficwatch.server.reports.dto.RotationSampleDto
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class OrientationTimelineTest {

    private fun rotation(capturedAt: Long, headingDegrees: Double) =
        RotationSampleDto(headingDegrees = headingDegrees, capturedAt = capturedAt)

    private fun location(capturedAt: Long, bearing: Double, speed: Double = 5.0) = LocationSampleDto(
        latitude = 0.0, longitude = 0.0, accuracy = 5.0, altitude = 0.0,
        bearing = bearing, speed = speed, capturedAt = capturedAt,
    )

    private fun locationSample(capturedAt: Long, speed: Double, bearing: Double = 0.0) =
        LocationSampleDto(
            latitude = 31.52, longitude = 74.35, accuracy = 5.0, altitude = 210.0,
            bearing = bearing, speed = speed, capturedAt = capturedAt,
        )

    @Test
    fun `interpolates between two bracketing rotation samples weighted by time distance`() {
        val timeline = OrientationTimeline(
            locationSamples = emptyList(),
            rotationSamples = listOf(rotation(1000L, 0.0), rotation(2000L, 90.0)),
        )

        // Anchor is the earliest sample (1000L). elapsedMs=500 -> target epoch 1500,
        // exactly halfway between the two samples -> exactly halfway between 0 and 90.
        val resolved = timeline.orientationAt(500L)

        assertEquals(45.0, resolved!!.bearingDegrees, 1e-6)
        assertEquals(OrientationSource.ROTATION, resolved.source)
    }

    @Test
    fun `uses the nearest sample unweighted when the target is before the first sample`() {
        val timeline = OrientationTimeline(
            locationSamples = emptyList(),
            rotationSamples = listOf(rotation(1000L, 10.0), rotation(2000L, 90.0)),
        )

        // elapsedMs=0 -> target epoch 1000, exactly the first (and thus also "before or at").
        assertEquals(10.0, timeline.orientationAt(0L)!!.bearingDegrees, 1e-6)
    }

    @Test
    fun `uses the nearest sample unweighted when the target is after the last sample`() {
        val timeline = OrientationTimeline(
            locationSamples = emptyList(),
            rotationSamples = listOf(rotation(1000L, 10.0), rotation(2000L, 90.0)),
        )

        // Anchor 1000L, elapsedMs=5000 -> target epoch 6000, well past the last sample (2000).
        assertEquals(90.0, timeline.orientationAt(5000L)!!.bearingDegrees, 1e-6)
    }

    @Test
    fun `falls back to location samples' bearing when there are no rotation samples`() {
        val timeline = OrientationTimeline(
            locationSamples = listOf(location(1000L, 0.0), location(2000L, 90.0)),
            rotationSamples = emptyList(),
        )

        val resolved = timeline.orientationAt(500L)

        assertEquals(45.0, resolved!!.bearingDegrees, 1e-6)
        assertEquals(OrientationSource.LOCATION, resolved.source)
    }

    @Test
    fun `never blends rotation and location samples even when both exist`() {
        val timeline = OrientationTimeline(
            locationSamples = listOf(location(1000L, 200.0)),
            rotationSamples = listOf(rotation(1000L, 10.0), rotation(2000L, 90.0)),
        )

        // Rotation exists, so location's very different bearing (200.0) must be ignored entirely.
        val resolved = timeline.orientationAt(500L)

        assertEquals(50.0, resolved!!.bearingDegrees, 1e-6)
        assertEquals(OrientationSource.ROTATION, resolved.source)
    }

    @Test
    fun `returns null when both sample lists are empty`() {
        val timeline = OrientationTimeline(locationSamples = emptyList(), rotationSamples = emptyList())

        assertNull(timeline.orientationAt(500L))
    }

    @Test
    fun `a single rotation sample is returned directly with no interpolation`() {
        val timeline = OrientationTimeline(
            locationSamples = emptyList(),
            rotationSamples = listOf(rotation(1000L, 42.0)),
        )

        assertEquals(42.0, timeline.orientationAt(999_999L)!!.bearingDegrees, 1e-6)
        assertEquals(42.0, timeline.orientationAt(0L)!!.bearingDegrees, 1e-6)
    }

    @Test
    fun `interpolates correctly through the 0-360 wraparound`() {
        // 350 degrees to 10 degrees should interpolate through 0/360 (landing near 0),
        // never through 180 (which a naive numeric average of 350 and 10 would give: 180.0).
        val timeline = OrientationTimeline(
            locationSamples = emptyList(),
            rotationSamples = listOf(rotation(1000L, 350.0), rotation(2000L, 10.0)),
        )

        val resolved = timeline.orientationAt(500L)!!.bearingDegrees

        assertEquals(0.0, resolved, 1e-6)
    }

    @Test
    fun `excludes a location sample below the reliable-bearing speed threshold, falling back to null`() {
        // speed 0.0 is exactly Android's "no direction of travel could be determined"
        // signal - bearing 45.0 here is unreliable/garbage and must never be surfaced.
        val timeline = OrientationTimeline(
            locationSamples = listOf(location(1000L, bearing = 45.0, speed = 0.0)),
            rotationSamples = emptyList(),
        )

        // LOCATION must be treated as wholly unavailable (not "resolved to 45.0") so the
        // caller's own fallback (e.g. report.compassHeadingDegrees) can correctly apply.
        assertNull(timeline.orientationAt(0L))
    }

    @Test
    fun `uses a location sample at or above the reliable-bearing speed threshold normally`() {
        val timeline = OrientationTimeline(
            locationSamples = listOf(
                location(1000L, bearing = 45.0, speed = MIN_SPEED_FOR_RELIABLE_BEARING_MPS),
            ),
            rotationSamples = emptyList(),
        )

        val resolved = timeline.orientationAt(0L)

        assertEquals(45.0, resolved!!.bearingDegrees, 1e-6)
        assertEquals(OrientationSource.LOCATION, resolved.source)
    }

    @Test
    fun `filters out only the below-threshold location samples, interpolating from the surviving ones`() {
        val timeline = OrientationTimeline(
            locationSamples = listOf(
                // Below threshold - excluded. If this weren't filtered, it would anchor
                // the timeline earlier and/or be blended into interpolation.
                location(500L, bearing = 270.0, speed = 0.0),
                location(1000L, bearing = 0.0, speed = 2.0),
                location(2000L, bearing = 90.0, speed = 2.0),
            ),
            rotationSamples = emptyList(),
        )

        // Anchor is still the earliest RAW sample (500L, per anchorEpochMs's own contract) -
        // elapsedMs=500 -> target epoch 1000, which lands exactly on the first surviving
        // (reliable) sample. If the excluded 270.0 sample had leaked into interpolation,
        // this would not land cleanly on 0.0.
        val resolved = timeline.orientationAt(500L)

        assertEquals(0.0, resolved!!.bearingDegrees, 1e-6)
        assertEquals(OrientationSource.LOCATION, resolved.source)
    }

    @Test
    fun `speed gate on location samples is irrelevant when rotation samples exist`() {
        val timeline = OrientationTimeline(
            // Below threshold AND a very different bearing than rotation's - if this
            // leaked through, the result would clearly reflect it (200.0 is nowhere near
            // rotation's 10.0/90.0 interpolated range).
            locationSamples = listOf(location(1000L, bearing = 200.0, speed = 0.0)),
            rotationSamples = listOf(rotation(1000L, 10.0), rotation(2000L, 90.0)),
        )

        val resolved = timeline.orientationAt(500L)

        assertEquals(50.0, resolved!!.bearingDegrees, 1e-6)
        assertEquals(OrientationSource.ROTATION, resolved.source)
    }

    @Test
    fun `returns null for an exactly-antipodal weighted mean rather than a fabricated value`() {
        // 0 and 180 degrees weighted 50/50 is a genuine degenerate case for a circular
        // mean (the vectors cancel exactly) - BearingMath.weightedCircularMeanDegrees
        // returns null here, and OrientationTimeline must propagate that, not fabricate
        // an arbitrary answer.
        val timeline = OrientationTimeline(
            locationSamples = emptyList(),
            rotationSamples = listOf(rotation(1000L, 0.0), rotation(2000L, 180.0)),
        )

        assertNull(timeline.orientationAt(500L))
    }

    @Test
    fun `recordingSpeedMetersPerSecondAt returns the nearest location sample's speed`() {
        val timeline = OrientationTimeline(
            locationSamples = listOf(location(1000L, bearing = 0.0, speed = 3.0)),
            rotationSamples = emptyList(),
        )

        assertEquals(3.0, timeline.recordingSpeedMetersPerSecondAt(500L))
    }

    @Test
    fun `recordingSpeedMetersPerSecondAt returns null when there are no location samples`() {
        val timeline = OrientationTimeline(locationSamples = emptyList(), rotationSamples = emptyList())

        assertNull(timeline.recordingSpeedMetersPerSecondAt(500L))
    }

    @Test
    fun `wasStationaryThroughout is true when every location sample is at or below walking pace`() {
        val timeline = OrientationTimeline(
            locationSamples = listOf(
                locationSample(capturedAt = 0, speed = 0.0),
                locationSample(capturedAt = 1000, speed = 0.4),
                locationSample(capturedAt = 2000, speed = 0.9),
            ),
            rotationSamples = emptyList(),
        )
        assertThat(timeline.wasStationaryThroughout()).isTrue()
    }

    @Test
    fun `wasStationaryThroughout is false when any location sample shows real motion`() {
        val timeline = OrientationTimeline(
            locationSamples = listOf(
                locationSample(capturedAt = 0, speed = 0.0),
                locationSample(capturedAt = 1000, speed = 3.0),
            ),
            rotationSamples = emptyList(),
        )
        assertThat(timeline.wasStationaryThroughout()).isFalse()
    }

    @Test
    fun `wasStationaryThroughout is false when there are no location samples`() {
        val timeline = OrientationTimeline(locationSamples = emptyList(), rotationSamples = emptyList())
        assertThat(timeline.wasStationaryThroughout()).isFalse()
    }
}
