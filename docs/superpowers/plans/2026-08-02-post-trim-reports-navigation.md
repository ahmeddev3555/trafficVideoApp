# Post-Trim Submit Navigation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Tapping "Submit Report" on the Review screen navigates straight back to the Reports list (instead of through a dedicated Upload screen), where the new report is immediately visible with an "Uploading" status.

**Architecture:** A new `SubmitReportUseCase` (mirroring the existing `RetryUploadUseCase`) creates the report row and enqueues the upload; `ReviewViewModel` orchestrates it and the cellular-data confirmation dialog; `ReviewScreen`/`AppNavigation` are rewired to navigate to Reports on completion instead of to a dedicated Upload screen, which is deleted.

**Tech Stack:** Jetpack Compose, Hilt, WorkManager, Kotlin Coroutines/Flow, MockK + Turbine (existing test deps).

## Global Constraints

- `UploadScreen.kt` and `UploadViewModel.kt` are deleted entirely, along with the `UPLOAD` route in `AppNavigation.kt`. `UploadWorker.kt` is NOT touched — both submit and retry use it unchanged.
- The Wi-Fi-only upload is enqueued immediately and unconditionally when Submit is tapped, before the cellular dialog is even shown — the report must never be lost if the user ignores or dismisses the dialog.
- The cellular-data confirmation dialog appears on the Review screen (reusing the existing `CellularConfirmDialog` composable), not on the Reports screen.
- No in-flight cancel affordance is added anywhere.
- No progress percentage is added to the Reports list row — only the existing "Uploading" status chip (already implemented, no changes needed to `HistoryScreen.kt`/`HistoryViewModel.kt`).
- No toast/snackbar on submit.
- `SubmitReportUseCase` cannot be unit-tested end-to-end: its `WorkManager.getInstance(context)` call requires Android instrumentation/Robolectric, which this project has no dependency on (confirmed: no `robolectric`/`work-testing` entries anywhere in `app/build.gradle.kts`, and the existing `RetryUploadUseCase` - which has the identical limitation - ships with zero tests today). `ReviewViewModelTest` covers the orchestration logic by mocking `SubmitReportUseCase` at its interface boundary; `SubmitReportUseCase` itself is verified by the build + Task 2's manual end-to-end check, matching the precedent already set by `RetryUploadUseCase`.

---

### Task 1: `SubmitReportUseCase` + `ReviewViewModel` orchestration

**Files:**
- Create: `app/src/main/java/com/trafficwatch/app/core/domain/usecase/SubmitReportUseCase.kt`
- Modify: `app/src/main/java/com/trafficwatch/app/feature/review/ReviewViewModel.kt`
- Test: `app/src/test/java/com/trafficwatch/app/feature/review/ReviewViewModelTest.kt`

**Interfaces:**
- Produces: `SubmitReportResult(reportId: String, effectiveLocation: LocationData, onWifi: Boolean)` — a plain data class.
- Produces: `SubmitReportUseCase` with `suspend operator fun invoke(trimmedFile: File, location: LocationData?, recordingStartedAt: Long, durationMs: Long): SubmitReportResult` and `suspend fun confirmCellular(reportId: String, videoPath: String, location: LocationData, recordingStartedAt: Long, durationMs: Long)`. Both are `@Inject constructor`-based (like `RetryUploadUseCase`), so Hilt provides them with no separate `@Provides` module needed.
- Produces: `ReviewViewModel` gains `val submitted: Flow<Unit>` (one-shot event, collected exactly once per emission by `ReviewScreen` in Task 2) and `ReviewUiState.showCellularPrompt: Boolean`; new functions `submit()`, `confirmCellularSubmit()`, `dismissCellularPrompt()`.
- Consumes (existing, unchanged): `ReportRepository.saveReport(report: Report)`, `NetworkMonitor.isOnWifi(): Boolean`, `UploadWorker.buildRequest(reportId, videoPath, location, recordingStartedAt, durationMs, requireWifiOnly): OneTimeWorkRequest`, `UploadWorker.uniqueWorkName(reportId): String`.

