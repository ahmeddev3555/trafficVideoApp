# Upload Progress Indicator Design

## Context

Backlog item (`docs/improvements-backlog.md`, "Navigation / UI flow", item 3):
`HistoryScreen.kt`'s `StatusChip` shows only a static "Uploading" label for
`ReportStatus.UPLOADING` (`HistoryScreen.kt:188`), with no indication of how
far the transfer has gotten or whether it's stuck. This is the third and
final item of the three the user chose to work through in this backlog
section (items 1 and 2 - the "+" button navigation bug and the Trim Preview
boundary fix - are already merged and pushed).

Unlike items 1 and 2, this is a genuinely new feature, not a bug fix.

## Current state (confirmed via code inspection)

- `UploadWorker.doWork()` (`UploadWorker.kt:48-113`) calls
  `setProgress(workDataOf(KEY_PROGRESS to 0))` before the upload and
  `setProgress(workDataOf(KEY_PROGRESS to 100))` after the entire
  synchronous `apiService.submitReport(...)` call returns - no intermediate
  progress at all.
- `File.asStreamingRequestBody()` (`FileUtil.kt:52-59`) streams the video
  file to OkHttp via `source().use { sink.writeAll(it) }` - a single
  unbroken write with no hook for progress.
- `Report.fileSizeBytes: Long` (`Report.kt:10`) already exists on the
  domain model, populated from the trimmed file's length at submit time
  (`SubmitReportUseCase.kt:60`) - gives the total-bytes figure for free.
- `FileUtil.formatFileSize(bytes: Long): String` (`FileUtil.kt:43-48`)
  already exists and matches the "X MB / Y MB" formatting need.
- WorkManager 2.9.0 (`libs.versions.toml`) is used; each upload is enqueued
  under a unique work name `UploadWorker.uniqueWorkName(reportId) =
  "upload_$reportId"` (`UploadWorker.kt:174`), from both
  `SubmitReportUseCase.enqueue()` and `RetryUploadUseCase`, both calling
  `WorkManager.getInstance(context).enqueueUniqueWork(...)` directly (no
  Hilt-provided `WorkManager` bean exists in this codebase - `getInstance`
  is called at each use site).
- `HistoryViewModel` (`HistoryViewModel.kt`) does not observe WorkManager
  `WorkInfo` at all today - only `reportRepository.observeReports()` (Room)
  and a 30s server-status poll (`GetReportStatusUseCase`, unrelated to live
  upload progress - that polls the *server* for post-upload status changes).

## Scope (confirmed with user)

- Progress is shown **only on the Reports list** (`HistoryScreen`'s
  `ReportCard`), not on `ReportDetailScreen` - matches the backlog item's
  literal wording and keeps the observation wiring in one place.
- Progress is tracked by instrumenting the existing
  `File.asStreamingRequestBody()` rather than adding a new
  `CountingRequestBody` wrapper around the whole multipart body - the video
  is the overwhelming majority of the payload, so tracking just its bytes
  is accurate enough, and this touches one existing function instead of
  adding a new class.
- No new database columns - progress is inherently transient (meaningless
  once an upload finishes or the app never observed it), so it is not
  persisted; it lives entirely in WorkManager's own transient progress
  `Data` and is read reactively via `Flow`, matching how `WorkInfo` already
  works for any in-flight work.

## Part A - Byte-level progress tracking (Worker + FileUtil)

`FileUtil.kt`'s `File.asStreamingRequestBody()` gains an optional
progress callback and writes in fixed-size chunks instead of one
`sink.writeAll()` call:

`FileUtil.kt` stays focused on raw chunked I/O only - no throttling, no
rate math - calling `onProgress` after every chunk unconditionally:

```kotlin
private const val UPLOAD_CHUNK_SIZE_BYTES = 64 * 1024L

/** Streams a file to OkHttp without loading it entirely into memory. */
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

Throttling and rate calculation move into a new, deliberately pure class,
`core/util/UploadProgressTracker.kt` - pure because it takes the current
time as a parameter rather than reading a clock itself, which is what
makes it directly unit-testable with synthetic timestamps and no Android
framework stubbing at all:

```kotlin
private const val PROGRESS_EMIT_INTERVAL_MS = 300L

data class UploadProgressSnapshot(
    val percent: Int,
    val bytesUploaded: Long,
    val totalBytes: Long,
    val bytesPerSecond: Long
)

/** Not thread-safe - used from a single OkHttp write callback per upload. */
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

`UploadWorker.doWork()` wires the two together, reading the wall clock
only at the call site (untestable glue, kept intentionally thin):

