# Continuous Rotation-Vector Capture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Capture a time-series of declination-corrected compass headings (via the rotation-vector sensor) throughout each recording, upload it, and persist it on the server. No change to how direction is currently computed - this is sub-project 2 of 4 in fixing the "stationary camera" assumption in wrong-way analysis (see `docs/superpowers/specs/2026-08-04-continuous-rotation-vector-capture-design.md`).

**Architecture:** Task 1 captures the samples on-device (mirroring sub-project 1's GPS-sample capture chain exactly) and threads them through `ReviewViewModel`'s UI state, including the trim-window filter designed in from the start this time. Task 2 transmits them (Android upload payload -> server parse -> DB column) and adds the tests, including a retroactive behavioral test for `location_samples`'s malformed-JSON handling (a gap the previous plan's final review found but didn't fix). Neither task touches `ClipFlowAnalyzer` or any direction-analysis logic.

**Tech Stack:** Kotlin (Android + Spring Boot server), Jetpack Compose, WorkManager, Retrofit/OkHttp, Gson (Android), Jackson (server), Flyway.

## Global Constraints

- Sensor: continuous `Sensor.TYPE_ROTATION_VECTOR` reads (not raw `TYPE_GYROSCOPE`) - each sample is an independent, self-correcting, declination-corrected absolute heading.
- Sampling interval during active recording: 200ms (5Hz) - independent of both the existing one-shot `CompassProvider.getSnapshot()` call and sub-project 1's separate 1Hz GPS sampling loop.
- Each sample stores only `capturedAt: Long` and `headingDegrees: Float` - no pitch/roll.
- Declination is computed ONCE per recording (from the same location reference the existing one-shot snapshot already uses), not once per sample - never call `GeomagneticField` inside the per-tick sensor callback.
- Storage: a single `rotation_samples` JSONB column on the `reports` table, matching `location_samples`'s pattern - not a new table.
- Wire format: one new optional multipart field, `rotation_samples` (JSON array string) - `required = false`, omitted entirely (not `"[]"`) when the list is empty - the "presence, not sentinel" convention.
- Samples must be filtered to the trimmed clip's time window before upload - extend the filter that already exists in `AppNavigation.kt`'s REVIEW composable for `locationSamples`, do not build a second, separate filter.
- Malformed `rotation_samples` JSON must be logged and treated as absent (null), never fail the whole report submission - same as `location_samples`.
- Not persisted to the local Room `Report` entity (matches `location_samples`'s current, already-accepted limitation) - do not expand scope to fix this for either field in this plan.

---

### Task 1: Android capture chain

**Files:**
- Create: `app/src/main/java/com/trafficwatch/app/core/domain/model/RotationSample.kt`
- Modify: `app/src/main/java/com/trafficwatch/app/core/util/CompassProvider.kt`
- Modify: `app/src/main/java/com/trafficwatch/app/feature/camera/CameraViewModel.kt`
- Modify: `app/src/main/java/com/trafficwatch/app/feature/camera/CameraScreen.kt`
- Modify: `app/src/main/java/com/trafficwatch/app/navigation/AppNavigation.kt`
- Modify: `app/src/main/java/com/trafficwatch/app/feature/review/ReviewScreen.kt`
- Modify: `app/src/main/java/com/trafficwatch/app/feature/review/ReviewViewModel.kt`

**Interfaces:**
- Produces: `CompassProvider.observeHeadings(latitude: Double, longitude: Double, altitude: Double, intervalMs: Long): Flow<Float?>` - continuous counterpart to the existing `getSnapshot(...)`. `getSnapshot(...)`'s own signature and behavior are unchanged.
- Produces: `CameraViewModel.getRotationSamples(): List<RotationSample>` - same pattern as the existing `getLocationSamples()`.
- Produces: `ReviewUiState.rotationSamples: List<RotationSample>` (default `emptyList()`) - consumed by Task 2. Task 1 does NOT wire this into `submit()`/`confirmCellularSubmit()`'s use-case calls - that's Task 2's job once the use case accepts the new parameter (mirrors sub-project 1's Task 1/Task 2 split exactly).
- Consumes (existing, unchanged): `LocationState.Fixed`, `CameraViewModel`'s existing `declinationReference` pattern.

- [ ] **Step 1: Add continuous heading observation to `CompassProvider`**

Replace the entire contents of `app/src/main/java/com/trafficwatch/app/core/util/CompassProvider.kt` with:

```kotlin
package com.trafficwatch.app.core.util

import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

private const val DEFAULT_TIMEOUT_MS = 2_000L
private const val SAMPLE_COUNT = 3

/**
 * Returns a single true-north compass heading snapshot at the moment of recording start -
 * the primary signal for translating in-frame vehicle movement (from the video-analysis
 * service) into a real-world compass bearing, mirroring [LocationUtil]'s single-snapshot
 * shape. [observeHeadings] is the continuous counterpart, used during active recording.
 */
@Singleton
class CompassProvider @Inject constructor(
    private val sensorManager: SensorManager,
) {

    /**
     * Reads [Sensor.TYPE_ROTATION_VECTOR] (fused/filtered, simpler and more accurate than
     * raw magnetometer+accelerometer) for a short window, averages a few samples for
     * stability, then corrects magnetic north to true north using [latitude]/[longitude]/
     * [altitude] (from the location snapshot captured at the same instant). Returns null if
     * no rotation-vector sensor exists or the read times out within [timeoutMs].
     */
    suspend fun getSnapshot(
        latitude: Double,
        longitude: Double,
        altitude: Double,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Float? = withTimeoutOrNull(timeoutMs) {
        val magneticAzimuthDegrees = readAveragedAzimuthDegrees() ?: return@withTimeoutOrNull null
        val declination = declinationDegrees(latitude, longitude, altitude)
        applyDeclination(magneticAzimuthDegrees, declination)
    }

    /**
     * Continuous version of [getSnapshot] - emits a declination-corrected heading on every
     * sensor update, requested at approximately [intervalMs] apart (best-effort; the OS
     * doesn't guarantee exact timing, same as [LocationUtil.observeLocation]'s interval
     * request). Declination is computed ONCE from [latitude]/[longitude]/[altitude] before
     * registering the listener, not per-emission - it doesn't meaningfully change within one
     * recording, and recomputing [GeomagneticField] on every tick would be wasted work.
     * Emits null once (and closes) if no rotation-vector sensor exists.
     */
    fun observeHeadings(
        latitude: Double,
        longitude: Double,
        altitude: Double,
        intervalMs: Long,
    ): Flow<Float?> = callbackFlow {
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sensor == null) {
            trySend(null)
            close()
            return@callbackFlow
        }

        val declination = declinationDegrees(latitude, longitude, altitude)
        val rotationMatrix = FloatArray(9)
        val orientation = FloatArray(3)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val magneticAzimuthDegrees = rawAzimuthDegrees(event, rotationMatrix, orientation)
                trySend(applyDeclination(magneticAzimuthDegrees, declination))
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        sensorManager.registerListener(listener, sensor, (intervalMs * 1000).toInt())
        awaitClose { sensorManager.unregisterListener(listener) }
    }

    private suspend fun readAveragedAzimuthDegrees(): Float? = suspendCancellableCoroutine { continuation ->
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sensor == null) {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }

        val samples = mutableListOf<Float>()
        val rotationMatrix = FloatArray(9)
        val orientation = FloatArray(3)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                samples.add(rawAzimuthDegrees(event, rotationMatrix, orientation))

                if (samples.size >= SAMPLE_COUNT) {
                    sensorManager.unregisterListener(this)
                    if (continuation.isActive) {
                        continuation.resume(circularMeanDegrees(samples))
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        continuation.invokeOnCancellation { sensorManager.unregisterListener(listener) }
    }

    private fun rawAzimuthDegrees(event: SensorEvent, rotationMatrix: FloatArray, orientation: FloatArray): Float {
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientation)
        return (Math.toDegrees(orientation[0].toDouble()).toFloat() + 360f) % 360f
    }

    private fun declinationDegrees(latitude: Double, longitude: Double, altitude: Double): Float =
        GeomagneticField(
            latitude.toFloat(),
            longitude.toFloat(),
            altitude.toFloat(),
            System.currentTimeMillis(),
        ).declination

    private fun applyDeclination(magneticAzimuthDegrees: Float, declinationDegrees: Float): Float =
        (magneticAzimuthDegrees + declinationDegrees + 360f) % 360f

    /** Arithmetic mean breaks near the 0/360 wrap-around, so this averages on the unit circle. */
    private fun circularMeanDegrees(samplesDegrees: List<Float>): Float {
        val sinSum = samplesDegrees.sumOf { sin(Math.toRadians(it.toDouble())) }
        val cosSum = samplesDegrees.sumOf { cos(Math.toRadians(it.toDouble())) }
        val meanRadians = atan2(sinSum, cosSum)
        return (Math.toDegrees(meanRadians).toFloat() + 360f) % 360f
    }
}
```

Note what changed versus the original file: `getSnapshot()`'s public signature and behavior are unchanged - only its internal declination step was extracted into `declinationDegrees(...)` + `applyDeclination(...)` (previously one combined private method) so `observeHeadings(...)` can reuse the same pieces without recomputing `GeomagneticField` per sample. The raw azimuth extraction (`getRotationMatrixFromVector`/`getOrientation`/degree conversion) was extracted into `rawAzimuthDegrees(...)`, reused by both `readAveragedAzimuthDegrees()` and the new `observeHeadings(...)`.

- [ ] **Step 2: Create the `RotationSample` domain model**

Create `app/src/main/java/com/trafficwatch/app/core/domain/model/RotationSample.kt`:

```kotlin
package com.trafficwatch.app.core.domain.model

/** One rotation-vector-derived, declination-corrected heading reading captured during active recording. */
data class RotationSample(
    val capturedAt: Long,
    val headingDegrees: Float,
)
```

- [ ] **Step 3: Capture samples during recording in `CameraViewModel`**

In `app/src/main/java/com/trafficwatch/app/feature/camera/CameraViewModel.kt`, add this import:

```kotlin
import com.trafficwatch.app.core.domain.model.RotationSample
```

Replace:

```kotlin
private const val MAX_RECORDING_MS = 600_000L
private const val RECORDING_SAMPLE_INTERVAL_MS = 1_000L
```

with:

```kotlin
private const val MAX_RECORDING_MS = 600_000L
private const val RECORDING_SAMPLE_INTERVAL_MS = 1_000L
private const val ROTATION_SAMPLE_INTERVAL_MS = 200L
```

Replace:

```kotlin
    private var maxDurationJob: Job? = null
    private var snapshotLocation: LocationData? = null
    private var snapshotCompassHeading: Float? = null
    private var recordingStartedAt: Long = 0L
    private val locationSamples = mutableListOf<LocationData>()
    private var samplingJob: Job? = null
```

with:

```kotlin
    private var maxDurationJob: Job? = null
    private var snapshotLocation: LocationData? = null
    private var snapshotCompassHeading: Float? = null
    private var recordingStartedAt: Long = 0L
    private val locationSamples = mutableListOf<LocationData>()
    private var samplingJob: Job? = null
    private val rotationSamples = mutableListOf<RotationSample>()
    private var rotationSamplingJob: Job? = null
```

Replace:

```kotlin
    fun onStartRecording(outputFile: File) {
        // Defensive cancellation: a prior recording's error path (see the onError callback
        // below) can leave these jobs running without ever going through stopRecording(), so
        // a fresh start must not let a leaked prior sampling job double up with this one.
        samplingJob?.cancel()
        maxDurationJob?.cancel()

        recordingStartedAt = System.currentTimeMillis()

        // The record button only enables once locationState is Fixed, so this is a real
        // fix, not a stale/placeholder one - used for magnetic declination without waiting
        // on a fresh GPS read (which would otherwise serialize behind the compass read).
        val declinationReference = (uiState.value.locationState as? LocationState.Fixed)?.data

        locationSamples.clear()
        samplingJob = viewModelScope.launch {
            locationUtil.observeLocation(RECORDING_SAMPLE_INTERVAL_MS)
                .filterNotNull()
                .collect { locationSamples.add(it) }
        }

        viewModelScope.launch {
            snapshotLocation = locationUtil.getSnapshot()
        }
        viewModelScope.launch {
            snapshotCompassHeading = if (declinationReference != null) {
                compassProvider.getSnapshot(
                    latitude = declinationReference.latitude,
                    longitude = declinationReference.longitude,
                    altitude = declinationReference.altitude,
                )
            } else {
                // No location fix available for declination correction - falls back to a
                // magnetic-north-only heading rather than skipping compass capture entirely.
                compassProvider.getSnapshot(latitude = 0.0, longitude = 0.0, altitude = 0.0)
            }
        }

        cameraController.startRecording(outputFile) { error ->
            // CameraController has already moved to Idle without going through
            // stopRecording(), so these jobs must be cancelled here or the 1Hz GPS
            // subscription (and the max-duration timer) leak indefinitely.
            samplingJob?.cancel()
            maxDurationJob?.cancel()
            _uiState.update { it.copy(cameraError = error) }
        }
        maxDurationJob = viewModelScope.launch {
            delay(MAX_RECORDING_MS)
            stopRecording()
        }
    }

    fun stopRecording() {
        maxDurationJob?.cancel()
        samplingJob?.cancel()
        cameraController.stopRecording()
    }
```

with:

```kotlin
    fun onStartRecording(outputFile: File) {
        // Defensive cancellation: a prior recording's error path (see the onError callback
        // below) can leave these jobs running without ever going through stopRecording(), so
        // a fresh start must not let a leaked prior sampling job double up with this one.
        samplingJob?.cancel()
        rotationSamplingJob?.cancel()
        maxDurationJob?.cancel()

        recordingStartedAt = System.currentTimeMillis()

        // The record button only enables once locationState is Fixed, so this is a real
        // fix, not a stale/placeholder one - used for magnetic declination without waiting
        // on a fresh GPS read (which would otherwise serialize behind the compass read).
        val declinationReference = (uiState.value.locationState as? LocationState.Fixed)?.data

        locationSamples.clear()
        samplingJob = viewModelScope.launch {
            locationUtil.observeLocation(RECORDING_SAMPLE_INTERVAL_MS)
                .filterNotNull()
                .collect { locationSamples.add(it) }
        }

        rotationSamples.clear()
        rotationSamplingJob = viewModelScope.launch {
            val headings = if (declinationReference != null) {
                compassProvider.observeHeadings(
                    latitude = declinationReference.latitude,
                    longitude = declinationReference.longitude,
                    altitude = declinationReference.altitude,
                    intervalMs = ROTATION_SAMPLE_INTERVAL_MS,
                )
            } else {
                // No location fix available for declination correction - falls back to
                // magnetic-north-only headings, same as the one-shot snapshot's fallback
                // right below.
                compassProvider.observeHeadings(
                    latitude = 0.0, longitude = 0.0, altitude = 0.0,
                    intervalMs = ROTATION_SAMPLE_INTERVAL_MS,
                )
            }
            headings.filterNotNull().collect { heading ->
                rotationSamples.add(RotationSample(capturedAt = System.currentTimeMillis(), headingDegrees = heading))
            }
        }

        viewModelScope.launch {
            snapshotLocation = locationUtil.getSnapshot()
        }
        viewModelScope.launch {
            snapshotCompassHeading = if (declinationReference != null) {
                compassProvider.getSnapshot(
                    latitude = declinationReference.latitude,
                    longitude = declinationReference.longitude,
                    altitude = declinationReference.altitude,
                )
            } else {
                // No location fix available for declination correction - falls back to a
                // magnetic-north-only heading rather than skipping compass capture entirely.
                compassProvider.getSnapshot(latitude = 0.0, longitude = 0.0, altitude = 0.0)
            }
        }

        cameraController.startRecording(outputFile) { error ->
            // CameraController has already moved to Idle without going through
            // stopRecording(), so these jobs must be cancelled here or the 1Hz GPS
            // subscription (and the max-duration timer) leak indefinitely.
            samplingJob?.cancel()
            rotationSamplingJob?.cancel()
            maxDurationJob?.cancel()
            _uiState.update { it.copy(cameraError = error) }
        }
        maxDurationJob = viewModelScope.launch {
            delay(MAX_RECORDING_MS)
            stopRecording()
        }
    }

    fun stopRecording() {
        maxDurationJob?.cancel()
        samplingJob?.cancel()
        rotationSamplingJob?.cancel()
        cameraController.stopRecording()
    }
```

Add a new accessor right after the existing `getLocationSamples()`:

```kotlin
    fun getLocationSamples(): List<LocationData> = locationSamples.toList()

    fun getRotationSamples(): List<RotationSample> = rotationSamples.toList()
```

- [ ] **Step 4: Thread the samples through `CameraScreen`'s callback**

In `app/src/main/java/com/trafficwatch/app/feature/camera/CameraScreen.kt`, add this import:

```kotlin
import com.trafficwatch.app.core.domain.model.RotationSample
```

Replace:

```kotlin
fun CameraScreen(
    onVideoRecorded: (file: File, location: LocationData?, recordingStartedAt: Long, locationSamples: List<LocationData>) -> Unit,
    viewModel: CameraViewModel = hiltViewModel()
) {
```

with:

```kotlin
fun CameraScreen(
    onVideoRecorded: (file: File, location: LocationData?, recordingStartedAt: Long, locationSamples: List<LocationData>, rotationSamples: List<RotationSample>) -> Unit,
    viewModel: CameraViewModel = hiltViewModel()
) {
```

Replace:

```kotlin
            onVideoRecorded(
                (recordingState as RecordingState.Finalizing).outputFile,
                viewModel.getSnapshotLocation(),
                viewModel.getRecordingStartedAt(),
                viewModel.getLocationSamples()
            )
```

with:

```kotlin
            onVideoRecorded(
                (recordingState as RecordingState.Finalizing).outputFile,
                viewModel.getSnapshotLocation(),
                viewModel.getRecordingStartedAt(),
                viewModel.getLocationSamples(),
                viewModel.getRotationSamples()
            )
```

- [ ] **Step 5: Thread the samples through `AppNavigation`'s shared state, extending the existing trim-window filter**

In `app/src/main/java/com/trafficwatch/app/navigation/AppNavigation.kt`, add this import:

```kotlin
import com.trafficwatch.app.core.domain.model.RotationSample
```

Replace:

```kotlin
    var snapshotLocation by remember { mutableStateOf<LocationData?>(null) }
    var locationSamples by remember { mutableStateOf<List<LocationData>>(emptyList()) }
```

with:

```kotlin
    var snapshotLocation by remember { mutableStateOf<LocationData?>(null) }
    var locationSamples by remember { mutableStateOf<List<LocationData>>(emptyList()) }
    var rotationSamples by remember { mutableStateOf<List<RotationSample>>(emptyList()) }
```

Replace the `CAMERA` composable block:

```kotlin
        composable(Routes.CAMERA) {
            CameraScreen(
                onVideoRecorded = { file, location, startedAt, samples ->
                    rawVideoFile = file.absolutePath
                    snapshotLocation = location
                    recordingStartedAt = startedAt
                    locationSamples = samples
                    navController.navigate(Routes.TRIM)
                }
            )
        }
```

with:

```kotlin
        composable(Routes.CAMERA) {
            CameraScreen(
                onVideoRecorded = { file, location, startedAt, samples, rotationSamplesFromRecording ->
                    rawVideoFile = file.absolutePath
                    snapshotLocation = location
                    recordingStartedAt = startedAt
                    locationSamples = samples
                    rotationSamples = rotationSamplesFromRecording
                    navController.navigate(Routes.TRIM)
                }
            )
        }
```

Replace the REVIEW composable block:

```kotlin
        composable(Routes.REVIEW) {
            val trimmed = trimmedVideoFile ?: return@composable

            // Extract duration once; remember so it doesn't re-run on recomposition
            val duration = remember(trimmed) {
                MediaMetadataRetriever().run {
                    setDataSource(trimmed)
                    val ms = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    release()
                    ms
                }
            }

            // Bound + correlate GPS samples to the actual submitted (trimmed) clip.
            // Continuous sampling runs for the whole raw recording (up to MAX_RECORDING_MS),
            // but only a MAX_TRIM_DURATION_MS window of it is ever uploaded - filtering here,
            // to just the samples whose absolute capture time falls within that trimmed
            // window, keeps the payload small (bounded by the trim window, not the raw
            // recording) and makes each sample correspond to the clip actually submitted.
            val filteredLocationSamples = remember(locationSamples, trimStartMs, duration) {
                val windowStart = recordingStartedAt + trimStartMs
                val windowEnd = windowStart + duration
                locationSamples.filter { it.capturedAt in windowStart..windowEnd }
            }

            ReviewScreen(
                trimmedFile = File(trimmed),
                location = snapshotLocation,
                locationSamples = filteredLocationSamples,
                recordingStartedAt = recordingStartedAt,
                durationMs = duration,
                onSubmit = {
                    rawVideoFile = null
                    trimmedVideoFile = null
                    snapshotLocation = null
                    locationSamples = emptyList()
                    trimStartMs = 0L
                    navController.navigate(Routes.HISTORY) {
                        popUpTo(Routes.HISTORY) { inclusive = true }
                    }
                },
                onRetrim = { navController.popBackStack() },
                onNavigateBack = { navController.popBackStack() }
            )
        }
```

with:

```kotlin
        composable(Routes.REVIEW) {
            val trimmed = trimmedVideoFile ?: return@composable

            // Extract duration once; remember so it doesn't re-run on recomposition
            val duration = remember(trimmed) {
                MediaMetadataRetriever().run {
                    setDataSource(trimmed)
                    val ms = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    release()
                    ms
                }
            }

            // Bound + correlate GPS/rotation samples to the actual submitted (trimmed) clip.
            // Continuous sampling runs for the whole raw recording (up to MAX_RECORDING_MS),
            // but only a MAX_TRIM_DURATION_MS window of it is ever uploaded - filtering here,
            // to just the samples whose absolute capture time falls within that trimmed
            // window, keeps the payload small (bounded by the trim window, not the raw
            // recording) and makes each sample correspond to the clip actually submitted.
            // Both lists share the same window bounds, computed once.
            val windowStart = recordingStartedAt + trimStartMs
            val windowEnd = windowStart + duration
            val filteredLocationSamples = remember(locationSamples, windowStart, windowEnd) {
                locationSamples.filter { it.capturedAt in windowStart..windowEnd }
            }
            val filteredRotationSamples = remember(rotationSamples, windowStart, windowEnd) {
                rotationSamples.filter { it.capturedAt in windowStart..windowEnd }
            }

            ReviewScreen(
                trimmedFile = File(trimmed),
                location = snapshotLocation,
                locationSamples = filteredLocationSamples,
                rotationSamples = filteredRotationSamples,
                recordingStartedAt = recordingStartedAt,
                durationMs = duration,
                onSubmit = {
                    rawVideoFile = null
                    trimmedVideoFile = null
                    snapshotLocation = null
                    locationSamples = emptyList()
                    rotationSamples = emptyList()
                    trimStartMs = 0L
                    navController.navigate(Routes.HISTORY) {
                        popUpTo(Routes.HISTORY) { inclusive = true }
                    }
                },
                onRetrim = { navController.popBackStack() },
                onNavigateBack = { navController.popBackStack() }
            )
        }
```

(Note: `windowStart`/`windowEnd` moved out of the `remember(locationSamples, trimStartMs, duration)` block and computed directly, since they're cheap `Long` arithmetic and are now shared by two `remember` blocks - each keyed on the exact values it depends on, `windowStart`/`windowEnd` themselves recomputing on every recomposition is fine since Compose's `remember` on the filters below is what actually avoids repeated list filtering.)

- [ ] **Step 6: Accept and hold the samples in `ReviewScreen`/`ReviewViewModel`**

In `app/src/main/java/com/trafficwatch/app/feature/review/ReviewScreen.kt`, add this import:

```kotlin
import com.trafficwatch.app.core.domain.model.RotationSample
```

Replace:

```kotlin
fun ReviewScreen(
    trimmedFile: File,
    location: LocationData?,
    locationSamples: List<LocationData>,
    recordingStartedAt: Long,
    durationMs: Long,
    onSubmit: () -> Unit,
    onRetrim: () -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: ReviewViewModel = hiltViewModel()
) {
```

with:

```kotlin
fun ReviewScreen(
    trimmedFile: File,
    location: LocationData?,
    locationSamples: List<LocationData>,
    rotationSamples: List<RotationSample>,
    recordingStartedAt: Long,
    durationMs: Long,
    onSubmit: () -> Unit,
    onRetrim: () -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: ReviewViewModel = hiltViewModel()
) {
```

Replace:

```kotlin
    LaunchedEffect(trimmedFile.absolutePath) {
        viewModel.init(trimmedFile, location, locationSamples, recordingStartedAt, durationMs)
    }
```

with:

```kotlin
    LaunchedEffect(trimmedFile.absolutePath) {
        viewModel.init(trimmedFile, location, locationSamples, rotationSamples, recordingStartedAt, durationMs)
    }
```

In `app/src/main/java/com/trafficwatch/app/feature/review/ReviewViewModel.kt`, add this import:

```kotlin
import com.trafficwatch.app.core.domain.model.RotationSample
```

Replace:

```kotlin
data class ReviewUiState(
    val trimmedFilePath: String = "",
    val location: LocationData? = null,
    val locationSamples: List<LocationData> = emptyList(),
    val recordingStartedAt: Long = 0L,
    val durationMs: Long = 0L,
    val fileSizeBytes: Long = 0L,
    val showCellularPrompt: Boolean = false,
    val isSubmitting: Boolean = false
)
```

with:

```kotlin
data class ReviewUiState(
    val trimmedFilePath: String = "",
    val location: LocationData? = null,
    val locationSamples: List<LocationData> = emptyList(),
    val rotationSamples: List<RotationSample> = emptyList(),
    val recordingStartedAt: Long = 0L,
    val durationMs: Long = 0L,
    val fileSizeBytes: Long = 0L,
    val showCellularPrompt: Boolean = false,
    val isSubmitting: Boolean = false
)
```

Replace:

```kotlin
    fun init(
        trimmedFile: File,
        location: LocationData?,
        locationSamples: List<LocationData>,
        recordingStartedAt: Long,
        durationMs: Long
    ) {
        _uiState.update {
            it.copy(
                trimmedFilePath = trimmedFile.absolutePath,
                location = location,
                locationSamples = locationSamples,
                recordingStartedAt = recordingStartedAt,
                durationMs = durationMs,
                fileSizeBytes = trimmedFile.length()
            )
        }
    }
```

with:

```kotlin
    fun init(
        trimmedFile: File,
        location: LocationData?,
        locationSamples: List<LocationData>,
        rotationSamples: List<RotationSample>,
        recordingStartedAt: Long,
        durationMs: Long
    ) {
        _uiState.update {
            it.copy(
                trimmedFilePath = trimmedFile.absolutePath,
                location = location,
                locationSamples = locationSamples,
                rotationSamples = rotationSamples,
                recordingStartedAt = recordingStartedAt,
                durationMs = durationMs,
                fileSizeBytes = trimmedFile.length()
            )
        }
    }
```

Do NOT modify `submit()` or `confirmCellularSubmit()` in this task - they still call `submitReportUseCase`/`submitReportUseCase.confirmCellular` with today's exact signature. `state.rotationSamples` is populated and available on `ReviewUiState`, but not yet passed anywhere - Task 2 wires it in once `SubmitReportUseCase` actually accepts it.

- [ ] **Step 7: Build to verify it compiles**

Run (from repo root): `./gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Manual verification**

Install on the connected device (`./gradlew.bat :app:installDebug`), record a clip while physically rotating the phone (e.g. panning left-right), then check `CameraViewModel.getRotationSamples()`'s result reached `ReviewViewModel`'s `uiState.value.rotationSamples` with multiple entries and headings that change plausibly with the physical rotation - easiest via a temporary log line or the debugger, since there's no UI surface for this yet.
Expected: multiple samples with distinct, increasing `capturedAt` values and headings that track real device rotation across the clip.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/trafficwatch/app/core/domain/model/RotationSample.kt app/src/main/java/com/trafficwatch/app/core/util/CompassProvider.kt app/src/main/java/com/trafficwatch/app/feature/camera/CameraViewModel.kt app/src/main/java/com/trafficwatch/app/feature/camera/CameraScreen.kt app/src/main/java/com/trafficwatch/app/navigation/AppNavigation.kt app/src/main/java/com/trafficwatch/app/feature/review/ReviewScreen.kt app/src/main/java/com/trafficwatch/app/feature/review/ReviewViewModel.kt
git commit -m "feat(app): capture continuous rotation-vector headings throughout recording"
```

---

### Task 2: Wire format, transmission, and persistence

**Files:**
- Create: `app/src/main/java/com/trafficwatch/app/core/data/remote/dto/RotationSampleDto.kt`
- Modify: `app/src/main/java/com/trafficwatch/app/core/domain/usecase/SubmitReportUseCase.kt`
- Modify: `app/src/main/java/com/trafficwatch/app/feature/review/ReviewViewModel.kt`
- Modify: `app/src/main/java/com/trafficwatch/app/feature/upload/UploadWorker.kt`
- Modify: `app/src/main/java/com/trafficwatch/app/core/data/remote/ApiService.kt`
- Test: `app/src/test/java/com/trafficwatch/app/core/data/remote/dto/RotationSampleDtoTest.kt` (new)
- Create: `server/src/main/kotlin/com/trafficwatch/server/reports/dto/RotationSampleDto.kt`
- Modify: `server/src/main/kotlin/com/trafficwatch/server/reports/ReportController.kt`
- Modify: `server/src/main/kotlin/com/trafficwatch/server/reports/ReportService.kt`
- Modify: `server/src/main/kotlin/com/trafficwatch/server/reports/Report.kt`
- Create: `server/src/main/resources/db/migration/V8__add_rotation_samples_to_reports.sql`
- Test: `server/src/test/kotlin/com/trafficwatch/server/reports/dto/RotationSampleDtoTest.kt` (new)
- Test: `server/src/test/kotlin/com/trafficwatch/server/reports/ReportServiceTest.kt`
- Test: `server/src/test/kotlin/com/trafficwatch/server/EndToEndFlowTest.kt`
- Modify: `docs/improvements-backlog.md`

**Interfaces:**
- Consumes (from Task 1): `ReviewUiState.rotationSamples: List<RotationSample>`.
- Produces: Android `RotationSampleDto(headingDegrees: Float, capturedAt: Long)`.
- Produces: Server `RotationSampleDto(headingDegrees: Double, capturedAt: Long)` (plain camelCase - global Jackson snake_case naming strategy maps `capturedAt`/`headingDegrees` to `captured_at`/`heading_degrees`, matching the Android DTO's explicit annotations).
- Produces: `SubmitReportUseCase.invoke(..., rotationSamples: List<RotationSample>, ...)` and `confirmCellular(..., rotationSamples: List<RotationSample>, ...)` - both gain a new parameter, inserted immediately after `locationSamples` in both signatures.

- [ ] **Step 1: Create the Android DTO and its test**

Create `app/src/main/java/com/trafficwatch/app/core/data/remote/dto/RotationSampleDto.kt`:

```kotlin
package com.trafficwatch.app.core.data.remote.dto

import com.google.gson.annotations.SerializedName
import com.trafficwatch.app.core.domain.model.RotationSample

data class RotationSampleDto(
    @SerializedName("heading_degrees") val headingDegrees: Float,
    // Deliberately named to match the server's Jackson-mapped `capturedAt` property under
    // its global snake_case naming strategy - see the server-side RotationSampleDto.
    @SerializedName("captured_at") val capturedAt: Long,
)

fun RotationSample.toSampleDto(): RotationSampleDto = RotationSampleDto(
    headingDegrees = headingDegrees,
    capturedAt = capturedAt,
)
```

Create `app/src/test/java/com/trafficwatch/app/core/data/remote/dto/RotationSampleDtoTest.kt`:

```kotlin
package com.trafficwatch.app.core.data.remote.dto

import com.google.gson.Gson
import com.trafficwatch.app.core.domain.model.RotationSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RotationSampleDtoTest {

    @Test
    fun `toSampleDto maps every RotationSample field across`() {
        val sample = RotationSample(capturedAt = 1735814400123L, headingDegrees = 271.5f)

        val dto = sample.toSampleDto()

        assertEquals(271.5f, dto.headingDegrees, 1e-6f)
        assertEquals(1735814400123L, dto.capturedAt)
    }

    @Test
    fun `list of samples serializes to a JSON array with snake_case keys`() {
        val samples = listOf(
            RotationSample(capturedAt = 1000L, headingDegrees = 90.0f).toSampleDto(),
            RotationSample(capturedAt = 1200L, headingDegrees = 95.5f).toSampleDto(),
        )

        val json = Gson().toJson(samples)

        assertTrue(json.contains("\"captured_at\":1000"))
        assertTrue(json.contains("\"heading_degrees\":90.0"))
        assertTrue(json.contains("\"captured_at\":1200"))
        assertTrue(json.startsWith("["))
        assertTrue(json.endsWith("]"))
    }
}
```

- [ ] **Step 2: Run the new Android tests to verify they pass**

Run (from repo root): `./gradlew.bat :app:testDebugUnitTest --tests "com.trafficwatch.app.core.data.remote.dto.RotationSampleDtoTest"`
Expected: PASS (2 tests). New coverage, written alongside the DTO it tests - no red-first step here either.

- [ ] **Step 3: Wire the samples through `SubmitReportUseCase`**

In `app/src/main/java/com/trafficwatch/app/core/domain/usecase/SubmitReportUseCase.kt`, add this import:

```kotlin
import com.trafficwatch.app.core.domain.model.RotationSample
```

Replace:

```kotlin
    suspend operator fun invoke(
        trimmedFile: File,
        location: LocationData?,
        locationSamples: List<LocationData>,
        recordingStartedAt: Long,
        durationMs: Long
    ): SubmitReportResult {
        val reportId = UUID.randomUUID().toString()
        val effectiveLocation = location ?: LocationData(0.0, 0.0, 0f, 0.0, 0f, 0f, recordingStartedAt)

        reportRepository.saveReport(
            Report(
                id = reportId,
                videoPath = trimmedFile.absolutePath,
                location = effectiveLocation,
                recordingStartedAt = recordingStartedAt,
                durationMs = durationMs,
                fileSizeBytes = trimmedFile.length(),
                status = ReportStatus.UPLOADING,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        )

        enqueue(
            reportId, trimmedFile.absolutePath, effectiveLocation, locationSamples, recordingStartedAt, durationMs,
            requireWifiOnly = true, policy = ExistingWorkPolicy.KEEP
        )

        return SubmitReportResult(reportId, effectiveLocation, onWifi = networkMonitor.isOnWifi())
    }
```

with:

```kotlin
    suspend operator fun invoke(
        trimmedFile: File,
        location: LocationData?,
        locationSamples: List<LocationData>,
        rotationSamples: List<RotationSample>,
        recordingStartedAt: Long,
        durationMs: Long
    ): SubmitReportResult {
        val reportId = UUID.randomUUID().toString()
        val effectiveLocation = location ?: LocationData(0.0, 0.0, 0f, 0.0, 0f, 0f, recordingStartedAt)

        reportRepository.saveReport(
            Report(
                id = reportId,
                videoPath = trimmedFile.absolutePath,
                location = effectiveLocation,
                recordingStartedAt = recordingStartedAt,
                durationMs = durationMs,
                fileSizeBytes = trimmedFile.length(),
                status = ReportStatus.UPLOADING,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        )

        enqueue(
            reportId, trimmedFile.absolutePath, effectiveLocation, locationSamples, rotationSamples, recordingStartedAt, durationMs,
            requireWifiOnly = true, policy = ExistingWorkPolicy.KEEP
        )

        return SubmitReportResult(reportId, effectiveLocation, onWifi = networkMonitor.isOnWifi())
    }
```

Replace:

```kotlin
    suspend fun confirmCellular(
        reportId: String,
        videoPath: String,
        location: LocationData,
        locationSamples: List<LocationData>,
        recordingStartedAt: Long,
        durationMs: Long
    ) {
        enqueue(
            reportId, videoPath, location, locationSamples, recordingStartedAt, durationMs,
            requireWifiOnly = false, policy = ExistingWorkPolicy.REPLACE
        )
    }

    private fun enqueue(
        reportId: String,
        videoPath: String,
        location: LocationData,
        locationSamples: List<LocationData>,
        recordingStartedAt: Long,
        durationMs: Long,
        requireWifiOnly: Boolean,
        policy: ExistingWorkPolicy
    ) {
        // Serialization (and omitting the field entirely when the list is empty - the same
        // "presence, not sentinel" convention as compass heading) happens inside
        // UploadWorker.buildInputData, not here - keeps this use case free of a Gson/DTO
        // dependency and matches how compassHeadingDegrees is threaded through unconverted.
        val request = UploadWorker.buildRequest(
            reportId, videoPath, location, locationSamples, recordingStartedAt, durationMs, requireWifiOnly
        )
        WorkManager.getInstance(context)
            .enqueueUniqueWork(UploadWorker.uniqueWorkName(reportId), policy, request)
    }
```

with:

```kotlin
    suspend fun confirmCellular(
        reportId: String,
        videoPath: String,
        location: LocationData,
        locationSamples: List<LocationData>,
        rotationSamples: List<RotationSample>,
        recordingStartedAt: Long,
        durationMs: Long
    ) {
        enqueue(
            reportId, videoPath, location, locationSamples, rotationSamples, recordingStartedAt, durationMs,
            requireWifiOnly = false, policy = ExistingWorkPolicy.REPLACE
        )
    }

    private fun enqueue(
        reportId: String,
        videoPath: String,
        location: LocationData,
        locationSamples: List<LocationData>,
        rotationSamples: List<RotationSample>,
        recordingStartedAt: Long,
        durationMs: Long,
        requireWifiOnly: Boolean,
        policy: ExistingWorkPolicy
    ) {
        // Serialization (and omitting the field entirely when the list is empty - the same
        // "presence, not sentinel" convention as compass heading) happens inside
        // UploadWorker.buildInputData, not here - keeps this use case free of a Gson/DTO
        // dependency and matches how compassHeadingDegrees is threaded through unconverted.
        val request = UploadWorker.buildRequest(
            reportId, videoPath, location, locationSamples, rotationSamples, recordingStartedAt, durationMs, requireWifiOnly
        )
        WorkManager.getInstance(context)
            .enqueueUniqueWork(UploadWorker.uniqueWorkName(reportId), policy, request)
    }
```

- [ ] **Step 4: Thread the field through `ReviewViewModel`'s two call sites**

In `app/src/main/java/com/trafficwatch/app/feature/review/ReviewViewModel.kt`, replace:

```kotlin
            val result = submitReportUseCase(
                File(state.trimmedFilePath), state.location, state.locationSamples, state.recordingStartedAt, state.durationMs
            )
```

with:

```kotlin
            val result = submitReportUseCase(
                File(state.trimmedFilePath), state.location, state.locationSamples, state.rotationSamples, state.recordingStartedAt, state.durationMs
            )
```

Replace:

```kotlin
            submitReportUseCase.confirmCellular(
                reportId, state.trimmedFilePath, location, state.locationSamples, state.recordingStartedAt, state.durationMs
            )
```

with:

```kotlin
            submitReportUseCase.confirmCellular(
                reportId, state.trimmedFilePath, location, state.locationSamples, state.rotationSamples, state.recordingStartedAt, state.durationMs
            )
```

- [ ] **Step 5: Thread the JSON string through `UploadWorker` and `ApiService`**

In `app/src/main/java/com/trafficwatch/app/feature/upload/UploadWorker.kt`, add this import:

```kotlin
import com.trafficwatch.app.core.domain.model.RotationSample
```

Replace:

```kotlin
        const val KEY_COMPASS_HEADING = "compass_heading_degrees"
        const val KEY_LOCATION_SAMPLES_JSON = "location_samples_json"
```

with:

```kotlin
        const val KEY_COMPASS_HEADING = "compass_heading_degrees"
        const val KEY_LOCATION_SAMPLES_JSON = "location_samples_json"
        const val KEY_ROTATION_SAMPLES_JSON = "rotation_samples_json"
```

Replace:

```kotlin
        fun buildInputData(
            reportId: String,
            videoPath: String,
            location: LocationData,
            locationSamples: List<LocationData>,
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
            return builder.build()
        }
```

with:

```kotlin
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
```

Replace:

```kotlin
        fun buildRequest(
            reportId: String,
            videoPath: String,
            location: LocationData,
            locationSamples: List<LocationData>,
            recordingStartedAt: Long,
            durationMs: Long,
            requireWifiOnly: Boolean
        ): OneTimeWorkRequest {
            val inputData = buildInputData(reportId, videoPath, location, locationSamples, recordingStartedAt, durationMs)
```

with:

```kotlin
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
```

In `doWork()`, replace:

```kotlin
        val locationSamplesJson = inputData.getString(KEY_LOCATION_SAMPLES_JSON)
```

with:

```kotlin
        val locationSamplesJson = inputData.getString(KEY_LOCATION_SAMPLES_JSON)
        val rotationSamplesJson = inputData.getString(KEY_ROTATION_SAMPLES_JSON)
```

Replace:

```kotlin
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
                locationSamples = locationSamplesJson?.toRequestBody()
            )
```

with:

```kotlin
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
```

In `app/src/main/java/com/trafficwatch/app/core/data/remote/ApiService.kt`, replace:

```kotlin
        @Part("compass_heading_degrees") compassHeadingDegrees: RequestBody?,
        @Part("location_samples") locationSamples: RequestBody?
    ): SubmitReportResponse
```

with:

```kotlin
        @Part("compass_heading_degrees") compassHeadingDegrees: RequestBody?,
        @Part("location_samples") locationSamples: RequestBody?,
        @Part("rotation_samples") rotationSamples: RequestBody?
    ): SubmitReportResponse
```

- [ ] **Step 6: Build the Android app to verify it compiles**

Run (from repo root): `./gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Create the server DTO and its test**

Create `server/src/main/kotlin/com/trafficwatch/server/reports/dto/RotationSampleDto.kt`:

```kotlin
package com.trafficwatch.server.reports.dto

/**
 * One rotation-vector-derived heading reading from the Android client's continuous
 * during-recording sampling (see app-side RotationSampleDto). Plain camelCase properties -
 * the server's global Jackson snake_case naming strategy maps these to `heading_degrees`/
 * `captured_at` with no extra annotations needed, matching every other DTO in this
 * codebase. Not yet consumed by any direction-analysis logic - stored as-is for a future
 * sub-project to use.
 */
data class RotationSampleDto(
    val headingDegrees: Double,
    val capturedAt: Long,
)
```

Create `server/src/test/kotlin/com/trafficwatch/server/reports/dto/RotationSampleDtoTest.kt`:

```kotlin
package com.trafficwatch.server.reports.dto

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RotationSampleDtoTest {

    // Mirrors the app-wide default ObjectMapper's snake_case naming strategy without
    // needing a full Spring context.
    private val objectMapper: ObjectMapper = jacksonObjectMapper()
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)

    @Test
    fun `parses a snake_case JSON array into a list of samples`() {
        val json = """
            [
              {"heading_degrees":271.5,"captured_at":1735814400123},
              {"heading_degrees":268.2,"captured_at":1735814400323}
            ]
        """.trimIndent()

        val samples: List<RotationSampleDto> = objectMapper.readValue(json)

        assertThat(samples).hasSize(2)
        assertThat(samples[0].headingDegrees).isEqualTo(271.5)
        assertThat(samples[0].capturedAt).isEqualTo(1735814400123L)
        assertThat(samples[1].capturedAt).isEqualTo(1735814400323L)
    }

    @Test
    fun `round-trips through serialization back to the same snake_case shape`() {
        val samples = listOf(RotationSampleDto(headingDegrees = 90.0, capturedAt = 1000L))

        val json = objectMapper.writeValueAsString(samples)

        assertThat(json).contains("\"captured_at\":1000")
        assertThat(json).contains("\"heading_degrees\":90.0")
        assertThat(json).doesNotContain("capturedAt")
    }
}
```

- [ ] **Step 8: Run the new server test to verify it passes**

Run (from `server/`): `./gradlew.bat test --tests "com.trafficwatch.server.reports.dto.RotationSampleDtoTest"`
Expected: PASS (2 tests).

- [ ] **Step 9: Add the migration and the `Report` entity column**

Create `server/src/main/resources/db/migration/V8__add_rotation_samples_to_reports.sql`:

```sql
ALTER TABLE reports ADD COLUMN rotation_samples JSONB;
```

In `server/src/main/kotlin/com/trafficwatch/server/reports/Report.kt`, replace:

```kotlin
    // Time-series of GPS fixes captured throughout the recording (not just the single
    // snapshot at recording start - see latitude/longitude/etc. above). Absent on
    // submissions from app versions predating continuous capture. Not yet consumed by
    // any direction-analysis logic - see LocationSampleDto and the design spec for the
    // planned future use.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "location_samples", columnDefinition = "jsonb")
    var locationSamples: String? = null,
```

with:

```kotlin
    // Time-series of GPS fixes captured throughout the recording (not just the single
    // snapshot at recording start - see latitude/longitude/etc. above). Absent on
    // submissions from app versions predating continuous capture. Not yet consumed by
    // any direction-analysis logic - see LocationSampleDto and the design spec for the
    // planned future use.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "location_samples", columnDefinition = "jsonb")
    var locationSamples: String? = null,

    // Time-series of rotation-vector-derived, declination-corrected headings captured
    // throughout the recording (not just the single snapshot at recording start - see
    // compassHeadingDegrees above). Absent on submissions from app versions predating
    // continuous capture. Not yet consumed by any direction-analysis logic - see
    // RotationSampleDto and the design spec for the planned future use.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rotation_samples", columnDefinition = "jsonb")
    var rotationSamples: String? = null,
```

- [ ] **Step 10: Accept, parse, and store the field in `ReportController`/`ReportService`**

In `server/src/main/kotlin/com/trafficwatch/server/reports/ReportController.kt`, replace:

```kotlin
        @RequestParam("location_samples", required = false) locationSamplesJson: String?,
    ): SubmitReportResponse =
        reportService.submit(
            video = video,
            latitude = latitude,
            longitude = longitude,
            accuracy = accuracy,
            altitude = altitude,
            bearing = bearing,
            speed = speed,
            recordedAt = recordedAt,
            durationMs = durationMs,
            deviceId = deviceId,
            compassHeadingDegrees = compassHeadingDegrees,
            locationSamplesJson = locationSamplesJson,
        )