- [ ] **Step 1: Create `SubmitReportUseCase`**

```kotlin
package com.trafficwatch.app.core.domain.usecase

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import com.trafficwatch.app.core.data.repository.ReportRepository
import com.trafficwatch.app.core.domain.model.LocationData
import com.trafficwatch.app.core.domain.model.Report
import com.trafficwatch.app.core.domain.model.ReportStatus
import com.trafficwatch.app.core.util.NetworkMonitor
import com.trafficwatch.app.feature.upload.UploadWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [effectiveLocation] is the location actually persisted on the [Report] row (falls back to
 * a zeroed [LocationData] when the caller has none) - callers re-enqueueing later via
 * [SubmitReportUseCase.confirmCellular] must reuse this value rather than recompute the
 * fallback themselves.
 */
data class SubmitReportResult(
    val reportId: String,
    val effectiveLocation: LocationData,
    val onWifi: Boolean
)

/**
 * Creates a brand-new [Report] row (status [ReportStatus.UPLOADING]) and enqueues its upload,
 * always Wi-Fi-only first - so the report is never lost even if the caller never resolves the
 * cellular-data prompt this triggers when off Wi-Fi (see [confirmCellular]).
 */
@Singleton
class SubmitReportUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val reportRepository: ReportRepository,
    private val networkMonitor: NetworkMonitor
) {
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

    /**
     * User explicitly confirmed uploading [reportId] over cellular data. The report is
     * already [ReportStatus.UPLOADING] from [invoke], so unlike [com.trafficwatch.app.core.domain.usecase.RetryUploadUseCase]
     * there is no status to re-set here - just a re-enqueue with a relaxed network constraint.
     */
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
}
```

- [ ] **Step 2: Write the failing `ReviewViewModelTest`**

```kotlin
package com.trafficwatch.app.feature.review

import app.cash.turbine.test
import com.trafficwatch.app.core.domain.model.LocationData
import com.trafficwatch.app.core.domain.usecase.SubmitReportResult
import com.trafficwatch.app.core.domain.usecase.SubmitReportUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val submitReportUseCase = mockk<SubmitReportUseCase>()
    private lateinit var viewModel: ReviewViewModel

    private val location = LocationData(31.5, 74.3, 5f, 200.0, 90f, 10f, 1000L)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        viewModel = ReviewViewModel(submitReportUseCase)
        viewModel.init(File("/tmp/clip.mp4"), location, 1000L, 8000L)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `submit on wifi fires submitted without showing cellular prompt`() = runTest {
        coEvery { submitReportUseCase(any(), any(), any(), any()) } returns
            SubmitReportResult(reportId = "r1", effectiveLocation = location, onWifi = true)

        viewModel.submit()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.submitted.test { awaitItem() }
        assertFalse(viewModel.uiState.value.showCellularPrompt)
    }

    @Test
    fun `submit off wifi shows cellular prompt without firing submitted yet`() = runTest {
        coEvery { submitReportUseCase(any(), any(), any(), any()) } returns
            SubmitReportResult(reportId = "r1", effectiveLocation = location, onWifi = false)

        viewModel.submit()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.showCellularPrompt)
    }

    @Test
    fun `confirmCellularSubmit re-enqueues over cellular, clears prompt, and fires submitted`() = runTest {
        coEvery { submitReportUseCase(any(), any(), any(), any()) } returns
            SubmitReportResult(reportId = "r1", effectiveLocation = location, onWifi = false)
        coEvery {
            submitReportUseCase.confirmCellular(any(), any(), any(), any(), any())
        } just runs

        viewModel.submit()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.confirmCellularSubmit()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.submitted.test { awaitItem() }
        coVerify(exactly = 1) {
            submitReportUseCase.confirmCellular("r1", "/tmp/clip.mp4", location, 1000L, 8000L)
        }
        assertFalse(viewModel.uiState.value.showCellularPrompt)
    }

    @Test
    fun `dismissCellularPrompt clears prompt and fires submitted without re-enqueuing`() = runTest {
        coEvery { submitReportUseCase(any(), any(), any(), any()) } returns
            SubmitReportResult(reportId = "r1", effectiveLocation = location, onWifi = false)

        viewModel.submit()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.dismissCellularPrompt()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.submitted.test { awaitItem() }
        coVerify(exactly = 0) {
            submitReportUseCase.confirmCellular(any(), any(), any(), any(), any())
        }
        assertFalse(viewModel.uiState.value.showCellularPrompt)
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.trafficwatch.app.feature.review.ReviewViewModelTest"`
Expected: FAIL to compile — `ReviewViewModel` doesn't yet have a `submitted` property, `submit()`, `confirmCellularSubmit()`, or `dismissCellularPrompt()`, and its constructor doesn't take a `SubmitReportUseCase`.

