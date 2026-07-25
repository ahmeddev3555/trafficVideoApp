package com.trafficwatch.server.storage

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.mock.web.MockMultipartFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.random.Random

class LocalDiskVideoStorageServiceTest {

    @Test
    fun `store writes the uploaded bytes to disk byte-for-byte`(@TempDir tempDir: Path) {
        // videoDir itself does not exist yet - init() below must create it.
        val videoDir = tempDir.resolve("videos")
        val service = LocalDiskVideoStorageService(
            StorageProperties(videoDirectory = videoDir.toString()),
        )
        service.init()

        val originalBytes = Random.nextBytes(4096)
        val file = MockMultipartFile("file", "clip.mp4", "video/mp4", originalBytes)
        val reportId = UUID.randomUUID()

        val storedPath = service.store(reportId, file)
        val writtenBytes = Files.readAllBytes(service.resolve(storedPath))

        // Genuine byte-for-byte comparison, not just a length/existence check.
        assertThat(writtenBytes).isEqualTo(originalBytes)
        assertThat(writtenBytes.contentEquals(originalBytes)).isTrue()
    }

    @Test
    fun `store returns a bare relative filename, not an absolute path`(@TempDir tempDir: Path) {
        val videoDir = tempDir.resolve("videos")
        val service = LocalDiskVideoStorageService(
            StorageProperties(videoDirectory = videoDir.toString()),
        )
        service.init()

        val reportId = UUID.randomUUID()
        val file = MockMultipartFile("file", "clip.mp4", "video/mp4", byteArrayOf(1, 2, 3))

        val storedPath = service.store(reportId, file)

        assertThat(storedPath).isEqualTo("$reportId.mp4")
        assertThat(Path.of(storedPath).isAbsolute).isFalse()
    }

    @Test
    fun `init creates the configured video directory when it does not already exist`(@TempDir tempDir: Path) {
        // Nest a couple of levels so we know for certain nothing along this path
        // (not even the parent) has been created by JUnit's @TempDir.
        val videoDir = tempDir.resolve("does-not-exist-yet").resolve("videos")
        assertThat(Files.exists(videoDir)).isFalse()

        val service = LocalDiskVideoStorageService(
            StorageProperties(videoDirectory = videoDir.toString()),
        )
        service.init()

        assertThat(Files.isDirectory(videoDir)).isTrue()
    }

    @Test
    fun `resolve returns the file path within the configured video directory`(@TempDir tempDir: Path) {
        val videoDir = tempDir.resolve("videos")
        val service = LocalDiskVideoStorageService(
            StorageProperties(videoDirectory = videoDir.toString()),
        )
        service.init()

        val resolved = service.resolve("some-report-id.mp4")

        assertThat(resolved).isEqualTo(videoDir.resolve("some-report-id.mp4"))
    }
}
