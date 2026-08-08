# Improvements Backlog

Ideas identified during development that aren't scheduled yet. Grouped by
project area. **When work touches a listed area, check this list and
surface the relevant item(s) before/while working**, so they can be
considered rather than forgotten.

---

## Camera / Recording

**Area:** `app/src/main/java/com/trafficwatch/app/feature/camera/`

- **Allow zoom while recording.** No zoom control exists today. Explicitly
  deferred out of scope during the 2026-08-01 motorcycle-detection fix
  (trade-off: zoom narrows the field of view, which the corridor-consensus
  direction-analysis logic depends on seeing multiple vehicles across a
  wide lane to establish a reliable "normal flow" baseline — see
  `docs/superpowers/specs/2026-08-01-motorcycle-detection-resolution-fix-design.md`).
  Revisit alongside any future change to how corridor consensus is
  computed, or if users specifically ask for it again.
  *(added 2026-08-02)*

- **Show a small map on the recording screen with the current location and
  the direction the camera is pointing.** User-requested. Gives the person
  recording live feedback that GPS/compass data is actually being captured
  (today there's no visual indication at all), and could help them
  understand why a report might later come back with weak/`Unknown`
  direction evidence (e.g. no GPS fix yet). `core/ui/components/LocationMapView.kt`
  already exists (osmdroid, non-interactive, static pin) but only shows a
  plain marker with no heading/rotation - would need a rotating
  arrow/cone overlay driven by `CameraViewModel`'s already-live location
  and rotation-vector streams (the same continuous samples captured for
  sub-projects 1/2, `LocationUtil.observeLocation`/`CompassProvider.observeHeadings`)
  rather than a new capture mechanism. Needs a design pass: exact placement
  on `CameraScreen.kt`'s recording UI (already fairly dense - preview,
  record button, timer), size/prominence trade-off against not obscuring
  the camera preview, and whether to show it only pre-recording, only
  during recording, or both.
  *(added 2026-08-06)*

- **The recording-screen map's heading pin can pop out of and back into the
  layout while recording, given a GPS fix oscillating near the accuracy
  threshold.** `CameraUiState.locationState` reverts from `Fixed` to
  `Acquiring` whenever `accuracy > MAX_ACCEPTABLE_ACCURACY_METERS`
  (`CameraViewModel.kt`) - the map (and, per the just-shipped heading-map
  feature, the heading arrow with it) is gated on `locationState is Fixed`,
  so a fix bouncing around that threshold destroys and rebuilds the
  underlying `MapView` (re-fetching tiles) each time, a visible flash. This
  state-machine behavior predates the heading-map feature but is newly
  visible through it. Consider retaining the last known `Fixed` location
  for display purposes while actively recording, rather than reverting to
  no-map on a transient accuracy dip.
  *(added 2026-08-06, found during recording-screen-heading-map final review)*

- **The heading shown on the recording-screen map (and captured in
  `rotation_samples`) reflects the phone's physical top-of-device axis, not
  necessarily the camera's optical axis.** While filming in landscape
  orientation (the natural way to hold a phone for video), the top-of-device
  axis is roughly 90° off from the direction the camera lens is actually
  pointing. This is pre-existing capture semantics (shared with
  `rotation_samples`, used for direction analysis server-side), not
  something the heading-map feature introduced - but a user looking at the
  new live map, reading the pin as "where the camera points," could be
  misled by up to ~90° depending on how they're holding the phone. Would
  need either an orientation-aware remap (portrait vs. landscape,
  potentially device-rotation-sensor-driven) or explicit UI framing that
  the value represents device heading, not camera-lens heading.
  *(added 2026-08-06, found during recording-screen-heading-map final review)*

## Location / GPS accuracy

**Area:** `app/src/main/java/com/trafficwatch/app/core/util/LocationUtil.kt`
(capture side), `server/src/main/kotlin/com/trafficwatch/server/geo/`
(OSM street resolution side)

