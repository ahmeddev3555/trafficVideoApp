# Improvements Backlog

Ideas identified during development that aren't scheduled yet. Grouped by
project area. **When work touches a listed area, check this list and
surface the relevant item(s) before/while working**, so they can be
considered rather than forgotten.

---

## Navigation / UI flow

**Area:** `app/src/main/java/com/trafficwatch/app/navigation/AppNavigation.kt`
(`onNewReport`/`Routes.PERMISSIONS`/`Routes.CAMERA`/`Routes.TRIM` composables),
`app/src/main/java/com/trafficwatch/app/feature/history/HistoryScreen.kt`
(the "+" `FloatingActionButton`)

- **Tapping "+" on the Reports screen after a submission incorrectly opens
  the Trim screen instead of the Camera screen.** Reported by the user: the
  "+" button's wiring (`HistoryScreen`'s `onNewReport` -> `AppNavigation`
  sets `permissionsNextRoute = Routes.CAMERA` and navigates to
  `Routes.PERMISSIONS`, which on `onAllGranted` navigates to
  `permissionsNextRoute`) looks like it should always land on `Routes.CAMERA`
  - the root cause isn't yet identified (candidates: stale `rawVideoFile`/
  `trimmedVideoFile` `rememberSaveable` state from the previous recording
  not being fully cleared before the new navigation, a `PermissionsScreen`
  auto-skip path, or a back-stack/`popUpTo` interaction) - needs a proper
  investigation when picked up, not a guessed fix.
  *(added 2026-08-04)*

- **On the Trim screen, the "Preview" button doesn't actually preview the
  trimmed clip.** Reported by the user. `TrimScreen.kt`'s `ExoPlayer` is
  loaded with the raw (untrimmed) recording (`exoPlayer.setMediaItem(...,
  rawVideoFile)`), and the "Preview" button just does
  `exoPlayer.seekTo(uiState.trimStartMs); exoPlayer.play()` - it seeks into
  the raw video at the selection's start but never stops playback at
  `uiState.trimEndMs`, and `repeatMode = Player.REPEAT_MODE_ONE` means it
  loops the raw video rather than looping just the selected window. The
  user expects this button to show what the actual trimmed output will
  look like (start-to-end of the selection only, then stop or loop within
  that window) - needs either bounding playback to
  `[trimStartMs, trimEndMs]` on the raw-video player, or actually running
  the trim first and previewing the real output file.
  *(added 2026-08-04)*

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

## Location / GPS accuracy

**Area:** `app/src/main/java/com/trafficwatch/app/core/util/LocationUtil.kt`
(capture side), `server/src/main/kotlin/com/trafficwatch/server/geo/`
(OSM street resolution side)

- **Prompt the user to confirm/correct the exact location on a map when GPS
  accuracy is weak** (e.g. accuracy > 10m). Confirmed real-world impact:
  a submitted report with 37.7m GPS accuracy resolved to the wrong OSM
  street ("Street 4", a small residential road) instead of the actual road
  in the video ("Khayaban-e-Jinnah", a major arterial) — the true position
  was ~56m from the reported point, exceeding both the phone's own accuracy
  estimate and the server's fixed 50m Overpass search radius. The server
  currently searches a flat 50m radius around the raw GPS point regardless
  of how uncertain that point actually is. A map-confirmation prompt (or,
  alternatively, widening the search radius to scale with reported
  accuracy) would directly address this.
  *(added 2026-08-02)*

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

**Area:** `app/src/main/java/com/trafficwatch/app/core/util/CompassProvider.kt`
(single-snapshot capture side), `server/src/main/kotlin/com/trafficwatch/server/geo/ClipFlowAnalyzer.kt`
(`absoluteBearingDegrees = (compassHeadingDegrees + frameBearing) % 360.0`)

- **Absolute bearing conversion assumes a stationary camera; recordings from
  a moving vehicle can produce a wrong-way verdict miss even when both the
  vehicle detection and the frame-relative tracking are correct.** Root-caused
  via a real diagnosed clip: the frame-relative bearing computed for a
  confirmed wrong-way motorcycle (171°, from a 10-point/~1.1s tracked
  trajectory) checked out as numerically solid — 7 of 9 consecutive
  frame-to-frame segments clustered tightly around it. But converting it to
  an absolute compass bearing via a single compass reading taken once at
  recording start assumes that reading describes the camera's orientation
  for the whole clip. In this report, the device's own submitted GPS
  metadata showed `bearing: 0.0°, speed: 0.0 m/s` at that same instant
  (car likely stationary or GPS not yet locked when the snapshot was
  taken), while the video shows a busy, fast-moving multi-lane road
  throughout — strongly suggesting the vehicle started moving sometime
  during the clip, invalidating the single static compass snapshot as a
  stand-in for the whole recording's camera orientation. Independent
  corroboration: normal, correctly-flowing traffic's own frame-relative
  bearings clustered around 245-287° (mostly lateral/"leftward" apparent
  motion) rather than near 0° (straight-ahead-and-receding, what you'd
  expect from a truly stationary camera pointed down a straight road) -
  consistent with the camera itself actively moving during the clip.
  This is the same "designed for a stationary bystander, not a moving
  dashcam" gap already noted for corridor clustering/cohesion
  (`docs/superpowers/specs/2026-08-02-corridor-cohesion-locality-fix-design.md`)
  and the displacement floor
  (`docs/superpowers/specs/2026-08-02-displacement-floor-bbox-relative-fix-design.md`),
  but showing up here in the bearing conversion itself rather than in
  clustering. Real fix is a bigger effort than those two - likely either
  continuous compass sampling throughout the clip (not just at recording
  start) so the true camera orientation is known at the moment each
  vehicle's bearing is measured, or a different direction-determination
  approach that doesn't depend on a single static reading at all.
  *(added 2026-08-02)*

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
  *(added 2026-08-03, updated 2026-08-04 to cover rotation_samples too)*
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
