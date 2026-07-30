package com.trafficwatch.server.reports

import com.trafficwatch.server.geo.DirectionResolution
import com.trafficwatch.server.geo.StreetDirectionResolver
import com.trafficwatch.server.storage.VideoStorageService
import com.trafficwatch.server.storage.WrongWayFrameStorageService
import com.trafficwatch.server.videoanalysis.VideoAnalysisClient
import com.trafficwatch.server.videoanalysis.VideoAnalysisException
import com.trafficwatch.server.videoanalysis.dto.BoundingBox
import com.trafficwatch.server.videoanalysis.dto.VehicleAnalysisResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID

/**
 * Unit-level coverage of [ReportAnalysisJob]'s real decision logic, exercised through the
 * package-internal [ReportAnalysisJob.applyOutcome] with [StreetDirectionResolver] and
 * [VideoAnalysisClient] mocked - deterministic, no real HTTP calls, no randomness (unlike
 * the old stub's 80/20 dice roll this test used to assert). The full async plumbing
 * (afterCommit registration, thread pool) is covered separately by
 * `ReportAnalysisIntegrationTest`.
 */
class ReportAnalysisJobTest {

    private val reportRepository = mockk<ReportRepository>()
    private val analysisProperties = AnalysisProperties(wrongWayToleranceDegrees = 60.0)
    private val streetDirectionResolver = mockk<StreetDirectionResolver>()
    private val videoAnalysisClient = mockk<VideoAnalysisClient>()
    private val videoStorageService = mockk<VideoStorageService>()
    private val wrongWayFrameStorageService = mockk<WrongWayFrameStorageService>()

    private val job = ReportAnalysisJob(
        reportRepository,
        analysisProperties,
        streetDirectionResolver,
        videoAnalysisClient,
        videoStorageService,
        wrongWayFrameStorageService,
    )

    private val fakeVideoPath: Path = Path.of("/videos/fake.mp4")

    @BeforeEach
    fun stubVideoResolution() {
        every { videoStorageService.resolve(any()) } returns fakeVideoPath
    }

    private fun sampleReport(
        id: UUID = UUID.randomUUID(),
        compassHeadingDegrees: BigDecimal? = BigDecimal("90.0"),
        createdUpdatedAt: OffsetDateTime = OffsetDateTime.parse("2026-07-25T10:00:00Z"),
    ) = Report(
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
        compassHeadingDegrees = compassHeadingDegrees,
        updatedAt = createdUpdatedAt,
    ).apply { this.id = id }

    private fun vehicle(
        trackId: Long = 1,
        bearingDegrees: Double? = 90.0,
        plateText: String? = "LEA-1234",
        plateConfidence: Double? = 0.9,
        boundingBox: BoundingBox? = null,
        frameJpegBase64: String? = null,
    ) = VehicleAnalysisResult(
        trackId = trackId,
        vehicleType = "car",
        detectionConfidence = 0.8,
        bearingDegrees = bearingDegrees,
        plateText = plateText,
        plateConfidence = plateConfidence,
        boundingBox = boundingBox,
        frameJpegBase64 = frameJpegBase64,
    )

    @Test
    fun `applyOutcome rejects with a specific message when compass heading is missing`() {
        val report = sampleReport(compassHeadingDegrees = null)
        every { reportRepository.save(any()) } answers { firstArg() }

        job.applyOutcome(report)

        assertThat(report.status).isEqualTo(ReportStatus.REJECTED)
        assertThat(report.analysisMessage).isEqualTo("Device compass heading unavailable for this report")
        assertThat(report.licensePlate).isNull()
        assertThat(report.confidence).isNull()
        verify(exactly = 0) { streetDirectionResolver.resolve(any(), any()) }
    }

    @Test
    fun `applyOutcome rejects when no street can be identified at the location`() {
        val report = sampleReport()
        every { streetDirectionResolver.resolve(report.latitude, report.longitude) } returns DirectionResolution.NotFound
        every { reportRepository.save(any()) } answers { firstArg() }

        job.applyOutcome(report)

        assertThat(report.status).isEqualTo(ReportStatus.REJECTED)
        assertThat(report.analysisMessage).isEqualTo("Could not identify a street at this location")
        assertThat(report.streetName).isNull()
    }

