package com.trafficwatch.app.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UploadProgressTrackerTest {

    @Test
    fun `first chunk always emits with zero rate`() {
        val tracker = UploadProgressTracker(totalBytes = 1000L)

        val result = tracker.onChunk(bytesWritten = 100L, nowMs = 5000L)

        assertEquals(UploadProgressSnapshot(percent = 10, bytesUploaded = 100L, totalBytes = 1000L, bytesPerSecond = 0L), result)
    }

    @Test
    fun `chunk within throttle window and not final returns null`() {
        val tracker = UploadProgressTracker(totalBytes = 1000L)
        tracker.onChunk(bytesWritten = 100L, nowMs = 5000L)

        val result = tracker.onChunk(bytesWritten = 150L, nowMs = 5200L)

        assertNull(result)
    }

    @Test
    fun `chunk at or past throttle window emits with correct rate`() {
        val tracker = UploadProgressTracker(totalBytes = 1000L)
        tracker.onChunk(bytesWritten = 100L, nowMs = 5000L)

        val result = tracker.onChunk(bytesWritten = 500L, nowMs = 5400L)

        // (500 - 100) bytes over 400ms = 1000 bytes/sec
        assertEquals(UploadProgressSnapshot(percent = 50, bytesUploaded = 500L, totalBytes = 1000L, bytesPerSecond = 1000L), result)
    }

    @Test
    fun `final chunk always emits regardless of throttle window`() {
        val tracker = UploadProgressTracker(totalBytes = 1000L)
        tracker.onChunk(bytesWritten = 100L, nowMs = 5000L)

        val result = tracker.onChunk(bytesWritten = 1000L, nowMs = 5050L)

        // (1000 - 100) bytes over 50ms = 18000 bytes/sec
        assertEquals(UploadProgressSnapshot(percent = 100, bytesUploaded = 1000L, totalBytes = 1000L, bytesPerSecond = 18000L), result)
    }

    @Test
    fun `zero total bytes does not divide by zero`() {
        val tracker = UploadProgressTracker(totalBytes = 0L)

        val result = tracker.onChunk(bytesWritten = 0L, nowMs = 1000L)

        assertEquals(UploadProgressSnapshot(percent = 0, bytesUploaded = 0L, totalBytes = 0L, bytesPerSecond = 0L), result)
    }

    @Test
    fun `chunk at exactly throttle boundary emits without throttling`() {
        val tracker = UploadProgressTracker(totalBytes = 1000L)
        tracker.onChunk(bytesWritten = 100L, nowMs = 5000L)

        val result = tracker.onChunk(bytesWritten = 500L, nowMs = 5300L)

        // (500 - 100) bytes over exactly 300ms = 1333 bytes/sec
        assertEquals(UploadProgressSnapshot(percent = 50, bytesUploaded = 500L, totalBytes = 1000L, bytesPerSecond = 1333L), result)
    }
}
