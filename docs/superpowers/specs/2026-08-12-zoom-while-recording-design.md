# Zoom While Recording - Design

## Context

Backlog item ("Camera / Recording" section, added 2026-08-02): no zoom control
exists today. Explicitly deferred during the 2026-08-01 motorcycle-detection
fix, reasoning that zoom narrows the field of view, which the
corridor-consensus direction-analysis logic depends on seeing multiple
vehicles across a wide lane to establish a reliable "normal flow" baseline.

The user now wants to revisit this with a hard 2x cap and an explicit
requirement: the math that determines a vehicle's direction/location must
account for zoom level, not just the capture UI.

## Key finding that shapes this design

Before designing anything, the actual pixel-math in `video-analysis/` and
`server/.../geo/ClipFlowAnalyzer.kt` was traced end to end to determine
exactly what is and isn't zoom-sensitive - not assumed:

- **Already zoom-invariant, no change needed:**
  - `tracking_bearing.py`'s bearing *angle* itself
    (`atan2(dx, -dy)` on a pixel displacement vector) - zoom scales the
    vector uniformly, not the angle it points in.
  - `ClipFlowAnalyzer.kt`'s displacement-quality gate
    (`minDisplacementFraction * bboxDiagonal`) - already a fraction of the
    vehicle's own bounding-box size, which scales with zoom the same way
    displacement does.
  - `MIN_SCALE_CHANGE_FRACTION` (bbox-scale approach/recession fallback) -
    already a relative fraction, not an absolute pixel count.
  - `MIN_TRACK_FRAMES`/`MIN_OBSERVATIONS` - frame counts, not pixel
    quantities, unaffected by zoom.
- **Genuinely zoom-sensitive, needs a fix:**
  - `corridors.py`'s clustering threshold
    (`corridor_cluster_threshold_fraction * frame_diagonal`) - a fixed
    fraction of the frame's pixel *dimensions*, which don't change with
    zoom, even though the real-world distance a pixel represents does. At
    2x zoom the same real-world lane width shows up as roughly double the
    pixel distance, risking same-lane traffic being split into separate
    corridors.
  - `tracking_bearing.py`'s `MIN_DISPLACEMENT_PIXELS` (a flat 8px floor) -
    also zoom-sensitive, though more marginal: the code already accepts
    this floor is imprecise across near/far vehicles even without zoom.
    Fixed anyway for consistency, since it's a cheap, low-risk change once
    zoom ratio is threaded through regardless.

**Deliberately not addressed**: the original deferral concern (narrower
field of view at higher zoom means fewer vehicles may be visible for
corridor-consensus to build a baseline from) is an inherent data-
availability tradeoff, not a math bug - there's no calculation that fixes
"the camera literally can't see traffic outside its field of view." A hard
2x cap keeps this modest. Documented here as an accepted limitation, not
engineered around, the same way the recording-heading-rotation-correction
design explicitly scoped out camera pitch/tilt.

## Architecture overview

1. **Android capture**: `CameraController` gains a zoom ratio clamped to
   `[1.0, 2.0]` via CameraX's `CameraControl.setZoomRatio()`, driven by
   both a pinch gesture on the preview and three quick-select pill buttons
   (1x / 1.5x / 2x) - both control the same underlying state. Zoom is
   implicitly locked once recording starts (CameraX simply keeps whatever
   ratio was last set; no separate "lock" mechanism needed). See the
   confirmed mockup: zoom badge top-left, pill buttons directly above the
   record button, existing heading/location map preserved bottom-right.
2. **Capture-time snapshot**: whichever ratio is active when recording
   starts is captured once, the same one-shot-snapshot shape already used
   for the compass heading - not a continuous stream. Reuses `LocationData`
   as the capture-time metadata carrier (where `compassHeadingDegrees`
   already lives, despite the imperfect semantic fit - consistent with the
   existing pattern rather than introducing a second plumbing path).
3. **Upload → server → video-analysis**: threads through as one new
   optional field end to end - multipart form field → `Report.zoomRatio`
   column → passed into the existing `/v1/analyze` call to the Python
   service. Absent (older app versions, or a `null` value) defaults to
   `1.0` (no zoom) at every layer, so nothing breaks for pre-feature
   clients.
4. **video-analysis math fixes**: `pipeline.py` clamps the incoming zoom
   ratio to a `1.0` floor (a value `<= 0` would corrupt the division),
   divides the corridor clustering threshold by it, and computes a
   zoom-adjusted `min_displacement_pixels` passed down to
   `tracking_bearing.py`'s functions via a new optional parameter
   (defaulting to the existing module constant, so every unrelated call
   site and existing test is unaffected unless it explicitly opts in).

## File-level plumbing

**Android** (`app/src/main/java/com/trafficwatch/app/`):
- `feature/camera/CameraController.kt` - zoom control (clamped
  `setZoomRatio`), exposed as `currentZoomRatio: StateFlow<Float>`.
