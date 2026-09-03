# Upload Metadata Fidelity — `location_samples` / `rotation_samples` on Retry, and `recorded_at` UTC — Design

**Status:** draft for review

Combines two `docs/improvements-backlog.md` items from the "Upload reliability / data integrity" section:
- **#1** — a report that fails its first upload attempt permanently loses its `location_samples` / `rotation_samples` (confirmed in production, report `2dcf9912-…`).
- **#3** — `recorded_at` and `location_samples`' `captured_at` use two disagreeing time bases (`recorded_at` is device-local wall clock with a literal `Z` appended).

They are combined because both are report-metadata-fidelity bugs on the upload path, both touch `UploadWorker`, and #1's Room-schema change is the natural place to also carry #3's coordinated fix. See "Why combined" below for the seam.

---

## Context

### How samples reach the server today

`ReviewViewModel` holds the continuous `locationSamples: List<LocationData>` and
`rotationSamples: List<RotationSample>` in its **transient** `ReviewUiState`.
`ReviewViewModel.submit()` passes them to `SubmitReportUseCase.invoke(...)`, which:

1. builds a `Report` domain row and persists it via `reportRepository.saveReport(...)` — **the samples are not on `Report`, so they are not persisted**;
2. calls `UploadWorker.buildRequest(... locationSamples, rotationSamples ...)`, which in `UploadWorker.buildInputData` serializes each list with Gson (`list.map { it.toSampleDto() }`) into a JSON string and stores it in the WorkManager `Data` payload (empty list → key omitted entirely, the "presence, not sentinel" convention shared with `compassHeadingDegrees`).

`UploadWorker.doWork()` reads those JSON strings straight out of `inputData` and forwards them as multipart form fields `location_samples` / `rotation_samples`.

### Bug #1 — retry sends empty lists

`RetryUploadUseCase.invoke(report)` (invoked from `HistoryViewModel` when the user
taps retry on an `UPLOADING` / `UPLOAD_FAILED` report) rebuilds the request from the
persisted `Report` row. Because the row has no samples, it passes
`emptyList(), emptyList()` explicitly (with a comment pointing at the backlog).
`SubmitReportUseCase.confirmCellular(...)` re-passes `state.*` from the still-live
ViewModel, so the cellular-confirm path is fine **only while the ViewModel is
alive** — a process death between `submit()` and the cellular prompt loses them too,
though that window is much smaller.

Uploads are Wi-Fi-only (`NetworkType.UNMETERED`) by default, so first-attempt
failures are common. A wrong-way report that fails its first upload and is later
retried lands on the server with `location_samples` / `rotation_samples` **null** —
and the analysis pipeline now depends on both:
`OrientationTimeline.wasStationaryThroughout()` (the stationary-approach detector's
first gate) needs `location_samples`; per-vehicle bearing resolution needs
`rotation_samples` or the compass scalar. A retried report at a divided-carriageway
location silently cannot be confirmed.

### Bug #3 — `recorded_at` is not UTC

`UploadWorker.doWork()`:
```kotlin
val isoDate = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date(recordedAt))
```
No `TimeZone` is set, so `SimpleDateFormat` formats `recordedAt` (epoch millis) in the
**device's local zone** and then appends a literal `'Z'` claiming UTC. For a
Pakistan device (UTC+5) the string is 5 hours ahead of the real UTC instant.

**The server deliberately mirrors this bug.** `ReportService`:
```kotlin
// "Matches the Android client's recorded_at format exactly, including its known bug:
//  the client appends a literal "Z" without actually converting the timestamp to UTC first."
private val RECORDED_AT_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
val parsedRecordedAt = LocalDateTime.parse(recordedAt, RECORDED_AT_FORMATTER)  // literal Z, no offset
```
stored in `reports.recorded_at TIMESTAMP` (no timezone). So today client and server
**agree**: `recorded_at` is "wall-clock time as the device saw it, mislabelled `Z`".

