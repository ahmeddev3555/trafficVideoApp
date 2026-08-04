package com.trafficwatch.server.geo

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.github.tomakehurst.wiremock.stubbing.Scenario
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClient

class NominatimClientTest {

    private val wireMockServer = WireMockServer(WireMockConfiguration.options().dynamicPort())

    @BeforeEach
    fun startWireMock() {
        wireMockServer.start()
    }

    @AfterEach
    fun stopWireMock() {
        wireMockServer.stop()
    }

    private fun client(retryAttempts: Int = 3, retryDelayMs: Long = 1L): NominatimClient {
        val restClient = RestClient.builder().baseUrl("http://localhost:${wireMockServer.port()}").build()
        val properties = OsmProperties(lookupRetryAttempts = retryAttempts, lookupRetryDelayMs = retryDelayMs)
        return NominatimClient(restClient, properties)
    }

    @Test
    fun `reverseGeocode retries once on a connection-level failure then returns the successful result`() {
        wireMockServer.stubFor(
            get(urlPathEqualTo("/reverse"))
                .inScenario("retry")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(502))
                .willSetStateTo("second attempt"),
        )
        wireMockServer.stubFor(
            get(urlPathEqualTo("/reverse"))
                .inScenario("retry")
                .whenScenarioStateIs("second attempt")
                .willReturn(okJson("""{"address": {"road": "Test Road"}}""")),
        )

        val result = client().reverseGeocode(31.5, 74.3)

        assertThat(result.address?.road).isEqualTo("Test Road")
        assertThat(wireMockServer.allServeEvents).hasSize(2)
    }

    @Test
    fun `reverseGeocode does not retry a 404 and fails immediately`() {
        wireMockServer.stubFor(get(urlPathEqualTo("/reverse")).willReturn(aResponse().withStatus(404)))

        assertThatThrownBy { client().reverseGeocode(31.5, 74.3) }
            .isInstanceOf(OsmLookupException::class.java)

        assertThat(wireMockServer.allServeEvents).hasSize(1)
    }
}
