# Near-Camera Approach Bearing Fix Design

> **SUPERSEDED (2026-08-30) by
> `2026-08-30-stationary-approach-detection-design.md`.** Its mechanism -
> sustained-growth detection and the `resolve_bearing` override to
> `(180.0, "scale")` for a near-camera approacher - is folded into that
> design's `scale_trend` classifier. Do not implement this spec
> separately; kept for context.

## Context

`video-analysis/app/tracking_bearing.py`'s `resolve_bearing()` derives a
track's frame-relative bearing from **lateral** pixel-centroid displacement
(`atan2(dx, -dy)`, source `"centroid"`). A separate branch, added by the
2026-08-06 approach/recession fix
(`docs/superpowers/specs/2026-08-06-approach-recession-bearing-fix-design.md`),
handles a vehicle moving *nearly head-on* - negligible lateral motion but a
bounding box that grows or shrinks - by returning `180.0` ("toward camera")
or `0.0` ("away") with source `"scale"`. That branch only runs when lateral
displacement is **below** `MIN_DISPLACEMENT_PIXELS`.

Diagnosing five user-confirmed wrong-way reports on the same خیبان جناح
(Jinnah Avenue) stretch (`101aef9e...759cd`, `9e44e167...24908`,
`7d578a63...a5275`, and the cohesion-related `50bcc6`/`71f78`) surfaced a
gap the 2026-08-06 fix does not cover. In each clip a motorcycle rides
straight at the stationary recording camera, against traffic, along the
median. Because the rider passes *close* to the camera and near the frame
edge, its centroid sweeps a large distance (parallax) - 400 to 530 px, well
over the lateral floor - so `resolve_bearing()` returns a `"centroid"`
bearing at line 96 and never reaches the scale branch. The bearing it
returns (210 deg for `759cd`, 225 deg for `24908`, 254 deg for `a5275`) is
only 20 to 74 deg off the ~285 deg traffic flow instead of ~180 deg
opposite: a near-camera oncoming rider's centroid motion is dominated by
lateral parallax along the road axis, and "toward camera" versus "away from
camera" both project onto roughly the same frame line. Downstream, the
rider's opposing motion is absorbed into the clip's flow consensus, and no
vehicle is ever scored as a violator.

This is documented in `docs/improvements-backlog.md` under "Vehicle
detection / tracking" (the `[HIGH]` entry, added 2026-08-30). One report,
`a5275`, also shows the rider's track *fragmenting* into two tracker IDs;
track fragmentation is a **separate**, known problem (the 2026-08-13
motorcycle-tracking fix mitigated it for the merely-small case, not the
very-close + crossing case) and is out of scope here - see Non-goals.

## Scope decision (confirmed with the user during brainstorming - do not re-litigate)

- **Fix the bearing computation only.** Make `resolve_bearing()` recognise a
  near-camera approach even when lateral displacement is large, and return
  the existing `(180.0, "scale")` result for it. Success criterion: the
  wrong-way motorcycle in `759cd` / `24908` / `a5275` comes back as an
  opposing-direction track (`bearing_degrees: 180.0`, `bearing_source:
  "scale"`) instead of a with-flow `"centroid"` bearing.
- **Getting these three reports to CONFIRMED end-to-end is NOT the goal
  here.** They were REJECTED as "Conflicting direction evidence" because the
  observed traffic flow (~37 to 98 deg world) disagrees with the OSM legal
  bearing (11 deg) by 56 to 87 deg - a gap that looks like a bad compass
  heading (each clip was filmed from inside a car; headings 157 to 166 deg).
  Fixing the rider's bearing makes the direction split cleaner but does not
  close that OSM gap. The compass / OSM-conflict question is a **separate
  follow-up** (backlog: "No correction for camera translation" under
  Direction analysis, and a possible magnetometer-in-car investigation).
- **Emit option (i), not (ii).** Override to `(180.0, "scale")` rather than
  keeping the `"centroid"` bearing and adding a new `VehicleResult` field.
  This matches the 2026-08-06 precedent exactly, needs no wire-format or
  Kotlin change, and "opposing" versus "with" is all the downstream logic
  acts on.
- **Growth only, not shrink.** A *shrinking* box with large lateral motion
  (ordinary receding traffic) keeps its `"centroid"` bearing, unchanged.
  Only sustained *growth* (approach) is overridden. This keeps the blast
  radius to tracks that grow - which, on a stationary camera, are the
  wrong-way approachers.
