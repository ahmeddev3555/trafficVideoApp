# Continuous Rotation-Vector Capture Design

## Context

Sub-project 1 (2026-08-03, shipped) added continuous GPS heading capture -
a time-series of GPS fixes throughout each recording, replacing the single
snapshot taken at recording start. This is sub-project 2 of the same
4-part effort to fix the wrong-way direction-analysis pipeline's
"stationary camera" assumption:

1. Android continuous GPS heading capture (done, shipped).
2. **This plan: Android continuous rotation-vector (compass heading)
   capture.** Captures, uploads, and persists a time-series of
   declination-corrected compass headings throughout each recording.
   Verifiable entirely on its own (record -> submit -> query the stored
   data) with zero changes to how direction is currently computed.
3. Server-side fusion of (1) and (2) into a continuous per-timestamp
   camera-orientation signal, replacing the single `compassHeadingDegrees`
   scalar `ClipFlowAnalyzer` uses today (not this plan - this is the piece
   that actually closes the original bug).
4. Video-analysis visual odometry, as an independent follow-on validation/
   fallback layer once 1-3 exist (not this plan).

**This plan covers only #2.** No changes to `ClipFlowAnalyzer`,
`ReportAnalysisJob`, or any direction-analysis logic - this data is
captured, transmitted, and stored, not yet consumed.

`CompassProvider.kt` already reads `Sensor.TYPE_ROTATION_VECTOR` (fused
gyroscope + accelerometer + magnetometer, filtered by the platform) for a
single declination-corrected heading snapshot at recording start
(`getSnapshot()`, averaging 3 samples over a short window). This plan adds
a second, continuous variant of the same read - not raw gyroscope angular
velocity, which measures rotation *rate* rather than absolute heading and
would need its own integration/drift-correction logic that duplicates what
`TYPE_ROTATION_VECTOR`'s platform-level sensor fusion already does.

## Scope & explicit decisions (confirmed with the user - do not re-litigate)

- **Sensor: continuous `Sensor.TYPE_ROTATION_VECTOR` reads, not raw
  `TYPE_GYROSCOPE`.** Each sample is an independent, self-correcting,
  drift-free absolute heading - directly comparable to the existing single
  `compassHeadingDegrees` snapshot, and reuses `CompassProvider`'s existing
  extraction math (`getRotationMatrixFromVector` + `getOrientation` +
  declination correction) rather than introducing angular-velocity
  integration.
- **Sampling interval: 200ms (5Hz) during active recording**, a separate
  subscription from the existing one-shot `getSnapshot()` call (unchanged)
  and independent of sub-project 1's 1Hz GPS sampling loop. Denser than
  GPS's 1Hz because orientation can change faster than position, and part
  of this sub-project's purpose is finer-grained coverage between GPS
  fixes. A 10-second clip yields up to ~50 samples.
- **Each sample stores only a heading in degrees**, not full 3-axis
  orientation (pitch/roll). Matches exactly what `compassHeadingDegrees`
  already is and what sub-project 3's fusion layer needs; nothing planned
  consumes tilt data, so it's left out per YAGNI.
- **Declination is computed once per recording, not once per sample.**
  `GeomagneticField`'s correction depends on location and doesn't
  meaningfully change within one short clip, so the same declination
  offset (derived from the same location reference `getSnapshot()` already
  uses today) is applied to every sample in the loop. If no location
  reference is available at recording start, every sample falls back to
  uncorrected magnetic-north heading - the same fallback
  `CameraViewModel.onStartRecording()` already applies to the one-shot
  snapshot today.
- **Storage: a single JSON column on the `reports` table**
  (`rotation_samples`, `jsonb`), matching `location_samples`'s pattern
  from sub-project 1 - not a new table.
- **Wire format: one new optional multipart field**, `rotation_samples`,
  a JSON array string - same backward-compatibility convention as
  `location_samples` (`required = false`; absent on submissions from older
  app versions, never blocks submission; omitted entirely when the list is
  empty rather than sent as `"[]"` - the "presence, not sentinel"
  convention, and the exact bug sub-project 1's final review caught and
  fixed for `location_samples`, applied correctly from the start here).
- **Samples are filtered to the trimmed clip's time window before
  upload**, exactly like `location_samples` after sub-project 1's final
  review fix - not the whole raw (up to 10-minute) recording. This is
  designed in from the start this time rather than discovered the same way
  again: at 5Hz, an unfiltered raw recording would produce up to ~3000
  samples, which would overflow WorkManager's 10KB `Data` payload limit
  far more severely than the GPS case did.
