package com.trafficwatch.server.geo

import com.trafficwatch.server.reports.AnalysisProperties
import com.trafficwatch.server.reports.Report
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

class FlowObservationServiceTest {

    private val repository = mock<FlowObservationRepository>()
    private val service = FlowObservationService(repository, AnalysisProperties())

    private fun report(lat: String = "31.4685846", lon: String = "74.4057830") = Report(
        userId = UUID.randomUUID(),
        videoPath = "v.mp4",
        latitude = BigDecimal(lat),
        longitude = BigDecimal(lon),
        accuracy = BigDecimal.ONE,
        altitude = BigDecimal.ZERO,
        bearing = BigDecimal.ZERO,
        speed = BigDecimal.ZERO,
        recordedAt = LocalDateTime.now(),
        durationMs = 5000,
        deviceId = "d",
        id = UUID.randomUUID(),
    )

    private fun consensus(members: Int, r: Double = 0.95, bearing: Double = 90.0) =
        CorridorConsensus(corridorId = 0, bearingDegrees = bearing, resultantLength = r, memberCount = members, meanCohesion = 1.0)

    private fun observation(bearing: Double, reporter: UUID = UUID.randomUUID()) = FlowObservation(
        latBucket = BigDecimal("31.4686"),
        lonBucket = BigDecimal("74.4058"),
        bearingDegrees = BigDecimal.valueOf(bearing),
        vehicleCount = 3,
        resultantLength = BigDecimal("0.950"),
        reporterId = reporter,
        reportId = UUID.randomUUID(),
    )

    @Test
    fun `ingest writes one row per qualifying consensus with bucketed coordinates`() {
        service.ingest(report(), listOf(consensus(members = 3)))

        val captor = argumentCaptor<FlowObservation>()
        verify(repository).save(captor.capture())
        val row = captor.firstValue
        assertEquals(BigDecimal("31.4686"), row.latBucket)
        assertEquals(BigDecimal("74.4058"), row.lonBucket)
        assertEquals(3, row.vehicleCount)
    }

    @Test
    fun `ingest skips consensuses with fewer than two members`() {
        service.ingest(report(), listOf(consensus(members = 1)))
        verify(repository, never()).save(any())
    }

    @Test
    fun `ingest skips consensuses below the resultant length gate`() {
        service.ingest(report(), listOf(consensus(members = 3, r = 0.5)))
        verify(repository, never()).save(any())
    }

    @Test
    fun `history evidence requires minimum observation count`() {
        whenever(repository.findByLatBucketAndLonBucket(any(), any()))
            .thenReturn(List(4) { observation(90.0) }) // below historyMinObservations = 5
        assertNull(service.historyEvidence(BigDecimal("31.4685846"), BigDecimal("74.4057830")))
    }

    @Test
    fun `history evidence requires distinct reporters`() {
        val oneReporter = UUID.randomUUID()
        whenever(repository.findByLatBucketAndLonBucket(any(), any()))
            .thenReturn(List(6) { observation(90.0, reporter = oneReporter) })
        assertNull(service.historyEvidence(BigDecimal("31.4685846"), BigDecimal("74.4057830")))
    }

    @Test
    fun `history evidence requires unimodal distribution`() {
        val rows = List(3) { observation(90.0) } + List(3) { observation(270.0) }
        whenever(repository.findByLatBucketAndLonBucket(any(), any())).thenReturn(rows)
        assertNull(service.historyEvidence(BigDecimal("31.4685846"), BigDecimal("74.4057830")))
    }

    @Test
    fun `mature history yields evidence with the documented confidence curve`() {
        whenever(repository.findByLatBucketAndLonBucket(any(), any()))
            .thenReturn(List(5) { observation(90.0) })
        val evidence = service.historyEvidence(BigDecimal("31.4685846"), BigDecimal("74.4057830"))

        assertNotNull(evidence)
        assertEquals(EvidenceKind.LEARNED_HISTORY, evidence!!.kind)
        assertEquals(90.0, evidence.bearingDegrees, 1e-6)
        // (5/(5+5)) * 1.0 = 0.5
        assertEquals(0.5, evidence.confidence, 1e-9)
        assertTrue(evidence.confidence < 0.9)
    }
}