- **Lenient trigger + a sustained-growth guard.** Reuse
  `MIN_SCALE_CHANGE_FRACTION` (0.15) as the total-growth threshold - no new
  constant - but require the growth to be monotonic across three
  time-ordered segments of the track, so a single blown-up box (occlusion
  merge, detection error) does not trip it.

## The fix

### `video-analysis/app/tracking_bearing.py`

New module-private helper:

```python
def _has_sustained_growth(
    bboxes: Sequence[Tuple[float, float, float, float]],
) -> bool:
    """True when the bounding-box diagonal grows monotonically across three
    equal time-ordered segments of the track, by at least
    MIN_SCALE_CHANGE_FRACTION overall - the signature of a vehicle
    approaching the camera, as distinct from a single-frame size spike
    (occlusion merge / detection error) or ordinary jitter. Caller
    guarantees len(bboxes) >= MIN_OBSERVATIONS, so each segment has >= 4
    frames."""
    diagonals = [bbox_diagonal(b) for b in bboxes]
    k = len(diagonals) // 3
    if k == 0:
        return False
    s1 = sum(diagonals[:k]) / k
    s2 = sum(diagonals[k : 2 * k]) / k
    s3 = sum(diagonals[2 * k :]) / (len(diagonals) - 2 * k)
    return s1 > 0 and s1 < s2 < s3 and (s3 - s1) / s1 >= MIN_SCALE_CHANGE_FRACTION
```

In `resolve_bearing()`, the existing lateral-displacement return (currently
`tracking_bearing.py:95-96`) gains a pre-check:

```python
    if lateral_displacement >= min_displacement_pixels:
        if (
            bboxes is not None
            and len(bboxes) >= MIN_OBSERVATIONS
            and _has_sustained_growth(bboxes)
        ):
            return (180.0, "scale")
        return (math.degrees(math.atan2(dx, -dy)) % 360.0, "centroid")
```

Nothing below this line changes. The existing negligible-lateral scale
branch (`180.0` / `0.0` for grow / shrink) is untouched and still handles
the true head-on case the 2026-08-06 fix targeted.

`_has_sustained_growth` deliberately scans **all** the track's bboxes for
the monotonicity check, not just the `sample_size` early/late windows
`resolve_bearing` uses elsewhere - the monotonicity test needs the middle
of the track, and the whole-track scan is what makes "sustained" meaningful.

### No other files change

- **`pipeline.py`**: `_summarize_track` already calls `resolve_bearing(
  centroids, bboxes, min_displacement_pixels=...)` with `bboxes` populated.
  The new `(180.0, "scale")` return flows straight into `VehicleResult.
  bearing_degrees` / `bearing_source`. `displacement_pixels` is computed
  independently by `compute_displacement_pixels`, which for these
  large-lateral tracks already returns the (large) lateral value - well
  above `ClipFlowAnalyzer`'s `minDisplacementFraction * bboxDiagonal`
  floor - so nothing there needs to change.
- **`compute_bearing_degrees`** (the thin wrapper): unchanged; it simply
  returns whatever bearing `resolve_bearing` now produces.
- **Kotlin / server**: no change. `bearingSource == "scale"` is already
  fully handled by `ClipFlowAnalyzer.qualifyVehicles` - including the
  `MAX_RECORDING_SPEED_FOR_SCALE_BEARING_MPS` (1.0 m/s) corroboration gate
  via `OrientationTimeline.recordingSpeedMetersPerSecondAt`. All five
  diagnosed reports carry `location_samples` with per-sample GPS speed ~0
  (report-level `speed` is exactly 0.0), so the gate trusts the bearing.
- **No new tunable constants.** `MIN_DISPLACEMENT_PIXELS`,
  `MIN_SCALE_CHANGE_FRACTION`, `MIN_OBSERVATIONS` all keep their values.

### Behavioural consequence to be aware of

A track that previously resolved as `"centroid"` and sailed through
`qualifyVehicles` untouched will now resolve as `"scale"` and must pass the
GPS-speed corroboration gate. For a report with **no** `location_samples`
(app versions predating continuous GPS capture),
`recordingSpeedMetersPerSecondAt` returns `null` and the vehicle is
**dropped** rather than mis-folded into the flow consensus. Both outcomes
are a REJECT; dropping is the more honest one and is exactly the
conservative behaviour the existing `"scale"` path already has. Accepted.

