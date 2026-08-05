# Continuous Orientation Fusion Design

## Context

Sub-project 3 of 4 in the "fix camera motion tracking for wrong-way
detection" effort:

1. Android continuous GPS heading capture (`location_samples`) - **done, merged**.
2. Android continuous gyroscope/rotation-vector capture (`rotation_samples`) - **done, merged**.
3. **This sub-project**: server-side fusion of 1+2 into a continuous
   per-timestamp camera-orientation signal, replacing the single
   `compassHeadingDegrees` scalar currently used for every vehicle in a clip.
4. Video-analysis visual odometry (independent fallback/validation layer) -
   not started.

`location_samples` and `rotation_samples` are already captured, uploaded,
and persisted (as JSON on `reports`), but nothing consumes them - every
vehicle's frame-relative bearing is still converted to a real-world bearing
using one compass reading taken once at recording start
(`ClipFlowAnalyzer.qualifyVehicles`'s
`absoluteBearingDegrees = (compassHeadingDegrees + frameBearing) % 360.0`).
This is the original diagnosed bug: if the phone/vehicle physically rotates
during the clip, that single snapshot is wrong for the rest of the
recording. This sub-project is the piece that actually closes it.

## Scope & explicit decisions (confirmed with the user during brainstorming - do not re-litigate)

- **Real per-vehicle continuous fusion, not a clip-wide average.** A
  Kotlin-only approach (average the whole clip's samples into one improved
  but still-single scalar) was considered and rejected - it doesn't vary
  per-vehicle/per-moment, so it wouldn't actually fix the underlying bug
  for a clip where the camera rotates partway through. This requires a
  small Python change (exposing per-track timing) alongside the Kotlin
  fusion work.
- **Clip-start anchor: the earliest sample's own `capturedAt`.** No new
  wire field. `location_samples`/`rotation_samples` are already filtered
  client-side to the trimmed clip's time window
  (`recordingStartedAt + trimStartMs .. + duration`), so the earliest
  timestamp across both lists approximates the trimmed clip's frame-0
  wall-clock time. Accepted approximation: this slightly *overestimates*
  the true clip start (by up to ~1s if only 1Hz `location_samples` exist,
  ~200ms if `rotation_samples` exist) - bounded and small relative to the
  system's existing tolerances (e.g. 45° agreement tolerance), not a
  blocker. A report with neither sample list has no anchor and falls back
  to today's scalar-only behavior for every vehicle.
  - **Important, separately-confirmed correlation bug this decision
    sidesteps**: `report.recordedAt` is NOT a safe anchor - it's set from
    the *raw* (pre-trim) recording start (`UploadWorker.kt:151`'s
    `recordingStartedAt`), while the video actually analyzed is the
    trimmed clip and the sample lists are already trim-window-filtered.
    Using `recordedAt` as the anchor would silently offset every lookup by
    `trimStartMs` (up to several minutes on a 10-minute raw recording).
    Not documented anywhere before this design; add to
    `docs/improvements-backlog.md` if not already fixed as part of this
    plan's Kotlin work (it isn't - the sample-anchor approach avoids
    needing `recordedAt` to be correct for this purpose at all, but
    `recordedAt`'s own inaccuracy remains a latent trap for any *future*
    code that assumes it means "trimmed clip start").
- **Fusion priority: rotation is primary, GPS is fallback-only, never
  blended.** `rotation_samples` (compass/gyro-derived) is used whenever any
  exist for a report; `location_samples`' GPS `bearing` is used only when a
  report has zero `rotation_samples` at all (older client, sensor
  unavailable). The two are never averaged/weighted together even when
  both exist for a report - GPS bearing is meaningless at low/zero speed
  (the ORIGINAL bug's own root cause: `bearing: 0.0°, speed: 0.0 m/s` at
  the exact moment the old single compass snapshot was taken), so blending
  it in would reintroduce the noise that motivated building rotation
  capture as an independent signal in the first place.
- **Interpolation: nearest-bracketing-samples circular weighted mean, not
  full circular LERP or nearest-single-sample.** Reuses the existing
  `BearingMath.weightedCircularMeanDegrees` (no new formula) with the two
  time-bracketing samples weighted by inverse time-distance. At the edges
  (target before the first sample or after the last), use that single
  nearest sample directly, unweighted.
- **No new `EvidenceKind`.** Orientation source is an input to bearing
  computation, not evidence about a street's *legal* direction (which is
  what `DirectionEvidence`/`EvidenceKind` model - `OSM_TAG`, `HISTORY`,
  etc.). This sub-project changes how `absoluteBearingDegrees` is computed
  internally; it does not add a new evidence source to the scoring model.
