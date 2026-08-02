# Displacement Floor Bbox-Relative Fix Design

## Context

`ClipFlowAnalyzer.qualifyVehicles()` (`server/src/main/kotlin/com/trafficwatch/server/geo/ClipFlowAnalyzer.kt`)
drops any tracked vehicle whose displacement is below `MIN_DISPLACEMENT_FRACTION`
(5%) of the *video frame's* diagonal, before it's ever considered as a
wrong-way candidate. This floor exists to filter genuinely jittery/noisy
tracks (a stationary object whose detection box flickers slightly frame to
frame) out of corridor-consensus voting and candidate evaluation.

Diagnosing a real report - after visually confirming, frame by frame, which
vehicle in the clip was actually driving the wrong way - surfaced this: the
confirmed wrong-way motorcycle (track 11) has `displacement_pixels = 34.6`,
comfortably below the frame-relative floor (`0.05 * 2202.9 ≈ 110.1px` for
this 1920x1080 clip). It was silently dropped by `qualifyVehicles()` before
ever being evaluated, producing the misleading terminal message "No
vehicles detected moving against the legal direction" - the pipeline never
tried to evaluate this vehicle at all, rather than trying and failing to
match it.

The real reason: track 11's own bounding box is small (~55x83px, diagonal
~100px) because it's close to the camera. Its 34.6px displacement is ~35%
of its *own* size - clearly real motion, not jitter. A frame-relative floor
penalizes any vehicle close to the camera, since a nearby vehicle needs far
fewer absolute pixels of movement to represent the same real-world motion
than a distant one. This is the same underlying theme as the recently-fixed
corridor-cohesion-locality bug: a metric designed around one camera
geometry (implicitly, consistent object scale) breaking down for a
dashcam-style recording where objects vary widely in apparent size.

Notably, if track 11 had cleared this floor, it would very likely have been
correctly flagged: its bearing (171 degrees) is only ~13 degrees off from
the wrong-way direction implied by the established corridor consensus
(~4 degrees legal bearing, ~184 degrees wrong-way), well within the
existing 45-degree agreement tolerance.

## Scope & explicit decisions (confirmed with the user - do not re-litigate)

- **Normalize displacement against the vehicle's own bounding-box diagonal**,
  not the frame's diagonal. `VehicleAnalysisResult.boundingBox` is already
  present in the data (added in an earlier plan) and unused for this
  purpose - no new data needs to flow from Python. This fixes the general
  near-camera bias, not just this one case, unlike simply lowering the
  existing frame-relative percentage (considered and rejected - arbitrary,
  and a different close vehicle with even less absolute displacement could
  still fail the same way).
- **New threshold value: 15%** of the vehicle's own bounding-box diagonal
  (`MIN_DISPLACEMENT_FRACTION = 0.15`) - a different scale than the old
  5%-of-frame value, calibrated fresh rather than reused. Reasoning:
  detection jitter for a stable, correctly-tracked object is typically a
  small fraction of its own size (a few percent at most); 15% comfortably
  filters that out while passing genuine motion. Track 11 clears this
  easily (~35% of its own bbox diagonal).
- **`MIN_TRACK_FRAMES = 3` is untouched** - not what's blocking this case,
  out of scope.
- **A vehicle with no bounding box is dropped**, same graceful-degradation
  convention already used for null `corridorId`/`corridorCohesion`/
  `trackFrameCount`/`displacementPixels` in this same function.
- **The threshold becomes configurable** via `AnalysisProperties`/
  `app.analysis.*`, matching its sibling tuning knobs
  (`wrongWayToleranceDegrees`, `weakEvidenceFloor`,
  `consensusMinResultantLength`, etc.), which are all configurable there
  today while this one was a hardcoded private constant in
  `ClipFlowAnalyzer.kt`.

## The fix

`server/src/main/kotlin/com/trafficwatch/server/geo/ClipFlowAnalyzer.kt`:

