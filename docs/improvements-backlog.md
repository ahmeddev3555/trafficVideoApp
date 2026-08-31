# Improvements Backlog

Ideas identified during development that aren't scheduled yet. Grouped by
project area. **When work touches a listed area, check this list and
surface the relevant item(s) before/while working**, so they can be
considered rather than forgotten.

---

## Camera / Recording

**Area:** `app/src/main/java/com/trafficwatch/app/feature/camera/`

- **Allow zoom while recording - shipped 2026-08-12.** CameraX zoom capped
  at 2x, driven by both a pinch gesture and three quick-select pill buttons
  (1x/1.5x/2x), locked once recording starts (enforced at
  `CameraController.setZoomRatio` itself, not just via UI gating). The
  captured ratio threads end-to-end (Android → server → video-analysis) and
  scales the two pixel-space thresholds that are genuinely zoom-sensitive
  (`corridors.py`'s clustering threshold, `tracking_bearing.py`'s minimum
  displacement floor) — see
  `docs/superpowers/specs/2026-08-12-zoom-while-recording-design.md`. The
  original deferral concern (narrower field of view reduces available
  traffic for corridor-consensus baseline-building) is a deliberate,
  documented, unaddressed trade-off, not a bug — a hard 2x cap keeps it
  modest.
  *(added 2026-08-02, shipped 2026-08-12)*

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

- **The divided-carriageway guard above did NOT catch a real recurrence:
  report `a6877462-0675-482e-a2a8-a8d096649b9a` was confidently CONFIRMED
  (`wrong_way_confidence` 0.5711, just above the 0.5 threshold) against a
  vehicle that the stored evidence frame shows is plainly rear-facing -
  travelling the same direction as the recording car and every other
  vehicle in shot, not oncoming. Root-caused as far as reproducible: this
  is real, still-current OSM geometry (خیبان جناح / Khayaban-e-Jinnah has
  two independently-tagged `oneway=yes` ways only ~9-12m apart, opposite
  bearings - textbook divided-carriageway data). A new regression test,
  `StreetDirectionResolverTest`'s
  `downgrades to Unknown for the real Khayaban-e-Jinnah divided carriageway
  behind report 649b9a` (fixture:
  `server/src/test/resources/fixtures/overpass-khayaban-e-jinnah-report-649b9a.json`,
  captured live from Overpass), feeds this exact real geometry through the
  real resolver code and correctly gets `Unknown` - proving the guard logic
  itself is sound against this data. Also ruled out: cache staleness (the
  report's stored bearing has full double precision, provably from a fresh
  `resolveFresh()` call, not a rounded cache hit), a search-radius bug
  (`computeSearchRadius` is correct; 50m radius comfortably covers both
  ways), and stale OSM tagging (way 726823670 has been `oneway=yes` since
  2019, unchanged). Leading remaining hypothesis, unconfirmed: the live
  Overpass API's response to the production server's actual request, at
  the moment `ReportAnalysisJob` ran, only contained one of the two ways -
  plausibly the public overpass-api.de service's multi-replica/eventual-
  consistency behavior, not a bug in this codebase. Nothing logs the raw
  Overpass response at request time, so this can't be proven after the
  fact; would need response logging added before the next occurrence to
  confirm.

  **Update - non-deterministic single-fetch root cause addressed 2026-08-31**
  (spec/plan
  `2026-08-31-divided-carriageway-resolution-and-approach-on-unknown`):
  `OverpassClient` now queries multiple mirrors in sequence and unions the
  returned ways deduped by id, so one stale replica can no longer decide a
  result on its own; a `OneWay` backed by only a single un-cross-checked
  source downgrades to `Unknown(NOT_CROSS_CHECKED)`; the `osm_lookup_cache`
  now carries a 30-day TTL, so a poisoned row self-heals on the next lookup
  rather than sticking forever; and per-endpoint Overpass response logging
  (endpoint host, way count, way ids) is now in place, so the next
  occurrence is diagnosable straight from the server logs.
  *(added 2026-08-17, found investigating report 649b9a; root cause
  addressed 2026-08-31)*

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

