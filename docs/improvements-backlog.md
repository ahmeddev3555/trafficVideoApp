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

- **Shipped 2026-08-12** (`CameraController.kt`, `CompassProvider.kt`,
  `docs/superpowers/specs/2026-08-10-recording-heading-rotation-correction-design.md`):
  the heading captured during recording (`rotation_samples` and the
  one-shot `compassHeadingDegrees` snapshot) previously reflected the
  phone's physical top-of-device axis via `SensorManager.getOrientation()`,
  which is always relative to the device's fixed natural (portrait)
  orientation - a landscape-held recording reported a systematically wrong
  heading, confirmed by an on-device test (portrait/landscape-left/
  landscape-right facing the same direction produced three different
  headings). `CompassProvider` now applies
  `SensorManager.remapCoordinateSystem()` using `CameraController`'s
  already-tracked `Surface.ROTATION_*` state, re-evaluated per sample so a
  mid-recording re-orientation is reflected in later samples rather than
  only the value at recording start. The final review independently traced
  the correction against Android's real implementation and confirmed it's
  mathematically correct for all four rotation states.

- **Residual heading instability specifically in one landscape orientation,
  found during the above fix's on-device verification.** Across 3 separate
  on-device tests (2026-08-12), portrait consistently read stable and
  clean, and one landscape orientation (`Surface.ROTATION_90`) settled
  cleanly and agreed with portrait within ~6°, but the other
  (`Surface.ROTATION_270`) was noisy or slow-to-settle in 2 of the 3
  attempts - in the worst case, never stabilizing at all over ~6 seconds of
  held-still recording, drifting across an 84° range. The final review's
  independent math trace ruled out a sign/axis bug in the fix itself (the
  correction is symmetric across all four rotation states) - the leading
  hypothesis is that `SensorManager.getOrientation()`'s azimuth computation
  is inherently more sensitive to small tilt-angle variations at some
  device orientations than others (a known characteristic of this API, not
  something the fix introduced - previously masked because landscape
  recording was *always* wrong, so a stable-but-wrong reading was
  indistinguishable from a noisy-and-wrong one). Not a new safety risk
  (unstable orientation evidence is already handled conservatively by the
  server's evidence-fusion pipeline, rather than being trusted to assert a
  confident verdict), so shipping the fix as a net improvement was the
  right call rather than blocking on this. Needs further investigation in
  a controlled setting (magnetic-interference-free location, or averaging
  the rotation-vector reading over a longer window) to determine whether
  smoothing/filtering can close the gap, or whether it's an inherent
  hardware/API limitation to document instead.
  *(added 2026-08-06, root-caused and client-side fix shipped 2026-08-12,
  residual instability found during that fix's on-device verification)*

## Location / GPS accuracy

**Area:** `app/src/main/java/com/trafficwatch/app/core/util/LocationUtil.kt`
(capture side), `server/src/main/kotlin/com/trafficwatch/server/geo/`
(OSM street resolution side)

- **Shipped 2026-08-08** (`StreetDirectionResolver.kt`,
  `docs/superpowers/specs/2026-08-08-osm-street-direction-resolution-accuracy-design.md`):
  both the client-side (2026-08-07) and server-side halves of the
  weak-GPS-accuracy problem are now closed, plus the separate
  divided-carriageway misjudgment risk below. The server's Overpass search
  radius now scales with the report's own accuracy
  (`clamp(accuracy × 2.0, 50m, 200m)`, config-driven via `OsmProperties`),
  the nearest-way selection is ambiguity-aware (two different-named
  candidates within accuracy-meters of each other in distance downgrade to
  `Unknown` instead of confidently picking one - scanning past same-name
  sibling segments to find the nearest genuinely different street), and the
  lat/lon result cache is radius-and-accuracy-aware so a result cached from
  a narrow/precise lookup is never wrongly served to a later
  wider/less-precise one. Original context: a submitted report with 37.7m
  GPS accuracy once resolved to the wrong OSM street ("Street 4") instead
  of the actual road ("Khayaban-e-Jinnah") — the true position was ~56m
  from the reported point, exceeding both the phone's accuracy estimate and
  the old fixed 50m search radius.

- **Divided-carriageway false-positive risk - shipped 2026-08-08** (same
  plan as above): `StreetDirectionResolver` now detects a second nearby way
  that's also `oneway`-tagged and anti-parallel to the chosen way's legal
  bearing (measured by genuine segment-to-segment separation, within 30m),
  and downgrades the result to `Unknown` instead of confidently asserting a
  legal direction from just one carriageway's tag - closing the real risk
  (found 2026-08-04) of confirming a legally-driving motorist on the far
  carriageway as a wrong-way violator.
  *(added 2026-08-02 and 2026-08-04, both shipped 2026-08-08)*

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
