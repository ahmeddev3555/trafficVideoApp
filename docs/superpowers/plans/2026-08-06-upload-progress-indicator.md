# Upload Progress Indicator Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Reports screen's static "Uploading" label with a live progress bar and "X MB / Y MB · Z MB/s" text, sourced from real byte-level upload progress.

**Architecture:** `FileUtil.asStreamingRequestBody()` streams in fixed-size chunks and calls an optional per-chunk callback (no timing logic of its own). A new pure `UploadProgressTracker` class (clock passed in, not read internally) owns throttling and transfer-rate math, fully unit-testable with synthetic timestamps. `UploadWorker` wires the two together and reports `KEY_BYTES_UPLOADED`/`KEY_TOTAL_BYTES`/`KEY_BYTES_PER_SECOND` via `setProgressAsync`. `HistoryViewModel` gains a constructor-injected `WorkManager` and a derived `uploadProgress: StateFlow<Map<String, UploadProgress>>` that reactively follows each `UPLOADING` report's live `WorkInfo`. `HistoryScreen`'s `ReportCard` renders the bar/text when progress data is available, falling back to today's plain chip otherwise.

**Tech Stack:** Kotlin, Jetpack Compose, WorkManager 2.9.0, OkHttp/Okio, Hilt, MockK + Turbine + kotlinx-coroutines-test (existing Android app - no new dependencies).

## Global Constraints