- **No correction for camera translation (recording from a moving
  vehicle) - only rotation is compensated for.** Confirmed by a full trace
  of the pipeline (2026-08-13): sub-projects 1-3 of the "fix camera motion
  tracking" effort (`docs/superpowers/specs/2026-08-03-...`,
  `2026-08-04-...`, `2026-08-05-...`) give `OrientationTimeline` a
  continuous, per-vehicle-timestamp camera *heading* (from fused
  gyroscope/rotation-vector samples, or GPS bearing as a fallback), so
  panning/turning the phone mid-recording is handled correctly. But nothing
  corrects for the camera's own *position* changing over time - if the
  recording device itself is moving through space (dashboard-mounted,
  held out a car window), nearby objects sweep across the frame faster
  than distant ones from motion parallax alone, independent of their real
  direction of travel, and this is indistinguishable from genuine lateral
  vehicle motion in `tracking_bearing.py`'s pixel-space bearing math. The
  one related case that *is* handled -
  `ClipFlowAnalyzer.qualifyVehicles`'s recording-speed gate on
  `bearingSource == "scale"` (see the entry above) - only protects the
  head-on approach/recession fallback, not ordinary lateral bearing
  computation. True correction would need visual odometry (estimating the
  camera's own motion from the video background), which was explicitly
  scoped as the broad version of sub-project 4 and deliberately deferred:
  see "Non-goals" in
  `docs/superpowers/specs/2026-08-06-approach-recession-bearing-fix-design.md`.
  Net effect: recording from a moving vehicle degrades gracefully (the
  system's gates suppress unreliable signals rather than fabricate a wrong
  verdict) but is not fully corrected - stationary or in-place-panning
  camera use remains the reliable case the system was designed around.
  *(added 2026-08-13)*

## Vehicle detection / tracking

**Area:** `video-analysis/app/detection.py`, `video-analysis/app/pipeline.py`,
`video-analysis/app/tracking_bearing.py`,
`server/src/main/kotlin/com/trafficwatch/server/geo/ClipFlowAnalyzer.kt`

- **Motorcycles near the recording camera were silently dropped by
  tracking entirely - shipped 2026-08-13.** Found while auditing a
  confirmed wrong-way report: two real motorcycles, detected by YOLO
  repeatedly and confidently (0.48-0.81) across many sampled frames, never
  appeared anywhere in the tracked-vehicle output. Root-caused to a
  hardcoded, non-configurable 0.7 IoU threshold deep inside the
  `supervision` library's `ByteTrack` for confirming a brand-new track -
  a small, fast-moving object's frame-to-frame box overlap collapses below
  it under this service's sampling far more easily than a larger, farther
  car's does for the same absolute pixel displacement (measured real IoU:
  0.0-0.432 on the production clip that surfaced this). No public
  `ByteTrack` parameter touches that gate; the only lever that reliably
  fixed it (confirmed empirically) was raising `frame_stride` from 3 to 1.
  Shipped as: a dedicated `ByteTrack` instance for motorcycle-class
  detections fed at the new denser cadence, with car/bus/truck tracking's
  own parameters left untouched; plus five follow-on calibration fixes a
  final review caught as second-order effects of the denser sampling
  (`ByteTrack`'s occlusion-tolerance buffer is measured in ticks, not
  seconds, and silently lost ~2/3 of its real tolerance for cars;
  frame-count-based quality gates - `MIN_OBSERVATIONS`/`DEFAULT_SAMPLE_SIZE`
  in Python, `MIN_TRACK_FRAMES`/`TRACK_FRAMES_SATURATION` in Kotlin - are
  also tick-based and needed the same 3x rescaling to keep meaning the same
  real-world duration; `corridors.py`'s O(n²) clustering cost needed
  bounding via path-point capping, since tracks now have ~3x more points).
  Also fixed in the same pass, found during review: a pre-existing,
  unrelated bug where the long-lived tracker singleton never reset between
  videos, letting one report's tracking state leak into the next.
  See `docs/superpowers/specs/2026-08-13-motorcycle-tracking-iou-fix-design.md`.
  *(added and shipped 2026-08-13)*