- **One new diagnostic field, not a scoring change**: each evaluated
  candidate's resolved orientation *source* (rotation-interpolated /
  GPS-fallback / scalar-fallback) is recorded in the existing debug-only
  `direction_evidence` JSON, purely for diagnosability - this whole effort
  has repeatedly needed exactly this kind of introspection reconstructed
  by hand via SSH and frame extraction.
- **Out of scope**: visual odometry (sub-project 4, independent follow-on);
  fixing `report.recordedAt`'s own inaccuracy (noted above as a latent
  trap, not blocking this plan); the already-documented
  `recorded_at`/`location_samples` UTC-offset formatting bug (separate,
  pre-existing backlog item).

## Architecture overview

```
Python (video-analysis/): each track gains trackMidpointMs
   - new FPS lookup (cv2.CAP_PROP_FPS) at video open
   - trackMidpointMs = midpoint(first_frame_index, last_frame_index) / fps * 1000
   - null when FPS unavailable (graceful degradation, mirrors frameWidth/frameHeight)
   -> VehicleAnalysisResult.trackMidpointMs: Long?

Kotlin server:
  OrientationTimeline(locationSamples, rotationSamples)
     .orientationAt(elapsedMs: Long): Double?
       1. anchorEpochMs = min(capturedAt) across both lists (null if both empty)
       2. targetEpochMs = anchorEpochMs + elapsedMs
       3. if rotationSamples not empty: interpolate over rotationSamples
       4. else if locationSamples not empty: interpolate over locationSamples' bearing
       5. else: null

  ClipFlowAnalyzer.qualifyVehicles(vehicles, compassHeadingDegrees: Double?,
                                    orientationTimeline: OrientationTimeline?,
                                    frameWidth, frameHeight):
     for each vehicle, absoluteBearingDegrees resolution order:
       1. orientationTimeline?.orientationAt(vehicle.trackMidpointMs) if trackMidpointMs != null
       2. else/if-null: compassHeadingDegrees (report-level scalar fallback)
       3. else: vehicle dropped (mapNotNull, same as any other missing required field)

  ReportAnalysisJob.determineOutcome():
     hasOrientationSource = compassHeadingDegrees != null
                             || report.locationSamples not empty
                             || report.rotationSamples not empty
     if !hasOrientationSource: REJECTED "No orientation data available for this report"
       (video analysis + evidence breakdown still run, exactly as today's
       compass-missing path - only candidate scoring is skipped)
     else: qualifyVehicles(..., compassHeadingDegrees, orientationTimeline, ...)
```

## Part A - Python: per-track timing

**`video-analysis/app/detection.py`**: read FPS once when opening the video
(`cv2.VideoCapture(path).get(cv2.CAP_PROP_FPS)`), pass it through to
`pipeline.py`. `0.0` or a non-positive/NaN FPS (some malformed videos report
this) is treated the same as "unavailable" - `None`, not a fabricated value.

**`video-analysis/app/tracking_bearing.py`**: new pure function alongside
`compute_bearing_degrees`:

```python
def compute_track_midpoint_ms(
    first_frame_index: int,
    last_frame_index: int,
    fps: float | None,
) -> int | None:
    """Elapsed milliseconds from the analyzed clip's start to the midpoint
    between a track's first and last observed frame. None when fps is
    unavailable or non-positive - never a fabricated timestamp."""
    if fps is None or fps <= 0:
        return None
    midpoint_frame = (first_frame_index + last_frame_index) / 2
    return round(midpoint_frame / fps * 1000)
```

**`video-analysis/app/pipeline.py`**: `frames_sorted` (already sorted by
`frame_index`, see current `bearing = compute_bearing_degrees(centroids)`
call site) gives `frames_sorted[0].frame_index` and
`frames_sorted[-1].frame_index` directly - pass both plus `fps` into
`compute_track_midpoint_ms`, attach the result as `track_midpoint_ms` in
the per-vehicle result dict alongside the existing `track_frame_count`.

**`video-analysis/app/schemas.py`**: add `track_midpoint_ms: int | None = None`
to the vehicle response schema.

## Part B - Kotlin: `OrientationTimeline`

New file `server/src/main/kotlin/com/trafficwatch/server/geo/OrientationTimeline.kt`:

