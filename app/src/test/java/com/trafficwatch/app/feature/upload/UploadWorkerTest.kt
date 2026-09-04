package com.trafficwatch.app.feature.upload

import com.trafficwatch.app.core.domain.model.LocationData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UploadWorkerTest {
    private val loc = LocationData(31.5, 74.3, 5f, 200.0, 90f, 10f, 1000L)

    @Test fun `buildInputData stores sample json verbatim when present`() {
        val data = UploadWorker.buildInputData(
            "r1", "/v.mp4", loc,
            locationSamplesJson = """[{"a":1}]""", rotationSamplesJson = """[{"b":2}]""",
            recordingStartedAt = 1_756_000_000_000L, durationMs = 6000L,
        )
        assertEquals("""[{"a":1}]""", data.getString(UploadWorker.KEY_LOCATION_SAMPLES_JSON))
        assertEquals("""[{"b":2}]""", data.getString(UploadWorker.KEY_ROTATION_SAMPLES_JSON))
    }

    @Test fun `buildInputData omits sample keys when null`() {
        val data = UploadWorker.buildInputData(
            "r1", "/v.mp4", loc, null, null, 1_756_000_000_000L, 6000L,
        )
        assertNull(data.getString(UploadWorker.KEY_LOCATION_SAMPLES_JSON))
        assertNull(data.getString(UploadWorker.KEY_ROTATION_SAMPLES_JSON))
    }

    @Test fun `formatRecordedAt is UTC regardless of device zone`() {
        val millis = 1_756_479_157_000L // 2025-08-29T14:52:37Z (verified via date -u -d @1756479157)
        val expected = "2025-08-29T14:52:37Z"
        val default = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Karachi"))
            assertEquals(expected, UploadWorker.formatRecordedAt(millis))
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/Los_Angeles"))
            assertEquals(expected, UploadWorker.formatRecordedAt(millis))
        } finally {
            java.util.TimeZone.setDefault(default)
        }
    }
}