- Progress is shown **only on the Reports list** (`HistoryScreen`'s `ReportCard`) - not on `ReportDetailScreen`. Confirmed, deliberate scope limit.
- No new database columns - progress is transient WorkManager state only, never persisted.
- `System.currentTimeMillis()`, never `android.os.SystemClock.elapsedRealtime()` - this codebase has no Robolectric and no `testOptions.unitTests.isReturnDefaultValues` in `app/build.gradle.kts`, so `android.*` framework calls throw `RuntimeException: Method ... not mocked` in local JUnit tests.
- `UploadProgressTracker` takes the current time as a parameter (`nowMs: Long`) rather than reading a clock itself - this is what makes it directly unit-testable with synthetic timestamps and is a hard requirement of the design, not a style preference.
- `UploadWorker.doWork()` itself gets no new automated test - this repo has no `androidx.work:work-testing` and no Robolectric, and `WorkerParameters` is not cleanly mockable (see Task 2). Do not add `work-testing` or Robolectric as new dependencies to work around this - it is out of scope for this plan.
- 64KB chunk size (`UPLOAD_CHUNK_SIZE_BYTES = 64 * 1024L`), 300ms throttle window (`PROGRESS_EMIT_INTERVAL_MS = 300L`) - exact values, not tunable placeholders.
- `HistoryScreen`'s `ReportCard` obtains `FileUtil` by direct instantiation (`FileUtil(LocalContext.current)`), matching the existing precedent at `ReviewScreen.kt:141` (`FileUtil(context).formatFileSize(...)`) - not via Hilt injection or ViewModel-formatted strings.

---

### Task 1: Chunked streaming + pure progress tracker

**Files:**
- Modify: `app/src/main/java/com/trafficwatch/app/core/util/FileUtil.kt`
- Create: `app/src/main/java/com/trafficwatch/app/core/util/UploadProgressTracker.kt`
- Test: `app/src/test/java/com/trafficwatch/app/core/util/FileUtilTest.kt`
- Test: `app/src/test/java/com/trafficwatch/app/core/util/UploadProgressTrackerTest.kt`

**Interfaces:**
- Produces: `File.asStreamingRequestBody(mediaType: MediaType = "video/mp4".toMediaType(), onProgress: ((bytesWritten: Long, totalBytes: Long) -> Unit)? = null): RequestBody` (extends the existing signature with a new optional trailing param - all existing call sites keep compiling unchanged).
- Produces: `UploadProgressSnapshot(percent: Int, bytesUploaded: Long, totalBytes: Long, bytesPerSecond: Long)` (data class).
- Produces: `UploadProgressTracker(totalBytes: Long)` with `fun onChunk(bytesWritten: Long, nowMs: Long): UploadProgressSnapshot?`.

- [ ] **Step 1: Write the failing `UploadProgressTracker` tests**

Create `app/src/test/java/com/trafficwatch/app/core/util/UploadProgressTrackerTest.kt`:

```kotlin
package com.trafficwatch.app.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UploadProgressTrackerTest {

    @Test
    fun `first chunk always emits with zero rate`() {
        val tracker = UploadProgressTracker(totalBytes = 1000L)

        val result = tracker.onChunk(bytesWritten = 100L, nowMs = 5000L)

        assertEquals(UploadProgressSnapshot(percent = 10, bytesUploaded = 100L, totalBytes = 1000L, bytesPerSecond = 0L), result)
    }

    @Test
    fun `chunk within throttle window and not final returns null`() {
        val tracker = UploadProgressTracker(totalBytes = 1000L)
        tracker.onChunk(bytesWritten = 100L, nowMs = 5000L)

        val result = tracker.onChunk(bytesWritten = 150L, nowMs = 5200L)

        assertNull(result)
    }

    @Test
    fun `chunk at or past throttle window emits with correct rate`() {
        val tracker = UploadProgressTracker(totalBytes = 1000L)
        tracker.onChunk(bytesWritten = 100L, nowMs = 5000L)

        val result = tracker.onChunk(bytesWritten = 500L, nowMs = 5400L)

        // (500 - 100) bytes over 400ms = 1000 bytes/sec
        assertEquals(UploadProgressSnapshot(percent = 50, bytesUploaded = 500L, totalBytes = 1000L, bytesPerSecond = 1000L), result)
    }

    @Test
    fun `final chunk always emits regardless of throttle window`() {
        val tracker = UploadProgressTracker(totalBytes = 1000L)
        tracker.onChunk(bytesWritten = 100L, nowMs = 5000L)

        val result = tracker.onChunk(bytesWritten = 1000L, nowMs = 5050L)

        // (1000 - 100) bytes over 50ms = 18000 bytes/sec
        assertEquals(UploadProgressSnapshot(percent = 100, bytesUploaded = 1000L, totalBytes = 1000L, bytesPerSecond = 18000L), result)
    }

    @Test
    fun `zero total bytes does not divide by zero`() {
        val tracker = UploadProgressTracker(totalBytes = 0L)

        val result = tracker.onChunk(bytesWritten = 0L, nowMs = 1000L)

        assertEquals(UploadProgressSnapshot(percent = 0, bytesUploaded = 0L, totalBytes = 0L, bytesPerSecond = 0L), result)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.trafficwatch.app.core.util.UploadProgressTrackerTest"`
Expected: FAIL - `UploadProgressTracker`/`UploadProgressSnapshot` do not exist yet (compile error).

- [ ] **Step 3: Create `UploadProgressTracker.kt`**

Create `app/src/main/java/com/trafficwatch/app/core/util/UploadProgressTracker.kt`:

```kotlin
package com.trafficwatch.app.core.util

private const val PROGRESS_EMIT_INTERVAL_MS = 300L

data class UploadProgressSnapshot(
    val percent: Int,
    val bytesUploaded: Long,
    val totalBytes: Long,
    val bytesPerSecond: Long
)

/**
 * Pure progress/throttle tracker for a single upload - takes the current time as a
 * parameter rather than reading a clock itself, so it can be unit-tested with synthetic
 * timestamps and no Android framework stubbing. Not thread-safe - used from a single
 * OkHttp write callback per upload.
 */
class UploadProgressTracker(private val totalBytes: Long) {
    private var lastEmittedBytes = 0L
    private var lastEmittedAtMs = 0L
    private var hasEmitted = false

    /** Returns a snapshot to report, or null if this chunk should be throttled (not the final one). */
    fun onChunk(bytesWritten: Long, nowMs: Long): UploadProgressSnapshot? {
        val isFinal = bytesWritten >= totalBytes
        if (hasEmitted && !isFinal && nowMs - lastEmittedAtMs < PROGRESS_EMIT_INTERVAL_MS) return null

        val bytesPerSecond = if (hasEmitted) {
            val elapsedMs = (nowMs - lastEmittedAtMs).coerceAtLeast(1)
            ((bytesWritten - lastEmittedBytes) * 1000L) / elapsedMs
        } else {
            0L
        }
        lastEmittedBytes = bytesWritten
        lastEmittedAtMs = nowMs
        hasEmitted = true

        val percent = if (totalBytes > 0) ((bytesWritten * 100) / totalBytes).toInt() else 0
        return UploadProgressSnapshot(percent, bytesWritten, totalBytes, bytesPerSecond)
    }
}
```

- [ ] **Step 4: Run the `UploadProgressTracker` tests to verify they pass**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.trafficwatch.app.core.util.UploadProgressTrackerTest"`
Expected: PASS (5/5)

- [ ] **Step 5: Write the failing `FileUtil` chunking test**

Create `app/src/test/java/com/trafficwatch/app/core/util/FileUtilTest.kt`:

```kotlin
package com.trafficwatch.app.core.util

import okhttp3.MediaType.Companion.toMediaType
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FileUtilTest {

    @Test
    fun `asStreamingRequestBody reports monotonically increasing progress ending at file size`() {
        val fileSize = 132_072L // just over two 64KB chunks
        val tempFile = File.createTempFile("upload_test", ".mp4")
        tempFile.deleteOnExit()
        tempFile.writeBytes(ByteArray(fileSize.toInt()) { it.toByte() })

        val progressCalls = mutableListOf<Pair<Long, Long>>()
        val requestBody = tempFile.asStreamingRequestBody(
            mediaType = "video/mp4".toMediaType(),
            onProgress = { written, total -> progressCalls.add(written to total) }
        )

        requestBody.writeTo(Buffer())

        assertTrue("expected at least one progress call", progressCalls.isNotEmpty())
        assertEquals(fileSize to fileSize, progressCalls.last())
        assertTrue(
            "expected bytesWritten to be non-decreasing",
            progressCalls.map { it.first } == progressCalls.map { it.first }.sorted()
        )
        assertTrue(
            "expected every call to report the same total",
            progressCalls.all { it.second == fileSize }
        )

        tempFile.delete()
    }

    @Test
    fun `asStreamingRequestBody with no callback still writes the full file`() {
        val fileSize = 10_000L
        val tempFile = File.createTempFile("upload_test_no_callback", ".mp4")
        tempFile.deleteOnExit()
        tempFile.writeBytes(ByteArray(fileSize.toInt()) { it.toByte() })

        val requestBody = tempFile.asStreamingRequestBody(mediaType = "video/mp4".toMediaType())
        val sink = Buffer()
        requestBody.writeTo(sink)

        assertEquals(fileSize, sink.size)

        tempFile.delete()
    }
}
```

- [ ] **Step 6: Run the `FileUtil` tests to verify they fail**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.trafficwatch.app.core.util.FileUtilTest"`
Expected: FAIL - `asStreamingRequestBody` has no `onProgress` parameter yet (compile error).

- [ ] **Step 7: Update `asStreamingRequestBody` in `FileUtil.kt`**

In `app/src/main/java/com/trafficwatch/app/core/util/FileUtil.kt`, find the existing block (currently lines 51-59):

```kotlin
/** Streams a file to OkHttp without loading it entirely into memory. */
fun File.asStreamingRequestBody(mediaType: MediaType = "video/mp4".toMediaType()): RequestBody =
    object : RequestBody() {
        override fun contentType(): MediaType = mediaType
        override fun contentLength(): Long = length()
        override fun writeTo(sink: BufferedSink) {
            source().use { sink.writeAll(it) }
        }
    }
```

Replace it with:

```kotlin
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
                    sink.flush()
                    onProgress?.invoke(written, total)
                }
            }
        }
    }
```

- [ ] **Step 8: Run the `FileUtil` tests to verify they pass**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.trafficwatch.app.core.util.FileUtilTest"`
Expected: PASS (2/2)

- [ ] **Step 9: Run the full existing test suite to confirm nothing else broke**

Run: `./gradlew.bat :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass (the `asStreamingRequestBody` call in `UploadWorker.kt` still compiles unchanged - the new `onProgress` param is optional and trailing).

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/trafficwatch/app/core/util/FileUtil.kt app/src/main/java/com/trafficwatch/app/core/util/UploadProgressTracker.kt app/src/test/java/com/trafficwatch/app/core/util/FileUtilTest.kt app/src/test/java/com/trafficwatch/app/core/util/UploadProgressTrackerTest.kt
git commit -m "feat: add chunked streaming and a pure upload progress tracker

FileUtil.asStreamingRequestBody now writes in 64KB chunks and calls
an optional onProgress callback per chunk, with no timing logic of
its own. UploadProgressTracker is a new pure class (clock passed in,
not read internally) that owns throttling and transfer-rate math,
fully unit-testable with synthetic timestamps."
```

---

### Task 2: Wire UploadWorker to report real progress

**Files:**
- Modify: `app/src/main/java/com/trafficwatch/app/feature/upload/UploadWorker.kt`

**Interfaces:**
- Consumes: `File.asStreamingRequestBody(mediaType, onProgress)` (Task 1), `UploadProgressTracker(totalBytes)` / `UploadProgressTracker.onChunk(bytesWritten, nowMs): UploadProgressSnapshot?` / `UploadProgressSnapshot(percent, bytesUploaded, totalBytes, bytesPerSecond)` (Task 1).
- Produces: `UploadWorker.KEY_BYTES_UPLOADED`, `UploadWorker.KEY_TOTAL_BYTES`, `UploadWorker.KEY_BYTES_PER_SECOND` (companion `String` constants, consumed by Task 3).

No automated test for this task - see Global Constraints and Task 1's rationale. Verification is a build check (Step 2) plus the manual on-device verification in Task 4.

- [ ] **Step 1: Update `UploadWorker.kt`'s video part construction**

In `app/src/main/java/com/trafficwatch/app/feature/upload/UploadWorker.kt`, find this exact block (currently lines 75-80):

```kotlin
        return try {
            val videoPart = MultipartBody.Part.createFormData(
                "video",
                videoFile.name,
                videoFile.asStreamingRequestBody("video/mp4".toMediaType())
            )
```

Replace it with:

```kotlin
        return try {
            val tracker = UploadProgressTracker(videoFile.length())
            val videoPart = MultipartBody.Part.createFormData(
                "video",
                videoFile.name,
                videoFile.asStreamingRequestBody("video/mp4".toMediaType()) { bytesWritten, totalBytes ->
                    tracker.onChunk(bytesWritten, System.currentTimeMillis())?.let { snapshot ->
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
```

- [ ] **Step 2: Add the new companion constants**

Find this exact block (currently lines 129-130, inside the `companion object`):

```kotlin
        const val KEY_PROGRESS = "progress"
        const val KEY_SERVER_ID = "server_id"
```

Replace it with:

```kotlin
        const val KEY_PROGRESS = "progress"
        const val KEY_BYTES_UPLOADED = "bytes_uploaded"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_BYTES_PER_SECOND = "bytes_per_second"
        const val KEY_SERVER_ID = "server_id"
```

- [ ] **Step 3: Add the new import**

In the same file's import block, add (alongside the existing `com.trafficwatch.app.core.util.*` imports at lines 23-25):

```kotlin
import com.trafficwatch.app.core.util.UploadProgressTracker
```

- [ ] **Step 4: Confirm the app still builds**

Run: `./gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Run the full test suite to confirm nothing broke**

Run: `./gradlew.bat :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all existing tests still pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/trafficwatch/app/feature/upload/UploadWorker.kt
git commit -m "feat: report real upload byte progress from UploadWorker

Wires FileUtil's chunked asStreamingRequestBody callback through a
new UploadProgressTracker per upload, reporting bytes uploaded,
total bytes, and transfer rate via setProgressAsync alongside the
existing percent field."
```

---

### Task 3: HistoryViewModel observes live upload progress

**Files:**
- Create: `app/src/main/java/com/trafficwatch/app/di/WorkManagerModule.kt`
- Modify: `app/src/main/java/com/trafficwatch/app/feature/history/HistoryViewModel.kt`
- Test: `app/src/test/java/com/trafficwatch/app/feature/history/HistoryViewModelTest.kt`

**Interfaces:**
- Consumes: `UploadWorker.uniqueWorkName(reportId): String` (existing), `UploadWorker.KEY_BYTES_UPLOADED`/`KEY_TOTAL_BYTES`/`KEY_BYTES_PER_SECOND` (Task 2), `ReportStatus.UPLOADING` (existing enum), `reportRepository.observeReports(): Flow<List<Report>>` (existing).
- Produces: `HistoryViewModel.UploadProgress(bytesUploaded: Long, totalBytes: Long, bytesPerSecond: Long)` (nested data class), `HistoryViewModel.uploadProgress: StateFlow<Map<String, UploadProgress>>` (keyed by `Report.id`), consumed by Task 4.

- [ ] **Step 1: Create `WorkManagerModule.kt`**

Create `app/src/main/java/com/trafficwatch/app/di/WorkManagerModule.kt`:

```kotlin
package com.trafficwatch.app.di

import android.content.Context
import androidx.work.WorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object WorkManagerModule {

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)
}
```

- [ ] **Step 2: Run the existing test suite to confirm the app still builds with the new module**

Run: `./gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit the module on its own**