- **The client-side half of this is shipped** (2026-08-07,
  `feature/confirmlocation/ConfirmLocationScreen.kt`): the Review screen
  now shows a "Confirm Location" step whenever `accuracy > 10m`, letting
  the user drag the pin (constrained to `accuracy × 1.5` meters from the
  original GPS point) before submitting, with the submitted accuracy
  replaced by a fixed 5m "user-confirmed" value regardless of whether the
  pin was actually moved. **The server-side half remains open** - the
  server still searches a flat 50m Overpass radius regardless of a
  report's accuracy; scaling that radius to the reported accuracy was
  explicitly deferred as a separate, independent fix during the
  client-side design (a report with strong accuracy today still gets the
  same fixed 50m radius as one with a corrected/confirmed 5m accuracy -
  there's no server-side benefit yet from the client-side improvement).
  Original context, still accurate: a submitted report with 37.7m GPS
  accuracy resolved to the wrong OSM street ("Street 4", a small
  residential road) instead of the actual road in the video
  ("Khayaban-e-Jinnah", a major arterial) — the true position was ~56m
  from the reported point, exceeding both the phone's own accuracy
  estimate and the server's fixed 50m search radius.
  *(added 2026-08-02, client-side half shipped 2026-08-07)*

- **A divided-carriageway one-way road can be misjudged as a wrong-way
  violation on the far carriageway.** Found during the final review of the
  2026-08-04 OSM-lookup-retry/contested-corridor plan
  (`docs/superpowers/specs/2026-08-04-osm-lookup-retry-and-contested-corridor-fix-design.md`).
  That plan's fix lets a candidate in a "contested" (bimodal-bearing)
  corridor reach OSM evidence when at least one other corridor member
  shares its bearing (`ClipFlowAnalyzer.hasPeerSupport`) - the design
  reasoned this was safe because a genuinely two-way road is already caught
  earlier by `DirectionResolution.TwoWay`. That reasoning has a gap: a real
  divided road (physically separated carriageways, opposite legal
  directions) is mapped in OSM as *two separate ways, each tagged
  `oneway=yes`* - not one `oneway=no` way - so `StreetDirectionResolver`
  picks whichever carriageway is nearest and returns `OneWay`, never
  `TwoWay`. If `corridors.py`'s direction-agnostic spatial clustering
  (correct, intentional per the 2026-08-02 corridor-cohesion fix) merges
  both carriageways into one corridor, every legally-driving vehicle on the
  far carriageway now has peer support from its own carriageway-mates and
  gets evaluated against the near carriageway's OSM tag alone - a real risk
  of confirming a legally-driving motorist as a wrong-way violator, with
  their plate captured. Considered net-positive to ship anyway (the
  status quo - the contested-corridor gate blocking ALL confirmations
  whenever any traffic shares a corridor - demonstrably broke the primary
  one-way-street use case this whole system exists for), but this specific
  gap needs a real fix: either have `StreetDirectionResolver` detect a
  second nearby way that's roughly anti-parallel and also one-way (the
  dual-carriageway signature) and downgrade to `TwoWay`/`Unknown`, or
  require more than OSM-alone evidence when a corridor is contested.
  *(added 2026-08-04)*

## Direction analysis (compass + moving camera)

**Area:** `server/src/main/kotlin/com/trafficwatch/server/geo/ClipFlowAnalyzer.kt`

- **`ClipFlowAnalyzer.qualifiesForFlowExceptOrientation` doesn't mirror the
  new scale-bearing/recording-speed safety gate, causing an inaccurate
  REJECTED message in one case.** Found during the final-review fix wave's
  own scoped re-review for the 2026-08-06 approach-recession-bearing-fix
  plan. `qualifyVehicles` (`server/src/main/kotlin/com/trafficwatch/server/geo/ClipFlowAnalyzer.kt`)
  now drops any vehicle whose bearing came from the video-analysis service's
  bbox-scale fallback (`bearingSource == "scale"`) unless the recording
  vehicle's own GPS speed was verified low at that moment (see the plan's
  Critical fix - a moving dashcam closing on a parked/slower vehicle must
  not fabricate a wrong-way bearing). `qualifiesForFlowExceptOrientation`, a
  separate helper whose own docstring promises to mirror every
  `qualifyVehicles` gate except orientation resolution (used only to decide
  which REJECTED message to show), was not updated with this same check.
  Confirmed effect is limited to diagnostic message wording, not the
  safety-critical accept/reject decision itself: a vehicle correctly
  dropped by the real gate can cause the report to say "Vehicle orientation
  could not be determined for this report" instead of a more accurate
  "scale-sourced bearing could not be verified" reason - the report still
  correctly lands on REJECTED either way; no false wrong-way CONFIRMED
  verdict is possible from this gap. Fix: add the same
  `bearingSource == "scale"` + recording-speed check to
  `qualifiesForFlowExceptOrientation`, keeping it a true mirror of
  `qualifyVehicles` as its docstring already claims.
  *(added 2026-08-06, parked at the final-review fix wave's breaker rather
  than triggering a second fix wave - not load-bearing, message-accuracy
  only)*

