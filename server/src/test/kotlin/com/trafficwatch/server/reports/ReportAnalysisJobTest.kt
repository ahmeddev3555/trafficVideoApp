package com.trafficwatch.server.reports

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.trafficwatch.server.geo.ClipFlowAnalyzer
import com.trafficwatch.server.geo.DirectionEvidence
import com.trafficwatch.server.geo.DirectionEvidenceResolver
import com.trafficwatch.server.geo.DirectionResolution
import com.trafficwatch.server.geo.EvidenceKind
import com.trafficwatch.server.geo.FlowObservationService
import com.trafficwatch.server.geo.StreetDirectionResolver
import com.trafficwatch.server.storage.VideoStorageService
import com.trafficwatch.server.storage.WrongWayFrameStorageService
import com.trafficwatch.server.videoanalysis.VideoAnalysisClient
import com.trafficwatch.server.videoanalysis.VideoAnalysisException
import com.trafficwatch.server.videoanalysis.dto.BoundingBox
import com.trafficwatch.server.videoanalysis.dto.VehicleAnalysisResult
import com.trafficwatch.server.videoanalysis.dto.VideoAnalysisResponse
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
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
 *
 * [ClipFlowAnalyzer] and [DirectionEvidenceResolver] are used as real (pure, no-I/O)
 * collaborators rather than mocked - only [FlowObservationService] (the DB-backed learned
 * history source) is a MockK mock, defaulted in [stubVideoResolution] to "no history yet"
 * (`historyEvidence` returns null) and "ingestion always succeeds" (`ingest` is a no-op).
 */
class ReportAnalysisJobTest {

    private val reportRepository = mockk<ReportRepository>()
    private val analysisProperties = AnalysisProperties(wrongWayToleranceDegrees = 60.0)
    private val streetDirectionResolver = mockk<StreetDirectionResolver>()
    private val videoAnalysisClient = mockk<VideoAnalysisClient>()
    private val videoStorageService = mockk<VideoStorageService>()
    private val wrongWayFrameStorageService = mockk<WrongWayFrameStorageService>()
    private val clipFlowAnalyzer = ClipFlowAnalyzer(analysisProperties)
    private val directionEvidenceResolver = DirectionEvidenceResolver(analysisProperties)
    private val flowObservationService = mockk<FlowObservationService>()
    private val objectMapper = ObjectMapper()
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .registerKotlinModule()

    private val job = ReportAnalysisJob(
        reportRepository,
        analysisProperties,
        streetDirectionResolver,
        videoAnalysisClient,
        videoStorageService,
        wrongWayFrameStorageService,
        clipFlowAnalyzer,
        directionEvidenceResolver,
        flowObservationService,
        objectMapper,
    )

    private val fakeVideoPath: Path = Path.of("/videos/fake.mp4")

    @BeforeEach
    fun stubVideoResolution() {
        every { videoStorageService.resolve(any()) } returns fakeVideoPath
        every { flowObservationService.historyEvidence(any(), any()) } returns null
        every { flowObservationService.ingest(any(), any()) } just Runs
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
        detectionConfidence: Double = 0.8,
        plateText: String? = "LEA-1234",
        plateConfidence: Double? = 0.9,
        boundingBox: BoundingBox? = BoundingBox(x1 = 0.0, y1 = 0.0, x2 = 1414.0, y2 = 1414.0),
        frameJpegBase64: String? = null,
        corridorId: Long? = 0L,
        corridorCohesion: Double? = 1.0,
        trackFrameCount: Int? = 10,
        displacementPixels: Double? = 310.0,
    ) = VehicleAnalysisResult(
        trackId = trackId,
        vehicleType = "car",
        detectionConfidence = detectionConfidence,
        bearingDegrees = bearingDegrees,
        plateText = plateText,
        plateConfidence = plateConfidence,
        boundingBox = boundingBox,
        frameJpegBase64 = frameJpegBase64,
        corridorId = corridorId,
        corridorCohesion = corridorCohesion,
        trackFrameCount = trackFrameCount,
        displacementPixels = displacementPixels,
    )