    @Test
    fun `applyOutcome rejects with the street name when the legal direction is unknown`() {
        val report = sampleReport()
        every {
            streetDirectionResolver.resolve(report.latitude, report.longitude)
        } returns DirectionResolution.Unknown("Side Street")
        every { reportRepository.save(any()) } answers { firstArg() }

        job.applyOutcome(report)

        assertThat(report.status).isEqualTo(ReportStatus.REJECTED)
        assertThat(report.analysisMessage).isEqualTo("Legal traffic direction unknown for this street")
        assertThat(report.streetName).isEqualTo("Side Street")
    }

    @Test
    fun `applyOutcome rejects a two-way street since no wrong-way violation is possible`() {
        val report = sampleReport()
        every {
            streetDirectionResolver.resolve(report.latitude, report.longitude)
        } returns DirectionResolution.TwoWay("Two Way Ave")
        every { reportRepository.save(any()) } answers { firstArg() }

        job.applyOutcome(report)

        assertThat(report.status).isEqualTo(ReportStatus.REJECTED)
        assertThat(report.analysisMessage).isEqualTo("Street is two-way; no wrong-way violation is possible here")
        assertThat(report.streetName).isEqualTo("Two Way Ave")
    }

    @Test
    fun `applyOutcome rejects without caching-relevant side effects when the OSM lookup fails`() {
        val report = sampleReport()
        every {
            streetDirectionResolver.resolve(report.latitude, report.longitude)
        } returns DirectionResolution.LookupFailed("Overpass timed out")
        every { reportRepository.save(any()) } answers { firstArg() }

        job.applyOutcome(report)

        assertThat(report.status).isEqualTo(ReportStatus.REJECTED)
        assertThat(report.analysisMessage).isEqualTo("Street lookup temporarily failed: Overpass timed out")
        verify(exactly = 0) { videoAnalysisClient.analyze(any(), any()) }
    }

    @Test
    fun `applyOutcome rejects when the video analysis service call fails`() {
        val report = sampleReport()
        every {
            streetDirectionResolver.resolve(report.latitude, report.longitude)
        } returns DirectionResolution.OneWay("Main Boulevard", 90.0)
        every { videoAnalysisClient.analyze(fakeVideoPath, any()) } throws VideoAnalysisException("connection refused")
        every { reportRepository.save(any()) } answers { firstArg() }

        job.applyOutcome(report)

        assertThat(report.status).isEqualTo(ReportStatus.REJECTED)
        assertThat(report.analysisMessage).isEqualTo("Video analysis service unavailable: connection refused")
        assertThat(report.streetName).isEqualTo("Main Boulevard")
    }

    @Test
    fun `applyOutcome rejects when no vehicle is moving against the legal direction`() {
        val report = sampleReport(compassHeadingDegrees = BigDecimal("0.0"))
        every {
            streetDirectionResolver.resolve(report.latitude, report.longitude)
        } returns DirectionResolution.OneWay("Main Boulevard", 0.0)
        // Legal bearing 0, illegal bearing 180. A vehicle with an absolute bearing of 0
        // (same direction as legal) is not a wrong-way vehicle.
        every {
            videoAnalysisClient.analyze(fakeVideoPath, any())
        } returns listOf(vehicle(bearingDegrees = 0.0))
        every { reportRepository.save(any()) } answers { firstArg() }

        job.applyOutcome(report)

        assertThat(report.status).isEqualTo(ReportStatus.REJECTED)
        assertThat(report.analysisMessage).isEqualTo("No vehicles detected moving against the legal direction")
        assertThat(report.streetName).isEqualTo("Main Boulevard")
    }

    @Test
    fun `applyOutcome confirms and records the plate of a genuine wrong-way vehicle`() {
        val report = sampleReport(compassHeadingDegrees = BigDecimal("0.0"))
        every {
            streetDirectionResolver.resolve(report.latitude, report.longitude)
        } returns DirectionResolution.OneWay("Main Boulevard", 0.0)
        // Legal bearing 0, illegal bearing 180 - a vehicle whose frame-relative bearing
        // (added to the 0-degree compass heading) lands on 180 is moving the wrong way.
        every {
            videoAnalysisClient.analyze(fakeVideoPath, any())
        } returns listOf(vehicle(bearingDegrees = 180.0, plateText = "LEA-1234", plateConfidence = 0.9))
        every { reportRepository.save(any()) } answers { firstArg() }

        job.applyOutcome(report)

        assertThat(report.status).isEqualTo(ReportStatus.CONFIRMED)
        assertThat(report.licensePlate).isEqualTo("LEA-1234")
        assertThat(report.confidence).isEqualByComparingTo(BigDecimal("0.9"))
        assertThat(report.streetName).isEqualTo("Main Boulevard")
        assertThat(report.analysisMessage).contains("Main Boulevard")
    }

