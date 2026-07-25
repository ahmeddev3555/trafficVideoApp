package com.trafficwatch.server.storage

import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Writes report videos to a local directory on disk, one file per report named
 * `<reportId>.mp4`. The configured directory is created once at startup (see [init]) so
 * later writes never need to check for its existence.
 */
@Service
class LocalDiskVideoStorageService(
    storageProperties: StorageProperties,
) : VideoStorageService {

    private val videoDirectory: Path = Path.of(storageProperties.videoDirectory)

    @PostConstruct
    fun init() {
        Files.createDirectories(videoDirectory)
    }

    override fun store(reportId: UUID, file: MultipartFile): String {
        val filename = "$reportId.mp4"
        val destination = videoDirectory.resolve(filename)
        file.inputStream.use { input ->
            Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING)
        }
        return filename
    }

    override fun resolve(path: String): Path = videoDirectory.resolve(path)

    override fun delete(path: String) {
        Files.deleteIfExists(resolve(path))
    }
}