Nothing safety-critical consumes it: `OrientationTimeline` explicitly uses the trimmed
clip's sample timestamps, not `report.recordedAt` (there's a comment saying so), and
the analysis pipeline never compares `recorded_at` against `captured_at`. The Android
History UI (`ReportDetailScreen`, `ReviewScreen`) displays the **local** epoch-millis
`recordingStartedAt` directly and is unaffected. The concrete risk is future code that
correlates the two — and the general wrongness of a timestamp column that lies about
its timezone.

**This makes #3 a coordinated client + server change, not the client one-liner the
backlog entry implied.** Fixing only the client formatter would flip client and
server out of agreement and shift every new `recorded_at` 5 hours relative to every
historical row.

---

## Why combined

- Both are upload-path metadata-fidelity defects; both live in / around `UploadWorker`.
- #1 requires a Room schema bump (`reports` table → add two columns) with a
  `Migration(3,4)`. Once that migration exists, #3's need to keep historical
  `recorded_at` rows consistent with the new UTC convention is a natural companion
  server-side Flyway migration — the change set is "make the report's stored metadata
  trustworthy," done once across both stores.
- One review pass over "does a report round-trip its metadata faithfully through
  submit, process-death, and retry" covers both.
- They do **not** share code beyond `UploadWorker` and the review lens; if the team
  wants to split, #3 can be lifted out cleanly (it's Parts 3–4 below).

---

## Scope decisions (confirm before planning)

1. **Persisted form = the wire JSON string.** Store `location_samples` /
   `rotation_samples` on the `reports` Room row as the exact snake_case JSON string
   that goes on the wire (`Gson().toJson(list.map { it.toSampleDto() })`), computed
   **once** at submit time. Not Room `TypeConverter`s over `List<LocationData>` — a
   single serialization point, and the persisted bytes are identical to what a
   first-attempt upload sends, so retry is provably equivalent.
2. **Real `Migration(3,4)`, not `fallbackToDestructiveMigration()`.** The app
   currently destroys the DB on version bump (`DatabaseModule`). For a fix whose
   entire purpose is "don't lose a stuck report's data," wiping every
   `UPLOADING` / `UPLOAD_FAILED` row on the update that ships the fix is
   self-defeating. Add a proper additive migration, set `exportSchema = true`, and
   keep `fallbackToDestructiveMigration()` only as the backstop for unknown older
   versions.
3. **`recorded_at` becomes genuinely UTC on both sides going forward**
   (Parts 3–4). The column stays `TIMESTAMP` (not `timestamptz`) but is
   redocumented as "UTC wall clock"; the server formatter switches to real
   offset-aware parsing. **Recommended: no historical backfill** — document a
   dated seam (rows before the server-deploy date are device-local, rows after are
   UTC) rather than run a migration that has to assume every prior device was
   UTC+5. `recorded_at` has no current consumer that a dated seam breaks. The
   backfill (`V12`) and the documentation-only options are in "Open questions"; the
   longer both sides mirror the bug, the more entrenched it gets, so fixing forward
   now is worth doing regardless of the backfill choice.
4. **No re-capture, no retroactive re-analysis.** Applies to reports submitted after
   this ships (plus any still-retryable row, which gains samples the moment its
   row is migrated — see Part 2). Already-CONFIRMED/REJECTED server reports are not
   re-run.

---

## Design

### Part 1 — persist `location_samples` / `rotation_samples` on the report row

#### `ReportEntity` + `Report` (domain)

Add to `com.trafficwatch.app.core.domain.model.Report`:
```kotlin
val locationSamplesJson: String? = null,   // snake_case wire JSON, or null when none captured
val rotationSamplesJson: String? = null,
```
Add the matching nullable `TEXT` columns to `ReportEntity` (`locationSamplesJson`,
`rotationSamplesJson`) and thread them through `toDomain()` / `fromDomain()`.

#### Room migration

- `AppDatabase`: `version = 3` → `4`, `exportSchema = true`.
- `app/build.gradle(.kts)`: add the Room schema export dir
  (`room { schemaDirectory("$projectDir/schemas") }` or the KSP arg), commit the
  generated `4.json`.
