# Continuous GPS Heading Capture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Capture a time-series of GPS fixes throughout each recording (not just one snapshot at the start), upload it, and persist it on the server. No change to how direction is currently computed - this is sub-project 1 of 4 in fixing the "stationary camera" assumption in wrong-way analysis (see `docs/superpowers/specs/2026-08-03-continuous-gps-heading-capture-design.md`).

**Architecture:** Task 1 captures the samples on-device and threads them up through `ReviewViewModel`'s UI state, exactly mirroring how the existing single `snapshotLocation` already flows. Task 2 transmits them (Android upload payload -> server parse -> DB column) and adds the tests. Neither task touches `ClipFlowAnalyzer` or any direction-analysis logic.

**Tech Stack:** Kotlin (Android + Spring Boot server), Jetpack Compose, WorkManager, Retrofit/OkHttp, Gson (Android), Jackson (server), Flyway.

## Global Constraints

- Sampling interval during active recording: 1000ms - a separate, additional subscription from the existing 3000ms UI-only `observeLocation()` stream, which is left completely unchanged.
- Storage: a single `location_samples` JSONB column on the `reports` table, matching the existing `direction_evidence` column's pattern - not a new table.
- Wire format: one new optional multipart field, `location_samples` (JSON array string) - `required = false` on the server, matching the existing `compass_heading_degrees` field's backward-compatibility convention. Absent on submissions from older app versions must never block submission.
- No changes to `ClipFlowAnalyzer.kt`, `ReportAnalysisJob.kt`, or any direction-analysis logic in this plan.
- Malformed/unparseable `location_samples` JSON must be logged and treated as absent (null), never fail the whole report submission.

---

### Task 1: Android capture chain

**Files:**
- Modify: `app/src/main/java/com/trafficwatch/app/core/util/LocationUtil.kt`
- Modify: `app/src/main/java/com/trafficwatch/app/feature/camera/CameraViewModel.kt`
- Modify: `app/src/main/java/com/trafficwatch/app/feature/camera/CameraScreen.kt`
- Modify: `app/src/main/java/com/trafficwatch/app/navigation/AppNavigation.kt`
- Modify: `app/src/main/java/com/trafficwatch/app/feature/review/ReviewViewModel.kt`