## Upload reliability / data integrity

**Area:** `app/src/main/java/com/trafficwatch/app/feature/upload/UploadWorker.kt`,
`core/domain/usecase/RetryUploadUseCase.kt`, `server/src/main/kotlin/com/trafficwatch/server/reports/ReportService.kt`

- **`recorded_at` and `location_samples`' `captured_at` use two different,
  disagreeing time bases.** `UploadWorker`'s `SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)`
  (used to build `recorded_at`) has no explicit `TimeZone` set, so it
  formats in the device's local zone while appending a literal `Z` claiming
  UTC — for a Pakistan-local device this is 5 hours off from true UTC. The
  continuous GPS samples added in the 2026-08-03 plan (`captured_at`) are
  true epoch millis and don't share this bug. This was a pre-existing wart
  in `recorded_at` before that plan, but it now matters more: any future
  code that correlates `location_samples` timestamps against `recorded_at`
  (e.g. sub-project 3's fusion work) will see them disagree by the device's
  UTC offset. Fix `recorded_at`'s formatter to use `TimeZone.getTimeZone("UTC")`
  explicitly.
  *(added 2026-08-03, found during continuous-GPS-heading-capture final review)*
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
  **Confirmed happening in production, not just theoretical**: during
  2026-08-05 manual verification of continuous-rotation-vector-capture, a
  real submission on unstable Wi-Fi failed its first attempt 5 times
  (`SocketTimeoutException`/`Connection reset`/`EOFException` in server
  logs) before a retry finally succeeded - the landed report
  (`2dcf9912-7c7e-47e6-a761-d8e3d226722e`) had `compass_heading_degrees`,
  `location_samples`, and `rotation_samples` all null, exactly as this
  entry predicts. A second, clean first-attempt submission on stable
  Wi-Fi confirmed all three fields populate correctly when no retry is
  involved - so the capture/wire/persistence path itself is fine; this
  retry gap is the only thing that drops the data.
  *(added 2026-08-03, updated 2026-08-04 to cover rotation_samples too,
  confirmed in production 2026-08-05)*
- **`Report.locationSamples`/`rotationSamples` round-trip a Kotlin `null`
  through the database as the literal 4-character text `"null"`, not a
  true SQL NULL - any code reading these columns must check for both.**
  Found during the 2026-08-05 continuous-orientation-fusion plan's Task 3:
  both columns are `String?` mapped with `@JdbcTypeCode(SqlTypes.JSON)`
  (`server/src/main/kotlin/com/trafficwatch/server/reports/Report.kt`);
  Hibernate's JSON `UserType` for a String-typed property serializes an
  absent value as the JSON-null token `"null"` on write and hands that
  same literal text back on read, rather than a SQL NULL. This task was
  the first code ever to read these two columns back after a real
  Hibernate round-trip (every prior consumer only wrote them) - running
  straightforward `report.locationSamples != null` / `json?.let { ... }`
  logic against it caused 6 real `ReportAnalysisIntegrationTest` failures
  (the async analysis job crashed silently, reports stuck `PENDING`) before
  being root-caused and fixed by checking `json == null || json == "null"`
  in both `ReportAnalysisJob`'s new `parseLocationSamples`/
  `parseRotationSamples` helpers. No false-positive risk (a real sample
  list always serializes as `[...]`, never the bare string `"null"`), but
  the fix is duplicated across the two near-identical helper functions and
  isolated to this one read site - any *future* code that reads either
  column (a new endpoint, a data migration, a debug tool) will hit the
  same trap unless it independently knows to check for the `"null"`
  string too. Worth a shared helper (or an entity-level Hibernate
  converter that normalizes `"null"` to a true absent value at the
  persistence boundary, closing the gap for every consumer at once) if a
  third `@JdbcTypeCode(SqlTypes.JSON)` `String?` column is ever added, or
  proactively before then.
  *(added 2026-08-05, found during continuous-orientation-fusion Task 3)*
- **`ReviewViewModel.submit()` has no error handling around the enqueue
  call, so any exception there (e.g. a WorkManager `Data` payload
  overflow, or any other `SubmitReportUseCase.invoke()` failure) crashes
  the app via an unhandled coroutine exception** - and since
  `reportRepository.saveReport(...)` already ran before the enqueue
  attempt, the local report is stranded in `UPLOADING` forever with no
  work ever queued for it. Found during the continuous-rotation-vector-
  capture plan's final review, alongside the WorkManager-overflow bug it
  fixed - that specific crash cause is now fixed, but the missing
  try/catch itself is a general resilience gap: any other future
  `enqueue()` failure would hit the same crash-plus-stranded-row failure
  mode. Wrapping the `submitReportUseCase` call in
  `ReviewViewModel.submit()`/`confirmCellularSubmit()` with a try/catch
  that surfaces an error state and rolls the report back out of
  `UPLOADING` (or marks it `UPLOAD_FAILED`, matching `UploadWorker`'s own
  failure handling) would close this off generally.
  *(added 2026-08-05, found during continuous-rotation-vector-capture final review)*
- **A `MediaMetadataRetriever` duration-extraction failure (falls back to
  `0L`) makes the trim-window's `windowEnd` equal `windowStart`, silently
  discarding both `location_samples` and `rotation_samples` at once.**
  `AppNavigation.kt`'s REVIEW composable shares one `windowStart`/
  `windowEnd` pair between both sample lists' filters (a deliberate,
  correct design choice) - but that sharing means a single duration-
  extraction failure now zeroes both signals simultaneously, where before
  (sub-project 1 alone) it only zeroed `location_samples`. Pre-existing in
  kind, not a new bug, but worth fixing before sub-project 3 needs to
  distinguish "sensor unavailable" from "duration extraction failed" for
  either signal.
  *(added 2026-08-05, found during continuous-rotation-vector-capture final review)*
