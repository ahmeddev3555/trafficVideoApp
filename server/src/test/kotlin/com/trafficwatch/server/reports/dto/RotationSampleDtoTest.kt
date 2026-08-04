package com.trafficwatch.server.reports.dto

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RotationSampleDtoTest {

    // Mirrors the app-wide default ObjectMapper's snake_case naming strategy without
    // needing a full Spring context.
    private val objectMapper: ObjectMapper = jacksonObjectMapper()
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)

    @Test
    fun `parses a snake_case JSON array into a list of samples`() {
        val json = """
            [
              {"heading_degrees":271.5,"captured_at":1735814400123},
              {"heading_degrees":268.2,"captured_at":1735814400323}
            ]
        """.trimIndent()

        val samples: List<RotationSampleDto> = objectMapper.readValue(json)

        assertThat(samples).hasSize(2)
        assertThat(samples[0].headingDegrees).isEqualTo(271.5)
        assertThat(samples[0].capturedAt).isEqualTo(1735814400123L)
        assertThat(samples[1].capturedAt).isEqualTo(1735814400323L)
    }

    @Test
    fun `round-trips through serialization back to the same snake_case shape`() {
        val samples = listOf(RotationSampleDto(headingDegrees = 90.0, capturedAt = 1000L))

        val json = objectMapper.writeValueAsString(samples)

        assertThat(json).contains("\"captured_at\":1000")
        assertThat(json).contains("\"heading_degrees\":90.0")
        assertThat(json).doesNotContain("capturedAt")
    }
}