- [ ] **Step 4: Rewrite `ReviewViewModel`**

Replace the full contents of `app/src/main/java/com/trafficwatch/app/feature/review/ReviewViewModel.kt` with:

```kotlin
package com.trafficwatch.app.feature.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trafficwatch.app.core.domain.model.LocationData
import com.trafficwatch.app.core.domain.usecase.SubmitReportUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class ReviewUiState(
    val trimmedFilePath: String = "",
    val location: LocationData? = null,
    val recordingStartedAt: Long = 0L,
    val durationMs: Long = 0L,
    val fileSizeBytes: Long = 0L,
    val showCellularPrompt: Boolean = false
)

@HiltViewModel
class ReviewViewModel @Inject constructor(
    private val submitReportUseCase: SubmitReportUseCase
) : ViewModel() {
    private val _uiState = MutableStateFlow(ReviewUiState())
    val uiState = _uiState.asStateFlow()

    // Buffered (not conflated) so a send that happens before the collector attaches - e.g.
    // ReviewScreen's LaunchedEffect racing the coroutine launched by submit() - is never
    // dropped; ReviewScreen only ever calls submit() once per screen instance, so at most
    // one item is ever buffered in practice.
    private val _submitted = Channel<Unit>(Channel.BUFFERED)
    val submitted: Flow<Unit> = _submitted.receiveAsFlow()

    private var lastReportId: String? = null
    private var lastEffectiveLocation: LocationData? = null

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

    fun submit() {
        val state = _uiState.value
        viewModelScope.launch {
            val result = submitReportUseCase(
                File(state.trimmedFilePath), state.location, state.recordingStartedAt, state.durationMs
            )
            lastReportId = result.reportId
            lastEffectiveLocation = result.effectiveLocation
            if (result.onWifi) {
                _submitted.send(Unit)
            } else {
                _uiState.update { it.copy(showCellularPrompt = true) }
            }
        }
    }

    /** User explicitly confirmed uploading over cellular data for the current submission. */
    fun confirmCellularSubmit() {
        val reportId = lastReportId ?: return
        val location = lastEffectiveLocation ?: return
        val state = _uiState.value
        viewModelScope.launch {
            submitReportUseCase.confirmCellular(
                reportId, state.trimmedFilePath, location, state.recordingStartedAt, state.durationMs
            )
            _uiState.update { it.copy(showCellularPrompt = false) }
            _submitted.send(Unit)
        }
    }

    /** User dismissed the cellular prompt - the Wi-Fi-only enqueue from submit() already stands. */
    fun dismissCellularPrompt() {
        _uiState.update { it.copy(showCellularPrompt = false) }
        viewModelScope.launch { _submitted.send(Unit) }
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.trafficwatch.app.feature.review.ReviewViewModelTest"`
Expected: PASS (4 tests).

- [ ] **Step 6: Build to catch any other compile break**

