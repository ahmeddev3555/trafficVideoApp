package com.trafficwatch.app.core.util

private const val PROGRESS_EMIT_INTERVAL_MS = 300L

data class UploadProgressSnapshot(
    val percent: Int,
    val bytesUploaded: Long,
    val totalBytes: Long,
    val bytesPerSecond: Long
)

/**
 * Pure progress/throttle tracker for a single upload - takes the current time as a
 * parameter rather than reading a clock itself, so it can be unit-tested with synthetic
 * timestamps and no Android framework stubbing. Not thread-safe - used from a single
 * OkHttp write callback per upload.
 */
class UploadProgressTracker(private val totalBytes: Long) {
    private var lastEmittedBytes = 0L
    private var lastEmittedAtMs = 0L
    private var hasEmitted = false

    /** Returns a snapshot to report, or null if this chunk should be throttled (not the final one). */
    fun onChunk(bytesWritten: Long, nowMs: Long): UploadProgressSnapshot? {
        if (bytesWritten < lastEmittedBytes) {
            hasEmitted = false
        }
        val isFinal = bytesWritten >= totalBytes
        if (hasEmitted && !isFinal && nowMs - lastEmittedAtMs < PROGRESS_EMIT_INTERVAL_MS) return null

        val bytesPerSecond = if (hasEmitted) {
            val elapsedMs = (nowMs - lastEmittedAtMs).coerceAtLeast(1)
            ((bytesWritten - lastEmittedBytes) * 1000L) / elapsedMs
        } else {
            0L
        }
        lastEmittedBytes = bytesWritten
        lastEmittedAtMs = nowMs
        hasEmitted = true

        val percent = if (totalBytes > 0) ((bytesWritten * 100) / totalBytes).toInt() else 0
        return UploadProgressSnapshot(percent, bytesWritten, totalBytes, bytesPerSecond)
    }
}