- New `Migration(3, 4)` in a `Migrations.kt`:
  ```sql
  ALTER TABLE reports ADD COLUMN locationSamplesJson TEXT;
  ALTER TABLE reports ADD COLUMN rotationSamplesJson TEXT;
  ```
- `DatabaseModule.provideDatabase`: `.addMigrations(MIGRATION_3_4)` before
  `.fallbackToDestructiveMigration()`.

#### Single serialization point

Introduce `SampleJson` (small object in `core/data/remote/dto/`), the one place
either list becomes a string:
```kotlin
object SampleJson {
    fun location(samples: List<LocationData>): String? =
        samples.takeIf { it.isNotEmpty() }?.let { Gson().toJson(it.map(LocationData::toSampleDto)) }
    fun rotation(samples: List<RotationSample>): String? =
        samples.takeIf { it.isNotEmpty() }?.let { Gson().toJson(it.map(RotationSample::toSampleDto)) }
}
```
`UploadWorker.buildInputData` stops serializing; its `locationSamples` /
`rotationSamples` list params become `locationSamplesJson: String?` /
`rotationSamplesJson: String?`, stored verbatim (null → key omitted, unchanged
convention). `buildRequest` signature changes the same way.

#### `SubmitReportUseCase`

`invoke(...)` still receives the two lists from the ViewModel. It now:
```kotlin
val locJson = SampleJson.location(locationSamples)
val rotJson = SampleJson.rotation(rotationSamples)
reportRepository.saveReport(Report(..., locationSamplesJson = locJson, rotationSamplesJson = rotJson))
enqueue(reportId, ..., locJson, rotJson, ...)     // pass strings, not lists
```
`confirmCellular(reportId, ...)` no longer takes sample params — it reads the
persisted row:
```kotlin
val row = reportRepository.getReport(reportId) ?: return   // or surface an error
enqueue(reportId, row.videoPath, row.location, row.locationSamplesJson, row.rotationSamplesJson, ...)
```

#### `RetryUploadUseCase`

Drop the `emptyList(), emptyList()` and the backlog comment; pass
`report.locationSamplesJson`, `report.rotationSamplesJson` (the `report` argument is
already the persisted row).

#### `ReviewViewModel`

`confirmCellularSubmit()` stops passing `state.locationSamples` /
`state.rotationSamples` (the use case reads the row now). `submit()` is unchanged.
`ReviewUiState` keeps the lists (still needed for the first `invoke` call and any
on-screen display).

### Part 2 — backfill retryable rows (bounded, best-effort)

Rows already in `UPLOADING` / `UPLOAD_FAILED` when the app updates have
`locationSamplesJson = null` after the migration and cannot be backfilled (the
samples exist nowhere on device). This is unavoidable and acceptable — it is the
pre-existing loss, not a regression. No code for this; call it out in the plan and
release notes: *"reports stuck mid-upload before this version will still upload
without the continuous GPS/rotation series; new reports are unaffected."*

### Part 3 — client: emit real UTC `recorded_at`

`UploadWorker.doWork()`:
```kotlin
val isoDate = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
    .withZone(ZoneOffset.UTC)
    .format(Instant.ofEpochMilli(recordedAt))
```
Same `yyyy-MM-dd'T'HH:mm:ss'Z'` shape (literal `Z`, seconds precision — the server
parser is strict on this), but the wall-clock digits are now the true UTC instant.
`java.time` is available (`coreLibraryDesugaring` is already on for this project —
confirm in `app/build.gradle`; if not, `ThreeTenABP` or a `SimpleDateFormat` with
`timeZone = TimeZone.getTimeZone("UTC")` is the fallback).

### Part 4 — server: parse real UTC, migrate historical rows

- `ReportService.RECORDED_AT_FORMATTER` + `parse`: switch to
  ```kotlin
  private val RECORDED_AT_FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'X'")   // 'X' = real zone offset; accepts 'Z'
  val parsedRecordedAt = OffsetDateTime.parse(recordedAt, RECORDED_AT_FORMATTER)
      .withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime()
  ```
  Rewrite the comment: `recorded_at` is now stored as a UTC `LocalDateTime`. Keep
  `LocalDateTime` (the column is `TIMESTAMP`), just derived from a real instant.
