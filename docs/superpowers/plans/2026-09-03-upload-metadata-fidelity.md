# Upload Metadata Fidelity — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A wrong-way report carries its `location_samples` / `rotation_samples` and a correct UTC `recorded_at` all the way to the server, including after a failed first upload and a manual retry.

**Architecture:** Phase A (Android only) persists the two sample series on the `reports` Room row as the exact wire JSON string, serialized once at submit time, so all three enqueue paths (`invoke`, `confirmCellular`, retry) send identical bytes. Phase B fixes `recorded_at` to a true UTC instant on the client and teaches the server to parse it as UTC when the client marks the request as fixed-format — old clients are untouched.

**Tech Stack:** Kotlin, Room 2.6.1 (kapt), Hilt, WorkManager, Retrofit/OkHttp multipart, Gson, MockK + JUnit4 (JVM unit tests only — no Robolectric/instrumented tests in this project); server: Kotlin/Spring Boot, `java.time`.

**Spec:** `docs/superpowers/specs/2026-09-03-upload-metadata-fidelity-design.md`

## Global Constraints

- **Persisted form = the wire JSON string.** `location_samples` / `rotation_samples` are serialized exactly once, in `SubmitReportUseCase`, via a single `SampleJson` helper (`Gson().toJson(list.map { it.toSampleDto() })`); an empty list → `null` (the existing "presence, not sentinel" convention — a null/absent value omits the multipart field entirely). No Room `TypeConverter`s, no `List<>` on the domain `Report`.
- **Real `Migration(3, 4)`**, additive columns only, `exportSchema = true`. Keep `fallbackToDestructiveMigration()` as the backstop for unknown older versions. No automated migration test (project has no Robolectric/instrumented infra); Room's runtime schema validation on first open is the safety net, and `exportSchema = true` makes a future `MigrationTestHelper` test trivial.
- **`recorded_at`: fix-forward only.** No historical Flyway backfill. The fixed client sends a `recorded_at_is_utc` multipart marker; the server parses real UTC **only** when that marker is present, else the existing literal-`Z` path. Old clients keep working unchanged; there is no transition window where any client is 5 hours wrong.
- **`minSdk = 26`** — `java.time` is available natively on the client; use it (no desugaring, no ThreeTen).
- **No re-capture, no retroactive re-analysis.** Reports already submitted are not re-run. Reports stuck mid-upload before this ships cannot be backfilled (samples exist nowhere on device) — documented in release notes, not code.
- **Exact identifiers** (verbatim): `locationSamplesJson`, `rotationSamplesJson` (domain + entity + Room columns); `SampleJson` with `location(...)` / `rotation(...)`; `MIGRATION_3_4`; multipart part name `recorded_at_is_utc`; server request param `recorded_at_is_utc`.
- Android `./gradlew :app:testDebugUnitTest` green; server `./gradlew test` green (bar the known pre-existing `EndToEndFlowTest` real-network flake).

---

## File Structure

### Phase A — Android

| File | Change | Task |
|---|---|---|
| `app/src/main/java/com/trafficwatch/app/core/data/remote/dto/SampleJson.kt` | **new** — single serialization point | 1 |
| `app/src/main/java/com/trafficwatch/app/core/domain/model/Report.kt` | + `locationSamplesJson: String? = null`, `rotationSamplesJson: String? = null` | 1 |
| `app/src/main/java/com/trafficwatch/app/core/data/local/entity/ReportEntity.kt` | + two nullable columns; `toDomain()` / `fromDomain()` | 1 |
| `app/src/main/java/com/trafficwatch/app/core/data/local/AppDatabase.kt` | `version = 4`, `exportSchema = true` | 1 |
| `app/src/main/java/com/trafficwatch/app/core/data/local/Migrations.kt` | **new** — `MIGRATION_3_4` | 1 |
| `app/src/main/java/com/trafficwatch/app/di/DatabaseModule.kt` | `.addMigrations(MIGRATION_3_4)` before `.fallbackToDestructiveMigration()` | 1 |
| `app/build.gradle.kts` + `app/schemas/…/4.json` | Room schema export dir; commit generated `3.json` + `4.json` | 1 |
| `app/src/main/java/com/trafficwatch/app/feature/upload/UploadWorker.kt` | `buildInputData` / `buildRequest`: `List<…>` params → `locationSamplesJson: String?` / `rotationSamplesJson: String?` | 2 |
| `app/src/main/java/com/trafficwatch/app/core/domain/usecase/SubmitReportUseCase.kt` | `invoke`: serialize once, persist on row, pass strings; `confirmCellular(reportId)`: read row | 2 |
| `app/src/main/java/com/trafficwatch/app/feature/review/ReviewViewModel.kt` | `confirmCellularSubmit()` stops passing sample lists / `lastEffectiveLocation` | 2 |
| `app/src/main/java/com/trafficwatch/app/core/domain/usecase/RetryUploadUseCase.kt` | pass `report.locationSamplesJson` / `report.rotationSamplesJson`; drop the `emptyList()` + backlog comment | 3 |
| `app/src/main/java/com/trafficwatch/app/feature/upload/UploadWorker.kt` | `formatRecordedAt(millis): String` companion (UTC via `java.time`); send `recorded_at_is_utc` marker | 4 |
| `app/src/main/java/com/trafficwatch/app/core/data/remote/ApiService.kt` | + `@Part("recorded_at_is_utc") recordedAtIsUtc: RequestBody?` | 4 |

