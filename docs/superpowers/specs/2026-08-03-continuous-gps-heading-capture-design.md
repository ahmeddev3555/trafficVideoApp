# Continuous GPS Heading Capture Design

## Context

The wrong-way direction-analysis pipeline currently converts each tracked
vehicle's frame-relative bearing into a real-world compass bearing using a
single formula in `ClipFlowAnalyzer.qualifyVehicles()`:
`absoluteBearingDegrees = (compassHeadingDegrees + frameBearing) % 360.0`.
`compassHeadingDegrees` comes from a single compass reading captured once,
at the exact instant recording starts (`CameraViewModel.onStartRecording()`
-> `CompassProvider.getSnapshot(...)`). This formula implicitly assumes the
camera's orientation is constant for the entire clip - true for a
stationary bystander (the scenario this system was originally designed
around), false for a recording made from inside a moving vehicle.

This was root-caused on a real diagnosed report: a confirmed wrong-way
motorcycle's frame-relative bearing (171 degrees) was verified solid across
its full ~1.1-second tracked trajectory (7 of 9 consecutive frame-to-frame
segments clustered tightly around it), but converting it to an absolute
bearing using the single compass snapshot produced a value that didn't
oppose the corridor consensus within tolerance. The report's own submitted
GPS metadata showed `bearing: 0.0 degrees, speed: 0.0 m/s` at the exact
instant the compass snapshot was taken - the vehicle was very likely
stationary (or the GPS hadn't yet locked a velocity) at that single
captured moment, even though the video shows a fast-moving multi-lane road
throughout the full 10-second clip. This gap is logged in
`docs/improvements-backlog.md` under "Direction analysis (compass + moving
camera)".

## Scope decomposition (confirmed with the user - do not re-litigate)

Fixing this properly requires combining three independent signal sources
for tracking camera motion throughout a recording: continuous GPS heading,
continuous gyroscope/rotation-vector sampling, and video-based visual
odometry. This is too large for one spec - it spans Android sensor
capture (two independent streams), a server-side fusion/data-model change,
and a substantial standalone computer-vision feature. It's split into
ordered sub-projects, each independently shippable:

1. **This plan: Android continuous GPS heading capture.** Capture, upload,
   and persist a time-series of GPS fixes throughout each recording.
   Verifiable entirely on its own (record -> submit -> query the stored
   data) with zero changes to how direction is currently computed.
2. Android continuous gyroscope/rotation-vector capture (not this plan).
3. Server-side fusion of (1) and (2) into a continuous per-timestamp
   camera-orientation signal, replacing the single `compassHeadingDegrees`
   scalar `ClipFlowAnalyzer` uses today (not this plan - this is the piece
   that actually closes the original bug).
4. Video-analysis visual odometry, as an independent follow-on validation/
   fallback layer once 1-3 exist (not this plan).

**This plan covers only #1.** No changes to `ClipFlowAnalyzer`,
`ReportAnalysisJob`, or any direction-analysis logic - this data is
captured, transmitted, and stored, not yet consumed.

## Scope & explicit decisions (confirmed with the user - do not re-litigate)

- **Sampling interval: 1000ms during active recording**, separate from the
  existing 3000ms UI-only location stream (`CameraViewModel.observeLocation`,
  which drives the `LocationState` GPS-status indicator and is left
  unchanged). A 10-second clip (`MAX_TRIM_DURATION_MS`) yields up to ~10
  samples. This is an additional, recording-scoped location-request
  subscription, not a change to the existing UI stream's behavior or
  interval.
- **Storage: a single JSON column on the `reports` table**
  (`location_samples`, `jsonb`), matching the existing `direction_evidence`
  column's pattern - not a new dedicated table. The sample list is always
  small (bounded by the 10-second recording cap) and only ever accessed
  together with its parent report.
- **Wire format: one new optional multipart field**, `location_samples`,
  a JSON array string - same backward-compatibility convention as the
  existing `compass_heading_degrees` field (`required = false`; absent on
  submissions from older app versions, never blocks submission).

## Data flow

```
CameraViewModel.onStartRecording()
  -> starts collecting locationUtil.observeLocation(intervalMs = 1000)
     into a growing list, alongside the existing single getSnapshot()/
     compass reads (unchanged)
CameraViewModel.stopRecording() -> stops the collection job
CameraViewModel.getLocationSamples(): List<LocationData>
  -> threaded through AppNavigation's Compose state exactly like
     snapshotLocation already is today, into ReviewScreen -> ReviewViewModel
SubmitReportUseCase.invoke(...) gains a new locationSamples parameter
  -> mapped to List<LocationSampleDto> and Gson-serialized to a JSON string
  -> passed through WorkManager's input Data as a new String key
     (Data only supports primitives/strings - a nested list can't go
     through it directly as-is), read back out in UploadWorker.doWork()
  -> new multipart field "location_samples" on POST /v1/reports
Server: ReportController accepts the new optional param, parses it with
  Jackson into a List<LocationSampleDto>, re-serializes to a canonical
  JSON string, stores it in the new location_samples JSONB column -
  no consumption logic yet.
```

## Components touched

**Android - new:**
- `app/src/main/java/com/trafficwatch/app/core/data/remote/dto/LocationSampleDto.kt`
  - a dedicated Gson DTO (not the shared `LocationData` domain model) with
    explicit `@SerializedName` snake_case fields (`latitude`, `longitude`,
    `accuracy`, `altitude`, `bearing`, `speed`, `captured_at`), matching
    this codebase's existing DTO convention (`AuthDtos.kt`/`ReportDtos.kt`
    both annotate explicitly - this app's Gson instance has no global
    naming policy).