```

with:

```kotlin
        @RequestParam("location_samples", required = false) locationSamplesJson: String?,
        @RequestParam("rotation_samples", required = false) rotationSamplesJson: String?,
    ): SubmitReportResponse =
        reportService.submit(
            video = video,
            latitude = latitude,
            longitude = longitude,
            accuracy = accuracy,
            altitude = altitude,
            bearing = bearing,
            speed = speed,
            recordedAt = recordedAt,
            durationMs = durationMs,
            deviceId = deviceId,
            compassHeadingDegrees = compassHeadingDegrees,
            locationSamplesJson = locationSamplesJson,
            rotationSamplesJson = rotationSamplesJson,
        )
```

In `server/src/main/kotlin/com/trafficwatch/server/reports/ReportService.kt`, add this import:

```kotlin
import com.trafficwatch.server.reports.dto.RotationSampleDto
```

Replace:

```kotlin
/**
 * Silent cap on the number of entries persisted from a client-submitted `location_samples`
 * array. The well-behaved app already bounds this to ~10 entries (one per second of the
 * 10-second trimmed clip), but this endpoint cannot trust that - any client can post an
 * arbitrarily large array directly. An oversized array is treated the same as malformed
 * JSON: logged and dropped, never rejecting the submission itself.
 */
private const val MAX_LOCATION_SAMPLES = 1000
```

with:

```kotlin
/**
 * Silent cap on the number of entries persisted from a client-submitted `location_samples`
 * array. The well-behaved app already bounds this to ~10 entries (one per second of the
 * 10-second trimmed clip), but this endpoint cannot trust that - any client can post an
 * arbitrarily large array directly. An oversized array is treated the same as malformed
 * JSON: logged and dropped, never rejecting the submission itself.
 */
