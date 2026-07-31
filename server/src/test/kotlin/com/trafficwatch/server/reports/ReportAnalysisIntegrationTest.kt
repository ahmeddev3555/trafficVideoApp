package com.trafficwatch.server.reports

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.trafficwatch.server.auth.User
import com.trafficwatch.server.auth.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.math.BigDecimal
import java.nio.file.Files
import java.time.Duration
import java.util.UUID

/**
 * End-to-end proof that a submitted report genuinely leaves `PENDING` on its own, via the
 * real `@Async` [ReportAnalysisJob] wired into [ReportService.submit] through the real
 * `afterCommit` registration - not mocked, unlike `ReportServiceTest`. Boots the full
 * application context (real `com.trafficwatch.server.config.AsyncConfig` executor, real
 * [ReportRepository] against the H2 test database, real [ReportAnalysisJob]), with
 * Nominatim/Overpass/the Python video-analysis service all stubbed via a single shared
 * WireMock server - the old stub's 80/20 random draw is gone entirely; every outcome here
 * is now driven by deterministic fixtures.
 *
 * Waiting for the async flip uses Awaitility's bounded `await().atMost(...).until(...)`
 * poll - not a single fixed `Thread.sleep` guess (slower than necessary and still flaky at
 * the margin) and not an unbounded wait (would hang forever if the async path were ever
 * broken).
 */
