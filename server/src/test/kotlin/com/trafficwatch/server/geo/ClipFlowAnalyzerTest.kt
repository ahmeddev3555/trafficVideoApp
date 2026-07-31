package com.trafficwatch.server.geo

import com.trafficwatch.server.reports.AnalysisProperties
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
    ) = VehicleAnalysisResult(
        trackId = trackId,
        vehicleType = "car",
        detectionConfidence = detectionConfidence,
        bearingDegrees = bearing,
        plateText = null,
        plateConfidence = null,
        corridorId = corridorId,
        corridorCohesion = cohesion,
        trackFrameCount = frames,
        displacementPixels = displacement,
    )

    // frame 1920x1080 -> diagonal ~2202.9, 5% floor ~110.1px

    @Test
    fun `qualifyVehicles converts frame bearing to absolute with compass heading`() {
        val result = analyzer.qualifyVehicles(listOf(vehicle(1, bearing = 90.0)), 45.0, 1920, 1080)
        assertEquals(1, result.size)
        assertEquals(135.0, result[0].absoluteBearingDegrees, 1e-9)
    }

    @Test
    fun `qualifyVehicles drops null bearings null corridor fields and null frame dims`() {
        assertTrue(analyzer.qualifyVehicles(listOf(vehicle(1, bearing = null)), 0.0, 1920, 1080).isEmpty())
        assertTrue(analyzer.qualifyVehicles(listOf(vehicle(1, 90.0, corridorId = null)), 0.0, 1920, 1080).isEmpty())
        assertTrue(analyzer.qualifyVehicles(listOf(vehicle(1, 90.0)), 0.0, null, null).isEmpty())
    }

    @Test
    fun `qualifyVehicles enforces the quality floor`() {
        // Too few frames.
        assertTrue(analyzer.qualifyVehicles(listOf(vehicle(1, 90.0, frames = 2)), 0.0, 1920, 1080).isEmpty())
        // Displacement under 5% of diagonal (~110.1px).
        assertTrue(analyzer.qualifyVehicles(listOf(vehicle(1, 90.0, displacement = 50.0)), 0.0, 1920, 1080).isEmpty())
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
}