private const val MAX_LOCATION_SAMPLES = 1000

/** Same reasoning as [MAX_LOCATION_SAMPLES], but sized for `rotation_samples`'s ~50-entry
 * well-behaved-app bound (5Hz over a 10-second trimmed clip) rather than `location_samples`'
 * ~10-entry bound (1Hz). */
private const val MAX_ROTATION_SAMPLES = 1000
```

Replace `submit(...)`'s signature and the malformed-JSON-tolerant parsing block:

```kotlin
    @Transactional
    fun submit(
        video: MultipartFile,
        latitude: BigDecimal,
        longitude: BigDecimal,
        accuracy: BigDecimal,
        altitude: BigDecimal,
        bearing: BigDecimal,
        speed: BigDecimal,
        recordedAt: String,
        durationMs: Long,
        deviceId: String,
        compassHeadingDegrees: BigDecimal?,
        locationSamplesJson: String?,
    ): SubmitReportResponse {
        val userId = CurrentUser.id()
        val parsedRecordedAt = LocalDateTime.parse(recordedAt, RECORDED_AT_FORMATTER)

        // Malformed/unparseable input never blocks submission - logged and treated as
        // absent, same tolerance as every other optional client-submitted field here.
        val canonicalLocationSamples = locationSamplesJson?.let {
            try {
                val parsed: List<LocationSampleDto> = objectMapper.readValue(
                    it,
                    objectMapper.typeFactory.constructCollectionType(List::class.java, LocationSampleDto::class.java),
                )
                if (parsed.size > MAX_LOCATION_SAMPLES) {
                    logger.warn(
                        "ReportService: location_samples had {} entries (max {}), treating as absent",
                        parsed.size,
                        MAX_LOCATION_SAMPLES,
                    )
                    null
                } else {
                    objectMapper.writeValueAsString(parsed)
                }
            } catch (ex: Exception) {
                logger.warn("ReportService: failed to parse location_samples, treating as absent", ex)
                null
            }
        }

        val report = Report(
            userId = userId,
            videoPath = "",
            latitude = latitude,
            longitude = longitude,
            accuracy = accuracy,
            altitude = altitude,
            bearing = bearing,
            speed = speed,
            recordedAt = parsedRecordedAt,
            durationMs = durationMs,
            deviceId = deviceId,
            status = ReportStatus.PENDING,
            compassHeadingDegrees = compassHeadingDegrees,
            locationSamples = canonicalLocationSamples,
        )
```

with:

```kotlin
    @Transactional
    fun submit(
        video: MultipartFile,
        latitude: BigDecimal,
        longitude: BigDecimal,
        accuracy: BigDecimal,
        altitude: BigDecimal,
        bearing: BigDecimal,
        speed: BigDecimal,
        recordedAt: String,
        durationMs: Long,
        deviceId: String,
        compassHeadingDegrees: BigDecimal?,
        locationSamplesJson: String?,
        rotationSamplesJson: String?,
    ): SubmitReportResponse {
        val userId = CurrentUser.id()
        val parsedRecordedAt = LocalDateTime.parse(recordedAt, RECORDED_AT_FORMATTER)

        // Malformed/unparseable input never blocks submission - logged and treated as
        // absent, same tolerance as every other optional client-submitted field here.
        val canonicalLocationSamples = locationSamplesJson?.let {
            try {
                val parsed: List<LocationSampleDto> = objectMapper.readValue(
                    it,
                    objectMapper.typeFactory.constructCollectionType(List::class.java, LocationSampleDto::class.java),
                )
                if (parsed.size > MAX_LOCATION_SAMPLES) {
                    logger.warn(
                        "ReportService: location_samples had {} entries (max {}), treating as absent",
                        parsed.size,
                        MAX_LOCATION_SAMPLES,
                    )
                    null
                } else {
                    objectMapper.writeValueAsString(parsed)
                }
            } catch (ex: Exception) {
                logger.warn("ReportService: failed to parse location_samples, treating as absent", ex)
                null
            }
        }

        val canonicalRotationSamples = rotationSamplesJson?.let {
            try {
                val parsed: List<RotationSampleDto> = objectMapper.readValue(
                    it,
                    objectMapper.typeFactory.constructCollectionType(List::class.java, RotationSampleDto::class.java),
                )
                if (parsed.size > MAX_ROTATION_SAMPLES) {
                    logger.warn(
                        "ReportService: rotation_samples had {} entries (max {}), treating as absent",
                        parsed.size,
                        MAX_ROTATION_SAMPLES,
                    )
                    null
                } else {
                    objectMapper.writeValueAsString(parsed)
                }
            } catch (ex: Exception) {
                logger.warn("ReportService: failed to parse rotation_samples, treating as absent", ex)
                null
            }
        }

        val report = Report(
            userId = userId,
            videoPath = "",
            latitude = latitude,
            longitude = longitude,
            accuracy = accuracy,
            altitude = altitude,
            bearing = bearing,
            speed = speed,
            recordedAt = parsedRecordedAt,
            durationMs = durationMs,
            deviceId = deviceId,
            status = ReportStatus.PENDING,
            compassHeadingDegrees = compassHeadingDegrees,
            locationSamples = canonicalLocationSamples,
            rotationSamples = canonicalRotationSamples,
        )
```

- [ ] **Step 11: Update `ReportServiceTest`'s existing `submit()` calls and add new behavioral tests**

In `server/src/test/kotlin/com/trafficwatch/server/reports/ReportServiceTest.kt`, there are three existing calls to `reportService.submit(...)` that pass `locationSamplesJson = null,` as their last named argument (in the tests `submit saves report as PENDING for the authenticated user with the stored video path`, `submit parses recorded_at leniently as a timezone-less LocalDateTime`, and `submit deletes the just-written video and rethrows if the second save fails`). In each of the three, replace:

```kotlin
                compassHeadingDegrees = null,
                locationSamplesJson = null,
            )
```

with:

```kotlin
                compassHeadingDegrees = null,
                locationSamplesJson = null,
                rotationSamplesJson = null,
            )