```kotlin
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

(`setProgressAsync` rather than the suspend `setProgress` - the callback
runs synchronously inside OkHttp's write loop, which is not a suspend
context; `CoroutineWorker` exposes `setProgressAsync` returning a
`ListenableFuture` for exactly this case, and the result is intentionally
not awaited - a dropped/delayed progress tick is harmless and the next
chunk's tick supersedes it.)

Three new companion constants on `UploadWorker`: `KEY_BYTES_UPLOADED`,
`KEY_TOTAL_BYTES`, `KEY_BYTES_PER_SECOND` (all `Long`, alongside the
existing `KEY_PROGRESS: Int`).

**Why this split, and why `System.currentTimeMillis()` where a clock read
is unavoidable:** an earlier version of this design put throttling and
rate math directly in `asStreamingRequestBody`, timed via
`android.os.SystemClock.elapsedRealtime()`. Two problems: (1) this
codebase has no Robolectric and no
`testOptions.unitTests.isReturnDefaultValues` in `app/build.gradle.kts`
(confirmed), so any `android.*` framework call throws
`RuntimeException: Method ... not mocked` in a plain local JUnit test;
(2) more importantly, the natural way to test `UploadWorker`'s progress
wiring - mocking `ApiService.submitReport` and asserting on
`setProgressAsync` calls - doesn't actually exercise this code at all,
because a MockK-mocked Retrofit call never serializes its multipart body,
so `RequestBody.writeTo()` (and therefore any progress callback inside it)
never runs. Extracting `UploadProgressTracker` as a pure, clock-injected
class fixes both: it's fully testable with synthetic `nowMs` values (see
Testing below), and `FileUtil.asStreamingRequestBody` becomes simple
enough (unconditional per-chunk callback, no timing logic at all) that its
own test needs no clock at all either. Only the one-line glue in
`UploadWorker` reads a real clock, and it stays untested, thin, and
low-risk - the same "extract the pure core, leave IO glue untested"
split this codebase already uses elsewhere (e.g. `tracking_bearing.py`'s
pure functions vs. `pipeline.py`'s orchestration, on the Python side).

**Throttling rationale:** on a fast connection a 20MB clip could produce
thousands of chunk callbacks; emitting `setProgress` on every one would
flood WorkManager's transient progress channel for no visible benefit.
300ms is well under human perception for "is this moving" while keeping
the update volume trivial. The final chunk always emits regardless of the
throttle window, so the UI never gets stuck below 100% after a fast finish.

## Part B - ViewModel/UI wiring

`HistoryViewModel` gains a constructor-injected `workManager: WorkManager`
parameter, rather than following `RetryUploadUseCase`'s direct
`WorkManager.getInstance(context)` call - `WorkManager` is a `final`
Android class, so calling the static `getInstance` inline would force
`HistoryViewModel`'s own tests to reach for MockK's `mockkStatic`, while
constructor injection lets tests pass a plain `mockk<WorkManager>()`
matching how every other dependency here (`reportRepository`,
`authRepository`, the use cases) is already tested. A new one-function
provider, `di/WorkManagerModule.kt`, matches this codebase's existing
single-`@Provides`-function-per-file convention (e.g. `di/SensorModule.kt`):

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object WorkManagerModule {
    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)
}
```

With `workManager` now injected, `uploadProgress` is:

```kotlin
data class UploadProgress(
    val bytesUploaded: Long,
    val totalBytes: Long,
    val bytesPerSecond: Long
)

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
            ) { pairs -> pairs.mapNotNull { (id, p) -> p?.let { id to it } }.toMap() }
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
```

Reactivity: whenever the set of `UPLOADING` report IDs changes,
`flatMapLatest` re-subscribes; `combine` merges each report's live
`WorkInfo` flow (WorkManager pushes updates - no polling). A report with
no progress data yet (just enqueued, still waiting on the Wi-Fi
constraint, or the work's info hasn't propagated yet) simply has no map
entry - the UI falls back to today's plain "Uploading" chip with no bar.
This also means the feature works correctly if the app is killed and
reopened mid-upload: `getWorkInfosForUniqueWorkFlow` reflects WorkManager's
current state for work that's still running, not just events observed live.

`HistoryScreen.kt`'s `ReportCard` gains an `uploadProgress: UploadProgress?`
parameter (looked up by `viewModel.uploadProgress` in `HistoryScreen`,
passed down alongside the existing `report`/`onClick`/`onRetry` params).
When `report.status == ReportStatus.UPLOADING && uploadProgress != null`,
render beneath the existing status Row:

