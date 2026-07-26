package com.trafficwatch.server.reports

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.Optional
import java.util.UUID

/**
 * Unit-level coverage of [ReportAnalysisJob]'s business logic (the 80/20 status-flip
 * decision and field population), exercised through the package-internal
 * [ReportAnalysisJob.applyOutcome] rather than the `@Async` entry point [ReportAnalysisJob.analyze] -
 * `applyOutcome` takes the random roll as a plain `Int` parameter so the CONFIRMED/REJECTED
 * boundary can be tested deterministically, without depending on real randomness or
 * `Thread.sleep`. The full async plumbing (delay, thread pool, wiring from
 * `ReportService.submit()`) is covered separately by `ReportAnalysisIntegrationTest`.
 */
class ReportAnalysisJobTest {

    private val reportRepository = mockk<ReportRepository>()
    private val analysisProperties = AnalysisProperties(delayMs = 0)
    private val job = ReportAnalysisJob(reportRepository, analysisProperties)

    private fun sampleReport(id: UUID, createdUpdatedAt: OffsetDateTime = OffsetDateTime.parse("2026-07-25T10:00:00Z")) =
        Report(
            userId = UUID.randomUUID(),
            videoPath = "/videos/$id.mp4",
            latitude = BigDecimal("31.520370"),
            longitude = BigDecimal("74.358749"),
            accuracy = BigDecimal("5.00"),
            altitude = BigDecimal("210.50"),
            bearing = BigDecimal("87.30"),
            speed = BigDecimal("12.40"),
            recordedAt = LocalDateTime.of(2026, 7, 25, 10, 0, 0),
            durationMs = 15000L,
            deviceId = "device-123",
            status = ReportStatus.PENDING,
            updatedAt = createdUpdatedAt,
        ).apply { this.id = id }

    @Test
    fun `applyOutcome with a roll under 80 flips status to CONFIRMED with non-null placeholder fields`() {
        val reportId = UUID.randomUUID()
        val original = sampleReport(reportId)
        val savedSlot = slot<Report>()
        every { reportRepository.findById(reportId) } returns Optional.of(original)
        every { reportRepository.save(capture(savedSlot)) } answers { firstArg() }

        job.applyOutcome(reportId, roll = 0)

        val saved = savedSlot.captured
        assertThat(saved.status).isEqualTo(ReportStatus.CONFIRMED)
        assertThat(saved.licensePlate).isNotNull()
        assertThat(saved.confidence).isNotNull()
        assertThat(saved.analysisMessage).isNotNull()
        verify(exactly = 1) { reportRepository.save(any()) }
    }

    @Test
    fun `applyOutcome with a roll of 79 (just under the 80 boundary) is still CONFIRMED`() {
        val reportId = UUID.randomUUID()
        val original = sampleReport(reportId)
        every { reportRepository.findById(reportId) } returns Optional.of(original)
        every { reportRepository.save(any()) } answers { firstArg() }

        job.applyOutcome(reportId, roll = 79)

        assertThat(original.status).isEqualTo(ReportStatus.CONFIRMED)
    }

    @Test
    fun `applyOutcome with a roll of exactly 80 flips status to REJECTED`() {
        val reportId = UUID.randomUUID()
        val original = sampleReport(reportId)
        every { reportRepository.findById(reportId) } returns Optional.of(original)
        every { reportRepository.save(any()) } answers { firstArg() }

        job.applyOutcome(reportId, roll = 80)

        assertThat(original.status).isEqualTo(ReportStatus.REJECTED)
        assertThat(original.licensePlate).isNull()
        assertThat(original.confidence).isNull()
    }

    @Test
    fun `applyOutcome with a roll of 99 is REJECTED`() {
        val reportId = UUID.randomUUID()
        val original = sampleReport(reportId)
        every { reportRepository.findById(reportId) } returns Optional.of(original)
        every { reportRepository.save(any()) } answers { firstArg() }

        job.applyOutcome(reportId, roll = 99)

        assertThat(original.status).isEqualTo(ReportStatus.REJECTED)
    }

    @Test
    fun `applyOutcome refreshes updatedAt to a time strictly after the original value`() {
        val reportId = UUID.randomUUID()
        val original = sampleReport(reportId, createdUpdatedAt = OffsetDateTime.parse("2020-01-01T00:00:00Z"))
        every { reportRepository.findById(reportId) } returns Optional.of(original)
        every { reportRepository.save(any()) } answers { firstArg() }

        job.applyOutcome(reportId, roll = 0)

        assertThat(original.updatedAt).isAfter(OffsetDateTime.parse("2020-01-01T00:00:00Z"))
    }

    @Test
    fun `applyOutcome for a report that no longer exists does nothing and never saves`() {
        val reportId = UUID.randomUUID()
        every { reportRepository.findById(reportId) } returns Optional.empty()

        job.applyOutcome(reportId, roll = 0)

        verify(exactly = 0) { reportRepository.save(any()) }
    }
}