```kotlin
fun qualifyVehicles(
    vehicles: List<VehicleAnalysisResult>,
    compassHeadingDegrees: Double,
    frameWidth: Int?,
    frameHeight: Int?,
): List<FlowVehicle> {
    if (frameWidth == null || frameHeight == null || frameWidth <= 0 || frameHeight <= 0) {
        return emptyList()
    }

    return vehicles.mapNotNull { vehicle ->
        val frameBearing = vehicle.bearingDegrees ?: return@mapNotNull null
        val corridorId = vehicle.corridorId ?: return@mapNotNull null
        val cohesion = vehicle.corridorCohesion ?: return@mapNotNull null
        val frames = vehicle.trackFrameCount ?: return@mapNotNull null
        val displacement = vehicle.displacementPixels ?: return@mapNotNull null
        val bbox = vehicle.boundingBox ?: return@mapNotNull null

        val bboxDiagonal = hypot(bbox.x2 - bbox.x1, bbox.y2 - bbox.y1)
        val minDisplacement = properties.minDisplacementFraction * bboxDiagonal

        if (frames < MIN_TRACK_FRAMES || displacement < minDisplacement) return@mapNotNull null

        FlowVehicle(
            vehicle = vehicle,
            absoluteBearingDegrees = (compassHeadingDegrees + frameBearing) % 360.0,
            trackQuality = min(frames / TRACK_FRAMES_SATURATION, 1.0) *
                min(displacement / minDisplacement, 1.0).coerceAtMost(1.0),
            corridorId = corridorId,
            corridorCohesion = cohesion,
        )
    }
}
```

Note: `diagonal` (the frame diagonal) and the file-level `MIN_DISPLACEMENT_FRACTION`
constant are removed entirely - the frame's own dimensions are no longer
used for this calculation at all (still received as parameters, still
needed for the `frameWidth`/`frameHeight` null/non-positive guard above).

`server/src/main/kotlin/com/trafficwatch/server/reports/AnalysisProperties.kt`
gains:

```kotlin
// A track's displacement must clear this fraction of its OWN bounding-box
// diagonal to count as real motion rather than detection jitter - scaled to
// the vehicle's own apparent size so nearby (large-in-frame) and distant
// (small-in-frame) vehicles are held to a comparable standard.
var minDisplacementFraction: Double = 0.15,
```

`server/src/main/resources/application.yml`'s `app.analysis` section gains:

```yaml
min-displacement-fraction: 0.15
```

## Testing

- `ClipFlowAnalyzerTest.kt`'s `vehicle()` helper gains a `boundingBox`
  parameter defaulting to a synthetic 50x50px box (diagonal ~70.7px), so
  the existing default `displacement = 200.0` continues to clear the new
  bbox-relative floor (`0.15 * 70.7 ≈ 10.6px`) and every test not
  specifically about the displacement floor keeps passing unchanged.
- `qualifyVehicles enforces the quality floor`'s displacement case is
  rewritten to test bbox-relative behavior: a displacement under 15% of
  the (default) bbox diagonal is dropped. The too-few-frames case is
  untouched.
- New test: a vehicle with a small, close-up bounding box (bbox diagonal
  ~100px, mirroring the real track 11 case) and a displacement of ~35px
  (well under the *old* frame-relative floor of ~110px, well over 15% of
  its *own* ~100px bbox diagonal) now qualifies - demonstrating the fix's
  actual effect, not just a reworded version of the old test.
- New test: a vehicle with `boundingBox = null` is dropped, matching the
  existing "drops null bearing/corridor/frame-dims fields" test's pattern.
- **Manual verification**: resubmit the same real clip used throughout this
  diagnosis through the full pipeline and confirm track 11 now qualifies
  and is evaluated as a wrong-way candidate - check whether the report
  now reaches `CONFIRMED`, given its bearing (171 degrees) is well within
  tolerance of the wrong-way direction implied by the established corridor
  consensus.