    /** Wraps [vehicles] into a full video-analysis response with usable frame dimensions. */
    private fun analysisResponse(
        vehicles: List<VehicleAnalysisResult>,
        frameWidth: Int? = 1920,
        frameHeight: Int? = 1080,
    ) = VideoAnalysisResponse(vehicles = vehicles, frameWidth = frameWidth, frameHeight = frameHeight)

    @Test
    fun `applyOutcome still resolves the street and calls video analysis when compass heading is missing, but rejects with a specific message`() {
        val report = sampleReport(compassHeadingDegrees = null)
        every {
            streetDirectionResolver.resolve(report.latitude, report.longitude)
        } returns DirectionResolution.OneWay("Main Boulevard", 0.0)
        every {
            videoAnalysisClient.analyze(fakeVideoPath, any())
        } returns analysisResponse(listOf(vehicle(bearingDegrees = 180.0)))
        every { reportRepository.save(any()) } answers { firstArg() }

        job.applyOutcome(report)

        assertThat(report.status).isEqualTo(ReportStatus.REJECTED)
        assertThat(report.analysisMessage)
            .isEqualTo("Device compass heading unavailable; cannot determine vehicle direction")
        assertThat(report.streetName).isEqualTo("Main Boulevard")
        assertThat(report.licensePlate).isNull()
        assertThat(report.confidence).isNull()
        verify(exactly = 1) { streetDirectionResolver.resolve(report.latitude, report.longitude) }
        verify(exactly = 1) { videoAnalysisClient.analyze(fakeVideoPath, any()) }
    }

    @Test
    fun `applyOutcome rejects when no street can be identified at the location`() {
        val report = sampleReport()
        every { streetDirectionResolver.resolve(report.latitude, report.longitude) } returns DirectionResolution.NotFound
        every { videoAnalysisClient.analyze(fakeVideoPath, any()) } returns analysisResponse(emptyList())
        every { reportRepository.save(any()) } answers { firstArg() }

        job.applyOutcome(report)

        assertThat(report.status).isEqualTo(ReportStatus.REJECTED)
        assertThat(report.analysisMessage).isEqualTo("Legal traffic direction could not be established for this street")
        assertThat(report.streetName).isNull()
    }

    @Test
    fun `applyOutcome rejects with the street name when the legal direction is unknown`() {
        val report = sampleReport()
        every {
            streetDirectionResolver.resolve(report.latitude, report.longitude)
        } returns DirectionResolution.Unknown("Side Street")
        every { videoAnalysisClient.analyze(fakeVideoPath, any()) } returns analysisResponse(emptyList())
        every { reportRepository.save(any()) } answers { firstArg() }

        job.applyOutcome(report)

        assertThat(report.status).isEqualTo(ReportStatus.REJECTED)
        assertThat(report.analysisMessage).isEqualTo("Legal traffic direction could not be established for this street")
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
    fun `applyOutcome rejects on OSM lookup failure but still proceeds to video evaluation`() {
        val report = sampleReport()
        every {
            streetDirectionResolver.resolve(report.latitude, report.longitude)
        } returns DirectionResolution.LookupFailed("Overpass timed out")
        every { videoAnalysisClient.analyze(fakeVideoPath, any()) } returns analysisResponse(emptyList())
        every { reportRepository.save(any()) } answers { firstArg() }

        job.applyOutcome(report)

        assertThat(report.status).isEqualTo(ReportStatus.REJECTED)
        assertThat(report.analysisMessage).isEqualTo("Legal traffic direction could not be established for this street")
        // Unlike the old terminal-on-lookup-failure behavior, the OSM outage no longer skips
        // video analysis - clip consensus/history can still resolve the direction (see the
        // "lookup failure with mature history proceeds to evaluation" test below).
        verify(exactly = 1) { videoAnalysisClient.analyze(fakeVideoPath, any()) }
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
        } returns analysisResponse(listOf(vehicle(bearingDegrees = 0.0)))
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
        } returns analysisResponse(listOf(vehicle(bearingDegrees = 180.0, plateText = "LEA-1234", plateConfidence = 0.9)))
        every { reportRepository.save(any()) } answers { firstArg() }

