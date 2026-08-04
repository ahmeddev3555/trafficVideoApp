package com.trafficwatch.app.core.data.remote.dto

import com.google.gson.Gson
import com.trafficwatch.app.core.domain.model.RotationSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RotationSampleDtoTest {

    @Test
    fun `toSampleDto maps every RotationSample field across`() {
        val sample = RotationSample(capturedAt = 1735814400123L, headingDegrees = 271.5f)

        val dto = sample.toSampleDto()

        assertEquals(271.5f, dto.headingDegrees, 1e-6f)
        assertEquals(1735814400123L, dto.capturedAt)
    }

    @Test
    fun `list of samples serializes to a JSON array with snake_case keys`() {
        val samples = listOf(
            RotationSample(capturedAt = 1000L, headingDegrees = 90.0f).toSampleDto(),
            RotationSample(capturedAt = 1200L, headingDegrees = 95.5f).toSampleDto(),
        )

        val json = Gson().toJson(samples)

        assertTrue(json.contains("\"captured_at\":1000"))
        assertTrue(json.contains("\"heading_degrees\":90.0"))
        assertTrue(json.contains("\"captured_at\":1200"))
        assertTrue(json.startsWith("["))
        assertTrue(json.endsWith("]"))
    }
}
