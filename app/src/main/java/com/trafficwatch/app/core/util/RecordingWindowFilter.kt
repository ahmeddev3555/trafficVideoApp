package com.trafficwatch.app.core.util

/**
 * Filters [items] to only those whose captured-at timestamp (extracted via [capturedAt])
 * falls within [windowStart]..[windowEnd] inclusive - used to bound continuous sample lists
 * (GPS fixes, rotation-vector headings) to just the trimmed clip's time window before
 * upload, rather than the whole raw recording.
 */
fun <T> filterToRecordingWindow(
    items: List<T>,
    windowStart: Long,
    windowEnd: Long,
    capturedAt: (T) -> Long,
): List<T> = items.filter { capturedAt(it) in windowStart..windowEnd }