- **Automatic WorkManager retries (attempts 2-3, per `Result.retry()` with
  `runAttemptCount < 3`) show a "Failed" chip with no upload progress at
  all, even while bytes are actively moving.** `UploadWorker.kt` writes
  `ReportStatus.UPLOAD_FAILED` to Room on any exception before retrying -
  nothing resets the row back to `ReportStatus.UPLOADING` when WorkManager
  re-runs the worker for a retry attempt (only `SubmitReportUseCase` and
  `RetryUploadUseCase`, both user/first-attempt-triggered paths, ever write
  `UPLOADING`). `HistoryViewModel.uploadProgress` filters on
  `status == UPLOADING`, so it correctly excludes these reports - the chip
  was already showing the wrong status before the upload-progress-indicator
  feature existed, but that feature is now invisible for exactly the
  attempts (2 and 3) most likely to belong to a slow/flaky connection a
  user most wants feedback on. Fix: have `UploadWorker.doWork()` write the
  row back to `UPLOADING` at the very top (or right after catching an
  exception, before returning `Result.retry()`), matching the status the
  work is actually still doing.
  *(added 2026-08-06, found during upload-progress-indicator final review)*
- **Two call sites still construct `WorkManager` via
  `WorkManager.getInstance(context)` instead of the new
  `WorkManagerModule`-provided injected instance.** `SubmitReportUseCase.kt`
  and `RetryUploadUseCase.kt` predate the DI module added for
  `HistoryViewModel`'s testability - both still call `getInstance` inline.
  No functional divergence (same process singleton either way), purely a
  style inconsistency now that the injected pattern exists. Migrate both to
  constructor-injected `WorkManager` for consistency, next time either file
  is touched.
  *(added 2026-08-06, found during upload-progress-indicator final review)*
- **The live upload-progress "Z MB/s" figure is an instantaneous
  ~300ms-sample rate and will visibly jitter.** `UploadProgressTracker`
  computes rate from just the two most recent emission points
  (`(bytesWritten - lastEmittedBytes) * 1000 / elapsedMs`) - correct, but
  noisy frame-to-frame on a real connection with variable throughput. An
  exponential moving average over the last few emissions (a small,
  self-contained change confined to `UploadProgressTracker`, fully
  unit-testable like the rest of that class) would read much more smoothly
  without changing the class's external contract.
  *(added 2026-08-06, found during upload-progress-indicator final review)*
