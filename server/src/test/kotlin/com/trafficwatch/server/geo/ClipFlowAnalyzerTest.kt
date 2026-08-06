package com.trafficwatch.server.geo

import com.trafficwatch.server.reports.AnalysisProperties
import com.trafficwatch.server.videoanalysis.dto.BoundingBox
import com.trafficwatch.server.videoanalysis.dto.VehicleAnalysisResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClipFlowAnalyzerTest {

    private val analyzer = ClipFlowAnalyzer(AnalysisProperties())

    private fun vehicle(
        trackId: Long,
        bearing: Double?,
        corridorId: Long? = 0L,
        cohesion: Double? = 1.0,
        frames: Int? = 10,
        displacement: Double? = 200.0,
        detectionConfidence: Double = 0.9,
        // 50x50 -> diagonal ~70.7px, so the 0.15 floor is ~10.6px; the default
        // displacement of 200.0 clears it trivially, same as it cleared the old
        // frame-relative floor, so unrelated tests need no other changes.
        boundingBox: BoundingBox? = BoundingBox(x1 = 0.0, y1 = 0.0, x2 = 50.0, y2 = 50.0),
        trackMidpointMs: Long? = null,
        bearingSource: String? = null,
    ) = VehicleAnalysisResult(
        trackId = trackId,
        vehicleType = "car",
        detectionConfidence = detectionConfidence,
        bearingDegrees = bearing,
        bearingSource = bearingSource,
        plateText = null,
        plateConfidence = null,
        boundingBox = boundingBox,
        corridorId = corridorId,
        corridorCohesion = cohesion,
        trackFrameCount = frames,
        displacementPixels = displacement,
        trackMidpointMs = trackMidpointMs,
    )

    @Test
    fun `qualifyVehicles converts frame bearing to absolute with compass heading`() {
        val result = analyzer.qualifyVehicles(listOf(vehicle(1, bearing = 90.0)), 45.0, 1920, 1080)
        assertEquals(1, result.size)
        assertEquals(135.0, result[0].absoluteBearingDegrees, 1e-9)
    }

    @Test
    fun `qualifyVehicles drops null bearings null corridor fields null bounding box and null frame dims`() {
        assertTrue(analyzer.qualifyVehicles(listOf(vehicle(1, bearing = null)), 0.0, 1920, 1080).isEmpty())
        assertTrue(analyzer.qualifyVehicles(listOf(vehicle(1, 90.0, corridorId = null)), 0.0, 1920, 1080).isEmpty())
        assertTrue(analyzer.qualifyVehicles(listOf(vehicle(1, 90.0, boundingBox = null)), 0.0, 1920, 1080).isEmpty())
        assertTrue(analyzer.qualifyVehicles(listOf(vehicle(1, 90.0)), 0.0, null, null).isEmpty())
    }

    @Test
    fun `qualifyVehicles enforces the quality floor`() {
        // Too few frames.
        assertTrue(analyzer.qualifyVehicles(listOf(vehicle(1, 90.0, frames = 2)), 0.0, 1920, 1080).isEmpty())
        // Displacement under 15% of the vehicle's own bbox diagonal (default 50x50 bbox,
        // diagonal ~70.7px, floor ~10.6px).
        assertTrue(analyzer.qualifyVehicles(listOf(vehicle(1, 90.0, displacement = 5.0)), 0.0, 1920, 1080).isEmpty())
    }

    @Test
    fun `a small close-up vehicle qualifies on modest absolute displacement relative to its own size`() {
        // Mirrors a real production case: a motorcycle close to the camera with a small
        // bounding box (~100.1px diagonal) and a displacement (34.6px) that would have
        // failed the OLD frame-relative floor (5% of a 1920x1080 frame's ~2202.9px
        // diagonal = ~110.1px) but is ~35% of its OWN bbox diagonal - clearly real
        // motion, not jitter.
        val closeBbox = BoundingBox(x1 = 453.6, y1 = 1049.4, x2 = 508.8, y2 = 1132.9)
        val result = analyzer.qualifyVehicles(
            listOf(vehicle(1, 171.0, displacement = 34.6, boundingBox = closeBbox)), 0.0, 1920, 1080
        )
        assertEquals(1, result.size)
    }

    @Test
    fun `trackQuality saturates at one and scales below the saturation points`() {
        val full = analyzer.qualifyVehicles(listOf(vehicle(1, 90.0, frames = 10, displacement = 500.0)), 0.0, 1920, 1080)
        assertEquals(1.0, full[0].trackQuality, 1e-9)

        val partial = analyzer.qualifyVehicles(listOf(vehicle(1, 90.0, frames = 4, displacement = 500.0)), 0.0, 1920, 1080)
        assertEquals(0.8, partial[0].trackQuality, 1e-9) // min(4/5, 1) * 1
    }

    @Test
    fun `unimodal corridor yields consensus with expected stats`() {
        val flow = analyzer.qualifyVehicles(
            listOf(vehicle(1, 88.0), vehicle(2, 92.0), vehicle(3, 90.0)), 0.0, 1920, 1080
        )
        val consensus = analyzer.corridorConsensus(flow, corridorId = 0L, excluding = null)!!
        assertEquals(3, consensus.memberCount)
        assertEquals(90.0, consensus.bearingDegrees, 0.5)
        assertTrue(consensus.resultantLength > 0.99)
        // clipConfidence = (3/5) * R * meanCohesion ~ 0.6
        assertEquals(0.6, consensus.clipConfidence, 0.01)
    }

    @Test
    fun `bimodal corridor yields no consensus`() {
        val flow = analyzer.qualifyVehicles(
            listOf(vehicle(1, 90.0), vehicle(2, 270.0)), 0.0, 1920, 1080
        )
        assertNull(analyzer.corridorConsensus(flow, corridorId = 0L, excluding = null))
    }

    @Test
    fun `consensus excludes the candidate under evaluation`() {
        val flow = analyzer.qualifyVehicles(
            listOf(vehicle(1, 90.0), vehicle(2, 90.0), vehicle(3, 270.0)), 0.0, 1920, 1080
        )
        val candidate = flow.first { it.vehicle.trackId == 3L }
        val consensus = analyzer.corridorConsensus(flow, 0L, excluding = candidate)!!
        assertEquals(2, consensus.memberCount)
        assertEquals(90.0, consensus.bearingDegrees, 1e-6)
    }

    @Test
    fun `movesWith is true within agreement tolerance and false when opposing`() {
        val flow = analyzer.qualifyVehicles(
            listOf(vehicle(1, 90.0), vehicle(2, 92.0), vehicle(3, 268.0, corridorId = 1L)), 0.0, 1920, 1080
        )
        val with = flow.first { it.vehicle.trackId == 2L }
        val against = flow.first { it.vehicle.trackId == 3L }
        val consensus = analyzer.corridorConsensus(flow, 0L, excluding = null)!!
        assertTrue(analyzer.movesWith(with, consensus))
        assertFalse(analyzer.movesWith(against, consensus))
    }

    @Test
    fun `consensus of an empty corridor is null`() {
        assertNull(analyzer.corridorConsensus(emptyList(), 0L, excluding = null))
    }

    @Test
    fun `hasPeerSupport is true when a corridor peer is within agreement tolerance`() {
        // Mirrors the real report this fix was diagnosed from: candidate at 257.7, one peer
        // at 262.9 (5.2 degrees away, well within the default 45-degree tolerance).
        val flow = analyzer.qualifyVehicles(
            listOf(vehicle(1, 257.7), vehicle(2, 262.9)), 0.0, 1920, 1080
        )
        val candidate = flow.first { it.vehicle.trackId == 1L }
        assertTrue(analyzer.hasPeerSupport(flow, candidate))
    }

    @Test
    fun `hasPeerSupport is false when every corridor peer is beyond agreement tolerance`() {
        // Mirrors the existing "contested corridor, never falsely confirmed" scenario: three
        // vehicles pairwise 120 degrees apart, no pair within the 45-degree tolerance.
        val flow = analyzer.qualifyVehicles(
            listOf(vehicle(1, 270.0), vehicle(2, 30.0), vehicle(3, 150.0)), 0.0, 1920, 1080
        )
        val candidate = flow.first { it.vehicle.trackId == 1L }
        assertFalse(analyzer.hasPeerSupport(flow, candidate))
    }

    @Test
    fun `hasPeerSupport is false for a candidate alone in its corridor`() {
        val flow = analyzer.qualifyVehicles(listOf(vehicle(1, 90.0)), 0.0, 1920, 1080)
        val candidate = flow.first { it.vehicle.trackId == 1L }
        assertFalse(analyzer.hasPeerSupport(flow, candidate))
    }

    @Test
    fun `qualifyVehicles resolves orientation per-vehicle from the orientation timeline when trackMidpointMs is set`() {
        val timeline = OrientationTimeline(
            locationSamples = emptyList(),
            rotationSamples = listOf(
                com.trafficwatch.server.reports.dto.RotationSampleDto(headingDegrees = 45.0, capturedAt = 1000L),
            ),
        )
        val result = analyzer.qualifyVehicles(
            listOf(vehicle(1, bearing = 90.0, trackMidpointMs = 500L)),
            compassHeadingDegrees = 0.0, // stale scalar - must be ignored since the timeline resolves
            frameWidth = 1920, frameHeight = 1080,
            orientationTimeline = timeline,
        )

        assertEquals(1, result.size)
        // 45.0 (from timeline, not the 0.0 scalar) + 90.0 frame bearing = 135.0.
        assertEquals(135.0, result[0].absoluteBearingDegrees, 1e-9)
        assertEquals(OrientationSource.ROTATION, result[0].orientationSource)
    }

    @Test
    fun `qualifyVehicles falls back to the compass scalar when trackMidpointMs is null`() {
        val timeline = OrientationTimeline(
            locationSamples = emptyList(),
            rotationSamples = listOf(
                com.trafficwatch.server.reports.dto.RotationSampleDto(headingDegrees = 45.0, capturedAt = 1000L),
            ),
        )
        val result = analyzer.qualifyVehicles(
            listOf(vehicle(1, bearing = 90.0, trackMidpointMs = null)), // old video-analysis service
            compassHeadingDegrees = 10.0,
            frameWidth = 1920, frameHeight = 1080,
            orientationTimeline = timeline,
        )

        assertEquals(1, result.size)
        assertEquals(100.0, result[0].absoluteBearingDegrees, 1e-9) // 10.0 (scalar) + 90.0
        assertNull(result[0].orientationSource)
    }

    @Test
    fun `qualifyVehicles falls back to the compass scalar when the timeline has no samples at all`() {
        val emptyTimeline = OrientationTimeline(locationSamples = emptyList(), rotationSamples = emptyList())
        val result = analyzer.qualifyVehicles(
            listOf(vehicle(1, bearing = 90.0, trackMidpointMs = 500L)),
            compassHeadingDegrees = 10.0,
            frameWidth = 1920, frameHeight = 1080,
            orientationTimeline = emptyTimeline,
        )

        assertEquals(1, result.size)
        assertEquals(100.0, result[0].absoluteBearingDegrees, 1e-9)
        assertNull(result[0].orientationSource)
    }

    @Test
    fun `qualifyVehicles drops a vehicle when neither the timeline nor the compass scalar can resolve an orientation`() {
        val emptyTimeline = OrientationTimeline(locationSamples = emptyList(), rotationSamples = emptyList())
        val result = analyzer.qualifyVehicles(
            listOf(vehicle(1, bearing = 90.0, trackMidpointMs = 500L)),
            compassHeadingDegrees = null,
            frameWidth = 1920, frameHeight = 1080,
            orientationTimeline = emptyTimeline,
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `qualifiesForFlowExceptOrientation is true for a vehicle that passes every other gate`() {
        assertTrue(analyzer.qualifiesForFlowExceptOrientation(vehicle(1, bearing = 90.0), 1920, 1080))
    }

    @Test
    fun `qualifiesForFlowExceptOrientation is false when a non-orientation gate fails`() {
        assertFalse(analyzer.qualifiesForFlowExceptOrientation(vehicle(1, bearing = null), 1920, 1080))
        assertFalse(analyzer.qualifiesForFlowExceptOrientation(vehicle(1, 90.0, corridorId = null), 1920, 1080))
        assertFalse(analyzer.qualifiesForFlowExceptOrientation(vehicle(1, 90.0, frames = 2), 1920, 1080))
        assertFalse(analyzer.qualifiesForFlowExceptOrientation(vehicle(1, 90.0, displacement = 5.0), 1920, 1080))
        assertFalse(analyzer.qualifiesForFlowExceptOrientation(vehicle(1, 90.0), null, null))
    }

    @Test
    fun `qualifyVehicles still works exactly as before when orientationTimeline is omitted`() {
        val result = analyzer.qualifyVehicles(listOf(vehicle(1, bearing = 90.0)), 45.0, 1920, 1080)

        assertEquals(1, result.size)
        assertEquals(135.0, result[0].absoluteBearingDegrees, 1e-9)
        assertNull(result[0].orientationSource)
    }

    @Test
    fun `qualifyVehicles trusts a scale-sourced bearing when the recording vehicle's own speed was low`() {
        val timeline = OrientationTimeline(
            locationSamples = listOf(
                com.trafficwatch.server.reports.dto.LocationSampleDto(
                    latitude = 0.0, longitude = 0.0, accuracy = 5.0, altitude = 0.0,
                    bearing = 0.0, speed = 0.5, capturedAt = 1000L,
                ),
            ),
            rotationSamples = emptyList(),
        )
        val result = analyzer.qualifyVehicles(
            listOf(vehicle(1, bearing = 90.0, trackMidpointMs = 0L, bearingSource = "scale")),
            compassHeadingDegrees = 0.0,
            frameWidth = 1920, frameHeight = 1080,
            orientationTimeline = timeline,
        )

        assertEquals(1, result.size)
    }

    @Test
    fun `qualifyVehicles drops a scale-sourced bearing when the recording vehicle's own speed was high`() {
        val timeline = OrientationTimeline(
            locationSamples = listOf(
                com.trafficwatch.server.reports.dto.LocationSampleDto(
                    latitude = 0.0, longitude = 0.0, accuracy = 5.0, altitude = 0.0,
                    bearing = 0.0, speed = 15.0, capturedAt = 1000L,
                ),
            ),
            rotationSamples = emptyList(),
        )
        val result = analyzer.qualifyVehicles(
            listOf(vehicle(1, bearing = 90.0, trackMidpointMs = 0L, bearingSource = "scale")),
            compassHeadingDegrees = 0.0,
            frameWidth = 1920, frameHeight = 1080,
            orientationTimeline = timeline,
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `qualifyVehicles drops a scale-sourced bearing when no location samples exist to verify recording speed`() {
        val timeline = OrientationTimeline(locationSamples = emptyList(), rotationSamples = emptyList())
        val result = analyzer.qualifyVehicles(
            listOf(vehicle(1, bearing = 90.0, trackMidpointMs = 0L, bearingSource = "scale")),
            compassHeadingDegrees = 0.0,
            frameWidth = 1920, frameHeight = 1080,
            orientationTimeline = timeline,
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `qualifyVehicles does not gate a centroid-sourced bearing on recording speed`() {
        val result = analyzer.qualifyVehicles(
            listOf(vehicle(1, bearing = 90.0, bearingSource = "centroid")),
            compassHeadingDegrees = 0.0,
            frameWidth = 1920, frameHeight = 1080,
        )

        assertEquals(1, result.size)
    }
}