```kotlin
package com.trafficwatch.server.geo

import com.trafficwatch.server.reports.dto.LocationSampleDto
import com.trafficwatch.server.reports.dto.RotationSampleDto

enum class OrientationSource { ROTATION, LOCATION }

data class ResolvedOrientation(val bearingDegrees: Double, val source: OrientationSource)

/**
 * Continuous per-timestamp camera orientation, fused from a report's
 * location_samples/rotation_samples. Pure, no I/O - mirrors BearingMath's
 * testability contract. rotation_samples is always preferred when any
 * exist; location_samples' GPS bearing is used only when a report has zero
 * rotation_samples (never blended - GPS bearing is unreliable at low/zero
 * speed, the exact failure mode that motivated capturing rotation
 * separately in the first place).
 */
class OrientationTimeline(
    private val locationSamples: List<LocationSampleDto>,
    private val rotationSamples: List<RotationSampleDto>,
) {
    private val anchorEpochMs: Long? = (rotationSamples.map { it.capturedAt } +
        locationSamples.map { it.capturedAt }).minOrNull()

    /** Orientation at [elapsedMs] into the clip, or null if no samples exist at all. */
    fun orientationAt(elapsedMs: Long): ResolvedOrientation? {
        val anchor = anchorEpochMs ?: return null
        val targetEpochMs = anchor + elapsedMs

        if (rotationSamples.isNotEmpty()) {
            return interpolate(rotationSamples.map { it.capturedAt to it.headingDegrees }, targetEpochMs)
                ?.let { ResolvedOrientation(it, OrientationSource.ROTATION) }
        }
        if (locationSamples.isNotEmpty()) {
            return interpolate(locationSamples.map { it.capturedAt to it.bearing }, targetEpochMs)
                ?.let { ResolvedOrientation(it, OrientationSource.LOCATION) }
        }
        return null
    }

    /**
     * Circular-weighted interpolation between the two samples in [points]
     * (epochMs, bearingDegrees) bracketing [targetEpochMs], weighted by
     * inverse time-distance. At the edges (target before the first or
     * after the last point), returns that single nearest point unweighted.
     */
    private fun interpolate(points: List<Pair<Long, Double>>, targetEpochMs: Long): Double? {
        if (points.isEmpty()) return null
        val sorted = points.sortedBy { it.first }

        if (targetEpochMs <= sorted.first().first) return sorted.first().second
        if (targetEpochMs >= sorted.last().first) return sorted.last().second

        val after = sorted.first { it.first >= targetEpochMs }
        val before = sorted.last { it.first <= targetEpochMs }
        if (before.first == after.first) return before.second

        val totalSpan = (after.first - before.first).toDouble()
        val weightBefore = (after.first - targetEpochMs) / totalSpan
        val weightAfter = (targetEpochMs - before.first) / totalSpan

        return BearingMath.weightedCircularMeanDegrees(
            listOf(before.second, after.second),
            listOf(weightBefore, weightAfter),
        )
    }
}
```

## Part C - Kotlin: `ClipFlowAnalyzer`/`ReportAnalysisJob` wiring

**`ClipFlowAnalyzer.qualifyVehicles`** signature changes from:

```kotlin
fun qualifyVehicles(
    vehicles: List<VehicleAnalysisResult>,
    compassHeadingDegrees: Double,
    frameWidth: Int?,
    frameHeight: Int?,
): List<FlowVehicle>
```

to:

```kotlin
fun qualifyVehicles(
    vehicles: List<VehicleAnalysisResult>,
    compassHeadingDegrees: Double?,
    orientationTimeline: OrientationTimeline?,
    frameWidth: Int?,
    frameHeight: Int?,
): List<FlowVehicle>
```

Inside the existing `mapNotNull` block, where `absoluteBearingDegrees` is
currently computed directly from the single scalar, resolve per-vehicle
instead:

```kotlin
val resolvedOrientation = vehicle.trackMidpointMs
    ?.let { orientationTimeline?.orientationAt(it) }
    ?.bearingDegrees
    ?: compassHeadingDegrees
    ?: return@mapNotNull null
```

`FlowVehicle` gains a new field `orientationSource: OrientationSource?` (null
when the scalar fallback was used) for the diagnostic evidence addition in
Part D.

**`ReportAnalysisJob.determineOutcome`**: replace the current

```kotlin
val flowVehicles = if (compassHeadingDegrees != null) {
    clipFlowAnalyzer.qualifyVehicles(analysis.vehicles, compassHeadingDegrees.toDouble(), ...)
} else emptyList()
```

with:

```kotlin
val orientationTimeline = OrientationTimeline(
    parseLocationSamples(report.locationSamples),
    parseRotationSamples(report.rotationSamples),
)
val hasOrientationSource = compassHeadingDegrees != null ||
    report.locationSamples != null || report.rotationSamples != null
val flowVehicles = if (hasOrientationSource) {
    clipFlowAnalyzer.qualifyVehicles(
        analysis.vehicles,
        compassHeadingDegrees?.toDouble(),
        orientationTimeline,
        analysis.frameWidth,
        analysis.frameHeight,
    )
} else emptyList()
```

