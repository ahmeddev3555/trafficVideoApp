package com.trafficwatch.app.feature.trim

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * A dual-handle range bar for selecting a trim start/end position.
 *
 * @param totalDurationMs   total video duration in milliseconds
 * @param startMs           current trim start in milliseconds
 * @param endMs             current trim end in milliseconds
 * @param onRangeChange     callback with updated (startMs, endMs)
 */
@Composable
fun TrimRangeBar(
    totalDurationMs: Long,
    startMs: Long,
    endMs: Long,
    onRangeChange: (startMs: Long, endMs: Long) -> Unit,
    modifier: Modifier = Modifier,
    barHeight: Dp = 56.dp,
    handleWidth: Dp = 20.dp
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant

    // Fraction positions [0,1] of start/end handles
    var startFrac by remember(startMs, totalDurationMs) {
        mutableFloatStateOf(startMs.toFloat() / totalDurationMs.coerceAtLeast(1))
    }
    var endFrac by remember(endMs, totalDurationMs) {
        mutableFloatStateOf(endMs.toFloat() / totalDurationMs.coerceAtLeast(1))
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(barHeight)
            .pointerInput(totalDurationMs) {
                val handlePx = handleWidth.toPx()
                val totalWidth = size.width.toFloat()

                detectDragGestures { change, _ ->
                    val x = change.position.x.coerceIn(0f, totalWidth)
                    val frac = x / totalWidth

                    val startX = startFrac * totalWidth
                    val endX = endFrac * totalWidth

                    // Determine which handle is closer
                    val distToStart = abs(x - startX)
                    val distToEnd = abs(x - endX)

                    if (distToStart <= distToEnd) {
                        startFrac = frac.coerceIn(0f, endFrac - handlePx / totalWidth)
                    } else {
                        endFrac = frac.coerceIn(startFrac + handlePx / totalWidth, 1f)
                    }

                    onRangeChange(
                        (startFrac * totalDurationMs).toLong(),
                        (endFrac * totalDurationMs).toLong()
                    )
                }
            }
    ) {
        val width = size.width
        val height = size.height
        val handlePx = handleWidth.toPx()
        val barY = height / 2f - 4.dp.toPx()
        val barH = 8.dp.toPx()

        val startX = startFrac * width
        val endX = endFrac * width

        // Background track
        drawRoundRect(
            color = surfaceVariant,
            topLeft = Offset(0f, barY),
            size = Size(width, barH),
            cornerRadius = CornerRadius(4.dp.toPx())
        )

        // Selected range highlight
        drawRoundRect(
            color = primaryColor.copy(alpha = 0.3f),
            topLeft = Offset(startX + handlePx, barY),
            size = Size((endX - startX - handlePx).coerceAtLeast(0f), barH),
            cornerRadius = CornerRadius(4.dp.toPx())
        )

        // Start handle
        drawRoundRect(
            color = primaryColor,
            topLeft = Offset(startX, 0f),
            size = Size(handlePx, height),
            cornerRadius = CornerRadius(6.dp.toPx())
        )

        // End handle
        drawRoundRect(
            color = primaryColor,
            topLeft = Offset(endX - handlePx, 0f),
            size = Size(handlePx, height),
            cornerRadius = CornerRadius(6.dp.toPx())
        )

        // Handle lines (visual affordance)
        val lineColor = Color.White
        val lineH = height * 0.4f
        val lineY = (height - lineH) / 2f
        for (i in 0..1) {
            val lx = startX + handlePx / 2f - 2.dp.toPx() + i * 4.dp.toPx()
            drawLine(lineColor, Offset(lx, lineY), Offset(lx, lineY + lineH), strokeWidth = 1.5.dp.toPx())
        }
        for (i in 0..1) {
            val lx = (endX - handlePx / 2f) - 2.dp.toPx() + i * 4.dp.toPx()
            drawLine(lineColor, Offset(lx, lineY), Offset(lx, lineY + lineH), strokeWidth = 1.5.dp.toPx())
        }
    }
}
