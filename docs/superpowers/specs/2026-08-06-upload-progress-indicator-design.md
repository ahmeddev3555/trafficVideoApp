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

```kotlin
private const val UPLOAD_CHUNK_SIZE_BYTES = 64 * 1024L
private const val PROGRESS_EMIT_INTERVAL_MS = 300L

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
            var lastEmitMs = System.currentTimeMillis()
            source().use { source ->
                while (true) {
                    val read = source.read(sink.buffer, UPLOAD_CHUNK_SIZE_BYTES)
                    if (read == -1L) break
                    written += read
                    sink.flush()
                    val now = System.currentTimeMillis()
                    val isLastChunk = written >= total
                    if (onProgress != null && (isLastChunk || now - lastEmitMs >= PROGRESS_EMIT_INTERVAL_MS)) {
                        onProgress(written, total)
                        lastEmitMs = now
                    }
                }
            }
        }
    }
```

`UploadWorker.doWork()` passes a callback that tracks the previous
emitted point (bytes + timestamp) in local closures to compute an
instantaneous transfer rate, and reports all three figures together:

```kotlin
var lastBytes = 0L
var lastTimeMs = System.currentTimeMillis()

val videoPart = MultipartBody.Part.createFormData(
    "video",
    videoFile.name,
    videoFile.asStreamingRequestBody("video/mp4".toMediaType()) { bytesWritten, totalBytes ->
        val now = System.currentTimeMillis()
        val elapsedMs = (now - lastTimeMs).coerceAtLeast(1)
        val bytesPerSecond = ((bytesWritten - lastBytes) * 1000L) / elapsedMs
        lastBytes = bytesWritten
        lastTimeMs = now
        val percent = if (totalBytes > 0) ((bytesWritten * 100) / totalBytes).toInt() else 0
        setProgressAsync(
            workDataOf(
                KEY_PROGRESS to percent,
                KEY_BYTES_UPLOADED to bytesWritten,
                KEY_TOTAL_BYTES to totalBytes,
                KEY_BYTES_PER_SECOND to bytesPerSecond
            )
        )
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

**Why `System.currentTimeMillis()`, not `android.os.SystemClock.elapsedRealtime()`:**
this codebase has no Robolectric and no
`testOptions.unitTests.isReturnDefaultValues` in `app/build.gradle.kts`
(confirmed), so any `android.*` framework call throws
`RuntimeException: Method ... not mocked` in a plain local JUnit test -
and `asStreamingRequestBody`'s chunking logic needs to be directly
unit-testable (see Testing below). `System.currentTimeMillis()` is a
few-hundred-millisecond-scale wall clock read used only to throttle UI
update frequency, not to measure a safety-critical duration - the
theoretical risk of a mid-upload clock adjustment skewing one throttle
window or one rate sample for a progress *display* is immaterial.

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

Neither `FileUtil`, `UploadWorker`, nor `HistoryViewModel` has an existing
test file in this codebase (confirmed - no
`app/src/test/java/.../{FileUtilTest,UploadWorkerTest,HistoryViewModelTest}.kt`
exists today), so there is no established pattern to match for these three
specifically; the plan follows this codebase's general JUnit4 + MockK +
`kotlinx-coroutines-test` convention used throughout
`app/src/test/java/com/trafficwatch/app/` (e.g.
`RegisterViewModelTest.kt`: `StandardTestDispatcher`,
`mockk<SomeUseCase>()`, `Dispatchers.setMain`/`resetMain`).

- **`asStreamingRequestBody`'s chunking/throttling** is a pure-enough unit
  to test directly: write a temp file of a known size (e.g. 500KB, several
  multiples of the 64KB chunk size), call `writeTo()` against a real
  `okio.Buffer` sink with a progress callback that records every
  `(bytesWritten, totalBytes)` pair, and assert the recorded sequence is
  monotonically increasing, ends at `(fileSize, fileSize)`, and (asserting
  on call-count bounds rather than exact timing, since real time passes in
  a unit test and the 300ms throttle window is short relative to
  in-process I/O) that the last call's values are correct regardless of
  how many throttled calls landed in between.
- **`UploadWorker`** gains its first test file, using MockK to fake
  `ApiService`/`ReportRepository`/`TokenStore`/`FileUtil` (matching the
  constructor-injection style already used for every other use
  case/ViewModel test in this codebase) and asserting that a successful
  `doWork()` run's `setProgressAsync` calls include `KEY_BYTES_UPLOADED`/
  `KEY_TOTAL_BYTES`/`KEY_BYTES_PER_SECOND` alongside the existing
  `KEY_PROGRESS`.
- **`HistoryViewModel.uploadProgress`** is tested with `mockk<WorkManager>()`
  (now trivial to construct thanks to the constructor-injection change
  above) stubbed via `coEvery`/`every` to return a fake
  `getWorkInfosForUniqueWorkFlow` flow per report ID, combined with a fake
  `ReportRepository.observeReports()` flow - asserting the resulting map
  updates as both the underlying reports list and the fake `WorkInfo`
  flows emit.
