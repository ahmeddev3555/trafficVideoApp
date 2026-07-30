// server/src/test/kotlin/com/trafficwatch/server/storage/LocalDiskWrongWayFrameStorageServiceTest.kt
package com.trafficwatch.server.storage

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class LocalDiskWrongWayFrameStorageServiceTest {

    @Test
    fun `store writes jpegBytes to reportId-jpg under videoDirectory-frames and resolve finds it again`(
        @TempDir tempDir: Path,
    ) {
        val storageProperties = StorageProperties(videoDirectory = tempDir.resolve("videos").toString())
        val service = LocalDiskWrongWayFrameStorageService(storageProperties)
        service.init()

        val reportId = UUID.randomUUID()
        val jpegBytes = byteArrayOf(1, 2, 3, 4)

        val storedPath = service.store(reportId, jpegBytes)

        assertThat(storedPath).isEqualTo("$reportId.jpg")
        val resolved = service.resolve(storedPath)
        assertThat(resolved).isEqualTo(tempDir.resolve("videos").resolve("frames").resolve("$reportId.jpg"))
        assertThat(Files.readAllBytes(resolved)).isEqualTo(jpegBytes)
    }

    @Test
    fun `delete removes a stored frame and is a no-op if it does not exist`(@TempDir tempDir: Path) {
        val storageProperties = StorageProperties(videoDirectory = tempDir.resolve("videos").toString())
        val service = LocalDiskWrongWayFrameStorageService(storageProperties)
        service.init()

        val reportId = UUID.randomUUID()
        val storedPath = service.store(reportId, byteArrayOf(1))
        assertThat(Files.exists(service.resolve(storedPath))).isTrue()

        service.delete(storedPath)
        assertThat(Files.exists(service.resolve(storedPath))).isFalse()

        // Deleting again must not throw.
        service.delete(storedPath)
    }
}