Run: `./gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. `ReviewScreen.kt` obtains `ReviewViewModel` via `hiltViewModel()`, so Hilt resolves the new `SubmitReportUseCase` constructor dependency automatically - this step just confirms nothing elsewhere in the module constructs `ReviewViewModel` directly (bypassing Hilt) in a way this change would break.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/trafficwatch/app/core/domain/usecase/SubmitReportUseCase.kt app/src/main/java/com/trafficwatch/app/feature/review/ReviewViewModel.kt app/src/test/java/com/trafficwatch/app/feature/review/ReviewViewModelTest.kt
git commit -m "feat(app): add SubmitReportUseCase and wire submit orchestration into ReviewViewModel"
```

---

### Task 2: Wire `ReviewScreen`/`AppNavigation` to Reports, delete `UploadScreen`/`UploadViewModel`

**Files:**
- Modify: `app/src/main/java/com/trafficwatch/app/feature/review/ReviewScreen.kt`
- Modify: `app/src/main/java/com/trafficwatch/app/navigation/AppNavigation.kt`
- Delete: `app/src/main/java/com/trafficwatch/app/feature/upload/UploadScreen.kt`
- Delete: `app/src/main/java/com/trafficwatch/app/feature/upload/UploadViewModel.kt`

**Interfaces:**
- Consumes (from Task 1): `ReviewViewModel.uiState.showCellularPrompt: Boolean`, `ReviewViewModel.submitted: Flow<Unit>`, `ReviewViewModel::submit`, `ReviewViewModel::confirmCellularSubmit`, `ReviewViewModel::dismissCellularPrompt`.
- Consumes (existing, unchanged): `com.trafficwatch.app.core.ui.components.CellularConfirmDialog(onConfirm, onDismiss)`.
- No new interfaces produced — this task is the final UI/navigation wiring, nothing later depends on it.

- [ ] **Step 1: Update `ReviewScreen.kt`**

`ReviewScreen.kt` already imports `LaunchedEffect` (used for the existing `viewModel.init(...)` effect) - only one new import is needed, added alongside the existing ones (after the `androidx.hilt.navigation.compose.hiltViewModel` import):

```kotlin
import com.trafficwatch.app.core.ui.components.CellularConfirmDialog
```

Replace the `Button(onClick = onSubmit, ...)` block:

```kotlin
                Button(
                    onClick = onSubmit,
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) { Text("Submit Report") }
```

with:

```kotlin
                Button(
                    onClick = viewModel::submit,
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) { Text("Submit Report") }
```

Add the cellular dialog and the submitted-event listener right after the existing `DisposableEffect(Unit) { onDispose { exoPlayer.release() } }` line:

```kotlin
    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }

    LaunchedEffect(Unit) {
        viewModel.submitted.collect { onSubmit() }
    }

    if (uiState.showCellularPrompt) {
        CellularConfirmDialog(
            onConfirm = viewModel::confirmCellularSubmit,
            onDismiss = viewModel::dismissCellularPrompt
        )
    }
```

- [ ] **Step 2: Update `AppNavigation.kt`**

Remove the now-dead `trimDurationMs` state variable — it exists today only to
carry the trimmed video's duration from the `REVIEW` composable into the
`UPLOAD` composable (`durationMs = trimDurationMs`); once `UPLOAD` is
deleted, nothing reads it anymore (`REVIEW` already passes its local
`duration` value directly to `ReviewScreen`). Remove this line from the
variable declarations near the top of `AppNavigation`:

```kotlin
    var trimDurationMs by rememberSaveable { mutableLongStateOf(0L) }
```

(Leave `mutableLongStateOf` imported — `recordingStartedAt` still uses it.)

Remove the `UPLOAD` route constant. Change:

```kotlin
private object Routes {
    const val LOGIN = "login"
    const val REGISTER = "register"
    const val PERMISSIONS = "permissions"
    const val CAMERA = "camera"
    const val TRIM = "trim"
    const val REVIEW = "review"
    const val UPLOAD = "upload"
    const val HISTORY = "history"
    const val REPORT_DETAIL = "report_detail/{reportId}"
    fun reportDetail(id: String) = "report_detail/$id"
}
```

