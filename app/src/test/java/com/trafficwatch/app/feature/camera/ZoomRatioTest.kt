package com.trafficwatch.app.feature.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class ZoomRatioTest {

    @Test
    fun `a value within both the app cap and device range passes through unchanged`() {
        assertEquals(1.5f, clampZoomRatio(1.5f, deviceMinZoomRatio = 1.0f, deviceMaxZoomRatio = 8.0f), 0.001f)
    }

    @Test
    fun `a value above the app's 2x cap is clamped to 2x even when the device supports more`() {
        assertEquals(2.0f, clampZoomRatio(5.0f, deviceMinZoomRatio = 1.0f, deviceMaxZoomRatio = 8.0f), 0.001f)
    }

    @Test
    fun `a value below 1x is clamped to 1x`() {
        assertEquals(1.0f, clampZoomRatio(0.5f, deviceMinZoomRatio = 1.0f, deviceMaxZoomRatio = 8.0f), 0.001f)
    }

    @Test
    fun `the app cap is further reduced when the device's own max zoom is below 2x`() {
        assertEquals(1.6f, clampZoomRatio(2.0f, deviceMinZoomRatio = 1.0f, deviceMaxZoomRatio = 1.6f), 0.001f)
    }

    @Test
    fun `the app floor is raised when the device's own min zoom is above 1x`() {
        assertEquals(1.2f, clampZoomRatio(1.0f, deviceMinZoomRatio = 1.2f, deviceMaxZoomRatio = 8.0f), 0.001f)
    }
}
