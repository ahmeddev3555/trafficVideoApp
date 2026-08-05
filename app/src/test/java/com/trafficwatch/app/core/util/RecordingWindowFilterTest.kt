package com.trafficwatch.app.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

private data class TimestampedThing(val capturedAt: Long, val label: String)

class RecordingWindowFilterTest {

    @Test
    fun `keeps items within the window inclusive of both bounds`() {
        val items = listOf(
            TimestampedThing(99, "before"),
            TimestampedThing(100, "at start"),
            TimestampedThing(150, "inside"),
            TimestampedThing(200, "at end"),
            TimestampedThing(201, "after"),
        )

        val result = filterToRecordingWindow(items, windowStart = 100, windowEnd = 200) { it.capturedAt }

        assertEquals(listOf("at start", "inside", "at end"), result.map { it.label })
    }

    @Test
    fun `returns an empty list when nothing falls in the window`() {
        val items = listOf(TimestampedThing(1, "a"), TimestampedThing(2, "b"))

        val result = filterToRecordingWindow(items, windowStart = 100, windowEnd = 200) { it.capturedAt }

        assertEquals(emptyList<String>(), result.map { it.label })
    }

    @Test
    fun `a zero-duration window only keeps items at exactly that instant`() {
        val items = listOf(TimestampedThing(100, "exact"), TimestampedThing(101, "one ms late"))

        val result = filterToRecordingWindow(items, windowStart = 100, windowEnd = 100) { it.capturedAt }

        assertEquals(listOf("exact"), result.map { it.label })
    }

    @Test
    fun `an empty input list returns an empty result`() {
        val result = filterToRecordingWindow(emptyList<TimestampedThing>(), windowStart = 0, windowEnd = 100) { it.capturedAt }

        assertEquals(emptyList<TimestampedThing>(), result)
    }
}