@SpringBootTest
@ActiveProfiles("test")
class ReportAnalysisIntegrationTest @Autowired constructor(
    private val reportService: ReportService,
    private val reportRepository: ReportRepository,
    private val userRepository: UserRepository,
) {

    companion object {
        // Videos submitted via the real ReportService/LocalDiskVideoStorageService during
        // this test are written to an isolated temp directory (rather than the module's
        // real server/storage/videos) so the test never depends on, or pollutes, that
        // directory - registered via @DynamicPropertySource since the path isn't known
        // until test class load time.
        private val tempVideoDir = Files.createTempDirectory("trafficwatch-analysis-it-videos")
        private val wireMockServer = WireMockServer(WireMockConfiguration.options().dynamicPort())

        @JvmStatic
        @BeforeAll
        fun startWireMock() {
            wireMockServer.start()
        }

        @JvmStatic
        @AfterAll
        fun stopWireMock() {
            wireMockServer.stop()
        }

        @JvmStatic
        @DynamicPropertySource
        fun overrideProperties(registry: DynamicPropertyRegistry) {
            registry.add("app.storage.video-directory") { tempVideoDir.toString() }
            registry.add("app.osm.nominatim-base-url") { "http://localhost:${wireMockServer.port()}" }
            registry.add("app.osm.overpass-base-url") { "http://localhost:${wireMockServer.port()}" }
            registry.add("app.video-analysis.base-url") { "http://localhost:${wireMockServer.port()}" }
        }
    }

    @AfterEach
    fun resetWireMockAndSecurityContext() {
        wireMockServer.resetAll()
        SecurityContextHolder.clearContext()
    }

    private fun authenticateAsNewUser(): UUID {
        val user = userRepository.saveAndFlush(
            User(
                name = "Analysis Test User",
                phoneNumber = "0300${(1_000_000..9_999_999).random()}",
                cnic = "12345-1234567-1",
                email = "analysis-test-${UUID.randomUUID()}@example.com",
                passwordHash = "hashed-password",
            ),
        )
        val userId = requireNotNull(user.id)
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(userId, null, emptyList())
        return userId
    }

    private fun sampleVideo() = MockMultipartFile("video", "clip.mp4", "video/mp4", byteArrayOf(1, 2, 3))

    // Each test uses its own latitude so StreetDirectionResolver's lat/lon-bucketed cache
    // (see the plan's "No PostGIS" decision) never lets one test's cached OSM resolution
    // leak into another's - @SpringBootTest doesn't roll back the H2 database between test
    // methods, so a shared bucket would silently reuse whichever fixture ran first.
    private fun submitReport(latitude: BigDecimal, compassHeadingDegrees: BigDecimal?): UUID {
        authenticateAsNewUser()
        val response = reportService.submit(
            video = sampleVideo(),
            latitude = latitude,
            longitude = BigDecimal("74.358749"),
            accuracy = BigDecimal("5.00"),
            altitude = BigDecimal("210.50"),
            bearing = BigDecimal("87.30"),
            speed = BigDecimal("12.40"),
            recordedAt = "2026-07-25T10:15:30Z",
            durationMs = 15000L,
            deviceId = "device-123",
            compassHeadingDegrees = compassHeadingDegrees,
        )
        return response.reportId
    }

    private fun waitForTerminalStatus(reportId: UUID): Report {
        await()
            .atMost(Duration.ofSeconds(5))
            .pollInterval(Duration.ofMillis(25))
            .until { reportRepository.findById(reportId).orElseThrow().status != ReportStatus.PENDING }
        return reportRepository.findById(reportId).orElseThrow()
    }

    /** A one-way street running due north, near [latitude]. */
    private fun stubOverpassOneWayNorth(latitude: BigDecimal) {
        wireMockServer.stubFor(
            post(urlEqualTo("/")).willReturn(
                okJson(
                    """
                    {
                      "elements": [
                        {
                          "type": "way",
                          "id": 1,
                          "tags": { "name": "Test One Way", "oneway": "yes" },
                          "geometry": [
                            {"lat": ${latitude.toDouble() - 0.001}, "lon": 74.358749},
                            {"lat": ${latitude.toDouble() + 0.001}, "lon": 74.358749}
                          ]
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
            ),
        )
    }

    // Includes frame dimensions and corridor/flow fields (single vehicle, alone in its own
    // corridor) so ClipFlowAnalyzer.qualifyVehicles treats it as a usable flow candidate -
    // without these, the new corridor-gated determineOutcome never scores any candidate at
    // all, regardless of bearing (see ReportAnalysisJobTest for the corridor-consensus cases
    // this single-vehicle-per-report suite deliberately doesn't exercise).
    private fun stubVideoAnalysis(bearingDegrees: Double, plateText: String?, plateConfidence: Double?) {
        wireMockServer.stubFor(
            post(urlPathEqualTo("/v1/analyze")).willReturn(
                okJson(
                    """
                    {
                      "vehicles": [
                        {
                          "track_id": 1,
                          "vehicle_type": "car",
                          "detection_confidence": 0.9,
                          "bearing_degrees": $bearingDegrees,
                          "plate_text": ${plateText?.let { "\"$it\"" } ?: "null"},
                          "plate_confidence": ${plateConfidence ?: "null"},
                          "corridor_id": 1,
                          "corridor_cohesion": 1.0,
                          "track_frame_count": 10,
                          "displacement_pixels": 300.0
                        }
                      ],
                      "frame_width": 1920,
                      "frame_height": 1080
                    }
                    """.trimIndent(),
                ),
            ),
        )
    }

    @Test
    fun `submit returns immediately with PENDING before the real async job flips it to a terminal status`() {
        val latitude = BigDecimal("31.5000")
        stubOverpassOneWayNorth(latitude)
        stubVideoAnalysis(bearingDegrees = 5.0, plateText = null, plateConfidence = null)

        val reportId = submitReport(latitude, compassHeadingDegrees = BigDecimal("0.0"))

        // Proves submit() doesn't block on analysis: read straight back from the
        // repository the instant submit() returns, before this test does any waiting of
        // its own.
        val immediatelyAfterSubmit = reportRepository.findById(reportId).orElseThrow()
        assertThat(immediatelyAfterSubmit.status).isEqualTo(ReportStatus.PENDING)
        val createdAt = immediatelyAfterSubmit.createdAt

        val finalReport = waitForTerminalStatus(reportId)
        assertThat(finalReport.status).isIn(ReportStatus.CONFIRMED, ReportStatus.REJECTED)
        assertThat(finalReport.updatedAt).isAfter(createdAt)
    }

    @Test
    fun `a genuine wrong-way vehicle on a one-way street is CONFIRMED with its plate and confidence`() {
        val latitude = BigDecimal("31.5100")
        stubOverpassOneWayNorth(latitude)
        // Legal direction is north (bearing 0); a vehicle whose frame-relative bearing
        // (180) added to a due-north (0 degree) compass heading lands on 180 - directly
        // opposite the legal direction, well within the default 60-degree tolerance.
        stubVideoAnalysis(bearingDegrees = 180.0, plateText = "LEA-1234", plateConfidence = 0.87)

        val reportId = submitReport(latitude, compassHeadingDegrees = BigDecimal("0.0"))
        val finalReport = waitForTerminalStatus(reportId)

        assertThat(finalReport.status).isEqualTo(ReportStatus.CONFIRMED)
        assertThat(finalReport.licensePlate).isEqualTo("LEA-1234")
        assertThat(finalReport.confidence).isEqualByComparingTo(BigDecimal("0.87"))
        assertThat(finalReport.streetName).isEqualTo("Test One Way")
        assertThat(finalReport.analysisMessage).isNotNull()
    }

    @Test
    fun `a vehicle moving with the legal direction is REJECTED, not a false CONFIRMED`() {
        val latitude = BigDecimal("31.5200")
        stubOverpassOneWayNorth(latitude)
        // Same due-north compass heading as the CONFIRMED test, but this vehicle's
        // frame-relative bearing (5) keeps its absolute bearing near the legal direction
        // (0), not the illegal one (180).
        stubVideoAnalysis(bearingDegrees = 5.0, plateText = "AAA-1111", plateConfidence = 0.99)

        val reportId = submitReport(latitude, compassHeadingDegrees = BigDecimal("0.0"))
        val finalReport = waitForTerminalStatus(reportId)

        assertThat(finalReport.status).isEqualTo(ReportStatus.REJECTED)
        assertThat(finalReport.licensePlate).isNull()
        assertThat(finalReport.confidence).isNull()
        assertThat(finalReport.analysisMessage).isEqualTo("No vehicles detected moving against the legal direction")
    }

    @Test
    fun `a report with no compass heading is REJECTED with a specific message, never reaching OSM or video analysis`() {
        val reportId = submitReport(BigDecimal("31.5300"), compassHeadingDegrees = null)
        val finalReport = waitForTerminalStatus(reportId)

        assertThat(finalReport.status).isEqualTo(ReportStatus.REJECTED)
        assertThat(finalReport.analysisMessage).isEqualTo("Device compass heading unavailable for this report")
        assertThat(finalReport.streetName).isNull()
        assertThat(wireMockServer.allServeEvents).isEmpty()
    }

    @Test
    fun `a coordinate with no oneway data at all is REJECTED as insufficient data, not a guess`() {
        val latitude = BigDecimal("31.5400")
        wireMockServer.stubFor(
            post(urlEqualTo("/")).willReturn(
                okJson(
                    """
                    {
                      "elements": [
                        {
                          "type": "way",
                          "id": 2,
                          "tags": { "name": "Untagged Street" },
                          "geometry": [
                            {"lat": ${latitude.toDouble() - 0.001}, "lon": 74.358749},
                            {"lat": ${latitude.toDouble() + 0.001}, "lon": 74.358749}
                          ]
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
            ),
        )
        // No OSM tag AND (deliberately, unlike stubVideoAnalysis) no corridor/flow fields on
        // the vehicle either, so ClipFlowAnalyzer.qualifyVehicles finds nothing usable -
        // determineOutcome now proceeds past the missing OSM tag into video analysis, but
        // with no evidence source able to fuse at all, the final message reflects total
        // absence of direction evidence, not specifically an "unknown OSM tag".
        wireMockServer.stubFor(
            post(urlPathEqualTo("/v1/analyze")).willReturn(
                okJson("""{"vehicles": [{"track_id": 1, "vehicle_type": "car", "detection_confidence": 0.9, "bearing_degrees": 5.0, "plate_text": null, "plate_confidence": null}]}"""),
            ),
        )

        val reportId = submitReport(latitude, compassHeadingDegrees = BigDecimal("0.0"))
        val finalReport = waitForTerminalStatus(reportId)

        assertThat(finalReport.status).isEqualTo(ReportStatus.REJECTED)
        assertThat(finalReport.analysisMessage)
            .isEqualTo("Legal traffic direction could not be established for this street")
        assertThat(finalReport.streetName).isEqualTo("Untagged Street")
    }
}
