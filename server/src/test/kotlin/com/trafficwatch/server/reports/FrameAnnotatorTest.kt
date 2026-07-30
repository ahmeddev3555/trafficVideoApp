// server/src/test/kotlin/com/trafficwatch/server/reports/FrameAnnotatorTest.kt
package com.trafficwatch.server.reports

import com.trafficwatch.server.videoanalysis.dto.BoundingBox
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class FrameAnnotatorTest {

    private fun whiteJpeg(width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = Color.WHITE
        graphics.fillRect(0, 0, width, height)
        graphics.dispose()

        val output = ByteArrayOutputStream()
        ImageIO.write(image, "jpg", output)
        return output.toByteArray()
    }

    @Test
    fun `annotate draws a red border at the bounding box location`() {
        val original = whiteJpeg(100, 100)
        val boundingBox = BoundingBox(x1 = 20.0, y1 = 20.0, x2 = 60.0, y2 = 60.0)

        val annotated = FrameAnnotator.annotate(original, boundingBox)

        val annotatedImage = ImageIO.read(ByteArrayInputStream(annotated))
        assertThat(annotatedImage.width).isEqualTo(100)
        assertThat(annotatedImage.height).isEqualTo(100)

        // The top-left corner of the box should now be predominantly red, not white.
        val borderPixel = annotatedImage.getRGB(20, 20)
        val red = (borderPixel shr 16) and 0xFF
        val green = (borderPixel shr 8) and 0xFF
        val blue = borderPixel and 0xFF
        assertThat(red).isGreaterThan(150)
        assertThat(green).isLessThan(100)
        assertThat(blue).isLessThan(100)
    }

    @Test
    fun `annotate leaves a pixel far from the box unchanged (still white)`() {
        val original = whiteJpeg(100, 100)
        val boundingBox = BoundingBox(x1 = 20.0, y1 = 20.0, x2 = 60.0, y2 = 60.0)

        val annotated = FrameAnnotator.annotate(original, boundingBox)

        val annotatedImage = ImageIO.read(ByteArrayInputStream(annotated))
        val farPixel = annotatedImage.getRGB(5, 5)
        val red = (farPixel shr 16) and 0xFF
        val green = (farPixel shr 8) and 0xFF
        val blue = farPixel and 0xFF
        assertThat(red).isGreaterThan(200)
        assertThat(green).isGreaterThan(200)
        assertThat(blue).isGreaterThan(200)
    }
}