```kotlin
LinearProgressIndicator(
    progress = { uploadProgress.bytesUploaded / uploadProgress.totalBytes.toFloat() },
    modifier = Modifier.fillMaxWidth()
)
Text(
    "${fileUtil.formatFileSize(uploadProgress.bytesUploaded)} / " +
        "${fileUtil.formatFileSize(uploadProgress.totalBytes)} · " +
        "${fileUtil.formatFileSize(uploadProgress.bytesPerSecond)}/s",
    style = MaterialTheme.typography.labelSmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant
)
```

(`FileUtil`'s constructor only needs a `Context`, and `ReviewScreen.kt:141`
already establishes the precedent for using it directly from a Composable
- `FileUtil(context).formatFileSize(trimmedFile.length())` - rather than
injecting it via Hilt or routing formatted strings through the ViewModel.
`ReportCard` follows the same pattern: `FileUtil(LocalContext.current)`,
called for the three values above. `UploadProgress` itself keeps raw
`Long` fields rather than pre-formatted strings, since the progress bar's
`progress = { bytesUploaded / totalBytes.toFloat() }` needs the raw numbers
too.)

## Non-goals

- `ReportDetailScreen` progress display (explicitly descoped per user's
  choice - list-only).
- A distinct "waiting for Wi-Fi" / constraint-not-yet-met sub-state beyond
  the plain "Uploading" label - out of scope for what was asked (X MB / Y
  MB + rate), and the existing cellular-confirmation dialog already
  surfaces the Wi-Fi-gating concern separately.
- Persisting progress to the database - transient WorkManager state is
  sufficient and avoids schema churn for data with no value once an upload
  completes or the app was never open to observe it.
- Any change to the actual upload mechanism, retry policy, or multipart
  request shape - this only adds observability into an existing, unchanged
  upload path.

## Testing

None of `FileUtil`, `UploadProgressTracker`, `UploadWorker`, nor
`HistoryViewModel` has an existing test file in this codebase (confirmed),
so there is no established pattern to match for these specifically; the
plan follows this codebase's general JUnit4 + MockK +
`kotlinx-coroutines-test` convention used throughout
`app/src/test/java/com/trafficwatch/app/` (e.g.
`RegisterViewModelTest.kt`: `StandardTestDispatcher`,
`mockk<SomeUseCase>()`, `Dispatchers.setMain`/`resetMain`;
`ReviewViewModelTest.kt`: Turbine's `flow.test { ... }` for asserting on
`StateFlow`/`Flow` emissions).

- **`asStreamingRequestBody`'s chunking** is fully deterministic now that
  it carries no timing logic: write a temp file of a known size (e.g.
  500KB, several multiples of the 64KB chunk size), call `writeTo()`
  against a real `okio.Buffer` sink with a callback that records every
  `(bytesWritten, totalBytes)` pair, and assert the exact expected chunk
  count, monotonically increasing `bytesWritten`, and a final call of
  `(fileSize, fileSize)`.
- **`UploadProgressTracker`** is tested directly and deterministically with
  synthetic `nowMs` values (no clock mocking needed at all): first call
  always emits with `bytesPerSecond == 0`; a call inside the 300ms window
  of the last emission returns `null`; a call at/past the window emits
  with the correct `(bytesWritten - lastEmittedBytes) * 1000 / elapsedMs`
  rate; a call where `bytesWritten >= totalBytes` always emits regardless
  of the window; `totalBytes == 0` doesn't divide by zero (`percent == 0`).
- **`UploadWorker`** gains its first test file. Note: mocking
  `ApiService.submitReport` (as every other `UploadWorker` behavior would
  naturally be tested) does *not* exercise the progress callback at all,
  because a MockK-mocked Retrofit call never serializes its `MultipartBody`
  - `RequestBody.writeTo()` simply never runs. Given `UploadProgressTracker`
  already has full direct coverage above, `UploadWorker`'s own test scope
  is the existing behaviors unaffected by this feature (success path
  updates status/deletes the file, failure path retries within
  `runAttemptCount < 3` then fails) using MockK fakes for
  `ApiService`/`ReportRepository`/`TokenStore`/`FileUtil` - the
  progress-callback wiring itself is thin, untested glue by design (see
  Part A's rationale above).
- **`HistoryViewModel.uploadProgress`** is tested with `mockk<WorkManager>()`
  (trivial to construct thanks to the constructor-injection change above)
  stubbed via `every` to return a fake `getWorkInfosForUniqueWorkFlow`
  flow per report ID (each backed by a `MutableStateFlow<List<WorkInfo>>`
  the test can push updates into), combined with a fake
  `ReportRepository.observeReports()` flow - asserted via Turbine,
  confirming the resulting map updates as both the underlying reports list
  and the fake `WorkInfo` flows emit, and that a report with no matching
  `WorkInfo` progress data is simply absent from the map.