```bash
git add app/src/main/java/com/trafficwatch/app/di/WorkManagerModule.kt
git commit -m "feat: provide WorkManager via Hilt

Enables constructor injection of WorkManager (rather than the
existing WorkManager.getInstance(context) call-site pattern) for
components that need to be unit-tested with a mock WorkManager."
```

- [ ] **Step 4: Write the failing `HistoryViewModel.uploadProgress` test**

Create `app/src/test/java/com/trafficwatch/app/feature/history/HistoryViewModelTest.kt`:

```kotlin
package com.trafficwatch.app.feature.history

import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.cash.turbine.test
import com.trafficwatch.app.core.data.repository.AuthRepository
import com.trafficwatch.app.core.data.repository.ReportRepository
import com.trafficwatch.app.core.domain.model.LocationData
import com.trafficwatch.app.core.domain.model.Report
import com.trafficwatch.app.core.domain.model.ReportStatus
import com.trafficwatch.app.core.domain.usecase.GetReportStatusUseCase
import com.trafficwatch.app.core.domain.usecase.RetryUploadUseCase
import com.trafficwatch.app.feature.upload.UploadWorker
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val reportRepository = mockk<ReportRepository>()
    private val authRepository = mockk<AuthRepository>()
    private val getReportStatusUseCase = mockk<GetReportStatusUseCase>()
    private val retryUploadUseCase = mockk<RetryUploadUseCase>()
    private val workManager = mockk<WorkManager>()

    private val location = LocationData(31.5, 74.3, 5f, 200.0, 90f, 10f, 1000L)

    private fun report(id: String, status: ReportStatus) = Report(
        id = id,
        videoPath = "/tmp/$id.mp4",
        location = location,
        recordingStartedAt = 1000L,
        durationMs = 8000L,
        fileSizeBytes = 1_000_000L,
        status = status,
        createdAt = 1000L,
        updatedAt = 1000L
    )

    private fun workInfo(bytesUploaded: Long, totalBytes: Long, bytesPerSecond: Long): WorkInfo {
        val progress = Data.Builder()
            .putInt(UploadWorker.KEY_PROGRESS, ((bytesUploaded * 100) / totalBytes).toInt())
            .putLong(UploadWorker.KEY_BYTES_UPLOADED, bytesUploaded)
            .putLong(UploadWorker.KEY_TOTAL_BYTES, totalBytes)
            .putLong(UploadWorker.KEY_BYTES_PER_SECOND, bytesPerSecond)
            .build()
        return mockk<WorkInfo> {
            every { this@mockk.progress } returns progress
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { getReportStatusUseCase() } returns emptyFlow()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // uploadProgress is stateIn(..., SharingStarted.WhileSubscribed(5000), ...) over a
    // combine/flatMapLatest chain - it only starts collecting its upstream once something
    // subscribes, so every test here must subscribe via Turbine's .test{} (which also
    // matches this codebase's existing Flow-testing convention, e.g. ReviewViewModelTest.kt)
    // rather than reading .value cold. A StateFlow always replays its current value to a
    // new subscriber first (the seed emptyMap()), so expectMostRecentItem() after advancing
    // the dispatcher is used to skip past that seed to the settled value, rather than
    // asserting on a specific emission count.

    @Test
    fun `uploadProgress reflects live WorkInfo for an uploading report`() = runTest {
        val reportId = UUID.randomUUID().toString()
        every { reportRepository.observeReports() } returns MutableStateFlow(listOf(report(reportId, ReportStatus.UPLOADING)))
        val workInfoFlow = MutableStateFlow(listOf(workInfo(500_000L, 1_000_000L, 2_000_000L)))
        every { workManager.getWorkInfosForUniqueWorkFlow(UploadWorker.uniqueWorkName(reportId)) } returns workInfoFlow

        val viewModel = HistoryViewModel(reportRepository, authRepository, getReportStatusUseCase, retryUploadUseCase, workManager)

        viewModel.uploadProgress.test {
            testDispatcher.scheduler.advanceUntilIdle()
            val progress = expectMostRecentItem()[reportId]
            assertEquals(HistoryViewModel.UploadProgress(500_000L, 1_000_000L, 2_000_000L), progress)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `uploadProgress has no entry for a report with no WorkInfo yet`() = runTest {
        val reportId = UUID.randomUUID().toString()
        every { reportRepository.observeReports() } returns MutableStateFlow(listOf(report(reportId, ReportStatus.UPLOADING)))
        every { workManager.getWorkInfosForUniqueWorkFlow(UploadWorker.uniqueWorkName(reportId)) } returns MutableStateFlow(emptyList())

        val viewModel = HistoryViewModel(reportRepository, authRepository, getReportStatusUseCase, retryUploadUseCase, workManager)

        viewModel.uploadProgress.test {
            testDispatcher.scheduler.advanceUntilIdle()
            assertTrue(expectMostRecentItem().isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `uploadProgress has no entry for a report that is not UPLOADING`() = runTest {
        val reportId = UUID.randomUUID().toString()
        every { reportRepository.observeReports() } returns MutableStateFlow(listOf(report(reportId, ReportStatus.CONFIRMED)))

        val viewModel = HistoryViewModel(reportRepository, authRepository, getReportStatusUseCase, retryUploadUseCase, workManager)

        viewModel.uploadProgress.test {
            testDispatcher.scheduler.advanceUntilIdle()
            assertTrue(expectMostRecentItem().isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
```