(`parseLocationSamples`/`parseRotationSamples` are new, simple - not
defensive - helpers in `ReportAnalysisJob`: unlike `ReportService.submit()`'s
inline parsing, which must tolerate malformed/oversized client input before
it's ever stored (see `MAX_LOCATION_SAMPLES`/`MAX_ROTATION_SAMPLES`), by the
time `ReportAnalysisJob` reads `report.locationSamples`/`rotationSamples`
back out, that JSON has already been validated and capped at write time -
a parse failure here would mean corrupted DB data, a genuine bug, not user
input to tolerate. A null column or a plain
`objectMapper.readValue<List<...>>(json)` is all that's needed; no
try/catch-and-fall-back-to-empty-list defensiveness like `ReportService`'s.)

The `compassMissing` flag threaded into `buildOutcome`'s message generation
becomes `orientationMissing = !hasOrientationSource`, with the REJECTED
message generalized from "Device compass heading unavailable for this
report" to "No orientation data available for this report".

## Part D - Diagnostic evidence addition

The existing debug-only `direction_evidence` JSON (built in
`ReportAnalysisJob`, consumed by the Android debug Score Breakdown card)
gains, per evaluated candidate, which `OrientationSource` resolved its
bearing (`ROTATION` / `LOCATION` / `null` for scalar-fallback). Purely
additive to the existing JSON shape - no schema migration needed since it's
stored as unstructured `jsonb`.

## Testing

- **Python** (`video-analysis/tests/test_bearing.py` or a new
  `test_track_timing.py`): `compute_track_midpoint_ms` against synthetic
  frame-index/fps combinations, including `fps=None`, `fps=0`, and a
  single-frame track (`first == last`).
- **Kotlin - new `OrientationTimelineTest.kt`**: synthetic sample lists
  covering interpolation between two rotation samples, GPS fallback when
  rotation is empty, edge cases (target before first / after last sample),
  empty-both-lists (returns null), single-sample lists (no interpolation
  possible, returns that sample), and 0°/360° wraparound (e.g. samples at
  350° and 10° interpolate through 360°/0°, not through 180°).
- **`ClipFlowAnalyzerTest`**: extend for the 3-step per-vehicle resolution
  order - rotation-interpolated available, GPS-fallback used (report has
  zero rotation_samples), scalar-fallback used (vehicle's
  `trackMidpointMs` is null, e.g. old Python service), and vehicle dropped
  (no orientation source resolves at all).
- **`ReportAnalysisJobTest`**: a new scenario directly proving the bug fix -
  a report with `compassHeadingDegrees` that would misjudge a vehicle's
  direction (e.g. a snapshot taken before the camera rotated), but whose
  `rotation_samples` show the camera's true orientation changing across the
  clip such that the per-vehicle continuous lookup produces the *correct*
  wrong-way verdict where the old single-scalar path would not have. Also:
  the generalized "no orientation data available" REJECTED path (neither
  scalar nor samples), and a report with samples but no scalar (proving the
  scalar is no longer a hard requirement).

## Files touched (summary)

**Python - modified**: `app/detection.py` (FPS lookup), `app/tracking_bearing.py`
(new `compute_track_midpoint_ms`), `app/pipeline.py` (wire timing through),
`app/schemas.py` (`track_midpoint_ms` field).
**Python - tests**: new/extended tests for `compute_track_midpoint_ms`.

**Server - new**: `geo/OrientationTimeline.kt`.
**Server - modified**: `videoanalysis/dto/VideoAnalysisDtos.kt`
(`trackMidpointMs: Long?` on `VehicleAnalysisResult`), `geo/ClipFlowAnalyzer.kt`
(`qualifyVehicles` signature + per-vehicle resolution, `FlowVehicle.orientationSource`),
`reports/ReportAnalysisJob.kt` (orientation-source wiring, generalized
REJECTED message, diagnostic evidence field).
**Server - tests**: new `geo/OrientationTimelineTest.kt`; extended
`geo/ClipFlowAnalyzerTest.kt`, `reports/ReportAnalysisJobTest.kt`.

## Verification

1. **Python timing in isolation**: `compute_track_midpoint_ms` unit tests
   with synthetic frame indices/fps (no video decoding needed).
2. **Kotlin fusion in isolation**: `OrientationTimelineTest` against
   synthetic sample lists with known expected interpolated bearings.
3. **Full pipeline**: `ReportAnalysisJobTest`'s new bug-fix scenario proves
   the fusion changes a verdict that the old single-scalar path would have
   gotten wrong.
4. **Production**: deploy, then re-run the same manual diagnostic technique
   used throughout this effort (SSH + direct DB query) against a real
   report recorded while physically rotating the phone mid-clip, confirming
   the debug evidence JSON shows `ROTATION`-sourced orientations that vary
   across the clip's evaluated candidates rather than one static value.
