package com.trafficwatch.server.storage

import org.springframework.web.multipart.MultipartFile
import java.nio.file.Path
import java.util.UUID

/**
 * Abstraction over where report video files physically live, so a future S3-backed (or
 * other remote) implementation can swap in without changing the `reports` table schema.
 */
interface VideoStorageService {

    /**
     * Persists [file]'s bytes for the report identified by [reportId] and returns a
     * relative path/filename identifying where it was stored. This is the value that gets
     * persisted in the DB's `video_path` column - callers must not assume it is an
     * absolute filesystem path.
     */
    fun store(reportId: UUID, file: MultipartFile): String

    /** Resolves a previously-returned [store] path back to a full filesystem [Path]. */
    fun resolve(path: String): Path
}