```

(Match the exact indentation already present at each call site - two are indented as shown above inside a plain `reportService.submit(...)` call, one is inside an `assertThatThrownBy { reportService.submit(...) }` block with the same relative indentation.)

Then add these three new tests, placed right after the third existing `submit` test (`submit deletes the just-written video and rethrows if the second save fails`), before the `// --- getStatus ---` comment:

```kotlin
    @Test
    fun `submit persists valid rotation_samples exactly as submitted`() {
        val fixedId = UUID.randomUUID()
        stubSaveAssigningId(fixedId)
        every { videoStorageService.store(fixedId, any()) } returns "$fixedId.mp4"
        every { reportAnalysisJob.analyze(fixedId) } just runs

        reportService.submit(
            video = sampleVideo(),
            latitude = BigDecimal("31.520370"),
            longitude = BigDecimal("74.358749"),
            accuracy = BigDecimal("5.00"),
            altitude = BigDecimal("210.50"),
            bearing = BigDecimal("87.30"),
            speed = BigDecimal("12.40"),
            recordedAt = "2026-07-25T10:15:30Z",
            durationMs = 15000L,
            deviceId = "device-123",
            compassHeadingDegrees = null,
            locationSamplesJson = null,
            rotationSamplesJson = """[{"heading_degrees":271.5,"captured_at":1735814400123}]""",
        )
        simulateCommit()

        assertThat(savedReport.rotationSamples).isNotNull()
        assertThat(savedReport.rotationSamples).contains("\"captured_at\":1735814400123")
        assertThat(savedReport.rotationSamples).contains("\"heading_degrees\":271.5")
    }

    @Test
    fun `submit treats malformed rotation_samples as absent rather than failing`() {
        val fixedId = UUID.randomUUID()
        stubSaveAssigningId(fixedId)
        every { videoStorageService.store(fixedId, any()) } returns "$fixedId.mp4"
        every { reportAnalysisJob.analyze(fixedId) } just runs

        val response = reportService.submit(
            video = sampleVideo(),
            latitude = BigDecimal("31.520370"),
            longitude = BigDecimal("74.358749"),
            accuracy = BigDecimal("5.00"),
            altitude = BigDecimal("210.50"),
            bearing = BigDecimal("87.30"),
            speed = BigDecimal("12.40"),
            recordedAt = "2026-07-25T10:15:30Z",
            durationMs = 15000L,
            deviceId = "device-123",
            compassHeadingDegrees = null,
            locationSamplesJson = null,
            rotationSamplesJson = "not valid json",
        )
        simulateCommit()

        assertThat(response.status).isEqualTo(ReportStatus.PENDING)
        assertThat(savedReport.rotationSamples).isNull()
    }

    @Test
    fun `submit treats malformed location_samples as absent rather than failing`() {
        val fixedId = UUID.randomUUID()
        stubSaveAssigningId(fixedId)
        every { videoStorageService.store(fixedId, any()) } returns "$fixedId.mp4"
        every { reportAnalysisJob.analyze(fixedId) } just runs

        val response = reportService.submit(
            video = sampleVideo(),
            latitude = BigDecimal("31.520370"),
            longitude = BigDecimal("74.358749"),
            accuracy = BigDecimal("5.00"),
            altitude = BigDecimal("210.50"),
            bearing = BigDecimal("87.30"),
            speed = BigDecimal("12.40"),
            recordedAt = "2026-07-25T10:15:30Z",
            durationMs = 15000L,
            deviceId = "device-123",
            compassHeadingDegrees = null,
            locationSamplesJson = "{not valid",
            rotationSamplesJson = null,
        )
        simulateCommit()

        assertThat(response.status).isEqualTo(ReportStatus.PENDING)
        assertThat(savedReport.locationSamples).isNull()
    }
```