- **Malformed JSON is logged and treated as absent**, matching the
  now-fixed `location_samples` behavior (the OSM-lookup-retry plan's final
  review added the missing log call there) - implemented correctly here
  the first time, with a regression test covering it (the earlier plan's
  final review flagged that `location_samples` itself still has no
  dedicated test for this path; this plan adds one for `rotation_samples`
  and that gap becomes a natural opportunity to add the missing
  `location_samples` test too, in the same touched area).
- **Not persisted to the local Room `Report` entity**, matching
  `location_samples`'s current, already-accepted limitation (recorded in
  `docs/improvements-backlog.md`) - a retry-after-failed-upload will not
  resend rotation samples, same as it doesn't resend GPS samples today.
  Not fixed here to keep this sub-project's scope matched to sub-project
  1's; the existing backlog entry gets updated to cover both fields
  instead of just `location_samples`.

## Data flow

```
CameraViewModel.onStartRecording()
  -> starts collecting compassProvider.observeHeadings(intervalMs = 200)
     into a growing list, alongside the existing one-shot getSnapshot()
     call (unchanged) and sub-project 1's independent 1Hz GPS sampling
     loop (unchanged)
CameraViewModel.stopRecording() -> stops the collection job (same
  defensive cancel-at-start-of-onStartRecording and cancel-in-the-
  recording-error-callback pattern already used for the GPS sampling job)
CameraViewModel.getRotationSamples(): List<RotationSample>
  -> threaded through AppNavigation's Compose state exactly like
     locationSamples already is
AppNavigation's REVIEW composable filters BOTH locationSamples and the
  new rotationSamples to the trimmed clip's absolute time window
  (recordingStartedAt + trimStartMs .. that + duration) before passing
  either to ReviewScreen - the same filter already computed there for
  locationSamples, extended to cover both lists from one pass over
  trimStartMs/duration
SubmitReportUseCase.invoke(...)/confirmCellular(...) gain a
  rotationSamples: List<RotationSample> parameter, mirrored end to end
  through UploadWorker (new KEY_ROTATION_SAMPLES_JSON, presence-not-
  sentinel) and ApiService (new "rotation_samples" multipart part)
  exactly like locationSamples
Server: ReportController accepts the new optional param, parses it with
  Jackson into a List<RotationSampleDto>, re-serializes to a canonical
  JSON string, stores it in the new rotation_samples JSONB column -
  logging and treating as absent on any parse failure, never blocking
  submission - no consumption logic yet
```

## Components touched

**Android - new:**
- `app/src/main/java/com/trafficwatch/app/core/domain/model/RotationSample.kt`
  - domain model: `capturedAt: Long`, `headingDegrees: Float`.
- `app/src/main/java/com/trafficwatch/app/core/data/remote/dto/RotationSampleDto.kt`
  - a dedicated Gson DTO (not the domain model directly), explicit
    `@SerializedName` snake_case fields (`captured_at`, `heading_degrees`),
    matching this codebase's existing DTO convention and
    `LocationSampleDto`'s precedent from sub-project 1.

**Android - modified:**
- `core/util/CompassProvider.kt` - new
  `observeHeadings(intervalMs: Long): Flow<Float?>`, refactored to share
  the existing rotation-matrix/orientation/declination extraction logic
  with `getSnapshot()` rather than duplicating it; `getSnapshot()`'s own
  behavior and signature are unchanged.
- `feature/camera/CameraViewModel.kt` - new
  `ROTATION_SAMPLE_INTERVAL_MS = 200L` constant; `onStartRecording()`
  launches a second collection coroutine (alongside the existing GPS one)
  collecting `compassProvider.observeHeadings(ROTATION_SAMPLE_INTERVAL_MS)`
  into a growing `MutableList<RotationSample>` (cleared at the start of
  each recording, using the same declination reference already resolved
  for the one-shot snapshot); `stopRecording()` and the recording-error
  callback cancel this job alongside the existing ones. New accessor
  `getRotationSamples(): List<RotationSample>`.
- `feature/camera/CameraScreen.kt` - `onVideoRecorded` callback gains a
  5th argument, the rotation samples list.
- `navigation/AppNavigation.kt` - new
  `var rotationSamples by remember { mutableStateOf<List<RotationSample>>(emptyList()) }`,
  threaded into the `CAMERA` and `REVIEW` composables the same way
  `locationSamples` already is; the REVIEW composable's existing
  trim-window filter is extended to also filter `rotationSamples` by
  `capturedAt`, using the same `windowStart`/`windowEnd` bounds already
  computed there; reset to `emptyList()` alongside the other cleared
  state after a successful submit.
- `feature/review/ReviewViewModel.kt` - `ReviewUiState` gains
  `rotationSamples: List<RotationSample> = emptyList()`, populated in
  `init(...)`.
