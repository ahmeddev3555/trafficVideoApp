package com.trafficwatch.app.core.util

import android.hardware.SensorManager
import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Test

class CompassProviderTest {

    @Test
    fun `ROTATION_0 needs no remap`() {
        assertEquals(SensorManager.AXIS_X to SensorManager.AXIS_Y, remapAxesFor(Surface.ROTATION_0))
    }

    @Test
    fun `ROTATION_90 remaps to Y, MINUS_X`() {
        assertEquals(SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X, remapAxesFor(Surface.ROTATION_90))
    }

    @Test
    fun `ROTATION_180 remaps to MINUS_X, MINUS_Y`() {
        assertEquals(SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y, remapAxesFor(Surface.ROTATION_180))
    }

    @Test
    fun `ROTATION_270 remaps to MINUS_Y, X`() {
        assertEquals(SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X, remapAxesFor(Surface.ROTATION_270))
    }
}