- **[HIGH - found 2026-08-30] A motorcycle riding straight at a
  stationary camera, against traffic, is not recognised as wrong-way at
  all: its frame bearing is pulled toward the traffic flow (and sometimes
  its track also fragments).** Three reports on the same خیبان جناح
  (Jinnah Ave) stretch, all REJECTED as "Conflicting direction evidence",
  each with a user-confirmed wrong-way rider coming toward the (stationary,
  GPS speed 0) camera along the median while all other traffic recedes:
  - `101aef9e-b669-4388-807e-a35f269759cd` — CLEAN 72-frame single track,
    no fragmentation, `bearing_degrees` 210&deg;, still ~74&deg; off the
    ~285&deg; flow instead of ~180&deg; opposite (under the 90&deg;
    "against flow" line). Isolates mechanism 1 from mechanism 2.
  - `9e44e167-0d3c-4e27-9480-30ae55024908` — rider tracked as one 80-frame
    motorcycle, `bearing_degrees` 225&deg;, only ~60&deg; off flow.
  - `7d578a63-874b-4af9-8cb8-263771aa5275` — SAME rider split across two
    tracker IDs: an 8-frame fragment (below `MIN_TRACK_FRAMES` = 9, never
    analysed) then a 34-frame fragment with `bearing_degrees` 254&deg;,
    ~20&deg; off flow.

  Two mechanisms, both in the video-analysis service:
  1. **Perspective-unaware bearing.** `tracking_bearing.py` derives
     direction from raw pixel-space centroid motion. For a vehicle close
     to the camera and near the frame edge, apparent motion is dominated
     by lateral parallax sweep, not along-road translation, and
     "toward camera" vs "away" both project onto roughly the same
     road-axis line in the frame — the sign is lost, so an oncoming rider
     lands only 20-60&deg; off the receding-traffic bearing.
  2. **Track fragmentation.** A fast near-camera motorcycle still gets
     dropped and re-acquired under a new ID (the 2026-08-13 IoU fix
     mitigated this for the merely-small case, not the very-close +
     crossing case), so no single track is long enough to score well.

  Downstream effect: the oncoming rider's opposing motion is absorbed
  into the clip's flow consensus, which then disagrees with the OSM legal
  bearing (11&deg; vs observed ~37-67&deg;) and the whole report is vetoed
  as "conflicting evidence" before any vehicle is scored. (The compass
  heading — 157-162&deg;, measured inside a car — may also be
  magnetically off, compounding the OSM disagreement; worth checking
  independently.) Possible directions, none evaluated: a vanishing-point /
  road-axis model so bearing sign survives perspective; a
  size-growth ("is it getting bigger?") approach/recession signal for
  near-camera tracks, reusing the `bearingSource == "scale"` path that
  already exists for head-on motion; better tracker continuity for
  very-close motorcycles; and lowering `MIN_TRACK_FRAMES` is NOT a fix
  (the fragments are genuinely too short). Related: the cohesion
  under-confirmation item below, and "No correction for camera
  translation" under Direction analysis.

  **Partly addressed by
  `docs/superpowers/plans/2026-08-30-stationary-approach-detection.md`**
  (implemented on `main`). A stationary-camera bounding-box scale-trend
  fallback (`ReportAnalysisJob.tryStationaryApproachDetection`) now CONFIRMS
  `759cd`, `24908`, and `a5275` with no world bearing at all: on a
  verified-stationary camera (`location_samples` all &le; 1.0 m/s) pointed
  down a street resolved to `OneWay` (the whole-branch review narrowed the
  gate to `is OneWay` - see the 2026-08-31 update below), a vehicle whose
  bbox grew sustainedly (`scale_trend == "growing"`, growth &ge; 0.8 over
  &ge; 30 frames) while &ge; 3 others receded is the wrong-way rider. A
  2026-08-30 production
  replay of the real pipeline confirmed the classifier output: `759cd`
  grower growth 1.52 / 72 fr / det 0.89 with 3 shrinking; `24908` 2.24 / 80
  / 0.89 with 10 shrinking; `a5275` 0.93 / 34 / 0.78 with 3 shrinking - all
  three pass every gate and CONFIRM (`wrong_way_confidence` = the grower's
  detection confidence: 0.89 / 0.89 / 0.78). `50bcc6` (the violator drives
  *past* the camera, so its track grows-then-shrinks -> `flat`, no strong
  grower) and `71f78` (moving camera, GPS speed 1.19 m/s ->
  `wasStationaryThroughout()` false) remain open, deferred to the future
  "B" (clip-flow-relative bearing) design in section 6 of that spec.

  **Update 2026-08-31** (spec/plan
  `2026-08-31-divided-carriageway-resolution-and-approach-on-unknown`): two
  things moved. First, the whole-branch review of the 2026-08-30 work
  narrowed the approach gate to `is OneWay` only - so once the
  divided-carriageway Overpass fix in the 2026-08-31 plan makes خیبان جناح
  resolve reliably to `Unknown(DIVIDED_CARRIAGEWAY)` instead of a coin-flip
  between a wrong `OneWay(11.23&deg;)` and `Unknown`, that narrowed gate
  would have blocked all three reports. Second, this plan closes the gap:
  the approach path now also fires on `Unknown(DIVIDED_CARRIAGEWAY)` when the
  clip's own qualified traffic forms one coherent flow consensus (strongest
  corridor consensus &ge; `app.analysis.approach-corroboration-min-members`,
  default 2). Net effect once deployed: `759cd` / `24908` / `a5275` resolve
  deterministically to `Unknown(DIVIDED_CARRIAGEWAY)` and CONFIRM via the
  approach path on current production, no longer dependent on which Overpass
  replica answered. `50bcc6` and `71f78` are unaffected - still open,
  deferred to the clip-flow-relative bearing "B" design.
  *(added 2026-08-30 while building per-report vehicle readouts; approach-detection note added 2026-08-30; approach-gate scope + divided-carriageway follow-up 2026-08-31)*

