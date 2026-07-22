package com.trafficwatch.app.feature.trim

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A thin overview strip spanning the entire video, showing roughly where the current
 * fixed-length selection window sits. Tapping anywhere jumps the window so it's
 * centered on the tapped point — a coarse "get me in the neighborhood" control,
 * meant to be paired with [TrimWindowScrubBar]'s fine-grained drag for precision.
 */
@Composable
fun TrimMinimap(
    totalDurationMs: Long,
    windowStartMs: Long,
    windowDurationMs: Long,
    onWindowPositionChange: (startMs: Long) -> Unit,
    modifier: Modifier = Modifier,
    barHeight: Dp = 14.dp
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(barHeight)
            .pointerInput(totalDurationMs, windowDurationMs) {
                detectTapGestures { offset ->
                    val tapFraction = (offset.x / size.width).coerceIn(0f, 1f)
                    val maxStartMs = (totalDurationMs - windowDurationMs).coerceAtLeast(0L)
                    val newStart = (tapFraction * totalDurationMs - windowDurationMs / 2f)
                        .toLong()
                        .coerceIn(0L, maxStartMs)
                    onWindowPositionChange(newStart)
                }
            }
    ) {
        val width = size.width
        val totalSec = totalDurationMs.coerceAtLeast(1)

        drawRoundRect(
            color = surfaceVariant,
            topLeft = Offset.Zero,
            size = Size(width, size.height),
            cornerRadius = CornerRadius(4.dp.toPx())
        )

        val markerLeft = (windowStartMs.toFloat() / totalSec) * width
        val markerWidth = ((windowDurationMs.toFloat() / totalSec) * width).coerceAtLeast(4.dp.toPx())
        drawRoundRect(
            color = primaryColor,
            topLeft = Offset(markerLeft, 0f),
            size = Size(markerWidth, size.height),
            cornerRadius = CornerRadius(4.dp.toPx())
        )
    }
}
