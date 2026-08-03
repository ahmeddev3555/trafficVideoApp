package com.trafficwatch.app.core.data.remote.dto

import com.google.gson.Gson
import com.trafficwatch.app.core.domain.model.LocationData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationSampleDtoTest {

    @Test
    fun `toSampleDto maps every LocationData field across`() {
        val location = LocationData(
            latitude = 31.470851,
            longitude = 74.4054352,
            accuracy = 12.3f,
            altitude = 210.5,
            bearing = 87.3f,
            speed = 8.1f,
            capturedAt = 1735814400123L,
        )

        val dto = location.toSampleDto()

        assertEquals(31.470851, dto.latitude, 1e-9)
        assertEquals(74.4054352, dto.longitude, 1e-9)
        assertEquals(12.3f, dto.accuracy, 1e-6f)
        assertEquals(210.5, dto.altitude, 1e-9)
        assertEquals(87.3f, dto.bearing, 1e-6f)
        assertEquals(8.1f, dto.speed, 1e-6f)
        assertEquals(1735814400123L, dto.capturedAt)
    }

    @Test
    fun `list of samples serializes to a JSON array with snake_case keys`() {
        val samples = listOf(
            LocationData(31.47, 74.40, 10f, 200.0, 90f, 5f, 1000L).toSampleDto(),
            LocationData(31.48, 74.41, 11f, 201.0, 91f, 6f, 2000L).toSampleDto(),
        )

        val json = Gson().toJson(samples)

        assertTrue(json.contains("\"captured_at\":1000"))
        assertTrue(json.contains("\"captured_at\":2000"))
        assertTrue(json.startsWith("["))
        assertTrue(json.endsWith("]"))
    }
}
