package com.trafficwatch.app.feature.upload

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.hasKeyWithValueOfType
import androidx.work.workDataOf
import com.google.gson.Gson
import com.trafficwatch.app.core.data.remote.ApiService
import com.trafficwatch.app.core.data.remote.dto.toSampleDto
import com.trafficwatch.app.core.data.repository.ReportRepository
import com.trafficwatch.app.core.domain.model.LocationData
import com.trafficwatch.app.core.domain.model.Report
import com.trafficwatch.app.core.domain.model.ReportStatus
import com.trafficwatch.app.core.domain.model.RotationSample
import com.trafficwatch.app.core.util.FileUtil
import com.trafficwatch.app.core.util.TokenStore
import com.trafficwatch.app.core.util.UploadProgressTracker
import com.trafficwatch.app.core.util.asStreamingRequestBody
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

@HiltWorker
class UploadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val apiService: ApiService,
    private val reportRepository: ReportRepository,
    private val tokenStore: TokenStore,
    private val fileUtil: FileUtil
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val localReportId = inputData.getString(KEY_REPORT_ID) ?: return Result.failure()
        val videoPath = inputData.getString(KEY_VIDEO_PATH) ?: return Result.failure()
        val latitude = inputData.getDouble(KEY_LATITUDE, 0.0)
        val longitude = inputData.getDouble(KEY_LONGITUDE, 0.0)
        val accuracy = inputData.getFloat(KEY_ACCURACY, 0f)
        val altitude = inputData.getDouble(KEY_ALTITUDE, 0.0)
        val bearing = inputData.getFloat(KEY_BEARING, 0f)
        val speed = inputData.getFloat(KEY_SPEED, 0f)
        val recordedAt = inputData.getLong(KEY_RECORDED_AT, 0L)
        val durationMs = inputData.getLong(KEY_DURATION_MS, 0L)
        // WorkManager's Data can't store a null Float directly - the key is simply omitted
        // by buildInputData when absent, so presence (not a sentinel value) is what
        // distinguishes "unavailable" from "present with any value."
        val compassHeadingDegrees = if (inputData.hasKeyWithValueOfType<Float>(KEY_COMPASS_HEADING)) {
            inputData.getFloat(KEY_COMPASS_HEADING, 0f)
        } else {
            null
        }
        val locationSamplesJson = inputData.getString(KEY_LOCATION_SAMPLES_JSON)
        val rotationSamplesJson = inputData.getString(KEY_ROTATION_SAMPLES_JSON)

        val videoFile = File(videoPath)
        if (!videoFile.exists()) return Result.failure(workDataOf("error" to "Video file not found"))

        setProgress(workDataOf(KEY_PROGRESS to 0))

        return try {
            val tracker = UploadProgressTracker(videoFile.length())
            val videoPart = MultipartBody.Part.createFormData(
                "video",
                videoFile.name,
                videoFile.asStreamingRequestBody("video/mp4".toMediaType()) { bytesWritten, totalBytes ->
                    tracker.onChunk(bytesWritten, android.os.SystemClock.elapsedRealtime())?.let { snapshot ->
                        setProgressAsync(
                            workDataOf(
                                KEY_PROGRESS to snapshot.percent,
                                KEY_BYTES_UPLOADED to snapshot.bytesUploaded,
                                KEY_TOTAL_BYTES to snapshot.totalBytes,
                                KEY_BYTES_PER_SECOND to snapshot.bytesPerSecond
                            )
                        )
                    }
                }
            )

            val isoDate = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date(recordedAt))

            val response = apiService.submitReport(
                video = videoPart,
                latitude = latitude.toString().toRequestBody(),
                longitude = longitude.toString().toRequestBody(),
                accuracy = accuracy.toString().toRequestBody(),
                altitude = altitude.toString().toRequestBody(),
                bearing = bearing.toString().toRequestBody(),
                speed = speed.toString().toRequestBody(),
                recordedAt = isoDate.toRequestBody(),
                durationMs = durationMs.toString().toRequestBody(),
                deviceId = tokenStore.getOrCreateDeviceId().toRequestBody(),
                compassHeadingDegrees = compassHeadingDegrees?.toString()?.toRequestBody(),
                locationSamples = locationSamplesJson?.toRequestBody(),
                rotationSamples = rotationSamplesJson?.toRequestBody()
            )

            setProgress(
                workDataOf(
                    KEY_PROGRESS to 100,
                    KEY_BYTES_UPLOADED to videoFile.length(),
                    KEY_TOTAL_BYTES to videoFile.length(),
                    KEY_BYTES_PER_SECOND to 0L
                )
            )

            reportRepository.updateStatus(localReportId, ReportStatus.PENDING, response.reportId)

            // Clean up local video file after successful upload
            fileUtil.deleteFile(videoPath)

            Result.success(workDataOf(KEY_SERVER_ID to response.reportId))
        } catch (e: Exception) {
            reportRepository.updateStatus(localReportId, ReportStatus.UPLOAD_FAILED, null)
            if (runAttemptCount < 3) Result.retry()
            else Result.failure(workDataOf("error" to (e.message ?: "Upload failed")))
        }
    }

    companion object {
        const val KEY_REPORT_ID = "report_id"
        const val KEY_VIDEO_PATH = "video_path"
        const val KEY_LATITUDE = "latitude"
        const val KEY_LONGITUDE = "longitude"
        const val KEY_ACCURACY = "accuracy"
        const val KEY_ALTITUDE = "altitude"
        const val KEY_BEARING = "bearing"
        const val KEY_SPEED = "speed"
        const val KEY_RECORDED_AT = "recorded_at"
        const val KEY_DURATION_MS = "duration_ms"
        const val KEY_COMPASS_HEADING = "compass_heading_degrees"
        const val KEY_LOCATION_SAMPLES_JSON = "location_samples_json"
        const val KEY_ROTATION_SAMPLES_JSON = "rotation_samples_json"
        const val KEY_PROGRESS = "progress"
        const val KEY_BYTES_UPLOADED = "bytes_uploaded"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_BYTES_PER_SECOND = "bytes_per_second"
        const val KEY_SERVER_ID = "server_id"

        fun buildInputData(
            reportId: String,
            videoPath: String,
            location: LocationData,
            locationSamples: List<LocationData>,
            rotationSamples: List<RotationSample>,
            recordingStartedAt: Long,
            durationMs: Long
        ): Data {
            val builder = Data.Builder().putAll(
                workDataOf(
                    KEY_REPORT_ID to reportId,
                    KEY_VIDEO_PATH to videoPath,
                    KEY_LATITUDE to location.latitude,
                    KEY_LONGITUDE to location.longitude,
                    KEY_ACCURACY to location.accuracy,
                    KEY_ALTITUDE to location.altitude,
                    KEY_BEARING to location.bearing,
                    KEY_SPEED to location.speed,
                    KEY_RECORDED_AT to recordingStartedAt,
                    KEY_DURATION_MS to durationMs
                )
            )
            // Omitted entirely when null - workDataOf/Data.Builder cannot store a null
            // Float, so presence of the key (checked via hasKeyWithValueOfType in doWork)
            // is what distinguishes "unavailable" from "present."
            location.compassHeadingDegrees?.let { builder.putFloat(KEY_COMPASS_HEADING, it) }
            // Same "presence, not sentinel" convention: an empty list omits the key entirely
            // rather than storing a "[]" string, so doWork()'s getString(...) naturally
            // returns null (matching "no samples captured") instead of an empty-array string.
            if (locationSamples.isNotEmpty()) {
                val json = Gson().toJson(locationSamples.map { it.toSampleDto() })
                builder.putString(KEY_LOCATION_SAMPLES_JSON, json)
            }
            if (rotationSamples.isNotEmpty()) {
                val json = Gson().toJson(rotationSamples.map { it.toSampleDto() })
                builder.putString(KEY_ROTATION_SAMPLES_JSON, json)
            }
            return builder.build()
        }

        /** Unique WorkManager work name for [reportId], so retries/re-enqueues can be deduped. */
        fun uniqueWorkName(reportId: String): String = "upload_$reportId"

        /**
         * [requireWifiOnly] true maps to [NetworkType.UNMETERED] (the safe default for every
         * upload attempt); false maps to [NetworkType.CONNECTED] (any network), used only when
         * the user has explicitly confirmed uploading over cellular data.
         */
        fun buildRequest(
            reportId: String,
            videoPath: String,
            location: LocationData,
            locationSamples: List<LocationData>,
            rotationSamples: List<RotationSample>,
            recordingStartedAt: Long,
            durationMs: Long,
            requireWifiOnly: Boolean
        ): OneTimeWorkRequest {
            val inputData = buildInputData(reportId, videoPath, location, locationSamples, rotationSamples, recordingStartedAt, durationMs)
            val networkType = if (requireWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
            val constraints = Constraints.Builder().setRequiredNetworkType(networkType).build()
            return OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(inputData)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .build()
        }
    }
}
