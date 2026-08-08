package com.trafficwatch.server.geo

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.github.tomakehurst.wiremock.stubbing.Scenario
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClient

class OverpassClientTest {

    private val wireMockServer = WireMockServer(WireMockConfiguration.options().dynamicPort())

    @BeforeEach
    fun startWireMock() {
        wireMockServer.start()
    }

    @AfterEach
    fun stopWireMock() {
        wireMockServer.stop()
    }

    private fun client(retryAttempts: Int = 3, retryDelayMs: Long = 1L): OverpassClient {
        val restClient = RestClient.builder().baseUrl("http://localhost:${wireMockServer.port()}").build()
        val properties = OsmProperties(lookupRetryAttempts = retryAttempts, lookupRetryDelayMs = retryDelayMs)
        return OverpassClient(restClient, properties)
    }

    @Test
    fun `findNearbyWays retries once on a 503 then returns the successful result`() {
        wireMockServer.stubFor(
            post(urlPathEqualTo("/"))
                .inScenario("retry")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("second attempt"),
        )
        wireMockServer.stubFor(
            post(urlPathEqualTo("/"))
                .inScenario("retry")
                .whenScenarioStateIs("second attempt")
                .willReturn(okJson("""{"elements": []}""")),
        )

        val result = client().findNearbyWays(31.5, 74.3, 50.0)

        assertThat(result).isEmpty()
        assertThat(wireMockServer.allServeEvents).hasSize(2)
    }

    @Test
    fun `findNearbyWays does not retry a 400 and fails immediately`() {
        wireMockServer.stubFor(post(urlPathEqualTo("/")).willReturn(aResponse().withStatus(400)))

        assertThatThrownBy { client().findNearbyWays(31.5, 74.3, 50.0) }
            .isInstanceOf(OsmLookupException::class.java)

        assertThat(wireMockServer.allServeEvents).hasSize(1)
    }

    @Test
    fun `findNearbyWays throws after exhausting all retry attempts`() {
        wireMockServer.stubFor(post(urlPathEqualTo("/")).willReturn(aResponse().withStatus(503)))

        assertThatThrownBy { client(retryAttempts = 3).findNearbyWays(31.5, 74.3, 50.0) }
            .isInstanceOf(OsmLookupException::class.java)

        assertThat(wireMockServer.allServeEvents).hasSize(3)
    }

    @Test
    fun `findNearbyWays includes the given radius in the query`() {
        wireMockServer.stubFor(post(urlPathEqualTo("/")).willReturn(okJson("""{"elements": []}""")))

        client().findNearbyWays(31.5, 74.3, 120.0)

        wireMockServer.verify(
            postRequestedFor(urlPathEqualTo("/")).withRequestBody(containing("around%3A120.0%2C31.5%2C74.3")),
        )
    }
}
