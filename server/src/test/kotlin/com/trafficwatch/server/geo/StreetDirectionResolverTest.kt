package com.trafficwatch.server.geo

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
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

        val result = streetDirectionResolver.resolve(BigDecimal("31.520000"), BigDecimal("74.350000"))

        assertThat(result).isInstanceOf(DirectionResolution.OneWay::class.java)
        assertThat((result as DirectionResolution.OneWay).streetName).isEqualTo("Main Boulevard")
    }

    @Test
    fun `returns OneWay in the reverse direction when tagged oneway=-1`() {
        wireMockServer.stubFor(
            post(urlMatching(".*")).willReturn(okJson(overpassResponseJson(oneway = "-1", name = "Reverse Street"))),
        )

        val forward = streetDirectionResolver.resolve(BigDecimal("32.520000"), BigDecimal("75.350000"))
        wireMockServer.resetAll()
        wireMockServer.stubFor(
            post(urlMatching(".*")).willReturn(okJson(overpassResponseJson(oneway = "yes", name = "Reverse Street"))),
        )
        val reverse = streetDirectionResolver.resolve(BigDecimal("33.520000"), BigDecimal("76.350000"))

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

        val result = streetDirectionResolver.resolve(BigDecimal("10.000000"), BigDecimal("10.000000"))

        assertThat(result).isEqualTo(DirectionResolution.NotFound)
    }

    @Test
    fun `returns Unknown when the way has no oneway tag`() {
        wireMockServer.stubFor(
            post(urlMatching(".*")).willReturn(okJson(overpassResponseJson(oneway = null, name = "Side Street"))),
        )

        val result = streetDirectionResolver.resolve(BigDecimal("20.000000"), BigDecimal("20.000000"))

        assertThat(result).isInstanceOf(DirectionResolution.Unknown::class.java)
        assertThat((result as DirectionResolution.Unknown).streetName).isEqualTo("Side Street")
    }

    @Test
    fun `returns TwoWay when the way is explicitly tagged oneway=no`() {
        wireMockServer.stubFor(
            post(urlMatching(".*")).willReturn(okJson(overpassResponseJson(oneway = "no", name = "Two Way Ave"))),
        )

        val result = streetDirectionResolver.resolve(BigDecimal("30.000000"), BigDecimal("30.000000"))

        assertThat(result).isInstanceOf(DirectionResolution.TwoWay::class.java)
    }

    @Test
    fun `caches the resolution so a second call for the same bucket does not hit Overpass again`() {
        wireMockServer.stubFor(
            post(urlMatching(".*")).willReturn(okJson(overpassResponseJson(oneway = "yes", name = "Cached Street"))),
        )

        streetDirectionResolver.resolve(BigDecimal("40.000000"), BigDecimal("40.000000"))
        streetDirectionResolver.resolve(BigDecimal("40.000000"), BigDecimal("40.000000"))

        wireMockServer.verify(1, postRequestedFor(urlMatching(".*")))
    }

    @Test
    fun `returns LookupFailed without caching when Overpass errors`() {
        wireMockServer.stubFor(post(urlMatching(".*")).willReturn(aResponse().withStatus(500)))

        val result = streetDirectionResolver.resolve(BigDecimal("50.000000"), BigDecimal("50.000000"))

        assertThat(result).isInstanceOf(DirectionResolution.LookupFailed::class.java)
        assertThat(cacheRepository.findByLatBucketAndLonBucket(BigDecimal("50.0000"), BigDecimal("50.0000"))).isNull()
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
}