- **[PRIORITY RAISED 2026-08-30 - recurred] A long, cleanly-detected
  wrong-way vehicle can be denied confirmation purely by low
  `corridor_cohesion`, especially on a single-corridor road.** The score is
  `candidateQuality = trackQuality × corridorCohesion`
  (`ClipFlowAnalyzer.FlowVehicle`); on a busy straight road `cluster_tracks`
  puts every vehicle in one corridor, and a near-camera weaving motorcycle
  (jittery centroid, bbox clipped at the frame edge) reads as a spatial
  outlier, so `corridor_cohesion = 1 − mean-distance-to-direct-corridor-
  mates / threshold` lands low and drags `candidate_quality` — and the
  final score — under the 0.50 confirm bar even when direction confidence,
  detection confidence, and bearing match are all near 1.0. The pipeline
  correctly identifies the direction violation but discounts its own
  tracking of the vehicle. Distinct from the 2026-08-13 fix (motorcycles
  dropped from tracking *entirely*); here the track is fine.

  Two user-confirmed true-positive reports, same wrong-way motorcycle on
  خیبان جناح (Jinnah Ave), ~48 s apart, both REJECTED:
  - `e4d53e59-3a7e-4fa8-b48b-f9bb02e71f78` — `final_score` 0.39 vs 0.50;
    `direction_confidence` 1.0, `bearing_match_score` 0.98,
    `detection_confidence` 0.90, `candidate_quality` 0.44 (the drag).
    Re-run: motorcycle track, 62 frames, `corridor_cohesion` 0.28.
  - `91ce999b-f391-4ffb-9790-9ef13d50bcc6` — `final_score` 0.33 vs 0.50;
    `direction_confidence` 1.0, `bearing_match_score` 0.96,
    `detection_confidence` 0.81, `candidate_quality` 0.42. Re-run:
    motorcycle track, 179 frames, 475 px travel (`trackQuality` saturates
    at 1.0), `corridor_cohesion` ≈ 0.19.

  Leading fix (brainstormed 2026-08-30, server-only "option B"): stop
  `corridor_cohesion` feeding per-candidate scoring — keep it only in
  consensus strength (`clipConfidence`) — and replace it in
  `candidateQuality` with a real track-trustworthiness signal from data
  already on the wire (frame count, displacement ratio, `bearing_source`).
  Alternatives considered: a minimal cohesion floor/cap (band-aid); making
  cohesion direction-aware (score the candidate against same-direction
  peers only); or a new per-track bearing-stability signal from
  `video-analysis` (correct but a cross-service wire change). Not started —
  needs a fresh go-ahead and its own spec.
  *(added 2026-08-30, priority raised same day after the second occurrence)*