## Testing

### `video-analysis/tests/test_bearing.py` (new cases)

| track shape | expected result |
|---|---|
| large lateral sweep + bbox diagonal growing monotonically across thirds, total >= 15% | `(180.0, "scale")` |
| large lateral sweep + bbox diagonal shrinking | unchanged `"centroid"` bearing (`atan2`) |
| large lateral sweep + bbox diagonal flat/stable | unchanged `"centroid"` bearing |
| large lateral sweep + bbox flat for segments 1-2 then one-frame spike (so `s2 <= s1`) | unchanged `"centroid"` - fails the monotonic guard |
| large lateral sweep + monotonic growth but total < 15% | unchanged `"centroid"` - fails the fraction guard |
| large lateral sweep + fewer than `MIN_OBSERVATIONS` bboxes | unchanged `"centroid"` - guard not reached |

Regression within this file:
- Every existing `resolve_bearing` / `compute_bearing_degrees` case either
  passes `bboxes=None` or small stable boxes; the new pre-check is inert for
  all of them, so their exact outputs are unchanged. Verify by running the
  file unchanged after the edit.
- The existing negligible-lateral `"scale"` cases still return via the old
  branch, unchanged.

### `video-analysis/tests/test_pipeline.py` (new case)

A synthetic near-camera-approach track (large centroid sweep + bbox
diagonal growing steadily across the frames) run through
`AnalysisPipeline.analyze` yields a `VehicleResult` with
`bearing_degrees == 180.0`, `bearing_source == "scale"`, and
`displacement_pixels` large enough to clear `ClipFlowAnalyzer`'s
`minDisplacementFraction * bboxDiagonal` floor (checked by hand against the
Kotlin formula - no Kotlin test change). Full existing `test_pipeline.py`
suite passes unchanged.

### Server suite

No server code changes. Run `./gradlew test` in `server/` to confirm no
test fixture (`ClipFlowAnalyzerTest`, `ReportAnalysisJobTest`,
`ReportAnalysisIntegrationTest`) depended on a track like this resolving as
`"centroid"`. None is expected to - server tests construct
`VehicleAnalysisResult` directly.

### Production replay

Re-run the `video-analysis` pipeline locally on the source videos for
`759cd`, `24908`, `a5275` (pull from the prod `trafficwatch-videos` volume -
see the project's prod-VPS-access notes). Confirm the wrong-way motorcycle
track that previously returned ~210 to 254 deg `"centroid"` now returns
`bearing_degrees: 180.0`, `bearing_source: "scale"`.

Then trace it through the Kotlin analysis path (unit-level
`ReportAnalysisJob.applyOutcome` with mocked OSM / video-analysis, or a
by-hand walk of `ClipFlowAnalyzer` + `DirectionEvidenceResolver`): the rider
should now be a qualified opposing-direction candidate. Whether the report
then reaches CONFIRMED still depends on the separate compass / OSM-conflict
issue - document that boundary in the replay notes rather than treating a
still-REJECTED outcome as this fix failing.

For `50bcc6` / `71f78`, also check whether their motorcycle track flips to
`"scale"`; it had a clearer opposing `"centroid"` bearing already, so it may
or may not show sustained growth. Either way those two reports' blocker is
`corridor_cohesion` (the separate `[PRIORITY RAISED]` backlog item), not
this fix.

## Non-goals (explicitly out of scope)

- **The compass / OSM-conflict issue.** These reports' "conflicting
  direction evidence" REJECT is not addressed here (see Scope decision).
- **Track fragmentation.** `a5275`'s rider splitting into two tracker IDs
  (an 8-frame fragment below `MIN_OBSERVATIONS`, then a 34-frame one) is a
  ByteTrack-continuity problem, related to but distinct from the
  2026-08-13 fix. Not touched.
- **`corridor_cohesion` under-confirmation** (`50bcc6` / `71f78`) - its own
  backlog item, own future spec.
- **Shrink-side override.** A receding vehicle with large lateral motion
  keeps its `"centroid"` bearing; only approach is overridden.
- **Any Kotlin / server-side code change.**
- **Retroactively re-analysing already-submitted reports** - this applies
  to future analysis only, like every other video-analysis change in this
  project.