- **Historical backfill — only if Open Question 1 picks (a).** Flyway
  `V12__recorded_at_to_utc.sql`:
  ```sql
  -- Historical recorded_at values were device-local wall clock (mislabelled Z) from app
  -- versions < the fixed release. This app is single-region (UTC+5). Shift to true UTC so
  -- the column's meaning is uniform. Safe to run unconditionally ONLY because it deploys
  -- before the fixed client ships, so no real-UTC rows exist yet.
  UPDATE reports SET recorded_at = recorded_at - INTERVAL '5 hours';
  ```
  **Ordering constraint:** deploy **before** the fixed client is released.
- With the recommended (b) fix-forward, there is no migration; the plan records the
  server-deploy date as the format seam in `docs/` and in a `reports.recorded_at`
  comment.
- A `recorded_at_format_version` column is overkill for a single-region app; skip it.

---

## Data flow, after

| Path | `location_samples` / `rotation_samples` | `recorded_at` |
|---|---|---|
| First upload (Wi-Fi) | serialized once in `SubmitReportUseCase`, stored on row + sent | true UTC |
| Cellular confirm | read from row | true UTC |
| Manual retry (History) | read from row | true UTC |
| Process death then retry | read from row | true UTC |
| Pre-update stuck row, retried | null (unrecoverable, documented) | shifted −5h by V12 |

---

## Edge cases

- **Empty capture** (no GPS fix / no rotation sensor): `SampleJson.*` returns null →
  row column null → wire key omitted. Unchanged behaviour, now consistent across all
  three enqueue paths.
- **`getReport` miss in `confirmCellular`**: the report was just written by `invoke`
  in the same use case; a miss means DB corruption. Log + return (the Wi-Fi-only
  enqueue from `invoke` already stands, so the report is not lost). Do **not** crash
  — this is also backlog item "ReviewViewModel.submit() has no error handling," kept
  separate but don't regress toward it here.
- **Very large sample arrays**: the server already caps at `MAX_LOCATION_SAMPLES =
  1000` and drops oversized arrays silently. The well-behaved app produces ~10.
  Persisting the JSON on the Room row adds ~1–2 KB per report; negligible.
- **WorkManager `Data` 10 KB limit**: unchanged — the JSON was already in `Data`
  before this change; moving serialization earlier doesn't grow it.
- **Migration on a row mid-flight**: `ALTER TABLE ADD COLUMN` with no default is
  instant on SQLite; existing rows get null. Safe.

---

## Testing

### Android (JVM unit — no Robolectric except the migration test)

`SampleJsonTest`
- non-empty list → exact expected snake_case JSON (`captured_at`, `heading_degrees`).
- empty list → null.

`UploadWorkerTest` (companion functions are pure, already unit-tested style)
- `buildInputData` with non-null JSON strings → keys present verbatim; with null → keys absent.
- `recorded_at`: `buildInputData(recordingStartedAt = <fixed millis>)` then the
  `doWork` formatting path — assert a known epoch millis (e.g. `1_756_000_000_000`)
  formats to the exact expected UTC string, and that a device in `Asia/Karachi`
  (`TimeZone.setDefault`) produces the **same** string (the regression the bug
  caused). Pull the formatting into a testable `companion fun formatRecordedAt(millis): String`.

`SubmitReportUseCaseTest`
- `invoke` with sample lists → `reportRepository.saveReport` captured `Report` has
  `locationSamplesJson` / `rotationSamplesJson` equal to `SampleJson.*` output;
  `UploadWorker.buildRequest` (mock/verify) receives the same strings.
- `confirmCellular` → `getReport` stubbed to return a row with JSON → `buildRequest`
  receives that row's JSON (not the ViewModel's).

`RetryUploadUseCaseTest`
- `invoke(report with locationSamplesJson = "[...]" )` → `buildRequest` receives
  that JSON, not `emptyList`/null. (New test — this is the bug.)
- `invoke(report with null JSON)` → keys omitted, still succeeds.