- [ ] **Step 5: Run the tests to verify they fail**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.trafficwatch.app.feature.history.HistoryViewModelTest"`
Expected: FAIL - `HistoryViewModel` has no `workManager` constructor parameter, `UploadProgress`, or `uploadProgress` property yet (compile error).

- [ ] **Step 6: Update `HistoryViewModel.kt`**

Replace the full contents of `app/src/main/java/com/trafficwatch/app/feature/history/HistoryViewModel.kt` with:

```kotlin
package com.trafficwatch.app.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.trafficwatch.app.core.data.repository.AuthRepository
import com.trafficwatch.app.core.data.repository.ReportRepository
import com.trafficwatch.app.core.domain.model.Report
import com.trafficwatch.app.core.domain.model.ReportStatus
import com.trafficwatch.app.core.domain.usecase.GetReportStatusUseCase
import com.trafficwatch.app.core.domain.usecase.RetryUploadResult
import com.trafficwatch.app.core.domain.usecase.RetryUploadUseCase
import com.trafficwatch.app.feature.upload.UploadWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HistoryUiState(
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val pendingCellularReport: Report? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val reportRepository: ReportRepository,
    private val authRepository: AuthRepository,
    private val getReportStatusUseCase: GetReportStatusUseCase,
    private val retryUploadUseCase: RetryUploadUseCase,
    private val workManager: WorkManager
) : ViewModel() {

    data class UploadProgress(
        val bytesUploaded: Long,
        val totalBytes: Long,
        val bytesPerSecond: Long
    )

    val reports = reportRepository.observeReports()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState = _uiState.asStateFlow()

    val uploadProgress: StateFlow<Map<String, UploadProgress>> = reports
        .map { list -> list.filter { it.status == ReportStatus.UPLOADING }.map { it.id } }
        .distinctUntilChanged()
        .flatMapLatest { uploadingIds ->
            if (uploadingIds.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(
                    uploadingIds.map { id ->
                        workManager.getWorkInfosForUniqueWorkFlow(UploadWorker.uniqueWorkName(id))
                            .map { workInfos -> id to workInfos.firstOrNull()?.toUploadProgress() }
                    }
                ) { pairs -> pairs.mapNotNull { (id, progress) -> progress?.let { id to it } }.toMap() }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private fun WorkInfo.toUploadProgress(): UploadProgress? {
        val bytesUploaded = progress.getLong(UploadWorker.KEY_BYTES_UPLOADED, -1L)
        val totalBytes = progress.getLong(UploadWorker.KEY_TOTAL_BYTES, -1L)
        val bytesPerSecond = progress.getLong(UploadWorker.KEY_BYTES_PER_SECOND, 0L)
        return if (bytesUploaded >= 0 && totalBytes > 0) {
            UploadProgress(bytesUploaded, totalBytes, bytesPerSecond)
        } else {
            null
        }
    }

    init {
        startPolling()
    }

    private fun startPolling() {
        viewModelScope.launch {
            getReportStatusUseCase().collect { /* triggers DB update via repository */ }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            reportRepository.syncPendingReports()
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    fun deleteReport(report: Report) {
        viewModelScope.launch {
            reportRepository.deleteReport(report.id)
        }
    }

    fun retryUpload(report: Report) {
        viewModelScope.launch {
            when (val result = retryUploadUseCase(report)) {
                is RetryUploadResult.VideoMissing ->
                    _uiState.update { it.copy(error = "Video file is no longer available on this device") }
                is RetryUploadResult.Enqueued ->
                    if (!result.onWifi) _uiState.update { it.copy(pendingCellularReport = report) }
            }
        }
    }

    fun confirmCellularRetry() {
        val report = _uiState.value.pendingCellularReport ?: return
        viewModelScope.launch {
            retryUploadUseCase(report, forceCellular = true)
            _uiState.update { it.copy(pendingCellularReport = null) }
        }
    }

    fun dismissCellularPrompt() {
        _uiState.update { it.copy(pendingCellularReport = null) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun logout() {
        authRepository.logout()
    }
}
```

