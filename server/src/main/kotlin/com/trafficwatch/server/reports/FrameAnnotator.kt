package com.trafficwatch.server.reports

import com.trafficwatch.server.videoanalysis.dto.BoundingBox
import java.awt.BasicStroke
import java.awt.Color
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Draws a red rectangle onto a JPEG frame at the given bounding box, using plain Java AWT -
 * no new dependency needed. Used by [ReportAnalysisJob] to highlight the flagged wrong-way
 * vehicle in its representative frame before it's stored.
 */
object FrameAnnotator {
    private const val STROKE_WIDTH = 4f

    /** [boundingBox] is in the same pixel space as the frame [jpegBytes] decodes to. */
    fun annotate(jpegBytes: ByteArray, boundingBox: BoundingBox): ByteArray {
        val image = ImageIO.read(ByteArrayInputStream(jpegBytes))
            ?: throw IllegalArgumentException("Could not decode frame JPEG bytes")

        val graphics = image.createGraphics()
        try {
            graphics.color = Color.RED
            graphics.stroke = BasicStroke(STROKE_WIDTH)
            val x = boundingBox.x1.toInt()
            val y = boundingBox.y1.toInt()
            val width = (boundingBox.x2 - boundingBox.x1).toInt()
            val height = (boundingBox.y2 - boundingBox.y1).toInt()
            graphics.drawRect(x, y, width, height)
        } finally {
            graphics.dispose()
        }

        val output = ByteArrayOutputStream()
        ImageIO.write(image, "jpg", output)
        return output.toByteArray()
    }
}
