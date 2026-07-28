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

/** Streams a file to OkHttp without loading it entirely into memory. */
fun File.asStreamingRequestBody(mediaType: MediaType = "video/mp4".toMediaType()): RequestBody =
    object : RequestBody() {
        override fun contentType(): MediaType = mediaType
        override fun contentLength(): Long = length()
        override fun writeTo(sink: BufferedSink) {
            source().use { sink.writeAll(it) }
        }
    }