**Tests (Phase A):** `SampleJsonTest` (new, Task 1); `SubmitReportUseCaseTest` (new, Task 2); `UploadWorkerTest` (new, Tasks 2 + 4); `RetryUploadUseCaseTest` (new, Task 3); `ReviewViewModelTest` (update, Task 2).

### Phase B — server

| File | Change | Task |
|---|---|---|
| `server/src/main/kotlin/com/trafficwatch/server/reports/ReportController.kt` | + `@RequestParam("recorded_at_is_utc", required = false) recordedAtIsUtc: Boolean?` | 5 |
| `server/src/main/kotlin/com/trafficwatch/server/reports/ReportService.kt` | dual `recorded_at` parse keyed on the flag; new offset-aware formatter | 5 |
| `server/src/main/kotlin/com/trafficwatch/server/reports/Report.kt` | update the `recorded_at` comment | 5 |
| `docs/improvements-backlog.md` | mark backlog #1 + #3 addressed; record the `recorded_at` format seam | 6 |

**Tests (Phase B):** `ReportServiceTest` / the existing `recorded_at`-parsing test (update, Task 5).

---

## Task 1: Persist the sample series on the report row

**Files:** `SampleJson.kt` (create), `Report.kt`, `ReportEntity.kt`, `AppDatabase.kt`, `Migrations.kt` (create), `DatabaseModule.kt`, `app/build.gradle.kts`, `app/schemas/**` (create), `app/src/test/java/com/trafficwatch/app/core/data/remote/dto/SampleJsonTest.kt` (create)

**Interfaces:**
- Produces: `object SampleJson { fun location(samples: List<LocationData>): String?; fun rotation(samples: List<RotationSample>): String? }`
- Produces: `Report.locationSamplesJson: String?`, `Report.rotationSamplesJson: String?` (both default `null`)
- Produces: `MIGRATION_3_4: Migration`
- Consumes: existing `LocationData.toSampleDto()`, `RotationSample.toSampleDto()`