- **Explored (not implemented): a vehicle-orientation (front vs rear)
  cross-check as an additional guard against false-positive wrong-way
  confirmations** - prompted by the report 649b9a false positive above,
  since the flagged vehicle's own evidence frame makes a rear-facing view
  visually obvious to a human. Feasibility test: a naive color heuristic
  (detect red taillight glow in the lower portion of a vehicle's bbox)
  scored 0% on every real vehicle crop tested, including large, clean,
  correctly-cropped rear views - because these are daytime clips and
  unlit taillights are just dark plastic housings, not distinguishable by
  color from the rest of the bumper. Daylight is the dominant real-world
  case for this app, so this specific approach is a dead end. The crops
  do show structurally obvious human-visible cues instead (tailgate/hatch
  shape, rear plate mounting height, passenger silhouettes through rear
  glass, motorcycle exhaust position) - plausible for a small trained
  front/rear image classifier, not a hand-rolled pixel heuristic. Not yet
  validated either way: every crop tested came from vehicles travelling
  the same direction as the recording car, so there's no genuine oncoming
  (front-facing) example yet to confirm discriminative power against.
  *(added 2026-08-17, feasibility only - no implementation)*

- **YOLO detects the recording car's own dashboard/windshield trim as a
  "vehicle"**, found incidentally while picking each track's largest bbox
  for the orientation-guard feasibility test above (not investigated
  further). Produced large, high-area, spurious tracks (up to 815k px²) in
  a real clip (`app/src/main/java/com/trafficwatch/app/feature/camera` test
  recording, "latest.mp4") that would win any "largest crop" or
  bbox-area-based selection over genuine, smaller, farther real vehicles.
  Untriaged: no repro isolated beyond the one clip it was noticed in, no
  root cause (camera framing catching too much of the dash/window trim
  under the vehicle bbox, YOLO class confusion, or something else).
  *(added 2026-08-17, found incidentally - not yet investigated)*

- **Track fragmentation still splits a very-close, laterally-crossing
  motorcycle into two short tracks.** `a5275`'s wrong-way rider was tracked
  as an 8-frame fragment (below `MIN_TRACK_FRAMES` = 9, never analysed) then
  a separate 34-frame fragment - the 2026-08-13 IoU fix mitigated the
  merely-small case, not the very-close + crossing case. The
  stationary-approach path
  (`docs/superpowers/plans/2026-08-30-stationary-approach-detection.md`) now
  CONFIRMS the report from the surviving 34-frame fragment, so fragmentation
  no longer blocks `a5275`, but a cleanly-held single track would still
  score better everywhere else in the pipeline (bearing, corridor cohesion,
  displacement) - the ByteTrack-continuity gap is its own item.
  *(added 2026-08-30 during stationary-approach detection production replay)*

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
