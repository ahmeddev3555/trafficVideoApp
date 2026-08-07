package com.trafficwatch.app.core.util

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.File
import java.text.DecimalFormat
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileUtil @Inject constructor(@ApplicationContext private val context: Context) {

    fun getRawRecordingDir(): File {
        val dir = File(context.cacheDir, "recordings")
        dir.mkdirs()
        return dir
    }

    fun getTrimmedVideoDir(): File {
        val dir = File(context.filesDir, "trimmed")
        dir.mkdirs()
        return dir
    }

    fun newRawRecordingFile(): File =
        File(getRawRecordingDir(), "raw_${System.currentTimeMillis()}.mp4")

    fun newTrimmedVideoFile(): File =
        File(getTrimmedVideoDir(), "trimmed_${System.currentTimeMillis()}.mp4")

    fun deleteFile(path: String) {
        val file = File(path)
        if (file.exists()) file.delete()
    }

    fun exists(path: String): Boolean = File(path).exists()

    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        return "${DecimalFormat("#,##0.#").format(bytes / Math.pow(1024.0, digitGroups.toDouble()))} ${units[digitGroups]}"
    }
}

/**
 * Ceiling passed to [okio.Source.read] per iteration of the streaming loop below - NOT a
 * guaranteed chunk size. Okio's [okio.Source.read] on an [okio.Source] backed by an
 * `InputStream` fills at most one internal segment (~8KB) per call regardless of this
 * value, so [onProgress] fires roughly every ~8KB in practice, not every 64KB. Harmless
 * for progress reporting (throttled downstream by [UploadProgressTracker]), but don't
 * assume this constant reflects real chunk boundaries.
 */
private const val UPLOAD_CHUNK_SIZE_BYTES = 64 * 1024L

/**
 * Streams a file to OkHttp without loading it entirely into memory, in fixed-size
 * chunks so [onProgress] can be observed as the upload proceeds. Carries no timing or
 * throttling logic of its own - callers that need throttled updates should filter
 * through something like [UploadProgressTracker].
 */
fun File.asStreamingRequestBody(
    mediaType: MediaType = "video/mp4".toMediaType(),
    onProgress: ((bytesWritten: Long, totalBytes: Long) -> Unit)? = null
): RequestBody =
    object : RequestBody() {
        override fun contentType(): MediaType = mediaType
        override fun contentLength(): Long = length()
        override fun writeTo(sink: BufferedSink) {
            val total = contentLength()
            var written = 0L
            source().use { source ->
                while (true) {
                    val read = source.read(sink.buffer, UPLOAD_CHUNK_SIZE_BYTES)
                    if (read == -1L) break
                    written += read
                    sink.emitCompleteSegments()
                    onProgress?.invoke(written, total)
                }
            }
        }
    }