- [ ] **Step 7: Run the `HistoryViewModel` tests to verify they pass**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.trafficwatch.app.feature.history.HistoryViewModelTest"`
Expected: PASS (3/3)

- [ ] **Step 8: Run the full test suite to confirm nothing else broke**

Run: `./gradlew.bat :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/trafficwatch/app/feature/history/HistoryViewModel.kt app/src/test/java/com/trafficwatch/app/feature/history/HistoryViewModelTest.kt
git commit -m "feat: expose live upload progress from HistoryViewModel

Adds a constructor-injected WorkManager and a derived
uploadProgress: StateFlow<Map<String, UploadProgress>> that
reactively follows each UPLOADING report's live WorkInfo, keyed by
report ID. A report with no WorkInfo progress data yet (or that
isn't UPLOADING) simply has no map entry."
```

---

### Task 4: Render the progress bar on the Reports list

**Files:**
- Modify: `app/src/main/java/com/trafficwatch/app/feature/history/HistoryScreen.kt`

**Interfaces:**
- Consumes: `HistoryViewModel.uploadProgress: StateFlow<Map<String, HistoryViewModel.UploadProgress>>` (Task 3), `HistoryViewModel.UploadProgress(bytesUploaded, totalBytes, bytesPerSecond)` (Task 3), `FileUtil(context: Context).formatFileSize(bytes: Long): String` (existing).