    @Test
    fun `applyOutcome picks the wrong-way vehicle with the highest plate confidence among several`() {
        val report = sampleReport(compassHeadingDegrees = BigDecimal("0.0"))
        every {
            streetDirectionResolver.resolve(report.latitude, report.longitude)
        } returns DirectionResolution.OneWay("Main Boulevard", 0.0)
        every {
            videoAnalysisClient.analyze(fakeVideoPath, any())
        } returns listOf(
            vehicle(trackId = 1, bearingDegrees = 180.0, plateText = "AAA-1111", plateConfidence = 0.4),
            vehicle(trackId = 2, bearingDegrees = 175.0, plateText = "BBB-2222", plateConfidence = 0.95),
            // Not a wrong-way vehicle at all (bearing near 0, same as legal direction).
            vehicle(trackId = 3, bearingDegrees = 5.0, plateText = "CCC-3333", plateConfidence = 0.99),
        )
        every { reportRepository.save(any()) } answers { firstArg() }

        job.applyOutcome(report)

        assertThat(report.status).isEqualTo(ReportStatus.CONFIRMED)
        assertThat(report.licensePlate).isEqualTo("BBB-2222")
    }

    @Test
    fun `applyOutcome confirms a wrong-way vehicle even when its plate could not be read`() {
        val report = sampleReport(compassHeadingDegrees = BigDecimal("0.0"))
        every {
            streetDirectionResolver.resolve(report.latitude, report.longitude)
        } returns DirectionResolution.OneWay("Main Boulevard", 0.0)
        every {
            videoAnalysisClient.analyze(fakeVideoPath, any())
        } returns listOf(vehicle(bearingDegrees = 180.0, plateText = null, plateConfidence = null))
        every { reportRepository.save(any()) } answers { firstArg() }

        job.applyOutcome(report)

        assertThat(report.status).isEqualTo(ReportStatus.CONFIRMED)
        assertThat(report.licensePlate).isNull()
        assertThat(report.confidence).isNull()
    }

    @Test
    fun `applyOutcome ignores vehicles with no bearing at all`() {
        val report = sampleReport(compassHeadingDegrees = BigDecimal("0.0"))
        every {
            streetDirectionResolver.resolve(report.latitude, report.longitude)
        } returns DirectionResolution.OneWay("Main Boulevard", 0.0)
        every {
            videoAnalysisClient.analyze(fakeVideoPath, any())
        } returns listOf(vehicle(bearingDegrees = null))
        every { reportRepository.save(any()) } answers { firstArg() }

        job.applyOutcome(report)

        assertThat(report.status).isEqualTo(ReportStatus.REJECTED)
        assertThat(report.analysisMessage).isEqualTo("No vehicles detected moving against the legal direction")
    }

    @Test
    fun `applyOutcome refreshes updatedAt to a time strictly after the original value`() {
        val report = sampleReport(
            compassHeadingDegrees = null,
            createdUpdatedAt = OffsetDateTime.parse("2020-01-01T00:00:00Z"),
        )
        every { reportRepository.save(any()) } answers { firstArg() }

        job.applyOutcome(report)

        assertThat(report.updatedAt).isAfter(OffsetDateTime.parse("2020-01-01T00:00:00Z"))
    }

    @Test
    fun `analyze does nothing and never saves when the report no longer exists`() {
        val reportId = UUID.randomUUID()
        every { reportRepository.findById(reportId) } returns java.util.Optional.empty()

        job.analyze(reportId)

        verify(exactly = 0) { reportRepository.save(any()) }
    }

    @Test
    fun `applyOutcome computes wrong-way confidence from detection confidence and bearing match tightness`() {
        val report = sampleReport(compassHeadingDegrees = BigDecimal("0.0"))
        every {
            streetDirectionResolver.resolve(report.latitude, report.longitude)
        } returns DirectionResolution.OneWay("Main Boulevard", 0.0)
        // Illegal bearing is 180 (legal 0 + 180); a vehicle at exactly 180 has
        // angularDistance 0 -> bearingMatchScore 1.0 -> confidence == detectionConfidence (0.8).
        every {
            videoAnalysisClient.analyze(fakeVideoPath, any())
        } returns listOf(vehicle(bearingDegrees = 180.0, plateConfidence = null))
        every { reportRepository.save(any()) } answers { firstArg() }

        job.applyOutcome(report)

        assertThat(report.wrongWayConfidence).isEqualByComparingTo(BigDecimal("0.8"))
    }