- `core/domain/usecase/SubmitReportUseCase.kt` - `invoke(...)` and
  `confirmCellular(...)` gain a `rotationSamples: List<RotationSample>`
  parameter, threaded straight through to `UploadWorker` unconverted
  (serialization happens in `UploadWorker`, matching sub-project 1's
  corrected design).
- `feature/upload/UploadWorker.kt` - `buildInputData(...)` gains a new
  `KEY_ROTATION_SAMPLES_JSON` string key, omitted entirely when the list
  is empty (same convention as `KEY_LOCATION_SAMPLES_JSON`); `doWork()`
  reads it back and passes it as the new multipart field.
- `core/data/remote/ApiService.kt` - `submitReport(...)` gains
  `@Part("rotation_samples") rotationSamples: RequestBody?`.

**Server - new:**
- `server/src/main/kotlin/com/trafficwatch/server/reports/dto/RotationSampleDto.kt`
  - plain camelCase Kotlin properties (`capturedAt: Long`,
    `headingDegrees: Double`) - the server's global Jackson snake_case
    naming strategy maps these to `captured_at`/`heading_degrees` with no
    extra annotations, matching the Android DTO's explicit
    `@SerializedName`s.
- `server/src/main/resources/db/migration/V8__add_rotation_samples_to_reports.sql`
  - `ALTER TABLE reports ADD COLUMN rotation_samples JSONB;`

**Server - modified:**
- `reports/ReportController.kt` - `submitReport(...)` gains
  `@RequestParam("rotation_samples", required = false) rotationSamplesJson: String?`.
- `reports/ReportService.kt` - parses `rotationSamplesJson` the same way
  `locationSamplesJson` already is (lenient try/catch, logged and treated
  as absent on failure - reusing the existing `logger` field), persists
  the canonical re-serialized JSON.
- `reports/Report.kt` - gains `rotationSamples: String?`,
  `@JdbcTypeCode(SqlTypes.JSON)`, `columnDefinition = "jsonb"`, matching
  `locationSamples`'s exact pattern.

**Unchanged:** `ClipFlowAnalyzer.kt`, `ReportAnalysisJob.kt`, and every
other piece of direction-analysis logic - this plan only captures,
transmits, and stores the data.

**Docs:**
- `docs/improvements-backlog.md` - the existing "retry loses
  `location_samples`" entry is edited to cover `rotation_samples` too,
  rather than adding a near-duplicate second entry.

## Edge cases

- **No rotation-vector sensor on the device, or the sensor read fails** -
  `observeHeadings` emits nothing usable (mirrors `getSnapshot()`
  returning `null` today); the field is simply omitted from the upload,
  same "presence, not sentinel" convention as `location_samples`. Never
  blocks submission.
- **No location reference available for declination correction at
  recording start** - every sample in that recording falls back to
  uncorrected magnetic-north heading, exactly like the existing one-shot
  snapshot's fallback (`CameraViewModel.onStartRecording()`'s
  `declinationReference == null` branch).
- **Malformed/unparseable JSON** in the `rotation_samples` field - the
  server logs and treats it as absent, never fails the whole report
  submission over this one optional field.
- **Older app versions that don't send this field at all** - already
  handled by `required = false`; no behavior change for them.
- **Recording longer than the 10-second trim window** - samples are
  filtered to the trim window before upload (see Scope decisions above),
  bounding the payload to roughly the same ~50-sample size regardless of
  how long the raw recording was.

## Testing

Matching sub-project 1's pattern - unit tests for pure logic, no new
mocked-Android-framework tests:

- **Android:** a pure unit test for `List<RotationSample>` ->
  `List<RotationSampleDto>` -> JSON serialization (no Android/WorkManager
  dependency, directly testable with Gson).
- **Server:** a unit test for `RotationSampleDto` JSON parsing - valid
  JSON parses to the expected list; malformed JSON results in a `null`
  persisted value rather than throwing or failing the submission (a
  behavioral test, not a log-call assertion - this codebase has no
  existing logger-testing infrastructure anywhere, e.g. no Logback test
  appender, and introducing one solely for this would be disproportionate
  to a nice-to-have regression test). Covers the same gap the
  OSM-lookup-retry plan's final review found missing for
  `location_samples` (behaviorally, not via log verification), for both
  fields while this area is already being touched.
- **Server:** extend the existing `EndToEndFlowTest` with a case that
  submits a report including a `rotation_samples` field and asserts the
  stored value round-trips correctly - same style already used for
  `location_samples`.
- **Manual verification:** record a real clip while moving/turning,
  submit, query the report row directly, confirm the JSON array has
  multiple entries with headings that plausibly track real device
  rotation across the clip.