No automated test - Compose UI, no test infra for this in the codebase (same as items 1/2). Manual on-device verification only.

- [ ] **Step 1: Add the `uploadProgress` state read and pass it down to `ReportCard`**

In `app/src/main/java/com/trafficwatch/app/feature/history/HistoryScreen.kt`, find this exact line (currently line 62):

```kotlin
    val reports by viewModel.reports.collectAsStateWithLifecycle()
```

Add directly after it:

```kotlin
    val uploadProgress by viewModel.uploadProgress.collectAsStateWithLifecycle()
```

- [ ] **Step 2: Pass progress into each `ReportCard`**

Find this exact block (currently lines 124-130):

```kotlin
                    items(reports, key = { it.id }) { report ->
                        ReportCard(
                            report = report,
                            onClick = { onReportClick(report.id) },
                            onRetry = { viewModel.retryUpload(report) }
                        )
                    }
```

Replace it with:

```kotlin
                    items(reports, key = { it.id }) { report ->
                        ReportCard(
                            report = report,
                            uploadProgress = uploadProgress[report.id],
                            onClick = { onReportClick(report.id) },
                            onRetry = { viewModel.retryUpload(report) }
                        )
                    }
```

- [ ] **Step 3: Update the `ReportCard` composable signature and render the progress block**