    @Test
    fun `applyOutcome gives a borderline wrong-way vehicle a lower confidence than a dead-on one`() {
        val report = sampleReport(compassHeadingDegrees = BigDecimal("0.0"))
        every {
            streetDirectionResolver.resolve(report.latitude, report.longitude)
        } returns DirectionResolution.OneWay("Main Boulevard", 0.0)
        // 150 is 30 degrees off the illegal bearing of 180 - within the 60-degree
        // tolerance, but not dead-on, so its confidence must be lower than 0.8.
        every {
            videoAnalysisClient.analyze(fakeVideoPath, any())
        } returns listOf(vehicle(bearingDegrees = 150.0, plateConfidence = null))
        every { reportRepository.save(any()) } answers { firstArg() }

        job.applyOutcome(report)

        assertThat(report.wrongWayConfidence).isLessThan(BigDecimal("0.8"))
    }

    @Test
    fun `applyOutcome stores an annotated frame and records its path for a wrong-way vehicle`() {
        val report = sampleReport(compassHeadingDegrees = BigDecimal("0.0"))
        every {
            streetDirectionResolver.resolve(report.latitude, report.longitude)
        } returns DirectionResolution.OneWay("Main Boulevard", 0.0)
        val boundingBox = BoundingBox(x1 = 10.0, y1 = 10.0, x2 = 50.0, y2 = 50.0)
        val fakeJpegBytes = byteArrayOf(1, 2, 3)
        val fakeFrameBase64 = Base64.getEncoder().encodeToString(fakeJpegBytes)
        every {
            videoAnalysisClient.analyze(fakeVideoPath, any())
        } returns listOf(vehicle(bearingDegrees = 180.0, boundingBox = boundingBox, frameJpegBase64 = fakeFrameBase64))
        every { reportRepository.save(any()) } answers { firstArg() }

        mockkObject(FrameAnnotator)
        try {
            every { FrameAnnotator.annotate(fakeJpegBytes, boundingBox) } returns byteArrayOf(9, 9, 9)
            every { wrongWayFrameStorageService.store(any(), byteArrayOf(9, 9, 9)) } returns "stored-frame.jpg"

            job.applyOutcome(report)

            assertThat(report.status).isEqualTo(ReportStatus.CONFIRMED)
            assertThat(report.wrongWayFramePath).isEqualTo("stored-frame.jpg")
        } finally {
            unmockkObject(FrameAnnotator)
        }
    }

    @Test
    fun `applyOutcome still confirms but leaves wrongWayFramePath null when frame storage fails`() {
        val report = sampleReport(compassHeadingDegrees = BigDecimal("0.0"))
        every {
            streetDirectionResolver.resolve(report.latitude, report.longitude)
        } returns DirectionResolution.OneWay("Main Boulevard", 0.0)
        val boundingBox = BoundingBox(x1 = 10.0, y1 = 10.0, x2 = 50.0, y2 = 50.0)
        val fakeFrameBase64 = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
        every {
            videoAnalysisClient.analyze(fakeVideoPath, any())
        } returns listOf(vehicle(bearingDegrees = 180.0, boundingBox = boundingBox, frameJpegBase64 = fakeFrameBase64))
        every { reportRepository.save(any()) } answers { firstArg() }
        every { wrongWayFrameStorageService.store(any(), any()) } throws RuntimeException("disk full")

        job.applyOutcome(report)

        assertThat(report.status).isEqualTo(ReportStatus.CONFIRMED)
        assertThat(report.wrongWayFramePath).isNull()
    }

    @Test
    fun `applyOutcome leaves wrongWayFramePath null when the vehicle has no frame data`() {
        val report = sampleReport(compassHeadingDegrees = BigDecimal("0.0"))
        every {
            streetDirectionResolver.resolve(report.latitude, report.longitude)
        } returns DirectionResolution.OneWay("Main Boulevard", 0.0)
        every {
            videoAnalysisClient.analyze(fakeVideoPath, any())
        } returns listOf(vehicle(bearingDegrees = 180.0, boundingBox = null, frameJpegBase64 = null))
        every { reportRepository.save(any()) } answers { firstArg() }

        job.applyOutcome(report)

        assertThat(report.status).isEqualTo(ReportStatus.CONFIRMED)
        assertThat(report.wrongWayFramePath).isNull()
        verify(exactly = 0) { wrongWayFrameStorageService.store(any(), any()) }
    }
}
