package com.trafficwatch.server.storage

import java.nio.file.Path
import java.util.UUID

interface WrongWayFrameStorageService {
    fun store(reportId: UUID, jpegBytes: ByteArray): String
    fun resolve(path: String): Path
    fun delete(path: String)
}