Find this exact block (currently lines 137-180):

```kotlin
@Composable
private fun ReportCard(report: Report, onClick: () -> Unit, onRetry: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    formatTimestamp(report.createdAt),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (report.status == ReportStatus.UPLOADING || report.status == ReportStatus.UPLOAD_FAILED) {
                        IconButton(onClick = onRetry) {
                            Icon(Icons.Default.CloudUpload, contentDescription = "Retry upload")
                        }
                    }
                    StatusChip(report.status)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "%.4f°, %.4f°".format(report.location.latitude, report.location.longitude),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (report.licensePlate != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Plate: ${report.licensePlate}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
```

Replace it with:

```kotlin
@Composable
private fun ReportCard(
    report: Report,
    uploadProgress: HistoryViewModel.UploadProgress?,
    onClick: () -> Unit,
    onRetry: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    formatTimestamp(report.createdAt),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (report.status == ReportStatus.UPLOADING || report.status == ReportStatus.UPLOAD_FAILED) {
                        IconButton(onClick = onRetry) {
                            Icon(Icons.Default.CloudUpload, contentDescription = "Retry upload")
                        }
                    }
                    StatusChip(report.status)
                }
            }
            if (report.status == ReportStatus.UPLOADING && uploadProgress != null) {
                Spacer(Modifier.height(8.dp))
                val context = LocalContext.current
                val fileUtil = remember(context) { FileUtil(context) }
                LinearProgressIndicator(
                    progress = { uploadProgress.bytesUploaded / uploadProgress.totalBytes.toFloat() },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${fileUtil.formatFileSize(uploadProgress.bytesUploaded)} / " +
                        "${fileUtil.formatFileSize(uploadProgress.totalBytes)} · " +
                        "${fileUtil.formatFileSize(uploadProgress.bytesPerSecond)}/s",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "%.4f°, %.4f°".format(report.location.latitude, report.location.longitude),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (report.licensePlate != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Plate: ${report.licensePlate}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
```

