package com.trafficwatch.server.geo

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlMatching
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.math.BigDecimal

/**
 * WireMock stubs both the Overpass and Nominatim base URLs (Overpass is exercised by every
 * test here; Nominatim would only be hit as a fallback when a way has no `name` tag, which
 * these fixtures always supply). Each test uses a distinct coordinate so its cache bucket
 * never collides with another test's, since the cache table persists across tests within
 * the same Spring context.
 */
@SpringBootTest
@ActiveProfiles("test")
class StreetDirectionResolverTest @Autowired constructor(
    private val streetDirectionResolver: StreetDirectionResolver,
    private val cacheRepository: OsmLookupCacheRepository,
) {

    companion object {
        private val wireMockServer = WireMockServer(WireMockConfiguration.options().dynamicPort())

        @JvmStatic
        @BeforeAll
        fun startServer() {
            wireMockServer.start()
        }

        @JvmStatic
        @AfterAll
        fun stopServer() {
            wireMockServer.stop()
        }

        @JvmStatic
        @DynamicPropertySource
        fun overrideOsmUrls(registry: DynamicPropertyRegistry) {
            registry.add("app.osm.nominatim-base-url") { "http://localhost:${wireMockServer.port()}" }
            registry.add("app.osm.overpass-base-url") { "http://localhost:${wireMockServer.port()}" }
        }
    }

    @AfterEach
    fun resetStubs() {
        wireMockServer.resetAll()
    }

    @Test
    fun `returns OneWay with a legal bearing when Overpass returns a way tagged oneway=yes`() {
        wireMockServer.stubFor(
            post(urlMatching(".*")).willReturn(okJson(overpassResponseJson(oneway = "yes", name = "Main Boulevard"))),
        )

        val result = streetDirectionResolver.resolve(BigDecimal("31.520000"), BigDecimal("74.350000"), accuracyMeters = 10.0)

        assertThat(result).isInstanceOf(DirectionResolution.OneWay::class.java)
        assertThat((result as DirectionResolution.OneWay).streetName).isEqualTo("Main Boulevard")
    }

    @Test
    fun `returns OneWay in the reverse direction when tagged oneway=-1`() {
        wireMockServer.stubFor(
            post(urlMatching(".*")).willReturn(okJson(overpassResponseJson(oneway = "-1", name = "Reverse Street"))),
        )

        val forward = streetDirectionResolver.resolve(BigDecimal("32.520000"), BigDecimal("75.350000"), accuracyMeters = 10.0)
        wireMockServer.resetAll()
        wireMockServer.stubFor(
            post(urlMatching(".*")).willReturn(okJson(overpassResponseJson(oneway = "yes", name = "Reverse Street"))),
        )
        val reverse = streetDirectionResolver.resolve(BigDecimal("33.520000"), BigDecimal("76.350000"), accuracyMeters = 10.0)

        val forwardBearing = (forward as DirectionResolution.OneWay).legalBearingDegrees
        val reverseBearing = (reverse as DirectionResolution.OneWay).legalBearingDegrees
        assertThat(BearingMath.angularDifferenceDegrees(forwardBearing, reverseBearing)).isCloseTo(
            180.0,
            org.assertj.core.api.Assertions.within(1.0),
        )
    }

    @Test
    fun `returns NotFound when Overpass returns no elements`() {
        wireMockServer.stubFor(post(urlMatching(".*")).willReturn(okJson("""{"elements": []}""")))

        val result = streetDirectionResolver.resolve(BigDecimal("10.000000"), BigDecimal("10.000000"), accuracyMeters = 10.0)

        assertThat(result).isEqualTo(DirectionResolution.NotFound)
    }

    @Test
    fun `returns Unknown when the way has no oneway tag`() {
        wireMockServer.stubFor(
            post(urlMatching(".*")).willReturn(okJson(overpassResponseJson(oneway = null, name = "Side Street"))),
        )

        val result = streetDirectionResolver.resolve(BigDecimal("20.000000"), BigDecimal("20.000000"), accuracyMeters = 10.0)

        assertThat(result).isInstanceOf(DirectionResolution.Unknown::class.java)
        assertThat((result as DirectionResolution.Unknown).streetName).isEqualTo("Side Street")
    }

    @Test
    fun `returns TwoWay when the way is explicitly tagged oneway=no`() {
        wireMockServer.stubFor(
            post(urlMatching(".*")).willReturn(okJson(overpassResponseJson(oneway = "no", name = "Two Way Ave"))),
        )

        val result = streetDirectionResolver.resolve(BigDecimal("30.000000"), BigDecimal("30.000000"), accuracyMeters = 10.0)

        assertThat(result).isInstanceOf(DirectionResolution.TwoWay::class.java)
    }

    @Test
    fun `caches the resolution so a second call for the same bucket does not hit Overpass again`() {
        wireMockServer.stubFor(
            post(urlMatching(".*")).willReturn(okJson(overpassResponseJson(oneway = "yes", name = "Cached Street"))),
        )

        streetDirectionResolver.resolve(BigDecimal("40.000000"), BigDecimal("40.000000"), accuracyMeters = 10.0)
        streetDirectionResolver.resolve(BigDecimal("40.000000"), BigDecimal("40.000000"), accuracyMeters = 10.0)

        wireMockServer.verify(1, postRequestedFor(urlMatching(".*")))
    }

    @Test
    fun `returns LookupFailed without caching when Overpass errors`() {
        wireMockServer.stubFor(post(urlMatching(".*")).willReturn(aResponse().withStatus(500)))

        val result = streetDirectionResolver.resolve(BigDecimal("50.000000"), BigDecimal("50.000000"), accuracyMeters = 10.0)

        assertThat(result).isInstanceOf(DirectionResolution.LookupFailed::class.java)
        assertThat(cacheRepository.findByLatBucketAndLonBucket(BigDecimal("50.0000"), BigDecimal("50.0000"))).isNull()
    }

    @Test
    fun `scales the Overpass search radius with the report's accuracy`() {
        wireMockServer.stubFor(post(urlMatching(".*")).willReturn(okJson("""{"elements": []}""")))

        streetDirectionResolver.resolve(BigDecimal("60.000000"), BigDecimal("60.000000"), accuracyMeters = 80.0)

        wireMockServer.verify(
            postRequestedFor(urlMatching(".*")).withRequestBody(containing("around%3A160.0")),
        )
    }

    @Test
    fun `returns Unknown when two different-named ways are within accuracy meters of each other`() {
        // Way A ~11.1m away, Way B ~22.2m away - gap ~11.1m, smaller than the 15.0m accuracy below.
        wireMockServer.stubFor(
            post(urlMatching(".*")).willReturn(
                okJson(
                    twoWayOverpassResponseJson(
                        wayAId = 301, wayAName = "Street A", wayAOneway = "yes", wayALatOffsetDegrees = 0.000100, wayAWestToEast = true,
                        wayBId = 302, wayBName = "Street B", wayBOneway = null, wayBLatOffsetDegrees = 0.000200, wayBWestToEast = true,
                        baseLat = 61.000000, baseLon = 61.000000,
                    ),
                ),
            ),
        )

        val result = streetDirectionResolver.resolve(BigDecimal("61.000000"), BigDecimal("61.000000"), accuracyMeters = 15.0)

        assertThat(result).isInstanceOf(DirectionResolution.Unknown::class.java)
        assertThat((result as DirectionResolution.Unknown).streetName).isEqualTo("Street A")
    }

    @Test
    fun `does not treat two segments of the same named street as ambiguous`() {
        // Same gap (~11.1m) as the previous test, but both ways share a name.
        wireMockServer.stubFor(
            post(urlMatching(".*")).willReturn(
                okJson(
                    twoWayOverpassResponseJson(
                        wayAId = 303, wayAName = "Shared Street", wayAOneway = "yes", wayALatOffsetDegrees = 0.000100, wayAWestToEast = true,
                        wayBId = 304, wayBName = "Shared Street", wayBOneway = "yes", wayBLatOffsetDegrees = 0.000200, wayBWestToEast = true,
                        baseLat = 62.000000, baseLon = 62.000000,
                    ),
                ),
            ),
        )

        val result = streetDirectionResolver.resolve(BigDecimal("62.000000"), BigDecimal("62.000000"), accuracyMeters = 15.0)

        assertThat(result).isInstanceOf(DirectionResolution.OneWay::class.java)
        assertThat((result as DirectionResolution.OneWay).streetName).isEqualTo("Shared Street")
    }

    @Test
    fun `downgrades to Unknown when a nearby anti-parallel oneway way signals a divided carriageway`() {
        // Way A ~11.1m away bearing ~90 deg (east); Way B ~27.8m away bearing ~270 deg (west,
        // anti-parallel). Gap ~16.7m: bigger than the 5.0m accuracy below (so the ambiguity
        // check does NOT fire), smaller than the 30m divided-carriageway proximity cap.
        wireMockServer.stubFor(
            post(urlMatching(".*")).willReturn(
                okJson(
                    twoWayOverpassResponseJson(
                        wayAId = 305, wayAName = "Ring Road North", wayAOneway = "yes", wayALatOffsetDegrees = 0.000100, wayAWestToEast = true,
                        wayBId = 306, wayBName = "Ring Road South", wayBOneway = "yes", wayBLatOffsetDegrees = 0.000250, wayBWestToEast = false,
                        baseLat = 63.000000, baseLon = 63.000000,
                    ),
                ),
            ),
        )

        val result = streetDirectionResolver.resolve(BigDecimal("63.000000"), BigDecimal("63.000000"), accuracyMeters = 5.0)

        assertThat(result).isInstanceOf(DirectionResolution.Unknown::class.java)
        assertThat((result as DirectionResolution.Unknown).streetName).isEqualTo("Ring Road North")
    }

    @Test
    fun `does not downgrade for a distant anti-parallel oneway way outside the carriageway proximity cap`() {
        // Way A ~11.1m away bearing ~90 deg; Way B ~55.6m away bearing ~270 deg (anti-parallel,
        // but the ~44.5m gap exceeds the 30m divided-carriageway proximity cap).
        wireMockServer.stubFor(
            post(urlMatching(".*")).willReturn(
                okJson(
                    twoWayOverpassResponseJson(
                        wayAId = 307, wayAName = "Avenue A", wayAOneway = "yes", wayALatOffsetDegrees = 0.000100, wayAWestToEast = true,
                        wayBId = 308, wayBName = "Avenue B", wayBOneway = "yes", wayBLatOffsetDegrees = 0.000500, wayBWestToEast = false,
                        baseLat = 64.000000, baseLon = 64.000000,
                    ),
                ),
            ),
        )

        val result = streetDirectionResolver.resolve(BigDecimal("64.000000"), BigDecimal("64.000000"), accuracyMeters = 5.0)

        assertThat(result).isInstanceOf(DirectionResolution.OneWay::class.java)
        assertThat((result as DirectionResolution.OneWay).streetName).isEqualTo("Avenue A")
    }

    private fun overpassResponseJson(oneway: String?, name: String): String {
        val tagsJson = buildString {
            append(""""name": "$name"""")
            if (oneway != null) append(""", "oneway": "$oneway"""")
        }
        return """
            {
              "elements": [
                {
                  "type": "way",
                  "id": 123,
                  "tags": { $tagsJson },
                  "geometry": [
                    {"lat": 31.5190, "lon": 74.3495},
                    {"lat": 31.5210, "lon": 74.3505}
                  ]
                }
              ]
            }
        """.trimIndent()
    }

    private fun twoWayOverpassResponseJson(
        wayAId: Long, wayAName: String?, wayAOneway: String?, wayALatOffsetDegrees: Double, wayAWestToEast: Boolean,
        wayBId: Long, wayBName: String?, wayBOneway: String?, wayBLatOffsetDegrees: Double, wayBWestToEast: Boolean,
        baseLat: Double, baseLon: Double,
    ): String {
        fun wayJson(id: Long, name: String?, oneway: String?, latOffset: Double, westToEast: Boolean): String {
            val lat = baseLat + latOffset
            val (lon1, lon2) = if (westToEast) (baseLon - 0.0010) to (baseLon + 0.0010) else (baseLon + 0.0010) to (baseLon - 0.0010)
            val tagsJson = buildString {
                if (name != null) append(""""name": "$name"""")
                if (oneway != null) {
                    if (name != null) append(", ")
                    append(""""oneway": "$oneway"""")
                }
            }
            return """
                {
                  "type": "way",
                  "id": $id,
                  "tags": { $tagsJson },
                  "geometry": [
                    {"lat": $lat, "lon": $lon1},
                    {"lat": $lat, "lon": $lon2}
                  ]
                }
            """.trimIndent()
        }
        return """{"elements": [${wayJson(wayAId, wayAName, wayAOneway, wayALatOffsetDegrees, wayAWestToEast)}, ${wayJson(wayBId, wayBName, wayBOneway, wayBLatOffsetDegrees, wayBWestToEast)}]}"""
    }
}