The third test above (`submit treats malformed location_samples as absent rather than failing`) closes a coverage gap the OSM-lookup-retry plan's final review found but didn't fix for `location_samples` - see Global Constraints.

- [ ] **Step 12: Run the full server test suite to confirm no regressions**

Run (from `server/`): `./gradlew.bat test`
Expected: all tests pass, aside from the same pre-existing, already-known network-dependent flake in `EndToEndFlowTest`'s `register, login, submit, poll...` test (unrelated to this change - passes in isolation). If anything else fails, investigate before continuing.

- [ ] **Step 13: Extend `EndToEndFlowTest` with a round-trip check**

In `server/src/test/kotlin/com/trafficwatch/server/EndToEndFlowTest.kt`, add a new test method at the end of the class, right before the closing brace:

```kotlin
    @Test
    fun `rotation_samples round-trips to the stored report exactly as submitted`() {
        val phone = uniquePhoneNumber()
        val email = uniqueEmail()
        val cnic = uniqueCnic()
        val password = "supersecret1"

        val registerResponse = register(phone, email, cnic, password)
        val token = requireNotNull(registerResponse.body?.get("token") as? String)

        val body: MultiValueMap<String, Any> = LinkedMultiValueMap()
        body.add("video", NamedByteArrayResource(ByteArray(1024) { it.toByte() }, "clip.mp4"))
        body.add("latitude", "31.520370")
        body.add("longitude", "74.358749")
        body.add("accuracy", "5.00")
        body.add("altitude", "210.50")
        body.add("bearing", "87.30")
        body.add("speed", "12.40")
        body.add("recorded_at", "2026-07-25T10:15:30Z")
        body.add("duration_ms", "15000")
        body.add("device_id", "device-e2e-test")
        body.add(
            "rotation_samples",
            """[{"heading_degrees":271.5,"captured_at":1735814400123}]""",
        )

        val headers = authHeaders(token)
        headers.contentType = MediaType.MULTIPART_FORM_DATA
        val submitResponse = restTemplate.exchange("/reports", HttpMethod.POST, HttpEntity(body, headers), mapType)

        assertThat(submitResponse.statusCode).isEqualTo(HttpStatus.CREATED)
        val reportId = requireNotNull(submitResponse.body?.get("report_id") as? String)

        val stored = reportRepository.findById(java.util.UUID.fromString(reportId)).orElseThrow()
        assertThat(stored.rotationSamples).isNotNull()
        assertThat(stored.rotationSamples).contains("\"captured_at\":1735814400123")
    }
```