- `feature/camera/CameraScreen.kt` - `ScaleGestureDetector` via
  `pointerInput` on the preview `Box`, plus the three pill buttons
  (matching the confirmed mockup: zoom badge top-left, pills bottom-center
  above the record `FloatingActionButton`, existing map bottom-right
  unchanged).
- `feature/camera/CameraViewModel.kt` - captures
  `cameraController.currentZoomRatio.value` once at recording start
  (mirrors the existing `snapshotCompassHeading` pattern exactly).
- `core/domain/model/LocationData.kt` - gains `zoomRatio: Double? = null`.
- `core/data/remote/ApiService.kt`, `feature/upload/UploadWorker.kt` - new
  optional multipart field `zoom_ratio`, same shape as the existing
  `compass_heading_degrees` field.

**Server** (`server/src/main/kotlin/com/trafficwatch/server/`):
- `reports/Report.kt` + new migration `V10__add_zoom_ratio_to_reports.sql`
  - nullable `zoomRatio: BigDecimal?` column.
- `reports/ReportController.kt`, `reports/ReportService.kt` - thread the
  optional param through, mirroring `compassHeadingDegrees`.
- `videoanalysis/VideoAnalysisClient.kt` - `analyze()` gains a
  `zoomRatio: BigDecimal?` parameter, sent as an optional `zoom_ratio`
  multipart field only when non-null (mirrors the existing `report_id`
  optional-field pattern).
- `reports/ReportAnalysisJob.kt` - passes `report.zoomRatio` through to
  `videoAnalysisClient.analyze(...)`.

**Python** (`video-analysis/app/`):
- `main.py` - `/v1/analyze` gains `zoom_ratio: float = Form(default=1.0)`,
  passed to `pipeline.analyze(tmp_file.name, zoom_ratio=zoom_ratio)`.
- `pipeline.py` - `analyze()` gains a `zoom_ratio: float = 1.0` parameter;
  computes `effective_zoom_ratio = max(zoom_ratio, 1.0)` once, uses it to
  divide the corridor `threshold_px` calculation, and computes
  `min_displacement_pixels = MIN_DISPLACEMENT_PIXELS / effective_zoom_ratio`
  once, passed into `_summarize_track`'s calls to `resolve_bearing`/
  `compute_displacement_pixels`.
- `tracking_bearing.py` - `resolve_bearing()` and
  `compute_displacement_pixels()` both gain a new optional
  `min_displacement_pixels: float = MIN_DISPLACEMENT_PIXELS` parameter,
  used in place of the module constant inside each function body.

## Testing

- **Android**: unit test for the zoom-ratio clamping logic (pure function,
  no CameraX framework dependency needed for the clamp math itself).
- **Python**: `tests/test_bearing.py`'s existing tests continue to pass
  unchanged (default parameter preserves current behavior); new cases
  cover `resolve_bearing`/`compute_displacement_pixels` with an explicit
  `min_displacement_pixels` override. `tests/test_pipeline.py` gains a
  case proving the zoom-ratio threading end to end: the same two tracks
  that cluster together at `zoom_ratio=1.0` but would incorrectly split at
  `zoom_ratio=2.0` under the old fixed-threshold behavior must still
  cluster together once `AnalysisPipeline.analyze()` is given
  `zoom_ratio=2.0`.
- **Server**: existing `ReportAnalysisJobTest`/`VideoAnalysisClientTest`
  patterns extended to cover the new optional field threading through,
  including the "absent/null defaults to 1.0-equivalent behavior"
  backward-compatibility case.
- **Manual on-device verification**: confirm the pinch gesture and pill
  buttons both work and stay in sync, confirm the cap holds at exactly 2x
  regardless of how far the device's own hardware would otherwise allow,
  confirm zoom is unresponsive once recording has started, and confirm a
  submitted report's stored `zoomRatio` matches what was actually set.

## Files touched (summary)

**Android - modified**: `feature/camera/CameraController.kt`,
`feature/camera/CameraScreen.kt`, `feature/camera/CameraViewModel.kt`,
`core/domain/model/LocationData.kt`, `core/data/remote/ApiService.kt`,
`feature/upload/UploadWorker.kt`.

**Server - new**: `db/migration/V10__add_zoom_ratio_to_reports.sql`.
**Server - modified**: `reports/Report.kt`, `reports/ReportController.kt`,
`reports/ReportService.kt`, `videoanalysis/VideoAnalysisClient.kt`,
`reports/ReportAnalysisJob.kt`.

**Python - modified**: `main.py`, `pipeline.py`, `tracking_bearing.py`.
**Python - tests**: `tests/test_bearing.py` gains zoom-adjusted-floor
cases; `tests/test_pipeline.py` gains the end-to-end threshold-scaling
case.
