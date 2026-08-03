package com.trafficwatch.server.reports.dto

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LocationSampleDtoTest {

    // Mirrors the app-wide default ObjectMapper's snake_case naming strategy (see
    // ServerApplication's Jackson configuration) without needing a full Spring context.
    private val objectMapper: ObjectMapper = jacksonObjectMapper()
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)

    @Test
    fun `parses a snake_case JSON array into a list of samples`() {
        val json = """
            [
              {"latitude":31.470851,"longitude":74.4054352,"accuracy":12.3,"altitude":210.5,"bearing":87.3,"speed":8.1,"captured_at":1735814400123},
              {"latitude":31.470900,"longitude":74.4054400,"accuracy":11.0,"altitude":211.0,"bearing":90.0,"speed":9.0,"captured_at":1735814401123}
            ]
        """.trimIndent()

        val samples: List<LocationSampleDto> = objectMapper.readValue(json)

        assertThat(samples).hasSize(2)
        assertThat(samples[0].latitude).isEqualTo(31.470851)
        assertThat(samples[0].capturedAt).isEqualTo(1735814400123L)
        assertThat(samples[1].capturedAt).isEqualTo(1735814401123L)
    }

    @Test
    fun `round-trips through serialization back to the same snake_case shape`() {
        val samples = listOf(
            LocationSampleDto(31.47, 74.40, 10.0, 200.0, 90.0, 5.0, 1000L),
        )

        val json = objectMapper.writeValueAsString(samples)

        assertThat(json).contains("\"captured_at\":1000")
        assertThat(json).doesNotContain("capturedAt")
    }
}
