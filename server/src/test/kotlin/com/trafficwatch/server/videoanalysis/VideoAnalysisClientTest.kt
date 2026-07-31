package com.trafficwatch.server.videoanalysis

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.client.ExpectedCount
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.nio.file.Files
import java.util.UUID

/**
 * Uses [MockRestServiceServer.bindTo] against a plain [RestClient.Builder] (available since
 * Spring Framework 6.1) rather than a running Python service, so this stays a fast, offline
 * unit test - contrast with `ReportAnalysisIntegrationTest`, which exercises this same
 * client through WireMock as part of the full async pipeline.
 *
 * In production, [VideoAnalysisClientConfig] builds its `RestClient` from Spring Boot's
 * autoconfigured, already-SNAKE_CASE-configured `RestClient.Builder` bean (see its doc
 * comment) - since this test calls `RestClient.builder()` directly instead, it must
 * explicitly register the same SNAKE_CASE Jackson converter itself, or `track_id`-shaped
 * JSON keys won't map onto `trackId`-shaped Kotlin properties.
 */
class VideoAnalysisClientTest {

    private lateinit var mockServer: MockRestServiceServer
    private lateinit var client: VideoAnalysisClient

    // FileSystemResource reads the file's bytes when the multipart request body is
    // actually serialized, so this must point at a real (if empty) file, not just a path.
    private val fakeVideoPath = Files.createTempFile("video-analysis-client-test", ".mp4").also {
        Files.write(it, byteArrayOf(1, 2, 3))
    }

    @BeforeEach
    fun setUp() {
        val objectMapper = ObjectMapper()
            .registerKotlinModule()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        val converter = MappingJackson2HttpMessageConverter(objectMapper)

        val builder = RestClient.builder()
            .baseUrl("http://video-analysis.test")
            .messageConverters { converters ->
                converters.removeIf { it is MappingJackson2HttpMessageConverter }
                converters.add(0, converter)
            }
        mockServer = MockRestServiceServer.bindTo(builder).build()
        client = VideoAnalysisClient(builder.build(), apiKey = "test-only-api-key")
    }

    @Test
    fun `analyze parses vehicles from a successful response and sends the API key header`() {
        val reportId = UUID.randomUUID()
        mockServer.expect(ExpectedCount.once(), requestTo("http://video-analysis.test/v1/analyze"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("X-API-Key", "test-only-api-key"))
            .andRespond(
                withSuccess(
                    """
                    {
                      "vehicles": [
                        {
                          "track_id": 1,
                          "vehicle_type": "car",
                          "detection_confidence": 0.91,
                          "bearing_degrees": 182.5,
                          "plate_text": "LEA-1234",
                          "plate_confidence": 0.87
                        },
                        {
                          "track_id": 2,
                          "vehicle_type": "truck",
                          "detection_confidence": 0.6,
                          "bearing_degrees": null,
                          "plate_text": null,
                          "plate_confidence": null
                        }
                      ]
                    }
                    """.trimIndent(),
                    MediaType.APPLICATION_JSON,
                ),
            )

        val vehicles = client.analyze(fakeVideoPath, reportId).vehicles

        assertThat(vehicles).hasSize(2)
        assertThat(vehicles[0].trackId).isEqualTo(1L)
        assertThat(vehicles[0].vehicleType).isEqualTo("car")
        assertThat(vehicles[0].bearingDegrees).isEqualTo(182.5)
        assertThat(vehicles[0].plateText).isEqualTo("LEA-1234")
        assertThat(vehicles[0].plateConfidence).isEqualTo(0.87)
        assertThat(vehicles[1].bearingDegrees).isNull()
        assertThat(vehicles[1].plateText).isNull()

        mockServer.verify()
    }

    @Test
    fun `analyze returns an empty list when the service reports no vehicles`() {
        mockServer.expect(requestTo("http://video-analysis.test/v1/analyze"))
            .andRespond(withSuccess("""{"vehicles": []}""", MediaType.APPLICATION_JSON))

        val vehicles = client.analyze(fakeVideoPath, UUID.randomUUID()).vehicles

        assertThat(vehicles).isEmpty()
    }

    @Test
    fun `analyze throws VideoAnalysisException on a server error`() {
        mockServer.expect(requestTo("http://video-analysis.test/v1/analyze"))
            .andRespond(withServerError())

        assertThatThrownBy { client.analyze(fakeVideoPath, UUID.randomUUID()) }
            .isInstanceOf(VideoAnalysisException::class.java)
    }
}
