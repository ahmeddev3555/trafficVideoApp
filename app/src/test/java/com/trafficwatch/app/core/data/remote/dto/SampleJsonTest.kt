package com.trafficwatch.app.core.data.remote.dto

import com.trafficwatch.app.core.domain.model.LocationData
import com.trafficwatch.app.core.domain.model.RotationSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SampleJsonTest {
    @Test fun `location returns null for an empty list`() {
        assertNull(SampleJson.location(emptyList()))
    }

    @Test fun `location serializes snake_case fields`() {
        val json = SampleJson.location(listOf(LocationData(31.5, 74.3, 5f, 200.0, 90f, 10f, 1000L)))
        assertEquals(
            """[{"latitude":31.5,"longitude":74.3,"accuracy":5.0,"altitude":200.0,"bearing":90.0,"speed":10.0,"captured_at":1000}]""",
            json,
        )
    }

    @Test fun `rotation returns null for an empty list`() {
        assertNull(SampleJson.rotation(emptyList()))
    }

    @Test fun `rotation serializes snake_case fields`() {
        val json = SampleJson.rotation(listOf(RotationSample(capturedAt = 2000L, headingDegrees = 187.5f)))
        assertEquals("""[{"heading_degrees":187.5,"captured_at":2000}]""", json)
    }
}