- [ ] **Step 14: Run the full server test suite again to confirm the new test passes**

Run (from `server/`): `./gradlew.bat test --tests "com.trafficwatch.server.EndToEndFlowTest"`
Expected: PASS (5 tests: the 4 existing plus the new one).

- [ ] **Step 15: Update the improvements backlog**

In `docs/improvements-backlog.md`, find the entry titled "A report that fails its first upload attempt permanently loses its `location_samples`" (under the "Upload reliability / data integrity" section, added 2026-08-03). Replace it with:

```markdown
- **A report that fails its first upload attempt permanently loses its
  `location_samples`/`rotation_samples`.** `RetryUploadUseCase` re-enqueues
  from the persisted `Report` Room entity, which never gained
  `locationSamples`/`rotationSamples` fields (only the transient
  `ReviewViewModel`/`ReviewUiState` did) - so retries always send empty
  lists for both. Uploads are Wi-Fi-only by default, so first-attempt
  failures aren't rare. Accepted as correct-for-now (neither field is
  consumed by anything yet), but if either becomes load-bearing for
  direction analysis (per sub-project 3), retried reports will silently
  have neither. Would need both persisted on the local `Report` entity
  too, not just the transient upload-flow state.
  *(added 2026-08-03, updated 2026-08-04 to cover rotation_samples too)*
```

