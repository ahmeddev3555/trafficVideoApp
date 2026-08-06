package com.trafficwatch.app.core.util

import okhttp3.MediaType.Companion.toMediaType
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FileUtilTest {

    @Test
    fun `asStreamingRequestBody reports monotonically increasing progress ending at file size`() {
        val fileSize = 132_072L // just over two 64KB chunks
        val tempFile = File.createTempFile("upload_test", ".mp4")
        tempFile.deleteOnExit()
        tempFile.writeBytes(ByteArray(fileSize.toInt()) { it.toByte() })

        val progressCalls = mutableListOf<Pair<Long, Long>>()
        val requestBody = tempFile.asStreamingRequestBody(
            mediaType = "video/mp4".toMediaType(),
            onProgress = { written, total -> progressCalls.add(written to total) }
        )

        requestBody.writeTo(Buffer())

        assertTrue("expected at least one progress call", progressCalls.isNotEmpty())
        assertEquals(fileSize to fileSize, progressCalls.last())
        assertTrue(
            "expected bytesWritten to be non-decreasing",
            progressCalls.map { it.first } == progressCalls.map { it.first }.sorted()
        )
        assertTrue(
            "expected every call to report the same total",
            progressCalls.all { it.second == fileSize }
        )

        tempFile.delete()
    }

    @Test
    fun `asStreamingRequestBody with no callback still writes the full file`() {
        val fileSize = 10_000L
        val tempFile = File.createTempFile("upload_test_no_callback", ".mp4")
        tempFile.deleteOnExit()
        tempFile.writeBytes(ByteArray(fileSize.toInt()) { it.toByte() })

        val requestBody = tempFile.asStreamingRequestBody(mediaType = "video/mp4".toMediaType())
        val sink = Buffer()
        requestBody.writeTo(sink)

        assertEquals(fileSize, sink.size)

        tempFile.delete()
    }
}