- [ ] **Step 1: `SampleJsonTest` (failing — class doesn't exist)**

`app/src/test/java/com/trafficwatch/app/core/data/remote/dto/SampleJsonTest.kt`:
```kotlin
package com.trafficwatch.app.core.data.remote.dto

import com.trafficwatch.app.core.domain.model.LocationData
import com.trafficwatch.app.core.domain.model.RotationSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SampleJsonTest {
    @Test fun `location returns null for an empty list`() {
        assertNull(SampleJson.location(emptyList()))
    }

    @Test fun `location serializes snake_case fields`() {
        val json = SampleJson.location(listOf(LocationData(31.5, 74.3, 5f, 200.0, 90f, 10f, 1000L)))
        assertEquals(
            """[{"latitude":31.5,"longitude":74.3,"accuracy":5.0,"altitude":200.0,"bearing":90.0,"speed":10.0,"captured_at":1000}]""",
            json,
        )
    }

    @Test fun `rotation returns null for an empty list`() {
        assertNull(SampleJson.rotation(emptyList()))
    }

    @Test fun `rotation serializes snake_case fields`() {
        val json = SampleJson.rotation(listOf(RotationSample(capturedAt = 2000L, headingDegrees = 187.5f)))
        assertEquals("""[{"heading_degrees":187.5,"captured_at":2000}]""", json)
    }
}
```

Run: `./gradlew :app:testDebugUnitTest --tests "*SampleJsonTest"` → FAIL (unresolved `SampleJson`).

- [ ] **Step 2: create `SampleJson`**

`app/src/main/java/com/trafficwatch/app/core/data/remote/dto/SampleJson.kt`:
```kotlin
package com.trafficwatch.app.core.data.remote.dto

import com.google.gson.Gson
import com.trafficwatch.app.core.domain.model.LocationData
import com.trafficwatch.app.core.domain.model.RotationSample

/**
 * The one place a captured sample series becomes its wire JSON string. Empty list -> null,
 * so the multipart field is omitted entirely (the "presence, not sentinel" convention shared
 * with compassHeadingDegrees). Persisted on the report row AND sent by [UploadWorker] so a
 * first upload and any later retry transmit byte-identical data.
 */
object SampleJson {
    private val gson = Gson()

    fun location(samples: List<LocationData>): String? =
        samples.takeIf { it.isNotEmpty() }?.let { gson.toJson(it.map(LocationData::toSampleDto)) }

    fun rotation(samples: List<RotationSample>): String? =
        samples.takeIf { it.isNotEmpty() }?.let { gson.toJson(it.map(RotationSample::toSampleDto)) }
}
```

Run the test → PASS. (If Gson emits fields in a different order than the assertion, fix the assertion to Gson's actual order — it's deterministic per the DTO declaration order; the DTO fields are declared latitude→…→captured_at / heading_degrees→captured_at.)

- [ ] **Step 3: domain + entity fields**

`Report.kt` — add after `evidenceBreakdownJson`:
```kotlin
    val locationSamplesJson: String? = null,
    val rotationSamplesJson: String? = null,
```

`ReportEntity.kt` — add two columns after `evidenceBreakdownJson`:
```kotlin
    val locationSamplesJson: String?,
    val rotationSamplesJson: String?,
```
and thread them through both `toDomain()` (`locationSamplesJson = locationSamplesJson, rotationSamplesJson = rotationSamplesJson`) and `fromDomain()` (`locationSamplesJson = report.locationSamplesJson, rotationSamplesJson = report.rotationSamplesJson`).

- [ ] **Step 4: bump the DB version + export schema**

`AppDatabase.kt`:
```kotlin
@Database(
    entities = [ReportEntity::class],
    version = 4,
    exportSchema = true,
)
```

`app/build.gradle.kts` — inside `android { }` add:
```kotlin
    room {
        schemaDirectory("$projectDir/schemas")
    }
```
(Requires the `androidx.room` Gradle plugin. If it isn't applied, instead pass the kapt arg:
```kotlin
    kapt { arguments { arg("room.schemaLocation", "$projectDir/schemas") } }
```
Use whichever matches the project's existing plugin setup — check `plugins { }` for `androidx.room`.)

Build once (`./gradlew :app:assembleDebug` or `:app:compileDebugKotlin` then a Room-processing task) so Room generates `app/schemas/com.trafficwatch.app.core.data.local.AppDatabase/4.json` (and `3.json` if it back-generates; if only `4.json` appears that's fine). Commit the generated JSON.

- [ ] **Step 5: `MIGRATION_3_4`**

`app/src/main/java/com/trafficwatch/app/core/data/local/Migrations.kt`:
```kotlin
package com.trafficwatch.app.core.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v3 -> v4: persist the continuous GPS / rotation sample series on the report row so a
 * retried upload (which rebuilds the request from this row) can resend them. Additive,
 * nullable, no default - existing rows get NULL. See
 * docs/superpowers/specs/2026-09-03-upload-metadata-fidelity-design.md.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE reports ADD COLUMN locationSamplesJson TEXT")
        db.execSQL("ALTER TABLE reports ADD COLUMN rotationSamplesJson TEXT")
    }
}
```

- [ ] **Step 6: wire the migration**

`DatabaseModule.kt` — `provideDatabase`:
```kotlin
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.DATABASE_NAME)
            .addMigrations(MIGRATION_3_4)
            .fallbackToDestructiveMigration()
            .build()
```

- [ ] **Step 7: build + commit**

`./gradlew :app:testDebugUnitTest` → green (existing tests may need the two new `ReportEntity` constructor args in fixtures — add `locationSamplesJson = null, rotationSamplesJson = null` wherever a test builds a `ReportEntity` or `Report` positionally; named-arg / default-arg sites are unaffected since the domain fields default).

```bash
git add -A && git commit -m "feat(app): persist location/rotation sample JSON on the report row (Room v4)"
```

---

## Task 2: Serialize once at submit; `confirmCellular` reads the row

**Files:** `UploadWorker.kt`, `SubmitReportUseCase.kt`, `ReviewViewModel.kt`, `app/src/test/java/com/trafficwatch/app/core/domain/usecase/SubmitReportUseCaseTest.kt` (create), `app/src/test/java/com/trafficwatch/app/feature/upload/UploadWorkerTest.kt` (create), `ReviewViewModelTest.kt` (update)

**Interfaces:**
- Consumes: `SampleJson` (Task 1), `Report.locationSamplesJson` / `rotationSamplesJson` (Task 1), `ReportRepository.getReport(id): Report?`
- Produces: `UploadWorker.buildRequest(reportId, videoPath, location, locationSamplesJson: String?, rotationSamplesJson: String?, recordingStartedAt, durationMs, requireWifiOnly): OneTimeWorkRequest`
- Produces: `SubmitReportUseCase.confirmCellular(reportId: String)` (all other params removed)

- [ ] **Step 1: `UploadWorkerTest` for `buildInputData` (failing — signature)**

`app/src/test/java/com/trafficwatch/app/feature/upload/UploadWorkerTest.kt`:
```kotlin
package com.trafficwatch.app.feature.upload

import com.trafficwatch.app.core.domain.model.LocationData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UploadWorkerTest {
    private val loc = LocationData(31.5, 74.3, 5f, 200.0, 90f, 10f, 1000L)

    @Test fun `buildInputData stores sample json verbatim when present`() {
        val data = UploadWorker.buildInputData(
            "r1", "/v.mp4", loc,
            locationSamplesJson = """[{"a":1}]""", rotationSamplesJson = """[{"b":2}]""",
            recordingStartedAt = 1_756_000_000_000L, durationMs = 6000L,
        )
        assertEquals("""[{"a":1}]""", data.getString(UploadWorker.KEY_LOCATION_SAMPLES_JSON))
        assertEquals("""[{"b":2}]""", data.getString(UploadWorker.KEY_ROTATION_SAMPLES_JSON))
    }

    @Test fun `buildInputData omits sample keys when null`() {
        val data = UploadWorker.buildInputData(
            "r1", "/v.mp4", loc, null, null, 1_756_000_000_000L, 6000L,
        )
        assertNull(data.getString(UploadWorker.KEY_LOCATION_SAMPLES_JSON))
        assertNull(data.getString(UploadWorker.KEY_ROTATION_SAMPLES_JSON))
    }
}
```

Run → FAIL (compile: `buildInputData` still takes `List<…>`).

- [ ] **Step 2: `UploadWorker` — strings not lists**

In `UploadWorker.buildInputData` and `buildRequest`, replace params
`locationSamples: List<LocationData>, rotationSamples: List<RotationSample>` with
`locationSamplesJson: String?, rotationSamplesJson: String?`. Delete the two
`Gson().toJson(...)` blocks and the `toSampleDto` import; store the strings directly:
```kotlin
            locationSamplesJson?.let { builder.putString(KEY_LOCATION_SAMPLES_JSON, it) }
            rotationSamplesJson?.let { builder.putString(KEY_ROTATION_SAMPLES_JSON, it) }
```
`buildRequest` forwards the two strings to `buildInputData`. `doWork()` is unchanged
(it already reads the strings via `inputData.getString(...)`).

Run the Step 1 test → PASS.

- [ ] **Step 3: `SubmitReportUseCaseTest` (failing)**

`app/src/test/java/com/trafficwatch/app/core/domain/usecase/SubmitReportUseCaseTest.kt` — with MockK, mock `ReportRepository` + `NetworkMonitor`, stub `context`. Two tests:
```kotlin
@Test fun `invoke persists serialized sample json on the report row`() = runTest {
    val saved = slot<Report>()
    coEvery { reportRepository.saveReport(capture(saved)) } just Runs
    every { networkMonitor.isOnWifi() } returns true

    useCase.invoke(File("/v.mp4"), location, locationSamples, rotationSamples, 1_000L, 6_000L)

    assertEquals(SampleJson.location(locationSamples), saved.captured.locationSamplesJson)
    assertEquals(SampleJson.rotation(rotationSamples), saved.captured.rotationSamplesJson)
}

@Test fun `confirmCellular re-enqueues from the persisted row`() = runTest {
    coEvery { reportRepository.getReport("r1") } returns
        report.copy(locationSamplesJson = """[{"x":1}]""", rotationSamplesJson = null)
    // verify the enqueued request carries "[{"x":1}]" and null — assert via a spy on
    // UploadWorker.buildRequest (extract it behind an injectable seam) OR verify
    // WorkManager.enqueueUniqueWork was called with a request whose inputData matches.
}
```
The `confirmCellular` assertion needs a seam: either (a) `mockkStatic(WorkManager::class)` + capture the `OneTimeWorkRequest` and read `.workSpec.input`, or (b) extract the `WorkManager.getInstance(context).enqueueUniqueWork(...)` line into an injectable `UploadEnqueuer` collaborator (cleaner, also helps the existing `WorkManager.getInstance` backlog nit — but that's a **separate** backlog item; only do the extraction if it stays under ~20 lines, else use `mockkStatic`). Pick one, note it in the report.

Run → FAIL.

- [ ] **Step 4: `SubmitReportUseCase`**

`invoke(...)`:
```kotlin
        val locationSamplesJson = SampleJson.location(locationSamples)
        val rotationSamplesJson = SampleJson.rotation(rotationSamples)

        reportRepository.saveReport(
            Report(
                id = reportId,
                videoPath = trimmedFile.absolutePath,
                location = effectiveLocation,
                recordingStartedAt = recordingStartedAt,
                durationMs = durationMs,
                fileSizeBytes = trimmedFile.length(),
                status = ReportStatus.UPLOADING,
                locationSamplesJson = locationSamplesJson,
                rotationSamplesJson = rotationSamplesJson,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            )
        )

        enqueue(
            reportId, trimmedFile.absolutePath, effectiveLocation,
            locationSamplesJson, rotationSamplesJson, recordingStartedAt, durationMs,
            requireWifiOnly = true, policy = ExistingWorkPolicy.KEEP,
        )
```

`confirmCellular` — collapse to reading the row:
```kotlin
    suspend fun confirmCellular(reportId: String) {
        val row = reportRepository.getReport(reportId) ?: run {
            // The row was written by invoke() moments ago; a miss means local DB corruption.
            // The Wi-Fi-only enqueue from invoke() still stands, so the report is not lost.
            return
        }
        enqueue(
            reportId, row.videoPath, row.location,
            row.locationSamplesJson, row.rotationSamplesJson,
            row.recordingStartedAt, row.durationMs,
            requireWifiOnly = false, policy = ExistingWorkPolicy.REPLACE,
        )
    }
```

`enqueue(...)` private helper: change its `locationSamples` / `rotationSamples` params to
`locationSamplesJson: String?` / `rotationSamplesJson: String?`, forward to
`UploadWorker.buildRequest`.

- [ ] **Step 5: `ReviewViewModel.confirmCellularSubmit`**

```kotlin
    fun confirmCellularSubmit() {
        if (_uiState.value.isSubmitting) return
        val reportId = lastReportId ?: return
        _uiState.update { it.copy(isSubmitting = true) }
        viewModelScope.launch {
            submitReportUseCase.confirmCellular(reportId)
            _uiState.update { it.copy(showCellularPrompt = false, isSubmitting = false) }
            _submitted.send(Unit)
        }
    }
```
`lastEffectiveLocation` is now unused by this method; if nothing else in the file reads it,
delete the field and its assignment in `submit()`. (Check first.)

- [ ] **Step 6: update `ReviewViewModelTest`**

The existing cellular-confirm test asserts `submitReportUseCase.confirmCellular(...)` with the
old arg list — change it to `coVerify { submitReportUseCase.confirmCellular("someReportId") }`
and drop the sample-list setup for that path.

- [ ] **Step 7: full run + commit**

`./gradlew :app:testDebugUnitTest` → green.
```bash
git add -A && git commit -m "feat(app): serialize samples once at submit; confirmCellular reads the persisted row"
```

---

## Task 3: Retry resends the persisted samples

**Files:** `RetryUploadUseCase.kt`, `app/src/test/java/com/trafficwatch/app/core/domain/usecase/RetryUploadUseCaseTest.kt` (create)

**Interfaces:** Consumes `Report.locationSamplesJson` / `rotationSamplesJson`, `UploadWorker.buildRequest` (Task 2 shape).

- [ ] **Step 1: `RetryUploadUseCaseTest` (failing — this is the bug)**

```kotlin
@Test fun `retry resends the persisted sample json`() = runTest {
    every { fileUtil.exists(any()) } returns true
    coEvery { reportRepository.updateStatus(any(), any(), any()) } just Runs
    every { networkMonitor.isOnWifi() } returns true
    val report = baseReport.copy(
        locationSamplesJson = """[{"latitude":31.5}]""",
        rotationSamplesJson = """[{"heading_degrees":90.0}]""",
    )
    // capture the enqueued OneTimeWorkRequest (mockkStatic(WorkManager::class) or the
    // UploadEnqueuer seam from Task 2) and assert workSpec.input for
    // KEY_LOCATION_SAMPLES_JSON / KEY_ROTATION_SAMPLES_JSON equals the report's JSON.
    useCase.invoke(report)
}

@Test fun `retry with no persisted samples omits the keys and still succeeds`() = runTest {
    // report.locationSamplesJson == null -> keys absent, RetryUploadResult.Enqueued
}
```

Run → FAIL (current code passes `emptyList(), emptyList()` → keys absent even when JSON exists).

- [ ] **Step 2: fix `RetryUploadUseCase`**

Replace the sample block + comment with:
```kotlin
        val request = UploadWorker.buildRequest(
            report.id, report.videoPath, report.location,
            report.locationSamplesJson, report.rotationSamplesJson,
            report.recordingStartedAt, report.durationMs,
            requireWifiOnly = !forceCellular,
        )
```

Run → both tests PASS. `./gradlew :app:testDebugUnitTest` → green.

- [ ] **Step 3: commit**

```bash
git add -A && git commit -m "fix(app): retry resends the report's persisted location/rotation samples"
```

---

## Task 4: Client emits true-UTC `recorded_at` + a fixed-format marker

**Files:** `UploadWorker.kt`, `ApiService.kt`, `UploadWorkerTest.kt` (extend)

**Interfaces:**
- Produces: `UploadWorker.formatRecordedAt(epochMillis: Long): String` (companion, `internal`/`public` for test)
- Produces: multipart part `recorded_at_is_utc` = `"true"` from this client
- Consumes: `ApiService.submitReport(... recordedAtIsUtc: RequestBody? ...)`

- [ ] **Step 1: failing test — timezone independence**

Add to `UploadWorkerTest`:
```kotlin
@Test fun `formatRecordedAt is UTC regardless of device zone`() {
    val millis = 1_756_479_157_000L // 2025-08-29T14:52:37Z
    val expected = "2025-08-29T14:52:37Z"
    val default = java.util.TimeZone.getDefault()
    try {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Karachi"))
        assertEquals(expected, UploadWorker.formatRecordedAt(millis))
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/Los_Angeles"))
        assertEquals(expected, UploadWorker.formatRecordedAt(millis))
    } finally {
        java.util.TimeZone.setDefault(default)
    }
}
```
Run → FAIL (no such method). (Verify the exact `expected` string against a known
epoch-millis→UTC conversion before locking it in.)

- [ ] **Step 2: `formatRecordedAt`**

In `UploadWorker`'s companion:
```kotlin
        private val RECORDED_AT_FORMATTER: java.time.format.DateTimeFormatter =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                .withZone(java.time.ZoneOffset.UTC)

        /** ISO-8601 seconds-precision UTC (literal Z), from an epoch-millis instant. */
        fun formatRecordedAt(epochMillis: Long): String =
            RECORDED_AT_FORMATTER.format(java.time.Instant.ofEpochMilli(epochMillis))
```
In `doWork()` replace the `SimpleDateFormat(...)` line with `val isoDate = formatRecordedAt(recordedAt)`. Remove the now-unused `SimpleDateFormat` / `Date` / `Locale` imports if nothing else uses them.

Run → PASS.

- [ ] **Step 3: send the marker**

`ApiService.submitReport(...)` — add after `zoomRatio`:
```kotlin
        @Part("recorded_at_is_utc") recordedAtIsUtc: RequestBody?,
```
`UploadWorker.doWork()` — in the `apiService.submitReport(...)` call add:
```kotlin
                recordedAtIsUtc = "true".toRequestBody(),
```
(Always `"true"` from this client version — its presence is the marker; the server
treats absent/`false` as legacy.)

- [ ] **Step 4: run + commit**

`./gradlew :app:testDebugUnitTest` → green.
```bash
git add -A && git commit -m "fix(app): recorded_at is a true UTC instant; mark the request fixed-format"
```

---

## Task 5: Server parses marked requests as real UTC

**Files:** `ReportController.kt`, `ReportService.kt`, `Report.kt`, server `recorded_at` parse test

**Interfaces:**
- Consumes: multipart param `recorded_at_is_utc` (Task 4)
- Produces: `recordedAt` stored as a UTC-derived `LocalDateTime` when the marker is present

- [ ] **Step 1: failing test**

In the server test that exercises `RECORDED_AT_FORMATTER` / report submission
(find it: `grep -rl "recorded_at\|RECORDED_AT" server/src/test`), add:
```kotlin
@Test fun `marked recorded_at is parsed as real UTC`() {
    // recordedAtIsUtc = true, recordedAt = "2025-08-29T14:52:37Z"
    // -> stored recordedAt == LocalDateTime.of(2025, 8, 29, 14, 52, 37)
}
@Test fun `unmarked recorded_at keeps the legacy literal-Z parse`() {
    // recordedAtIsUtc = null, recordedAt = "2025-08-29T14:52:37Z"
    // -> stored recordedAt == LocalDateTime.of(2025, 8, 29, 14, 52, 37)  (same digits, legacy path)
}
@Test fun `marked recorded_at with a real offset is normalized to UTC`() {
    // recordedAtIsUtc = true, recordedAt = "2025-08-29T19:52:37+05:00"
    // -> stored recordedAt == LocalDateTime.of(2025, 8, 29, 14, 52, 37)
}
```
Run → FAIL.

- [ ] **Step 2: controller param**

`ReportController.kt` `submitReport(...)` — add:
```kotlin
        @RequestParam("recorded_at_is_utc", required = false) recordedAtIsUtc: Boolean?,
```
and pass it into the `reportService` call (add the parameter there too).

- [ ] **Step 3: dual parse in `ReportService`**

```kotlin
// Legacy: the pre-2026-09 Android client appended a literal "Z" without converting to UTC.
// We stored that wall-clock time as-is. Kept for old clients (no recorded_at_is_utc marker).
private val LEGACY_RECORDED_AT_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")

// Fixed client (recorded_at_is_utc=true) sends a real instant. Accept Z or a real offset,
// normalize to UTC, store the UTC wall clock.
private val UTC_RECORDED_AT_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss[XXX][X]")
```
In the submit path:
```kotlin
val parsedRecordedAt =
    if (recordedAtIsUtc == true) {
        OffsetDateTime.parse(recordedAt, UTC_RECORDED_AT_FORMATTER)
            .withOffsetSameInstant(ZoneOffset.UTC)
            .toLocalDateTime()
    } else {
        LocalDateTime.parse(recordedAt, LEGACY_RECORDED_AT_FORMATTER)
    }
```
(Confirm the `[XXX][X]` pattern parses both `+05:00` and `Z` in this JDK; if flaky, use
`DateTimeFormatter.ISO_OFFSET_DATE_TIME` and truncate — `recorded_at` is seconds-precision
so `.withNano(0)` after parse.)

- [ ] **Step 4: comment + run**

`Report.kt` — rewrite the `recorded_at` comment: values from clients sending
`recorded_at_is_utc=true` are UTC wall clock; older values are device-local wall clock
(mislabelled `Z`). No historical migration — a dated seam, see the 2026-09-03 spec.

`./gradlew test` (from `server/`) → green bar the known `EndToEndFlowTest` flake.

- [ ] **Step 5: commit**

```bash
git add -A && git commit -m "feat(server): parse recorded_at as real UTC when the client marks it fixed-format"
```

---

## Task 6: Backlog + release notes

**Files:** `docs/improvements-backlog.md`

- [ ] **Step 1: update the two entries**

In `## Upload reliability / data integrity`:
- **"A report that fails its first upload attempt permanently loses its `location_samples`/`rotation_samples`"** — add a dated line: fixed by persisting the wire JSON on the `reports` Room row (v4 migration) so `RetryUploadUseCase` and `SubmitReportUseCase.confirmCellular` resend it; reports stuck mid-upload *before* this release still can't be backfilled (samples exist nowhere on device). Spec/plan `2026-09-03-upload-metadata-fidelity`.
- **"`recorded_at` and `location_samples`' `captured_at` use two different, disagreeing time bases"** — add a dated line: the client now emits a true UTC instant and marks the request `recorded_at_is_utc=true`; the server parses real UTC only for marked requests, legacy literal-`Z` otherwise. **No historical backfill** — `recorded_at` from app versions before this release is device-local wall clock, after is UTC (a dated seam; `recorded_at` has no consumer that this breaks). `captured_at` (epoch millis) was always correct.

- [ ] **Step 2: commit**

```bash
git add -A && git commit -m "docs: mark upload-metadata-fidelity backlog items addressed"
```

---

## Rollout

1. **Deploy the server (Task 5) first.** It is backward-compatible — unmarked requests take the unchanged legacy path — so it can ship any time before the client.
2. **Release the client (Tasks 1–4).** From then on, that device's reports carry the sample series through retries and a correct UTC `recorded_at`.
3. Release notes: *"Reports interrupted mid-upload by a previous version will still upload, but without the continuous GPS/rotation detail used for wrong-way analysis. Newly recorded reports are unaffected."*
4. No DB migration on the server. The Android Room migration is additive and self-validating on first open.

---

## Self-Review

**Spec coverage:**
- Part 1 (persist samples, single serialization point, real migration, exportSchema) → Tasks 1–3. ✅
- Part 2 (pre-update stuck rows unrecoverable) → documented in Task 6 + Rollout, no code. ✅
- Part 3 (client true-UTC `recorded_at`) → Task 4. ✅
- Part 4 (server parse) → Task 5, done as the **marker-keyed** variant (spec Open Question 2 option b) so there is no transition skew and no historical migration (spec Open Question 1 → fix-forward, no `V12`). ✅
- Spec Open Question 3 (`'X'` offset parsing) → Task 5 accepts `Z` and real offsets, normalizes to UTC. ✅

**Decisions taken (spec open questions — flag if you disagree):**
1. `recorded_at`: fix-forward, **no `V12` backfill**. Dated seam documented.
2. Transition: **client marker `recorded_at_is_utc`**, server dual-path. Zero window where any client is wrong; old clients untouched forever.
3. Offset parsing: accept + normalize.
4. Migration test: **skipped** (no Robolectric/instrumented infra in the project). `exportSchema = true` + Room's first-open schema validation is the net; adding `MigrationTestHelper` is a standalone follow-up if wanted.

**Placeholder scan:** none — every step has literal code or a concrete file/assertion. Two spots say "verify the exact string / pattern against the JDK" (Gson field order, `formatRecordedAt` expected value, `[XXX][X]` parsing) — these are verification instructions, not placeholders.

**Type consistency:** `locationSamplesJson` / `rotationSamplesJson` (`String?`) identical across `SampleJson` output, domain `Report`, `ReportEntity`, `MIGRATION_3_4` columns, `UploadWorker.buildInputData` / `buildRequest` params, `SubmitReportUseCase` / `RetryUploadUseCase` call sites. `recorded_at_is_utc` identical: `ApiService` `@Part`, `UploadWorker` send, `ReportController` `@RequestParam`, `ReportService` branch. `MIGRATION_3_4` name consistent (Task 1 creates, Task 1 Step 6 wires).

**Cross-task interfaces:** Task 1 produces the fields + `SampleJson` + migration; Task 2 consumes all three and changes `UploadWorker` + `SubmitReportUseCase`; Task 3 consumes the fields + Task 2's `buildRequest` shape; Task 4 adds an independent `UploadWorker` companion fn + a multipart field (no collision with Task 2's param changes — different function); Task 5 consumes Task 4's marker. Task 2 and Task 4 both edit `UploadWorker.kt` but different regions (`buildInputData`/`buildRequest` vs the companion formatter + `doWork`'s `apiService.submitReport` call) — sequential, no conflict.