- [ ] **Step 4: Add the new imports**

`androidx.compose.runtime.remember` is already imported in this file (existing line, used for `snackbarHostState`) - do not add it again. Add these three new imports, alongside the existing `androidx.compose.material3.*`/`androidx.compose.ui.*` import groups respectively:

```kotlin
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.platform.LocalContext
import com.trafficwatch.app.core.util.FileUtil
```

- [ ] **Step 5: Confirm the app builds**

Run: `./gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Run the full test suite**

Run: `./gradlew.bat :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/trafficwatch/app/feature/history/HistoryScreen.kt
git commit -m "feat: render live upload progress bar on the Reports list

ReportCard shows a LinearProgressIndicator plus \"X MB / Y MB · Z
MB/s\" text under the status row while a report is UPLOADING and
live progress data is available, falling back to the existing plain
chip otherwise."
```

- [ ] **Step 8: Install on device and manually verify**

Run: `./gradlew.bat :app:installDebug` (device must be connected via adb - run `adb devices` first to confirm)

Manual verification steps:
1. Record and submit a real report. If possible, use a larger clip or a throttled/slow connection so intermediate progress is actually observable rather than completing in under a second.
2. **Expected**: while the report's status is "Uploading" on the Reports list, a progress bar and "X MB / Y MB · Z MB/s" text appear beneath the status chip, with the byte counts increasing over time.
3. Let the upload finish.
4. **Expected**: once the status changes away from "Uploading" (to "Pending", or "Failed" on an error), the progress bar disappears and the card returns to its normal (non-progress) layout.
5. Force an upload failure (e.g. airplane mode mid-upload) and confirm the card falls back to the plain "Failed" chip with no stale progress bar left showing.
6. Tap "Retry" on a failed upload and confirm the progress bar reappears and updates correctly for the new attempt.