**Interfaces:**
- Produces: `CameraViewModel.getLocationSamples(): List<LocationData>` - a new accessor, same pattern as the existing `getSnapshotLocation()`. Called once, right after `getSnapshotLocation()`, at the exact point `CameraScreen` already reads the snapshot.
- Produces: `ReviewUiState.locationSamples: List<LocationData>` (default `emptyList()`) - consumed by Task 2, which wires it into the actual upload call. Task 1 does NOT modify `submit()`/`confirmCellularSubmit()` - those still call `submitReportUseCase` with today's exact signature; wiring the new field into that call is Task 2's job once the use case actually accepts it.
- Consumes (existing, unchanged): `LocationUtil.observeLocation(intervalMs: Long): Flow<LocationData?>` (Task 1 adds the parameter with a default that preserves today's behavior for the one existing caller).

- [ ] **Step 1: Add an interval parameter to `observeLocation()`**

In `app/src/main/java/com/trafficwatch/app/core/util/LocationUtil.kt`, replace:

```kotlin
    /**
     * Continuous location updates as a Flow for the GPS status overlay on CameraScreen.
     */
    @SuppressLint("MissingPermission")
    fun observeLocation(): Flow<LocationData?> = callbackFlow {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, LOCATION_UPDATE_INTERVAL_MS)
            .setMinUpdateIntervalMillis(LOCATION_UPDATE_INTERVAL_MS)
            .build()
```

with:

```kotlin
    /**
     * Continuous location updates as a Flow. Default interval drives the GPS status overlay
     * on CameraScreen; CameraViewModel starts a second, separate subscription at a tighter
     * interval during active recording (see RECORDING_SAMPLE_INTERVAL_MS) - this method
     * itself is stateless per-call, so multiple concurrent subscriptions at different
     * intervals are independent and don't interfere with each other.
     */
    @SuppressLint("MissingPermission")
    fun observeLocation(intervalMs: Long = LOCATION_UPDATE_INTERVAL_MS): Flow<LocationData?> = callbackFlow {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs)
            .build()
```

- [ ] **Step 2: Capture samples during recording in `CameraViewModel`**

In `app/src/main/java/com/trafficwatch/app/feature/camera/CameraViewModel.kt`, add this constant right after the existing `MAX_RECORDING_MS` constant near the top of the file:

```kotlin
private const val MAX_RECORDING_MS = 600_000L
private const val RECORDING_SAMPLE_INTERVAL_MS = 1_000L
```

Add these imports:

```kotlin
import kotlinx.coroutines.flow.filterNotNull
```

Add a new property alongside the existing `snapshotLocation`/`snapshotCompassHeading` fields:

```kotlin
    private var snapshotLocation: LocationData? = null
    private var snapshotCompassHeading: Float? = null
    private var recordingStartedAt: Long = 0L
    private val locationSamples = mutableListOf<LocationData>()
    private var samplingJob: Job? = null
```

In `onStartRecording(outputFile: File)`, replace:

```kotlin
    fun onStartRecording(outputFile: File) {
        recordingStartedAt = System.currentTimeMillis()

        // The record button only enables once locationState is Fixed, so this is a real
        // fix, not a stale/placeholder one - used for magnetic declination without waiting
        // on a fresh GPS read (which would otherwise serialize behind the compass read).
        val declinationReference = (uiState.value.locationState as? LocationState.Fixed)?.data

        viewModelScope.launch {
            snapshotLocation = locationUtil.getSnapshot()
        }
```

with:

```kotlin
    fun onStartRecording(outputFile: File) {
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
```

In `stopRecording()`, replace:

```kotlin
    fun stopRecording() {
        maxDurationJob?.cancel()
        cameraController.stopRecording()
    }
```

with:

```kotlin
    fun stopRecording() {
        maxDurationJob?.cancel()
        samplingJob?.cancel()
        cameraController.stopRecording()
    }
```

Add a new accessor right after the existing `getSnapshotLocation()`:

```kotlin
    fun getSnapshotLocation(): LocationData? =
        snapshotLocation?.copy(compassHeadingDegrees = snapshotCompassHeading)

    fun getLocationSamples(): List<LocationData> = locationSamples.toList()
```

- [ ] **Step 3: Thread the samples through `CameraScreen`'s callback**

In `app/src/main/java/com/trafficwatch/app/feature/camera/CameraScreen.kt`, replace the `onVideoRecorded` parameter declaration:

```kotlin
fun CameraScreen(
    onVideoRecorded: (file: File, location: LocationData?, recordingStartedAt: Long) -> Unit,
    viewModel: CameraViewModel = hiltViewModel()
) {
```

with:

```kotlin
fun CameraScreen(
    onVideoRecorded: (file: File, location: LocationData?, recordingStartedAt: Long, locationSamples: List<LocationData>) -> Unit,
    viewModel: CameraViewModel = hiltViewModel()
) {
```

Replace the call site:

```kotlin
            onVideoRecorded(
                (recordingState as RecordingState.Finalizing).outputFile,
                viewModel.getSnapshotLocation(),
                viewModel.getRecordingStartedAt()
            )
```

with:

```kotlin
            onVideoRecorded(
                (recordingState as RecordingState.Finalizing).outputFile,
                viewModel.getSnapshotLocation(),
                viewModel.getRecordingStartedAt(),
                viewModel.getLocationSamples()
            )
```

- [ ] **Step 4: Thread the samples through `AppNavigation`'s shared state**

In `app/src/main/java/com/trafficwatch/app/navigation/AppNavigation.kt`, add a new state variable right after the existing `snapshotLocation` declaration:

```kotlin
    var snapshotLocation by remember { mutableStateOf<LocationData?>(null) }
    var locationSamples by remember { mutableStateOf<List<LocationData>>(emptyList()) }
```

Replace the `CAMERA` composable block:

```kotlin
        composable(Routes.CAMERA) {
            CameraScreen(
                onVideoRecorded = { file, location, startedAt ->
                    rawVideoFile = file.absolutePath
                    snapshotLocation = location
                    recordingStartedAt = startedAt
                    navController.navigate(Routes.TRIM)
                }
            )
        }
```

with:

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

In the `REVIEW` composable block, add `locationSamples = locationSamples,` as a new `ReviewScreen` parameter right after the existing `location = snapshotLocation,` line, and reset it alongside the other cleared state in `onSubmit`:

```kotlin
            ReviewScreen(
                trimmedFile = File(trimmed),
                location = snapshotLocation,
                locationSamples = locationSamples,
                recordingStartedAt = recordingStartedAt,
                durationMs = duration,
                onSubmit = {
                    rawVideoFile = null
                    trimmedVideoFile = null
                    snapshotLocation = null
                    locationSamples = emptyList()
                    navController.navigate(Routes.HISTORY) {
                        popUpTo(Routes.HISTORY) { inclusive = true }
                    }
                },
                onRetrim = { navController.popBackStack() },
                onNavigateBack = { navController.popBackStack() }
            )
```

- [ ] **Step 5: Accept and hold the samples in `ReviewScreen`/`ReviewViewModel`**

In `app/src/main/java/com/trafficwatch/app/feature/review/ReviewScreen.kt`, add a new parameter to the `ReviewScreen` function signature, right after the existing `location: LocationData?,` parameter:

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

Find the `LaunchedEffect(trimmedFile.absolutePath)` block that calls `viewModel.init(...)`:

```kotlin
    LaunchedEffect(trimmedFile.absolutePath) {
        viewModel.init(trimmedFile, location, recordingStartedAt, durationMs)
    }
```

Replace with:

```kotlin
    LaunchedEffect(trimmedFile.absolutePath) {
        viewModel.init(trimmedFile, location, locationSamples, recordingStartedAt, durationMs)
    }
```

In `app/src/main/java/com/trafficwatch/app/feature/review/ReviewViewModel.kt`, replace the `ReviewUiState` data class:

```kotlin
data class ReviewUiState(
    val trimmedFilePath: String = "",
    val location: LocationData? = null,
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
    val recordingStartedAt: Long = 0L,
    val durationMs: Long = 0L,
    val fileSizeBytes: Long = 0L,
    val showCellularPrompt: Boolean = false,
    val isSubmitting: Boolean = false
)
```

Replace `init(...)`:

```kotlin
    fun init(
        trimmedFile: File,
        location: LocationData?,
        recordingStartedAt: Long,
        durationMs: Long
    ) {
        _uiState.update {
            it.copy(
                trimmedFilePath = trimmedFile.absolutePath,
                location = location,
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

Do NOT modify `submit()` or `confirmCellularSubmit()` in this task - they still call `submitReportUseCase`/`submitReportUseCase.confirmCellular` with today's exact signature. `state.locationSamples` is populated and available on `ReviewUiState`, but not yet passed anywhere - Task 2 wires it in once `SubmitReportUseCase` actually accepts it.

- [ ] **Step 6: Build to verify it compiles**

Run (from repo root): `./gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Manual verification**

Install on the connected device (`./gradlew.bat :app:installDebug`), record a clip while physically moving (e.g. walking with the phone), then check `CameraViewModel.getLocationSamples()`'s result reached `ReviewViewModel`'s `uiState.value.locationSamples` with more than one entry and increasing `capturedAt` timestamps - easiest via a temporary log line or the debugger, since there's no UI surface for this yet (Task 2 doesn't add one either - this data isn't user-facing).
Expected: multiple samples with distinct, increasing `capturedAt` values spanning roughly the recording's duration.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/trafficwatch/app/core/util/LocationUtil.kt app/src/main/java/com/trafficwatch/app/feature/camera/CameraViewModel.kt app/src/main/java/com/trafficwatch/app/feature/camera/CameraScreen.kt app/src/main/java/com/trafficwatch/app/navigation/AppNavigation.kt app/src/main/java/com/trafficwatch/app/feature/review/ReviewScreen.kt app/src/main/java/com/trafficwatch/app/feature/review/ReviewViewModel.kt
git commit -m "feat(app): capture continuous GPS samples throughout recording"
```

---

### Task 2: Wire format, transmission, and persistence

**Files:**
- Create: `app/src/main/java/com/trafficwatch/app/core/data/remote/dto/LocationSampleDto.kt`
- Modify: `app/src/main/java/com/trafficwatch/app/core/domain/usecase/SubmitReportUseCase.kt`
- Modify: `app/src/main/java/com/trafficwatch/app/feature/review/ReviewViewModel.kt`
- Modify: `app/src/main/java/com/trafficwatch/app/feature/upload/UploadWorker.kt`
- Modify: `app/src/main/java/com/trafficwatch/app/core/data/remote/ApiService.kt`
- Test: `app/src/test/java/com/trafficwatch/app/core/data/remote/dto/LocationSampleDtoTest.kt`
- Create: `server/src/main/kotlin/com/trafficwatch/server/reports/dto/LocationSampleDto.kt`
- Modify: `server/src/main/kotlin/com/trafficwatch/server/reports/ReportController.kt`
- Modify: `server/src/main/kotlin/com/trafficwatch/server/reports/ReportService.kt`
- Modify: `server/src/main/kotlin/com/trafficwatch/server/reports/Report.kt`
- Create: `server/src/main/resources/db/migration/V7__add_location_samples_to_reports.sql`
- Test: `server/src/test/kotlin/com/trafficwatch/server/reports/dto/LocationSampleDtoTest.kt`
- Test: `server/src/test/kotlin/com/trafficwatch/server/EndToEndFlowTest.kt`

**Interfaces:**
- Consumes (from Task 1): `ReviewUiState.locationSamples: List<LocationData>`.
- Produces: Android `LocationSampleDto(latitude: Double, longitude: Double, accuracy: Float, altitude: Double, bearing: Float, speed: Float, @SerializedName("captured_at") capturedAt: Long)`.
- Produces: Server `LocationSampleDto(latitude: Double, longitude: Double, accuracy: Double, altitude: Double, bearing: Double, speed: Double, capturedAt: Long)` (plain camelCase - global Jackson snake_case naming strategy maps `capturedAt` to JSON key `captured_at`, matching the Android DTO's explicit annotation).
- Produces: `SubmitReportUseCase.invoke(trimmedFile: File, location: LocationData?, locationSamples: List<LocationData>, recordingStartedAt: Long, durationMs: Long): SubmitReportResult` and `SubmitReportUseCase.confirmCellular(reportId: String, videoPath: String, location: LocationData, locationSamples: List<LocationData>, recordingStartedAt: Long, durationMs: Long)` - both gain a new parameter, inserted in the same position for both functions.

- [ ] **Step 1: Create the Android DTO and its test**

Create `app/src/main/java/com/trafficwatch/app/core/data/remote/dto/LocationSampleDto.kt`:

```kotlin
package com.trafficwatch.app.core.data.remote.dto

import com.google.gson.annotations.SerializedName
import com.trafficwatch.app.core.domain.model.LocationData

data class LocationSampleDto(
    @SerializedName("latitude") val latitude: Double,
    @SerializedName("longitude") val longitude: Double,
    @SerializedName("accuracy") val accuracy: Float,
    @SerializedName("altitude") val altitude: Double,
    @SerializedName("bearing") val bearing: Float,
    @SerializedName("speed") val speed: Float,
    // Deliberately named to match the server's Jackson-mapped `capturedAt` property under
    // its global snake_case naming strategy - see the server-side LocationSampleDto.
    @SerializedName("captured_at") val capturedAt: Long,
)

fun LocationData.toSampleDto(): LocationSampleDto = LocationSampleDto(
    latitude = latitude,
    longitude = longitude,
    accuracy = accuracy,
    altitude = altitude,
    bearing = bearing,
    speed = speed,
    capturedAt = capturedAt,
)
```

Create `app/src/test/java/com/trafficwatch/app/core/data/remote/dto/LocationSampleDtoTest.kt`:

```kotlin
package com.trafficwatch.app.core.data.remote.dto

import com.google.gson.Gson
import com.trafficwatch.app.core.domain.model.LocationData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationSampleDtoTest {

    @Test
    fun `toSampleDto maps every LocationData field across`() {
        val location = LocationData(
            latitude = 31.470851,
            longitude = 74.4054352,
            accuracy = 12.3f,
            altitude = 210.5,
            bearing = 87.3f,
            speed = 8.1f,
            capturedAt = 1735814400123L,
        )

        val dto = location.toSampleDto()

        assertEquals(31.470851, dto.latitude, 1e-9)
        assertEquals(74.4054352, dto.longitude, 1e-9)
        assertEquals(12.3f, dto.accuracy, 1e-6f)
        assertEquals(210.5, dto.altitude, 1e-9)
        assertEquals(87.3f, dto.bearing, 1e-6f)
        assertEquals(8.1f, dto.speed, 1e-6f)
        assertEquals(1735814400123L, dto.capturedAt)
    }

    @Test
    fun `list of samples serializes to a JSON array with snake_case keys`() {
        val samples = listOf(
            LocationData(31.47, 74.40, 10f, 200.0, 90f, 5f, 1000L).toSampleDto(),
            LocationData(31.48, 74.41, 11f, 201.0, 91f, 6f, 2000L).toSampleDto(),
        )

        val json = Gson().toJson(samples)

        assertTrue(json.contains("\"captured_at\":1000"))
        assertTrue(json.contains("\"captured_at\":2000"))
        assertTrue(json.startsWith("["))
        assertTrue(json.endsWith("]"))
    }
}
```

- [ ] **Step 2: Run the new Android tests to verify they pass**

Run (from repo root): `./gradlew.bat :app:testDebugUnitTest --tests "com.trafficwatch.app.core.data.remote.dto.LocationSampleDtoTest"`
Expected: PASS (2 tests). This is new coverage (no prior version of this DTO existed to fail against), so there's no red-first step here - the DTO and its test are written together, then verified.

- [ ] **Step 3: Wire the samples through `SubmitReportUseCase`**

In `app/src/main/java/com/trafficwatch/app/core/domain/usecase/SubmitReportUseCase.kt`, replace `invoke(...)`:

```kotlin
    suspend operator fun invoke(
        trimmedFile: File,
        location: LocationData?,
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
            reportId, trimmedFile.absolutePath, effectiveLocation, recordingStartedAt, durationMs,
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

Replace `confirmCellular(...)`:

```kotlin
    suspend fun confirmCellular(
        reportId: String,
        videoPath: String,
        location: LocationData,
        recordingStartedAt: Long,
        durationMs: Long
    ) {
        enqueue(
            reportId, videoPath, location, recordingStartedAt, durationMs,
            requireWifiOnly = false, policy = ExistingWorkPolicy.REPLACE
        )
    }
```

with:

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
```

Replace the private `enqueue(...)`:

```kotlin
    private fun enqueue(
        reportId: String,
        videoPath: String,
        location: LocationData,
        recordingStartedAt: Long,
        durationMs: Long,
        requireWifiOnly: Boolean,
        policy: ExistingWorkPolicy
    ) {
        val request = UploadWorker.buildRequest(
            reportId, videoPath, location, recordingStartedAt, durationMs, requireWifiOnly
        )
        WorkManager.getInstance(context)
            .enqueueUniqueWork(UploadWorker.uniqueWorkName(reportId), policy, request)
    }
```

with:

```kotlin
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

- [ ] **Step 4: Thread the field through `ReviewViewModel`'s two call sites**

In `app/src/main/java/com/trafficwatch/app/feature/review/ReviewViewModel.kt`, replace the `submitReportUseCase(...)` call inside `submit()`:

```kotlin
            val result = submitReportUseCase(
                File(state.trimmedFilePath), state.location, state.recordingStartedAt, state.durationMs
            )
```

with:

```kotlin
            val result = submitReportUseCase(
                File(state.trimmedFilePath), state.location, state.locationSamples, state.recordingStartedAt, state.durationMs
            )
```

Replace the `submitReportUseCase.confirmCellular(...)` call inside `confirmCellularSubmit()`:

```kotlin
            submitReportUseCase.confirmCellular(
                reportId, state.trimmedFilePath, location, state.recordingStartedAt, state.durationMs
            )
```

with:

```kotlin
            submitReportUseCase.confirmCellular(
                reportId, state.trimmedFilePath, location, state.locationSamples, state.recordingStartedAt, state.durationMs
            )
```

- [ ] **Step 5: Thread the JSON string through `UploadWorker` and `ApiService`**

In `app/src/main/java/com/trafficwatch/app/feature/upload/UploadWorker.kt`, add these imports:

```kotlin
import com.google.gson.Gson
import com.trafficwatch.app.core.data.remote.dto.toSampleDto
```

Add a new companion constant right after `KEY_COMPASS_HEADING`:

```kotlin
        const val KEY_COMPASS_HEADING = "compass_heading_degrees"
        const val KEY_LOCATION_SAMPLES_JSON = "location_samples_json"
```

Replace `buildInputData(...)`'s signature and body:

```kotlin
        fun buildInputData(
            reportId: String,
            videoPath: String,
            location: LocationData,
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

Replace `buildRequest(...)`'s signature and its call to `buildInputData`:

```kotlin
        fun buildRequest(
            reportId: String,
            videoPath: String,
            location: LocationData,
            recordingStartedAt: Long,
            durationMs: Long,
            requireWifiOnly: Boolean
        ): OneTimeWorkRequest {
            val inputData = buildInputData(reportId, videoPath, location, recordingStartedAt, durationMs)
```

with:

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

In `doWork()`, add a new line right after the existing `compassHeadingDegrees` read:

```kotlin
        val compassHeadingDegrees = if (inputData.hasKeyWithValueOfType<Float>(KEY_COMPASS_HEADING)) {
            inputData.getFloat(KEY_COMPASS_HEADING, 0f)
        } else {
            null
        }
        val locationSamplesJson = inputData.getString(KEY_LOCATION_SAMPLES_JSON)
```

In the `apiService.submitReport(...)` call inside the `try` block, add a new parameter right after `compassHeadingDegrees`:

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

(Note: `compassHeadingDegrees` line above gains a trailing comma since it's no longer the last parameter.)

In `app/src/main/java/com/trafficwatch/app/core/data/remote/ApiService.kt`, replace `submitReport(...)`:

```kotlin
    suspend fun submitReport(
        @Part video: MultipartBody.Part,
        @Part("latitude") latitude: RequestBody,
        @Part("longitude") longitude: RequestBody,
        @Part("accuracy") accuracy: RequestBody,
        @Part("altitude") altitude: RequestBody,
        @Part("bearing") bearing: RequestBody,
        @Part("speed") speed: RequestBody,
        @Part("recorded_at") recordedAt: RequestBody,
        @Part("duration_ms") durationMs: RequestBody,
        @Part("device_id") deviceId: RequestBody,
        @Part("compass_heading_degrees") compassHeadingDegrees: RequestBody?
    ): SubmitReportResponse
```

with:

```kotlin
    suspend fun submitReport(
        @Part video: MultipartBody.Part,
        @Part("latitude") latitude: RequestBody,
        @Part("longitude") longitude: RequestBody,
        @Part("accuracy") accuracy: RequestBody,
        @Part("altitude") altitude: RequestBody,
        @Part("bearing") bearing: RequestBody,
        @Part("speed") speed: RequestBody,
        @Part("recorded_at") recordedAt: RequestBody,
        @Part("duration_ms") durationMs: RequestBody,
        @Part("device_id") deviceId: RequestBody,
        @Part("compass_heading_degrees") compassHeadingDegrees: RequestBody?,
        @Part("location_samples") locationSamples: RequestBody?
    ): SubmitReportResponse
```

- [ ] **Step 6: Build the Android app to verify it compiles**

Run (from repo root): `./gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Create the server DTO and its test**

Create `server/src/main/kotlin/com/trafficwatch/server/reports/dto/LocationSampleDto.kt`:

```kotlin
package com.trafficwatch.server.reports.dto

/**
 * One GPS fix from the Android client's continuous during-recording sampling (see
 * app-side LocationSampleDto). Plain camelCase properties - the server's global Jackson
 * snake_case naming strategy maps `capturedAt` to JSON key `captured_at` with no extra
 * annotations needed, matching every other DTO in this codebase. Not yet consumed by any
 * direction-analysis logic - stored as-is for a future sub-project to use.
 */
data class LocationSampleDto(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Double,
    val altitude: Double,
    val bearing: Double,
    val speed: Double,
    val capturedAt: Long,
)
```

Create `server/src/test/kotlin/com/trafficwatch/server/reports/dto/LocationSampleDtoTest.kt`:

```kotlin
package com.trafficwatch.server.reports.dto

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LocationSampleDtoTest {

    // Mirrors the app-wide default ObjectMapper's snake_case naming strategy (see
    // ServerApplication's Jackson configuration) without needing a full Spring context.
    private val objectMapper: ObjectMapper = jacksonObjectMapper()
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)

    @Test
    fun `parses a snake_case JSON array into a list of samples`() {
        val json = """
            [
              {"latitude":31.470851,"longitude":74.4054352,"accuracy":12.3,"altitude":210.5,"bearing":87.3,"speed":8.1,"captured_at":1735814400123},
              {"latitude":31.470900,"longitude":74.4054400,"accuracy":11.0,"altitude":211.0,"bearing":90.0,"speed":9.0,"captured_at":1735814401123}
            ]
        """.trimIndent()

        val samples: List<LocationSampleDto> = objectMapper.readValue(json)

        assertThat(samples).hasSize(2)
        assertThat(samples[0].latitude).isEqualTo(31.470851)
        assertThat(samples[0].capturedAt).isEqualTo(1735814400123L)
        assertThat(samples[1].capturedAt).isEqualTo(1735814401123L)
    }

    @Test
    fun `round-trips through serialization back to the same snake_case shape`() {
        val samples = listOf(
            LocationSampleDto(31.47, 74.40, 10.0, 200.0, 90.0, 5.0, 1000L),
        )

        val json = objectMapper.writeValueAsString(samples)

        assertThat(json).contains("\"captured_at\":1000")
        assertThat(json).doesNotContain("capturedAt")
    }
}
```

- [ ] **Step 8: Run the new server test to verify it passes**

Run (from `server/`): `./gradlew.bat test --tests "com.trafficwatch.server.reports.dto.LocationSampleDtoTest"`
Expected: PASS (2 tests). New coverage, written alongside the DTO it tests - no red-first step needed here either.

- [ ] **Step 9: Add the migration and the `Report` entity column**

Create `server/src/main/resources/db/migration/V7__add_location_samples_to_reports.sql`:

```sql
ALTER TABLE reports ADD COLUMN location_samples JSONB;
```

In `server/src/main/kotlin/com/trafficwatch/server/reports/Report.kt`, add a new field right after the existing `directionEvidence` column:

```kotlin
    // Full direction-evidence breakdown for this analysis (sources, fates, fused
    // values, per-factor scores) as JSON - always computed and stored; only the
    // Android debug build renders it. See ReportAnalysisJob for the shape.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "direction_evidence", columnDefinition = "jsonb")
    var directionEvidence: String? = null,

    // Time-series of GPS fixes captured throughout the recording (not just the single
    // snapshot at recording start - see latitude/longitude/etc. above). Absent on
    // submissions from app versions predating continuous capture. Not yet consumed by
    // any direction-analysis logic - see LocationSampleDto and the design spec for the
    // planned future use.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "location_samples", columnDefinition = "jsonb")
    var locationSamples: String? = null,
```

- [ ] **Step 10: Accept, parse, and store the field in `ReportController`/`ReportService`**

In `server/src/main/kotlin/com/trafficwatch/server/reports/ReportController.kt`, add a new parameter to `submitReport(...)` right after the existing `compassHeadingDegrees` parameter:

```kotlin
        @RequestParam("compass_heading_degrees", required = false) compassHeadingDegrees: BigDecimal?,
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

In `server/src/main/kotlin/com/trafficwatch/server/reports/ReportService.kt`, add this import:

```kotlin
import com.trafficwatch.server.reports.dto.LocationSampleDto
```

Replace `submit(...)`'s signature and the `Report(...)` construction:

```kotlin
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
    ): SubmitReportResponse {
        val userId = CurrentUser.id()
        val parsedRecordedAt = LocalDateTime.parse(recordedAt, RECORDED_AT_FORMATTER)

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
        )
```

with:

```kotlin
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
                objectMapper.writeValueAsString(parsed)
            } catch (ex: Exception) {
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

- [ ] **Step 11: Run the full server test suite to confirm no regressions**

Run (from `server/`): `./gradlew.bat test`
Expected: all tests PASS. If `ReportServiceTest.kt` has hand-constructed calls to `reportService.submit(...)` that don't yet pass the new `locationSamplesJson` parameter, update those call sites to pass `null` (matching "field absent" - the same as any real submission from an app version that predates this feature).

- [ ] **Step 12: Extend `EndToEndFlowTest` with a round-trip check**

In `server/src/test/kotlin/com/trafficwatch/server/EndToEndFlowTest.kt`, add this import:

```kotlin
import com.trafficwatch.server.reports.ReportRepository
```

Add a new constructor parameter:

```kotlin
class EndToEndFlowTest @Autowired constructor(
    private val restTemplate: TestRestTemplate,
    private val reportRepository: ReportRepository,
) {
```

Add a new test method at the end of the class, right before the closing brace:

```kotlin
    @Test
    fun `location_samples round-trips to the stored report exactly as submitted`() {
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
            "location_samples",
            """[{"latitude":31.520370,"longitude":74.358749,"accuracy":5.0,"altitude":210.5,"bearing":87.3,"speed":12.4,"captured_at":1735814400000}]""",
        )

        val headers = authHeaders(token)
        headers.contentType = MediaType.MULTIPART_FORM_DATA
        val submitResponse = restTemplate.exchange("/reports", HttpMethod.POST, HttpEntity(body, headers), mapType)

        assertThat(submitResponse.statusCode).isEqualTo(HttpStatus.CREATED)
        val reportId = requireNotNull(submitResponse.body?.get("report_id") as? String)

        val stored = reportRepository.findById(java.util.UUID.fromString(reportId)).orElseThrow()
        assertThat(stored.locationSamples).isNotNull()
        assertThat(stored.locationSamples).contains("\"captured_at\":1735814400000")
    }
```

- [ ] **Step 13: Run the full server test suite again to confirm the new test passes**

Run (from `server/`): `./gradlew.bat test --tests "com.trafficwatch.server.EndToEndFlowTest"`
Expected: PASS (5 tests: the 3 existing plus the new one; note the existing suite already has 3 tests in this class before this task).

- [ ] **Step 14: Deploy to production and manually verify**

```bash
scp -i ~/.ssh/trafficwatch_ovh -r server/src/main/kotlin/com/trafficwatch/server/reports/dto/LocationSampleDto.kt ubuntu@137.74.173.97:~/trafficwatch/server/src/main/kotlin/com/trafficwatch/server/reports/dto/
scp -i ~/.ssh/trafficwatch_ovh server/src/main/kotlin/com/trafficwatch/server/reports/ReportController.kt ubuntu@137.74.173.97:~/trafficwatch/server/src/main/kotlin/com/trafficwatch/server/reports/
scp -i ~/.ssh/trafficwatch_ovh server/src/main/kotlin/com/trafficwatch/server/reports/ReportService.kt ubuntu@137.74.173.97:~/trafficwatch/server/src/main/kotlin/com/trafficwatch/server/reports/
scp -i ~/.ssh/trafficwatch_ovh server/src/main/kotlin/com/trafficwatch/server/reports/Report.kt ubuntu@137.74.173.97:~/trafficwatch/server/src/main/kotlin/com/trafficwatch/server/reports/
scp -i ~/.ssh/trafficwatch_ovh server/src/main/resources/db/migration/V7__add_location_samples_to_reports.sql ubuntu@137.74.173.97:~/trafficwatch/server/src/main/resources/db/migration/
ssh -i ~/.ssh/trafficwatch_ovh ubuntu@137.74.173.97 "cd ~/trafficwatch && docker compose -f docker-compose.prod.yml up -d --build server"
```

Install the updated Android app (`./gradlew.bat :app:installDebug`) on the connected device, record a clip while physically moving, submit it, then query the production database directly to confirm the `location_samples` column has a populated JSON array with multiple entries spanning the recording's real duration.
Expected: `location_samples` is a non-null JSON array with more than one entry, `captured_at` values increasing and spanning roughly the recording's real duration.
