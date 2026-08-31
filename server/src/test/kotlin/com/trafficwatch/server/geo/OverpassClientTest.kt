package com.trafficwatch.server.geo

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClient

/**
 * [OverpassClient] queries every configured mirror in sequence and unions the ways. Three
 * WireMock servers stand in for the three mirrors; each test wires the client to all three
 * (absolute URLs, no base URL on the [RestClient]) and stubs each mirror independently.
 */
class OverpassClientTest {

    private val endpointA = WireMockServer(WireMockConfiguration.options().dynamicPort())
    private val endpointB = WireMockServer(WireMockConfiguration.options().dynamicPort())
    private val endpointC = WireMockServer(WireMockConfiguration.options().dynamicPort())

    private val endpoints get() = listOf(endpointA, endpointB, endpointC)

    @BeforeEach
    fun startWireMock() {
        endpoints.forEach { it.start() }
    }

    @AfterEach
    fun stopWireMock() {
        endpoints.forEach { it.stop() }
    }

    private fun url(server: WireMockServer) = "http://localhost:${server.port()}/"

    private fun client(): OverpassClient {
        val restClient = RestClient.builder().build()
        val properties = OsmProperties(
            overpassBaseUrls = listOf(url(endpointA), url(endpointB), url(endpointC)),
            overpassPerEndpointAttempts = 1,
            lookupRetryDelayMs = 1L,
        )
        return OverpassClient(restClient, properties)
    }

    private fun stub(server: WireMockServer, json: String) {
        server.stubFor(post(urlPathEqualTo("/")).willReturn(okJson(json)))
    }

    private fun stubEmpty(server: WireMockServer) = stub(server, """{"elements": []}""")

    private fun stubStatus(server: WireMockServer, status: Int) {
        server.stubFor(post(urlPathEqualTo("/")).willReturn(aResponse().withStatus(status)))
    }

    private fun geometry(nodeCount: Int): String =
        (0 until nodeCount).joinToString(", ") { """{"lat": ${31.5 + it * 0.0001}, "lon": 74.3}""" }

    private fun wayJson(id: Long, nodeCount: Int = 2): String = """
        {"type": "way", "id": $id, "tags": {"highway": "residential"}, "geometry": [${geometry(nodeCount)}]}
    """.trimIndent()

    private fun waysJson(vararg ids: Long): String =
        """{"elements": [${ids.joinToString(", ") { wayJson(it) }}]}"""

    @Test
    fun `unions and dedupes ways across endpoints by id`() {
        stub(endpointA, waysJson(1L, 2L)); stub(endpointB, waysJson(2L, 3L)); stub(endpointC, waysJson(3L))
        val result = client().findNearbyWays(31.5, 74.3, 50.0)
        assertThat(result.ways.mapNotNull { it.id }).containsExactlyInAnyOrder(1L, 2L, 3L)
        assertThat(result.sourceCount).isEqualTo(3)
    }

    @Test
    fun `on an id collision keeps the element with more geometry nodes`() {
        stub(endpointA, """{"elements": [${wayJson(id = 5L, nodeCount = 2)}]}""")
        stub(endpointB, """{"elements": [${wayJson(id = 5L, nodeCount = 6)}]}""")
        stubEmpty(endpointC)
        val result = client().findNearbyWays(31.5, 74.3, 50.0)
        assertThat(result.ways.single { it.id == 5L }.geometry).hasSize(6)
    }

    @Test
    fun `a failing endpoint is skipped and the rest still answer`() {
        stubStatus(endpointA, 500); stub(endpointB, waysJson(9L)); stubStatus(endpointC, 500)
        val result = client().findNearbyWays(31.5, 74.3, 50.0)
        assertThat(result.ways.mapNotNull { it.id }).containsExactly(9L)
        assertThat(result.sourceCount).isEqualTo(1)
    }

    @Test
    fun `throws OsmLookupException when every endpoint fails`() {
        stubStatus(endpointA, 500); stubStatus(endpointB, 500); stubStatus(endpointC, 500)
        assertThatThrownBy { client().findNearbyWays(31.5, 74.3, 50.0) }
            .isInstanceOf(OsmLookupException::class.java)
    }

    @Test
    fun `an empty elements body still counts as a source`() {
        stubEmpty(endpointA); stubEmpty(endpointB); stubEmpty(endpointC)
        val result = client().findNearbyWays(31.5, 74.3, 50.0)
        assertThat(result.ways).isEmpty()
        assertThat(result.sourceCount).isEqualTo(3)
    }

    @Test
    fun `includes the given radius in each endpoint query`() {
        stubEmpty(endpointA); stubEmpty(endpointB); stubEmpty(endpointC)
        client().findNearbyWays(31.5, 74.3, 120.0)
        endpointA.verify(
            postRequestedFor(urlPathEqualTo("/"))
                .withRequestBody(containing("around%3A120.0%2C31.5%2C74.3")),
        )
    }
}