to:

```kotlin
private object Routes {
    const val LOGIN = "login"
    const val REGISTER = "register"
    const val PERMISSIONS = "permissions"
    const val CAMERA = "camera"
    const val TRIM = "trim"
    const val REVIEW = "review"
    const val HISTORY = "history"
    const val REPORT_DETAIL = "report_detail/{reportId}"
    fun reportDetail(id: String) = "report_detail/$id"
}
```

Remove the now-unused import:

```kotlin
import com.trafficwatch.app.feature.upload.UploadScreen
```

Replace the `REVIEW` composable block:

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
            trimDurationMs = duration

            ReviewScreen(
                trimmedFile = File(trimmed),
                location = snapshotLocation,
                recordingStartedAt = recordingStartedAt,
                durationMs = duration,
                onSubmit = { navController.navigate(Routes.UPLOAD) },
                onRetrim = { navController.popBackStack() },
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Routes.UPLOAD) {
            val trimmed = trimmedVideoFile ?: return@composable
            UploadScreen(
                trimmedFile = File(trimmed),
                location = snapshotLocation,
                recordingStartedAt = recordingStartedAt,
                durationMs = trimDurationMs,
                onUploadSuccess = {
                    rawVideoFile = null
                    trimmedVideoFile = null
                    snapshotLocation = null
                    navController.navigate(Routes.HISTORY) {
                        popUpTo(Routes.HISTORY) { inclusive = true }
                    }
                },
                onRetry = {
                    navController.navigate(Routes.UPLOAD) {
                        popUpTo(Routes.UPLOAD) { inclusive = true }
                    }
                }
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

            ReviewScreen(
                trimmedFile = File(trimmed),
                location = snapshotLocation,
                recordingStartedAt = recordingStartedAt,
                durationMs = duration,
                onSubmit = {
                    rawVideoFile = null
                    trimmedVideoFile = null
                    snapshotLocation = null
                    navController.navigate(Routes.HISTORY) {
                        popUpTo(Routes.HISTORY) { inclusive = true }
                    }
                },
                onRetrim = { navController.popBackStack() },
                onNavigateBack = { navController.popBackStack() }
            )
        }
```

(Note the `trimDurationMs = duration` line from the original block is dropped
here too, along with the variable declaration above — `duration` is passed
straight to `ReviewScreen` already.)

- [ ] **Step 3: Delete the Upload screen files**

```bash
git rm app/src/main/java/com/trafficwatch/app/feature/upload/UploadScreen.kt app/src/main/java/com/trafficwatch/app/feature/upload/UploadViewModel.kt
```

- [ ] **Step 4: Build to verify it compiles**

Run: `./gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Run the full existing test suite to confirm nothing else broke**

Run: `./gradlew.bat :app:testDebugUnitTest`
Expected: all tests PASS (existing suite plus the 4 new `ReviewViewModelTest` cases from Task 1).

- [ ] **Step 6: Manual verification**

Install on the connected device (`./gradlew.bat :app:installDebug`), record → trim → review → tap "Submit Report". Confirm: the app lands directly on the Reports screen (not an intermediate Upload screen), the new report is visible at the top of the list with an "Uploading" chip, and it flips to "Pending" shortly after (the real upload completing over Wi-Fi). If the device is off Wi-Fi at submit time, confirm the cellular dialog appears on the Review screen before the Reports screen is shown, and that both "Upload Anyway" and "Wait for Wi-Fi" correctly navigate to Reports afterward.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/trafficwatch/app/feature/review/ReviewScreen.kt app/src/main/java/com/trafficwatch/app/navigation/AppNavigation.kt
git commit -m "feat(app): navigate straight to Reports on submit, remove UploadScreen"
```