- [ ] **Step 16: Commit**

```bash
git add app/src/main/java/com/trafficwatch/app/core/data/remote/dto/RotationSampleDto.kt app/src/main/java/com/trafficwatch/app/core/domain/usecase/SubmitReportUseCase.kt app/src/main/java/com/trafficwatch/app/feature/review/ReviewViewModel.kt app/src/main/java/com/trafficwatch/app/feature/upload/UploadWorker.kt app/src/main/java/com/trafficwatch/app/core/data/remote/ApiService.kt app/src/test/java/com/trafficwatch/app/core/data/remote/dto/RotationSampleDtoTest.kt server/src/main/kotlin/com/trafficwatch/server/reports/dto/RotationSampleDto.kt server/src/main/kotlin/com/trafficwatch/server/reports/ReportController.kt server/src/main/kotlin/com/trafficwatch/server/reports/ReportService.kt server/src/main/kotlin/com/trafficwatch/server/reports/Report.kt server/src/main/resources/db/migration/V8__add_rotation_samples_to_reports.sql server/src/test/kotlin/com/trafficwatch/server/reports/dto/RotationSampleDtoTest.kt server/src/test/kotlin/com/trafficwatch/server/reports/ReportServiceTest.kt server/src/test/kotlin/com/trafficwatch/server/EndToEndFlowTest.kt docs/improvements-backlog.md
git commit -m "feat: transmit and persist continuous rotation-vector heading samples"
```