**Android - modified:**
- `core/util/LocationUtil.kt` - `observeLocation()` gains an
  `intervalMs: Long = LOCATION_UPDATE_INTERVAL_MS` parameter (default
  preserves today's 3s behavior for existing callers unchanged).
- `feature/camera/CameraViewModel.kt` - new
  `RECORDING_SAMPLE_INTERVAL_MS = 1_000L` constant; `onStartRecording()`
  launches a coroutine collecting `locationUtil.observeLocation(RECORDING_SAMPLE_INTERVAL_MS)`
  into a growing `MutableList<LocationData>` (cleared at the start of each
  recording); `stopRecording()` cancels that job. New accessor
  `getLocationSamples(): List<LocationData>`.
- `feature/camera/CameraScreen.kt` - `onVideoRecorded` callback gains a
  4th argument, the samples list.
- `navigation/AppNavigation.kt` - new
  `var locationSamples by remember { mutableStateOf<List<LocationData>>(emptyList()) }`
  (`remember`, not `rememberSaveable`, matching `snapshotLocation`'s
  existing precedent - `LocationData` isn't Parcelable). Threaded into
  `ReviewScreen`; reset to `emptyList()` alongside `snapshotLocation = null`
  after a successful submit.
- `feature/review/ReviewViewModel.kt` - `ReviewUiState` gains
  `locationSamples: List<LocationData> = emptyList()`, populated in
  `init(...)`.
- `core/domain/usecase/SubmitReportUseCase.kt` - `invoke(...)` gains a
  `locationSamples: List<LocationData>` parameter; maps to
  `List<LocationSampleDto>` and Gson-serializes to a JSON string before
  building the upload request.
- `feature/upload/UploadWorker.kt` - `buildInputData(...)` gains a new
  `KEY_LOCATION_SAMPLES_JSON` string key, omitted entirely when the list
  is empty (same "presence, not sentinel" convention already used for
  `KEY_COMPASS_HEADING`); `doWork()` reads it back and passes it as the
  new multipart field.
- `core/data/remote/ApiService.kt` - `submitReport(...)` gains
  `@Part("location_samples") locationSamples: RequestBody?`.

**Server - new:**
- `server/src/main/kotlin/com/trafficwatch/server/reports/dto/LocationSampleDto.kt`
  - plain camelCase Kotlin properties (`latitude`, `longitude`,
    `accuracy`, `altitude`, `bearing`, `speed`, `capturedAt: Long`) - the
    server's global Jackson snake_case naming strategy maps `capturedAt`
    to JSON key `captured_at` with no extra annotations, exactly matching
    the Android DTO's `@SerializedName("captured_at")` key (deliberately
    named to match - a longer name like `capturedAtEpochMs` would instead
    auto-map to `captured_at_epoch_ms` and silently fail to parse the
    Android-sent field).
- `server/src/main/resources/db/migration/V7__add_location_samples_to_reports.sql`
  - `ALTER TABLE reports ADD COLUMN location_samples JSONB;`

**Server - modified:**
- `reports/ReportController.kt` - `submitReport(...)` gains
  `@RequestParam("location_samples", required = false) locationSamplesJson: String?`;
  parses with Jackson into `List<LocationSampleDto>` (lenient - malformed/
  unparseable input is logged and treated as absent, never fails the whole
  submission), re-serializes to a canonical JSON string before persisting.
- `reports/Report.kt` - gains `locationSamples: String?`,
  `@JdbcTypeCode(SqlTypes.JSON)`, `columnDefinition = "jsonb"`, exactly
  matching the existing `directionEvidence` column's pattern.

**Unchanged:** `ClipFlowAnalyzer.kt`, `ReportAnalysisJob.kt`, and every
other piece of direction-analysis logic - this plan only captures,
transmits, and stores the data.

## Edge cases

- **No samples captured** (GPS unavailable throughout, or the recording
  was too short for even one 1-second-interval tick) - the field is simply
  omitted from the upload (same "presence, not sentinel" convention as
  compass heading), and the server column stays `null`. Never blocks
  submission.
- **Malformed/unparseable JSON** in the `location_samples` field (a
  future/different client, or corruption) - the server logs and treats it
  as absent, never fails the whole report submission over this one
  optional field.
- **Older app versions that don't send this field at all** - already
  handled by `required = false`; no behavior change for them.

## Testing

Matching this project's existing pattern - unit tests for pure logic, no
new mocked-Android-framework tests:

- **Android:** a pure unit test for the `List<LocationData>` ->
  `List<LocationSampleDto>` -> JSON serialization step (no Android/
  WorkManager dependency, directly testable with Gson - same spirit as
  `CnicFormatterTest`).
- **Server:** a unit test for `LocationSampleDto` JSON parsing - valid
  JSON parses to the expected list; malformed JSON returns null/empty
  rather than throwing.
- **Server:** extend the existing `EndToEndFlowTest` (real HTTP, no
  mocks) with a case that submits a report including a `location_samples`
  field and asserts the stored value round-trips correctly - same style
  of test already used for this exact endpoint.
- **Manual verification:** record a real clip while moving, submit, query
  the report row directly, confirm the JSON array has multiple entries
  spread across the recording's real duration.