`ReviewViewModelTest`
- `confirmCellularSubmit` no longer passes samples (signature change) — update
  existing test; assert `submitReportUseCase.confirmCellular` called with the new
  arg list.

`MigrationTest` (Robolectric + `androidx.room.testing.MigrationTestHelper`)
- create DB at v3 with a report row, run `MIGRATION_3_4`, assert the row survives
  and both new columns exist and are null. Requires `exportSchema = true` + the
  committed `3.json` / `4.json`.

### Server

`ReportServiceTest` / wherever `RECORDED_AT_FORMATTER` is exercised
- `"2026-08-29T15:52:37Z"` → parses to `LocalDateTime` `2026-08-29T15:52:37`
  (real-UTC path; same wall-clock digits because input claims UTC).
- reject / handle a value with a real non-Z offset gracefully if `'X'` is used
  (decide: accept `+05:00` and normalize, or keep strict `Z`-only — recommend
  accept-and-normalize).
- existing `recorded_at` round-trip tests updated for the new parser.

`FlywayMigrationTest` (if the project has one) or a manual check
- `V12` shifts a seeded pre-migration row by exactly −5h; runs clean on an empty table.

### Regression

- `./gradlew test` (Android) green.
- `./gradlew test` (server) green.
- Full end-to-end: submit on Wi-Fi → row has JSON; kill Wi-Fi mid-first-attempt →
  retry from History → server receives non-null `location_samples` /
  `rotation_samples` and a `recorded_at` matching the true recording instant.

---

## Rollout

1. **Server first.** Deploy `V12` + the new parser. At this moment no fixed-client
   rows exist, so the unconditional −5h shift is correct. The new parser still
   accepts the old client's `...Z` strings (they parse to the same `LocalDateTime`
   as before for a hypothetical UTC device; a still-old client keeps sending
   local-as-Z, which after V12's shift will be… see note).
   - **Note / accepted imperfection:** between the server deploy and the client
     rollout reaching a given device, that device still sends local-wall-clock-as-Z.
     The new server parser treats `Z` as real UTC, so those in-between submissions
     land 5h off in the *opposite* direction from the old rows (which V12 just
     shifted). This window is the price of not versioning the format. Options: (a)
     accept it — `recorded_at` is non-critical and the window is one release cycle;
     (b) gate the parser on a client-sent `X-App-Version` / a new `recorded_at` form
     field the fixed client adds, and only real-UTC-parse when present. Recommend
     (b) if the plan has room — a 3-line conditional — else (a) with a logged
     counter.
2. **Client release** with Parts 1 + 3.
3. Release notes: pre-update stuck reports upload without the GPS/rotation series.

---

## Non-goals

- Room `TypeConverter`s / structured sample columns — the JSON-string form is
  deliberate (single serialization point, wire-identical).
- `timestamptz` for `reports.recorded_at` — the column stays `TIMESTAMP`,
  redocumented as UTC.
- Fixing `ReviewViewModel.submit()`'s missing try/catch (separate backlog item) —
  just don't regress toward it (Part 1's `confirmCellular` miss path fails soft).
- Persisting samples for reports that never had them (pre-update stuck rows).
- Any change to the analysis pipeline's consumption of these fields.
- `recordingStartedAt` on the local Room row / History UI — already correct
  (epoch millis, displayed local).

---

## Open questions for review

1. `recorded_at` — three choices, pick one:
   (a) **full fix** — Parts 3–4 + V12 backfill (uniform column meaning, migration risk);
   (b) **fix-forward** — Parts 3–4 but *no* V12; document that rows before deploy
       date D are device-local and rows after are UTC (no migration, a dated seam
       in the data);
   (c) **documentation-only** — leave both sides mirroring the bug, add KDoc on
       both formatters. Recommended: (b) — removes the migration's "assume every
       historical device was UTC+5" gamble while still making the column correct
       going forward; `recorded_at` has no current consumer that a dated seam
       would break.
2. Rollout note 1: format-versioning the parser (option b) or accepting the
   one-release-cycle skew (option a)?
3. `'X'` offset parsing on the server — accept real offsets and normalize, or stay
   strict `Z`-only?