- [ ] **Step 17: Deploy to production and manually verify**

```bash
scp -i ~/.ssh/trafficwatch_ovh -r server/src/main/kotlin/com/trafficwatch/server/reports/dto/RotationSampleDto.kt ubuntu@137.74.173.97:~/trafficwatch/server/src/main/kotlin/com/trafficwatch/server/reports/dto/
scp -i ~/.ssh/trafficwatch_ovh server/src/main/kotlin/com/trafficwatch/server/reports/ReportController.kt ubuntu@137.74.173.97:~/trafficwatch/server/src/main/kotlin/com/trafficwatch/server/reports/
scp -i ~/.ssh/trafficwatch_ovh server/src/main/kotlin/com/trafficwatch/server/reports/ReportService.kt ubuntu@137.74.173.97:~/trafficwatch/server/src/main/kotlin/com/trafficwatch/server/reports/
scp -i ~/.ssh/trafficwatch_ovh server/src/main/kotlin/com/trafficwatch/server/reports/Report.kt ubuntu@137.74.173.97:~/trafficwatch/server/src/main/kotlin/com/trafficwatch/server/reports/
scp -i ~/.ssh/trafficwatch_ovh server/src/main/resources/db/migration/V8__add_rotation_samples_to_reports.sql ubuntu@137.74.173.97:~/trafficwatch/server/src/main/resources/db/migration/
ssh -i ~/.ssh/trafficwatch_ovh ubuntu@137.74.173.97 "cd ~/trafficwatch && docker compose -f docker-compose.prod.yml up -d --build server"
```

Install the updated Android app (`./gradlew.bat :app:installDebug`) on the connected device, record a clip while physically rotating the phone, submit it, then query the production database directly to confirm the `rotation_samples` column has a populated JSON array with multiple entries and headings that plausibly track the real rotation.
Expected: `rotation_samples` is a non-null JSON array with more than one entry, headings changing across the array in a way that matches the physical motion, `captured_at` values increasing and spanning roughly the recording's real duration.
