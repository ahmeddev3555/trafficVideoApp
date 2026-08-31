package com.trafficwatch.server.reports

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.trafficwatch.server.auth.User
import com.trafficwatch.server.auth.UserRepository
import com.trafficwatch.server.geo.FlowObservationRepository
import com.trafficwatch.server.reports.dto.RotationSampleDto
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
    private val flowObservationRepository: FlowObservationRepository,
    private val objectMapper: ObjectMapper,
) {

    companion object {
        // Videos submitted via the real ReportService/LocalDiskVideoStorageService during
        // this test are written to an isolated temp directory (rather than the module's
        // real server/storage/videos) so the test never depends on, or pollutes, that
        // directory - registered via @DynamicPropertySource since the path isn't known
        // until test class load time.
        private val tempVideoDir = Files.createTempDirectory("trafficwatch-analysis-it-videos")
        private val wireMockServer = WireMockServer(WireMockConfiguration.options().dynamicPort())

        /** The two distinct Overpass "mirror" paths served by [wireMockServer]. */
        private val overpassMirrorPaths = listOf("/", "/api/interpreter")

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
            // Two DISTINCT mirror URLs (OverpassClient dedupes its endpoint list, so the same
            // URL twice would count as one source) so it sees sourceCount == 2 - a single
            // un-cross-checked source is downgraded to Unknown (StreetDirectionResolver),
            // which these OneWay-outcome tests must not trip. Both paths are stubbed
            // identically on the shared WireMock server by stubOverpass().
            registry.add("app.osm.overpass-base-urls") {
                overpassMirrorPaths.joinToString(",") { "http://localhost:${wireMockServer.port()}$it" }
            }
            registry.add("app.video-analysis.base-url") { "http://localhost:${wireMockServer.port()}" }
        }
    }

    @AfterEach
    fun resetWireMockAndSecurityContext() {
        wireMockServer.resetAll()
        SecurityContextHolder.clearContext()
        // flow_observations isn't otherwise touched by this class's other tests (they only
        // ever stub a single vehicle, which can never reach the >=2-member consensus
        // FlowObservationService requires to ingest a row) - cleared anyway so the
        // corridor-consensus test's "exactly 1 row" assertion never depends on method
        // execution order against the shared (DB_CLOSE_DELAY=-1) H2 instance.
        flowObservationRepository.deleteAll()
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
    private fun submitReport(
        latitude: BigDecimal,
        compassHeadingDegrees: BigDecimal?,
        locationSamplesJson: String? = null,
        rotationSamplesJson: String? = null,
    ): UUID {
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
            zoomRatio = null,
            locationSamplesJson = locationSamplesJson,
            rotationSamplesJson = rotationSamplesJson,
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

    /**
     * Answers [json] on BOTH configured Overpass mirror paths - OverpassClient needs two
     * distinct endpoints to answer before a `OneWay` survives its cross-check.
     */
    private fun stubOverpass(json: String) {
        overpassMirrorPaths.forEach { path ->
            wireMockServer.stubFor(post(urlEqualTo(path)).willReturn(okJson(json)))
        }
    }

    /** A one-way street running due north, near [latitude]. */
    private fun stubOverpassOneWayNorth(latitude: BigDecimal) {
        stubOverpass(
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
                          "bounding_box": {
                            "x1": 0.0,
                            "y1": 0.0,
                            "x2": 1414.0,
                            "y2": 1414.0
                          },
                          "corridor_id": 1,
                          "corridor_cohesion": 1.0,
                          "track_frame_count": 30,
                          "displacement_pixels": 310.0
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

    /** A way with neither a `name` nor an `oneway` tag, near [latitude] - forces both the
     * Unknown-direction fallback (no oneway tag) AND the Nominatim reverse-geocode fallback
     * (no name tag) that [stubNominatimReverse] answers. */
    private fun stubOverpassWayNoTags(latitude: BigDecimal) {
        stubOverpass(
            """
            {
              "elements": [
                {
                  "type": "way",
                  "id": 3,
                  "tags": {},
                  "geometry": [
                    {"lat": ${latitude.toDouble() - 0.001}, "lon": 74.358749},
                    {"lat": ${latitude.toDouble() + 0.001}, "lon": 74.358749}
                  ]
                }
              ]
            }
            """.trimIndent(),
        )
    }

    /** Answers StreetDirectionResolver's Nominatim fallback (used only when the nearest
     * Overpass way has no `name` tag) with [road] as the resolved street name. */
    private fun stubNominatimReverse(road: String) {
        wireMockServer.stubFor(
            get(urlPathEqualTo("/reverse")).willReturn(
                okJson("""{"address": {"road": "$road"}}"""),
            ),
        )
    }

    /** Overload of [stubVideoAnalysis] for tests that need full control over the response
     * body (e.g. multiple vehicles sharing a corridor for consensus scoring) rather than the
     * single-vehicle shape the other overload builds. */
    private fun stubVideoAnalysis(rawResponseBody: String) {
        wireMockServer.stubFor(
            post(urlPathEqualTo("/v1/analyze")).willReturn(okJson(rawResponseBody)),
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
    fun `a report with no compass heading still resolves the street and calls video analysis, but is REJECTED with a specific message`() {
        val latitude = BigDecimal("31.5300")
        stubOverpassOneWayNorth(latitude)
        // A vehicle that WOULD be a wrong-way violator if compass were known - proves the
        // missing-compass rejection isn't a coincidence of no evidence being available.
        stubVideoAnalysis(bearingDegrees = 180.0, plateText = "LEA-1234", plateConfidence = 0.9)

        val reportId = submitReport(latitude, compassHeadingDegrees = null)
        val finalReport = waitForTerminalStatus(reportId)

        assertThat(finalReport.status).isEqualTo(ReportStatus.REJECTED)
        assertThat(finalReport.analysisMessage)
            .isEqualTo("No orientation data available for this report")
        assertThat(finalReport.streetName).isEqualTo("Test One Way")
        assertThat(finalReport.licensePlate).isNull()
        assertThat(wireMockServer.allServeEvents).isNotEmpty()
    }

    @Test
    fun `a coordinate with no oneway data at all is REJECTED as insufficient data, not a guess`() {
        val latitude = BigDecimal("31.5400")
        stubOverpass(
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

    @Test
    fun `report confirms from clip consensus alone when osm has no oneway tag and writes an observation`() {
        val latitude = BigDecimal("31.5500")
        stubOverpassWayNoTags(latitude)
        stubNominatimReverse(road = "Street No 06")
        // Three vehicles tightly clustered around 90 degrees form corridor 0's consensus (R
        // close to 1.0, meanCohesion 1.0); the fourth (bearing 270, opposite the consensus)
        // is the suspected violator, in the SAME corridor - ClipFlowAnalyzer.corridorConsensus
        // excludes only the candidate under evaluation, so when this vehicle is scored, the
        // other three (not itself) form its corridor's consensus. There's no OSM oneway tag
        // (Unknown resolution) and no learned history at this fresh lat/lon bucket, so
        // CLIP_CONSENSUS is the only evidence source - the confirmation rests on the clip
        // alone: clipConfidence = (3/5) x R x 1.0 (~0.60), carried through fusion unchanged as
        // the sole source, then scaled by candidate quality/detection confidence/bearing
        // match into a final score comfortably past the 0.5 confirmation threshold.
        stubVideoAnalysis(
            """
            {
              "vehicles": [
                {"track_id": 1, "vehicle_type": "car", "detection_confidence": 0.95, "bearing_degrees": 88.0,
                 "plate_text": null, "plate_confidence": null, "bounding_box": {"x1": 0.0, "y1": 0.0, "x2": 1414.0, "y2": 1414.0},
                 "corridor_id": 0, "corridor_cohesion": 1.0,
                 "track_frame_count": 30, "displacement_pixels": 310.0},
                {"track_id": 2, "vehicle_type": "car", "detection_confidence": 0.95, "bearing_degrees": 90.0,
                 "plate_text": null, "plate_confidence": null, "bounding_box": {"x1": 0.0, "y1": 0.0, "x2": 1414.0, "y2": 1414.0},
                 "corridor_id": 0, "corridor_cohesion": 1.0,
                 "track_frame_count": 30, "displacement_pixels": 310.0},
                {"track_id": 3, "vehicle_type": "car", "detection_confidence": 0.95, "bearing_degrees": 92.0,
                 "plate_text": null, "plate_confidence": null, "bounding_box": {"x1": 0.0, "y1": 0.0, "x2": 1414.0, "y2": 1414.0},
                 "corridor_id": 0, "corridor_cohesion": 1.0,
                 "track_frame_count": 30, "displacement_pixels": 310.0},
                {"track_id": 4, "vehicle_type": "car", "detection_confidence": 0.95, "bearing_degrees": 270.0,
                 "plate_text": "LEB-5678", "plate_confidence": 0.8, "bounding_box": {"x1": 0.0, "y1": 0.0, "x2": 1414.0, "y2": 1414.0},
                 "corridor_id": 0, "corridor_cohesion": 1.0,
                 "track_frame_count": 30, "displacement_pixels": 310.0}
              ],
              "frame_width": 1920,
              "frame_height": 1080
            }
            """.trimIndent(),
        )

        val reportId = submitReport(latitude, compassHeadingDegrees = BigDecimal.ZERO)
        val finalReport = waitForTerminalStatus(reportId)

        assertThat(finalReport.status).isEqualTo(ReportStatus.CONFIRMED)
        assertThat(finalReport.licensePlate).isEqualTo("LEB-5678")
        assertThat(finalReport.wrongWayConfidence).isNotNull()
        assertThat(finalReport.wrongWayConfidence!!.toDouble()).isGreaterThanOrEqualTo(0.5)

        val evidenceJson = finalReport.directionEvidence
        assertThat(evidenceJson).isNotNull()
        val evidenceBreakdown = objectMapper.readTree(evidenceJson)
        assertThat(evidenceBreakdown.get("sources").get(0).get("kind").asText()).isEqualTo("CLIP_CONSENSUS")

        val observations = flowObservationRepository.findAll()
        assertThat(observations).hasSize(1)
        assertThat(observations[0].vehicleCount).isEqualTo(3)
    }

    @Test
    fun `stationary clip with a receding majority and a strong approacher is CONFIRMED via the approach path end to end`() {
        val latitude = BigDecimal("31.5800")
        stubOverpassOneWayNorth(latitude) // legal bearing 0 (north).

        // Four location fixes, ALL speed 0.0 - OrientationTimeline.wasStationaryThroughout()
        // is the gate for the stationary-approach path.
        val baseEpochMs = 1_700_000_000_000L
        val locationSamplesJson = """
            [
              {"latitude":${latitude.toDouble()},"longitude":74.358749,"accuracy":5.0,"altitude":210.0,"bearing":0.0,"speed":0.0,"captured_at":$baseEpochMs},
              {"latitude":${latitude.toDouble()},"longitude":74.358749,"accuracy":5.0,"altitude":210.0,"bearing":0.0,"speed":0.0,"captured_at":${baseEpochMs + 4000L}},
              {"latitude":${latitude.toDouble()},"longitude":74.358749,"accuracy":5.0,"altitude":210.0,"bearing":0.0,"speed":0.0,"captured_at":${baseEpochMs + 8000L}},
              {"latitude":${latitude.toDouble()},"longitude":74.358749,"accuracy":5.0,"altitude":210.0,"bearing":0.0,"speed":0.0,"captured_at":${baseEpochMs + 12000L}}
            ]
        """.trimIndent()

        // Every vehicle's frame-relative bearing (5) added to the due-north compass heading
        // (0) lands near the LEGAL direction, so the bearing path finds no violator and
        // REJECTS - then the additive stationary-approach path runs: 4 tracks receded
        // ("shrinking") while one grew sustainedly (scale_growth_fraction 1.4 >= 0.8,
        // 60 frames, detection 0.9), which on a verified-stationary camera is a wrong-way
        // approacher regardless of compass/OSM legal bearing.
        stubVideoAnalysis(
            """
            {
              "vehicles": [
                {"track_id": 1, "vehicle_type": "car", "detection_confidence": 0.9, "bearing_degrees": 5.0,
                 "plate_text": null, "plate_confidence": null, "bounding_box": {"x1": 0.0, "y1": 0.0, "x2": 1414.0, "y2": 1414.0},
                 "corridor_id": 1, "corridor_cohesion": 1.0, "track_frame_count": 40, "displacement_pixels": 310.0,
                 "scale_trend": "shrinking"},
                {"track_id": 2, "vehicle_type": "car", "detection_confidence": 0.9, "bearing_degrees": 5.0,
                 "plate_text": null, "plate_confidence": null, "bounding_box": {"x1": 0.0, "y1": 0.0, "x2": 1414.0, "y2": 1414.0},
                 "corridor_id": 1, "corridor_cohesion": 1.0, "track_frame_count": 40, "displacement_pixels": 310.0,
                 "scale_trend": "shrinking"},
                {"track_id": 3, "vehicle_type": "car", "detection_confidence": 0.9, "bearing_degrees": 5.0,
                 "plate_text": null, "plate_confidence": null, "bounding_box": {"x1": 0.0, "y1": 0.0, "x2": 1414.0, "y2": 1414.0},
                 "corridor_id": 1, "corridor_cohesion": 1.0, "track_frame_count": 40, "displacement_pixels": 310.0,
                 "scale_trend": "shrinking"},
                {"track_id": 4, "vehicle_type": "car", "detection_confidence": 0.9, "bearing_degrees": 5.0,
                 "plate_text": null, "plate_confidence": null, "bounding_box": {"x1": 0.0, "y1": 0.0, "x2": 1414.0, "y2": 1414.0},
                 "corridor_id": 1, "corridor_cohesion": 1.0, "track_frame_count": 40, "displacement_pixels": 310.0,
                 "scale_trend": "shrinking"},
                {"track_id": 5, "vehicle_type": "car", "detection_confidence": 0.9, "bearing_degrees": 5.0,
                 "plate_text": "LEA-1234", "plate_confidence": 0.8, "bounding_box": {"x1": 0.0, "y1": 0.0, "x2": 1414.0, "y2": 1414.0},
                 "corridor_id": 1, "corridor_cohesion": 1.0, "track_frame_count": 60, "displacement_pixels": 310.0,
                 "scale_trend": "growing", "scale_growth_fraction": 1.4}
              ],
              "frame_width": 1920,
              "frame_height": 1080
            }
            """.trimIndent(),
        )

        val reportId = submitReport(
            latitude,
            compassHeadingDegrees = BigDecimal("0.0"),
            locationSamplesJson = locationSamplesJson,
        )
        val finalReport = waitForTerminalStatus(reportId)

        assertThat(finalReport.status).isEqualTo(ReportStatus.CONFIRMED)
        assertThat(finalReport.analysisMessage).contains("approaching a stationary camera")
        assertThat(finalReport.directionEvidence).contains("stationary_approach")
        assertThat(finalReport.licensePlate).isEqualTo("LEA-1234")
    }

    @Test
    fun `rotation_samples persisted and read back through a real Hibernate round-trip confirm a violation a stale compass scalar alone would have missed`() {
        val latitude = BigDecimal("31.5700")
        stubOverpassOneWayNorth(latitude) // legal bearing 0 (north); illegal bearing 180.
        val baseEpochMs = 1_700_000_000_000L
        val rotationSamplesJson = objectMapper.writeValueAsString(
            listOf(
                RotationSampleDto(headingDegrees = 10.0, capturedAt = baseEpochMs),
                RotationSampleDto(headingDegrees = 90.0, capturedAt = baseEpochMs + 8000L),
            ),
        )
        // Single vehicle with trackMidpointMs = 8000, landing exactly on the second rotation
        // sample - plus the corridor/flow fields ClipFlowAnalyzer.qualifyVehicles requires.
        stubVideoAnalysis(
            """
            {
              "vehicles": [
                {
                  "track_id": 1, "vehicle_type": "car", "detection_confidence": 0.9,
                  "bearing_degrees": 90.0, "plate_text": "LEA-1234", "plate_confidence": 0.9,
                  "bounding_box": {"x1": 0.0, "y1": 0.0, "x2": 1414.0, "y2": 1414.0},
                  "corridor_id": 1, "corridor_cohesion": 1.0,
                  "track_frame_count": 30, "displacement_pixels": 310.0,
                  "track_midpoint_ms": 8000
                }
              ],
              "frame_width": 1920,
              "frame_height": 1080
            }
            """.trimIndent(),
        )

        // A stale compass scalar of 0.0 alone would give absoluteBearing (0+90)%360 = 90 -
        // 90 degrees from the illegal bearing (180), outside the default 60-degree
        // tolerance - REJECTED. This report also carries rotation_samples showing the
        // camera's real orientation at this vehicle's own observation midpoint was 90.0
        // (not the stale 0.0 scalar), giving absoluteBearing (90+90)%360 = 180 - exactly
        // the illegal bearing - CONFIRMED. Unlike ReportAnalysisJobTest's equivalent unit
        // test, rotation_samples here travels through the real ReportService.submit() ->
        // Hibernate @JdbcTypeCode(SqlTypes.JSON) write -> reportRepository.findById() read
        // path that ReportAnalysisJob actually runs against in production.
        val reportId = submitReport(
            latitude,
            compassHeadingDegrees = BigDecimal("0.0"),
            rotationSamplesJson = rotationSamplesJson,
        )
        val finalReport = waitForTerminalStatus(reportId)

        assertThat(finalReport.status).isEqualTo(ReportStatus.CONFIRMED)
        assertThat(finalReport.licensePlate).isEqualTo("LEA-1234")

        val evidenceJson = finalReport.directionEvidence
        assertThat(evidenceJson).isNotNull()
        val evidenceBreakdown = objectMapper.readTree(evidenceJson)
        assertThat(evidenceBreakdown.get("candidate_orientation_source").asText()).isEqualTo("ROTATION")
    }
}