        job.applyOutcome(report)

        assertThat(report.status).isEqualTo(ReportStatus.CONFIRMED)
        assertThat(report.licensePlate).isEqualTo("LEA-1234")
        assertThat(report.confidence).isEqualByComparingTo(BigDecimal("0.9"))
        assertThat(report.streetName).isEqualTo("Main Boulevard")
        assertThat(report.analysisMessage).contains("Main Boulevard")
    }

    @Test
    fun `applyOutcome picks the wrong-way vehicle with the highest final score among several`() {
        val report = sampleReport(compassHeadingDegrees = BigDecimal("0.0"))
        every {
            streetDirectionResolver.resolve(report.latitude, report.longitude)
        } returns DirectionResolution.OneWay("Main Boulevard", 0.0)
        // Distinct corridors so no cross-vehicle corridor consensus applies - each candidate
        // is scored on OSM evidence alone. Winner is decided by bearing proximity to the
        // illegal bearing (180), not plate confidence: track 2 (2 degrees off) beats track 1
        // (30 degrees off, still within the 60-degree tolerance).
        every {
            videoAnalysisClient.analyze(fakeVideoPath, any())
        } returns analysisResponse(
            listOf(
                vehicle(trackId = 1, corridorId = 1, bearingDegrees = 150.0, plateText = "AAA-1111", plateConfidence = 0.4),
                vehicle(trackId = 2, corridorId = 2, bearingDegrees = 178.0, plateText = "BBB-2222", plateConfidence = 0.95),
                // Not a wrong-way vehicle at all (bearing near 0, same as legal direction).
                vehicle(trackId = 3, corridorId = 3, bearingDegrees = 5.0, plateText = "CCC-3333", plateConfidence = 0.99),
            ),
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
        } returns analysisResponse(listOf(vehicle(bearingDegrees = 180.0, plateText = null, plateConfidence = null)))
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
        } returns analysisResponse(listOf(vehicle(bearingDegrees = null)))
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
        every { streetDirectionResolver.resolve(report.latitude, report.longitude) } returns DirectionResolution.NotFound
        every { videoAnalysisClient.analyze(fakeVideoPath, any()) } returns analysisResponse(emptyList())
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
        } returns analysisResponse(listOf(vehicle(bearingDegrees = 180.0, plateConfidence = null)))
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
        // 170 is 10 degrees off the illegal bearing of 180 - within the 60-degree tolerance,
        // and (unlike a 30-degree offset) still scores above the 0.5 confirmation threshold,
        // so wrongWayConfidence is populated and comparable to the dead-on case.
        every {
            videoAnalysisClient.analyze(fakeVideoPath, any())
        } returns analysisResponse(listOf(vehicle(bearingDegrees = 170.0, plateConfidence = null)))
        every { reportRepository.save(any()) } answers { firstArg() }

        job.applyOutcome(report)

        assertThat(report.status).isEqualTo(ReportStatus.CONFIRMED)
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
        } returns analysisResponse(
            listOf(vehicle(bearingDegrees = 180.0, boundingBox = boundingBox, frameJpegBase64 = fakeFrameBase64)),
        )
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
        } returns analysisResponse(
            listOf(vehicle(bearingDegrees = 180.0, boundingBox = boundingBox, frameJpegBase64 = fakeFrameBase64)),
        )
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
        } returns analysisResponse(listOf(vehicle(bearingDegrees = 180.0, frameJpegBase64 = null)))
        every { reportRepository.save(any()) } answers { firstArg() }

        job.applyOutcome(report)

        assertThat(report.status).isEqualTo(ReportStatus.CONFIRMED)
        assertThat(report.wrongWayFramePath).isNull()
        verify(exactly = 0) { wrongWayFrameStorageService.store(any(), any()) }
    }

    @Test
    fun `unknown street with strong clip consensus confirms with breakdown`() {
        val report = sampleReport(compassHeadingDegrees = BigDecimal.ZERO)
        every {
            streetDirectionResolver.resolve(report.latitude, report.longitude)
        } returns DirectionResolution.Unknown("Khayaban-e-Jinnah")
        // 3 consensus vehicles east (~90), violator west (270) in the same corridor.
        every {
            videoAnalysisClient.analyze(fakeVideoPath, any())
        } returns analysisResponse(
            listOf(
                vehicle(trackId = 1, bearingDegrees = 88.0, detectionConfidence = 0.95),
                vehicle(trackId = 2, bearingDegrees = 90.0, detectionConfidence = 0.95),
                vehicle(trackId = 3, bearingDegrees = 92.0, detectionConfidence = 0.95),
                vehicle(
                    trackId = 4,
                    bearingDegrees = 270.0,
                    detectionConfidence = 0.95,
                    plateText = "LEA-1234",
                    plateConfidence = 0.8,
                ),
            ),
        )
        every { reportRepository.save(any()) } answers { firstArg() }

        job.applyOutcome(report)

        assertThat(report.status).isEqualTo(ReportStatus.CONFIRMED)
        assertThat(report.licensePlate).isEqualTo("LEA-1234")
        assertThat(report.directionEvidence).isNotNull()
        assertThat(report.directionEvidence).contains("CLIP_CONSENSUS")
        // clipConfidence = (3/5)*~1*1 = ~0.6; score = 0.6*1*0.95*~1 = ~0.57 >= 0.5
        assertThat(report.wrongWayConfidence!!.toDouble()).isGreaterThanOrEqualTo(0.5)
    }

    @Test
    fun `candidate moving with its corridor is not a violator even against osm`() {
        val report = sampleReport(compassHeadingDegrees = BigDecimal.ZERO)
        // OSM says legal=90, so illegal=270. All three vehicles flow 270 together -
        // a legal opposing stream (divided road), NOT three violators.
        every {
            streetDirectionResolver.resolve(report.latitude, report.longitude)
        } returns DirectionResolution.OneWay("Main Blvd", 90.0)
        every {
            videoAnalysisClient.analyze(fakeVideoPath, any())
        } returns analysisResponse(
            listOf(
                vehicle(trackId = 1, bearingDegrees = 268.0, detectionConfidence = 0.95),
                vehicle(trackId = 2, bearingDegrees = 270.0, detectionConfidence = 0.95),
                vehicle(trackId = 3, bearingDegrees = 272.0, detectionConfidence = 0.95),
            ),
        )
        every { reportRepository.save(any()) } answers { firstArg() }

        job.applyOutcome(report)

        assertThat(report.status).isEqualTo(ReportStatus.REJECTED)
        // The corridor's consensus (270) conflicts with OSM (90) -> conflict message.
        assertThat(report.analysisMessage).isEqualTo("Conflicting direction evidence for this street")
    }

    @Test
    fun `candidate in a contested (bimodal) corridor is skipped, never falsely confirmed`() {
        val report = sampleReport(compassHeadingDegrees = BigDecimal.ZERO)
        every {
            streetDirectionResolver.resolve(report.latitude, report.longitude)
        } returns DirectionResolution.OneWay("Main Blvd", 90.0)
        // Three vehicles, one corridor, evenly spaced 120 degrees apart (270, 30, 150).
        // Excluding any ONE candidate, the remaining pair is also 120 degrees apart:
        // R = |sum of two unit vectors 120 degrees apart| / 2 = cos(60) = 0.5, below the
        // 0.6 consensus gate -> null for every candidate's own-corridor consensus. The
        // full (no-exclusion) consensus is null too: three unit vectors 120 degrees apart
        // sum to exactly zero -> R = 0. The vehicle at 270 sits exactly on the illegal
        // bearing (legal 90 + 180): before this fix, a null consensus was treated as
        // "genuinely alone", so this vehicle would have fused against OSM alone
        // (finalScore = 1.0 * 1.0 * 0.9 * 1.0 = 0.9) and wrongly CONFIRMED. With the fix,
        // every candidate has >= 1 other corridor member and a null consensus, so all three
        // are skipped outright; the fallback fusion (osm alone, since the full-set clip
        // consensus is also null) is Fused, giving the ordinary "no violator" message.
        every {
            videoAnalysisClient.analyze(fakeVideoPath, any())
        } returns analysisResponse(
            listOf(
                vehicle(trackId = 1, bearingDegrees = 270.0, detectionConfidence = 0.9),
                vehicle(trackId = 2, bearingDegrees = 30.0, detectionConfidence = 0.9),
                vehicle(trackId = 3, bearingDegrees = 150.0, detectionConfidence = 0.9),
            ),
        )
        every { reportRepository.save(any()) } answers { firstArg() }

        job.applyOutcome(report)

        assertThat(report.status).isEqualTo(ReportStatus.REJECTED)
        assertThat(report.status).isNotEqualTo(ReportStatus.CONFIRMED)
        assertThat(report.analysisMessage).isEqualTo("No vehicles detected moving against the legal direction")
    }

    @Test
    fun `candidate in a contested corridor WITH peer support is evaluated against OSM and can be confirmed`() {
        val report = sampleReport(compassHeadingDegrees = BigDecimal.ZERO)
        every {
            streetDirectionResolver.resolve(report.latitude, report.longitude)
        } returns DirectionResolution.OneWay("Khayaban-e-Jinnah", 90.0)
        // Mirrors the real report this fix was diagnosed from: two vehicles flowing with the
        // legal bearing (88, 92), one candidate moving dead against it (270, exactly the
        // illegal bearing) with one real peer nearby (250, 20 degrees away, within the
        // 45-degree tolerance) - all in the same corridor, so the corridor's OVERALL
        // consensus (excluding the candidate: 88/92/250) is bimodal/dispersed
        // (R ~= 0.37, below the 0.6 gate), but the candidate's OWN specific direction is
        // corroborated by track 4, not a lone coincidence.
        // Score check: candidate (track 3) angularDistance from illegal = 0 ->
        // bearingMatchScore 1.0 -> finalScore = 1.0(fusion) * 1.0(quality) * 0.9(detection)
        // * 1.0 = 0.9. The peer (track 4) is also evaluated independently (angularDistance
        // 20 -> bearingMatchScore 0.667 -> finalScore 0.6) but scores lower and has no
        // plate, so the winner is unambiguous.
        every {
            videoAnalysisClient.analyze(fakeVideoPath, any())
        } returns analysisResponse(
            listOf(
                vehicle(trackId = 1, bearingDegrees = 88.0, detectionConfidence = 0.9),
                vehicle(trackId = 2, bearingDegrees = 92.0, detectionConfidence = 0.9),
                vehicle(
                    trackId = 3,
                    bearingDegrees = 270.0,
                    detectionConfidence = 0.9,
                    plateText = "LEA-1234",
                    plateConfidence = 0.8,
                ),
                vehicle(trackId = 4, bearingDegrees = 250.0, detectionConfidence = 0.9, plateText = null, plateConfidence = null),
            ),
        )
        every { reportRepository.save(any()) } answers { firstArg() }

        job.applyOutcome(report)

        assertThat(report.status).isEqualTo(ReportStatus.CONFIRMED)
        assertThat(report.licensePlate).isEqualTo("LEA-1234")
    }

    @Test
    fun `candidate in a contested corridor WITH peer support but no OSM or history evidence still lands on insufficient`() {
        val report = sampleReport(compassHeadingDegrees = BigDecimal.ZERO)
        every {
            streetDirectionResolver.resolve(report.latitude, report.longitude)
        } returns DirectionResolution.Unknown(null)
        // Same peer-support shape as the test above, but no OSM tag (Unknown, not OneWay) and
        // no learned history (stubVideoResolution already defaults historyEvidence to null) -
        // peer support alone must never BE evidence, only a gate on whether to attempt fusion.
        every {
            videoAnalysisClient.analyze(fakeVideoPath, any())
        } returns analysisResponse(
            listOf(
                vehicle(trackId = 1, bearingDegrees = 88.0, detectionConfidence = 0.9),
                vehicle(trackId = 2, bearingDegrees = 92.0, detectionConfidence = 0.9),
                vehicle(trackId = 3, bearingDegrees = 270.0, detectionConfidence = 0.9),
                vehicle(trackId = 4, bearingDegrees = 250.0, detectionConfidence = 0.9),
            ),
        )
        every { reportRepository.save(any()) } answers { firstArg() }

        job.applyOutcome(report)

        assertThat(report.status).isEqualTo(ReportStatus.REJECTED)
        assertThat(report.status).isNotEqualTo(ReportStatus.CONFIRMED)
    }

    @Test
    fun `below-threshold candidate rejects with the too-low message`() {
        val report = sampleReport(compassHeadingDegrees = BigDecimal.ZERO)
        every {
            streetDirectionResolver.resolve(report.latitude, report.longitude)
        } returns DirectionResolution.Unknown(null)
        // Single consensus partner -> clipConfidence = (1/3)*1*1 = 0.33; score
        // = 0.33 * 1 * 0.9 * 1 = ~0.3 < 0.5 threshold.
        every {
            videoAnalysisClient.analyze(fakeVideoPath, any())
        } returns analysisResponse(
            listOf(
                vehicle(trackId = 1, bearingDegrees = 90.0, detectionConfidence = 0.9),
                vehicle(trackId = 2, bearingDegrees = 270.0, detectionConfidence = 0.9),
            ),
        )
        every { reportRepository.save(any()) } answers { firstArg() }

        job.applyOutcome(report)

        assertThat(report.status).isEqualTo(ReportStatus.REJECTED)
        assertThat(report.analysisMessage).isEqualTo(
            "Possible wrong-way vehicle detected, but confidence was too low to confirm",
        )
        assertThat(report.directionEvidence).isNotNull()
    }

    @Test
    fun `lookup failure with mature history proceeds to evaluation`() {
        val report = sampleReport(compassHeadingDegrees = BigDecimal.ZERO)
        every {
            streetDirectionResolver.resolve(report.latitude, report.longitude)
        } returns DirectionResolution.LookupFailed("Overpass lookup failed")
        every {
            flowObservationService.historyEvidence(any(), any())
        } returns DirectionEvidence(EvidenceKind.LEARNED_HISTORY, 90.0, 0.6)
        every {
            videoAnalysisClient.analyze(fakeVideoPath, any())
        } returns analysisResponse(listOf(vehicle(bearingDegrees = 270.0, detectionConfidence = 0.95)))
        every { reportRepository.save(any()) } answers { firstArg() }

        job.applyOutcome(report)

        // score = 0.6 * 1 * 0.95 * 1.0 = 0.57 >= 0.5 -> confirmed despite the OSM outage.
        assertThat(report.status).isEqualTo(ReportStatus.CONFIRMED)
    }

    @Test
    fun `observations are ingested for rejected reports too`() {
        val report = sampleReport(compassHeadingDegrees = BigDecimal.ZERO)
        every {
            streetDirectionResolver.resolve(report.latitude, report.longitude)
        } returns DirectionResolution.Unknown(null)
        every {
            videoAnalysisClient.analyze(fakeVideoPath, any())
        } returns analysisResponse(
            listOf(
                vehicle(trackId = 1, bearingDegrees = 88.0, detectionConfidence = 0.95),
                vehicle(trackId = 2, bearingDegrees = 92.0, detectionConfidence = 0.95),
            ),
        )
        every { reportRepository.save(any()) } answers { firstArg() }

        job.applyOutcome(report)

        assertThat(report.status).isEqualTo(ReportStatus.REJECTED) // nobody moves against the flow
        verify { flowObservationService.ingest(report, match { it.isNotEmpty() }) }
    }
}
