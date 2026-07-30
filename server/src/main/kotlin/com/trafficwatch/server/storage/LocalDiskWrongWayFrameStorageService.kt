package com.trafficwatch.server.storage

import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Writes annotated wrong-way-vehicle frame images to a `frames/` subdirectory of the
 * configured video directory, one file per report named `<reportId>.jpg`. Mirrors
 * [LocalDiskVideoStorageService]'s exact pattern for a different media type.
 */
@Service
class LocalDiskWrongWayFrameStorageService(
    storageProperties: StorageProperties,
) : WrongWayFrameStorageService {

    private val frameDirectory: Path = Path.of(storageProperties.videoDirectory).resolve("frames")

    @PostConstruct
    fun init() {
        Files.createDirectories(frameDirectory)
    }

    override fun store(reportId: UUID, jpegBytes: ByteArray): String {
        val filename = "$reportId.jpg"
        Files.write(frameDirectory.resolve(filename), jpegBytes)
        return filename
    }

    override fun resolve(path: String): Path = frameDirectory.resolve(path)

    override fun delete(path: String) {
        Files.deleteIfExists(resolve(path))
    }
}
