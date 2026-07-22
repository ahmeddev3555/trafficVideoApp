package com.trafficwatch.app.feature.trim

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

private const val PIXELS_PER_SECOND_DP = 30f

internal fun formatTimeOfDay(epochMs: Long): String =
    SimpleDateFormat("h:mm:ss a", Locale.getDefault()).format(Date(epochMs))

/**
 * A fixed-length selection window rendered at a constant on-screen size, with a
 * horizontally scrubbable ruler underneath representing the full video timeline.
 * Unlike a proportional range bar, the window's screen size never shrinks as the
 * video gets longer — dragging the ruler moves which portion of the video sits
 * under the window.
 *
 * @param totalDurationMs        total video duration in milliseconds
 * @param windowStartMs          start of the current selection window, in milliseconds
 * @param windowDurationMs       length of the selection window (constant while dragging)
 * @param recordingStartedAt     wall-clock time the recording started, for tick labels
 * @param onWindowPositionChange callback with the updated window start position
 * @param onScrubChange          called continuously with the window's start position while
 *                               dragging, and `null` the instant the drag ends
 */
@Composable
fun TrimWindowScrubBar(
    totalDurationMs: Long,
    windowStartMs: Long,
    windowDurationMs: Long,
    recordingStartedAt: Long,
    onWindowPositionChange: (startMs: Long) -> Unit,
    onScrubChange: (positionMs: Long?) -> Unit,
    modifier: Modifier = Modifier,
    barHeight: Dp = 64.dp
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val tickColor = MaterialTheme.colorScheme.onSurfaceVariant
    val textMeasurer = rememberTextMeasurer()

    // Locally-tracked source of truth during an active drag; resynced from the
    // parent-provided value (including externally, e.g. from the nudge buttons)
    // whenever it changes between gestures.
    var localStartMs by remember(windowStartMs, totalDurationMs, windowDurationMs) {
        mutableStateOf(windowStartMs)
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(barHeight)
            .pointerInput(totalDurationMs, windowDurationMs) {
                val pixelsPerSecondPx = PIXELS_PER_SECOND_DP.dp.toPx()
                val maxStartMs = (totalDurationMs - windowDurationMs).coerceAtLeast(0L)

                detectDragGestures(
                    onDragEnd = { onScrubChange(null) },
                    onDragCancel = { onScrubChange(null) }
                ) { change, dragAmount ->
                    change.consume()
                    // Dragging left reveals later content (standard scrub-filmstrip
                    // convention, e.g. TikTok/Instagram trim): content moves with the
                    // finger, so a negative (leftward) drag amount moves the window
                    // to a later point in time.
                    val deltaMs = (-dragAmount.x / pixelsPerSecondPx * 1000f).toLong()
                    localStartMs = (localStartMs + deltaMs).coerceIn(0L, maxStartMs)
                    onWindowPositionChange(localStartMs)
                    onScrubChange(localStartMs)
                }
            }
    ) {
        val pixelsPerSecondPx = PIXELS_PER_SECOND_DP.dp.toPx()
        val viewportWidth = size.width
        val windowWidthPx = windowDurationMs / 1000f * pixelsPerSecondPx
        val windowLeftPx = (viewportWidth - windowWidthPx) / 2f

        fun timeToX(timeMs: Long): Float =
            windowLeftPx + (timeMs - localStartMs) / 1000f * pixelsPerSecondPx

        // Background track
        drawRoundRect(
            color = surfaceVariant,
            topLeft = Offset(0f, size.height / 2f - 4.dp.toPx()),
            size = Size(viewportWidth, 8.dp.toPx()),
            cornerRadius = CornerRadius(4.dp.toPx())
        )

        // Tick marks + time-of-day labels for whole seconds currently in view
        val startSecond = floor((localStartMs - windowLeftPx / pixelsPerSecondPx * 1000f) / 1000f).toLong()
        val endSecond = ceil((localStartMs + (viewportWidth - windowLeftPx) / pixelsPerSecondPx * 1000f) / 1000f).toLong()
        for (second in startSecond..endSecond) {
            val timeMs = second * 1000L
            if (timeMs < 0 || timeMs > totalDurationMs) continue
            val x = timeToX(timeMs)
            val isMajor = second % 5 == 0L
            drawLine(
                color = tickColor.copy(alpha = if (isMajor) 0.6f else 0.3f),
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = if (isMajor) 2.dp.toPx() else 1.dp.toPx()
            )
            if (isMajor) {
                val label = formatTimeOfDay(recordingStartedAt + timeMs)
                val layout = textMeasurer.measure(label, style = TextStyle(fontSize = 9.sp, color = tickColor))
                drawText(layout, topLeft = Offset(x + 2.dp.toPx(), size.height - layout.size.height - 2.dp.toPx()))
            }
        }

        // Fixed selection window, always centered and at a constant width
        drawRoundRect(
            color = primaryColor.copy(alpha = 0.15f),
            topLeft = Offset(windowLeftPx, 0f),
            size = Size(windowWidthPx, size.height),
            cornerRadius = CornerRadius(8.dp.toPx())
        )
        drawRoundRect(
            color = primaryColor,
            topLeft = Offset(windowLeftPx, 0f),
            size = Size(windowWidthPx, size.height),
            cornerRadius = CornerRadius(8.dp.toPx()),
            style = Stroke(width = 2.dp.toPx())
        )
    }
}
