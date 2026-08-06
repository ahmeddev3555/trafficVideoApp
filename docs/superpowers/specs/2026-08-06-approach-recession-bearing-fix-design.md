# Approach/Recession Bearing Fix Design

## Context

Sub-project 4 of 4 in the "fix camera motion tracking for wrong-way
detection" effort:

1. Android continuous GPS heading capture - **done, merged**.
2. Android continuous gyroscope/rotation-vector capture - **done, merged**.
3. Server-side fusion of 1+2 into a continuous per-timestamp camera-orientation
   signal - **done, merged, deployed, production-verified**.
4. Video-analysis visual odometry - **this sub-project**, narrowed in scope
   during brainstorming (see below).

Sub-projects 1-3 all improved *whose orientation reading* gets used to
convert a vehicle's frame-relative bearing into a real-world bearing. This
sub-project addresses a different, more fundamental gap discovered during
production diagnosis of report `55f7f82a` (documented in
`docs/improvements-backlog.md`'s "Direction analysis" section, added
2026-08-05): a real, visually-unambiguous wrong-way motorcycle riding
straight at the recording camera produced **no scored candidate at all** -
not because of a wrong camera-orientation reading, but because
`video-analysis/app/tracking_bearing.py`'s `compute_bearing_degrees` derives
a track's frame-relative bearing purely from *lateral* pixel centroid
displacement. A vehicle approaching (or receding) nearly head-on grows (or
shrinks) rapidly in apparent bounding-box size but produces very little
lateral centroid movement, so it fails the existing displacement floor and
is dropped - regardless of how good the camera-orientation signal is,
because sub-project 3's fusion never gets a chance to run on a vehicle that
was already dropped upstream.

## Scope decision (confirmed with the user during brainstorming - do not re-litigate)

Two possible framings for "visual odometry" were considered:

- **(a) Broad**: derive the camera's own motion/orientation from the video
  background (optical flow or similar), as a sensor-independent
  fallback/cross-check for sub-project 3's GPS+gyro fusion - the originally
  envisioned scope, but vague and requiring real architecture decisions
  before anything concrete could be planned.
- **(b) Narrow** (chosen): fix the frame-relative bearing computation itself
  to account for bounding-box scale change (approach/recession along the
  camera's line of sight), directly closing the head-on-motion gap found in
  production. Small, concretely scoped, immediately valuable, entirely
  within the existing Python detection pipeline - no new sensor-independent
  camera-motion-estimation machinery needed.

This sub-project is scoped to (b) only. (a) remains a real idea for a future
sub-project if sub-project 3's sensor-based approach proves insufficient on
its own, but is out of scope here.

## The two independent gates (why one fix touches two places)

Investigation during brainstorming found the head-on-motion gap is actually
enforced *twice*, independently, both lateral-only:

1. **`compute_bearing_degrees`** (`tracking_bearing.py:47`): gates on
   `hypot(dx, dy) < MIN_DISPLACEMENT_PIXELS` using its own *averaged*
   early/late (`sample_size`=4 frames each) centroids. Returns `None` -
   "no bearing" - if this fails, regardless of how much the vehicle's
   apparent size changed.
2. **`_summarize_track`'s `displacement_pixels`** (`pipeline.py:76-78`):
   a *separate* calculation using only the raw first-vs-last single-frame
   centroids, sent to the Kotlin server. `ClipFlowAnalyzer.kt` gates on
   this value independently (`displacement < minDisplacementFraction *
   bboxDiagonal`) - a vehicle that somehow got a real bearing but has a
   near-zero *this* value would still be dropped downstream in Kotlin.

Both gates need the same kind of fix: bounding-box scale change (approach
or recession) must count as an independent, valid form of proof-of-motion,
alongside lateral pixel displacement - not replacing it.

## Design

**Core mechanism**: bounding-box scale change is OR'd with lateral
displacement, not blended into a fuzzy single formula. Both existing
lateral-motion code paths stay completely unchanged for the (common) case
where lateral displacement alone already clears the existing floor; the new
logic only activates when lateral displacement is under that floor.

**`compute_bearing_degrees`** gains a new `bboxes` parameter (parallel to
the existing `centroids` parameter, sharing the same `sample_size`
early/late averaging window it already uses). Behavior:
- If lateral displacement alone clears `MIN_DISPLACEMENT_PIXELS`: return
  `atan2(dx, -dy)` exactly as today - byte-for-byte unchanged behavior for
  every currently-passing test and every real lateral-motion track.
- Else if the *combined* displacement (lateral and scale, combined via
  `hypot(lateral, scale)` - i.e. treating scale change as an independent
  orthogonal "third axis" of apparent motion) clears the floor: return a
  fallback bearing - **180°** if the bbox diagonal grew (early < late,
  approaching) or **0°** if it shrank (early > late, receding). This matches
  the existing convention (`0°` = "up in frame" = "away from camera,
  assuming the camera faces the direction of normal traffic flow"), so
  `180°` = "the reverse of away" = "toward the camera" stays internally
  consistent with how every other bearing in this pipeline is interpreted.
- Else (neither clears the floor, or too few observations): return `None`,
  exactly as today - a barely-changing, non-growing detection is still
  correctly treated as noise, not a fabricated direction.

**`pipeline.py`'s `displacement_pixels`** calculation (sent to Kotlin as-is,
consumed by `ClipFlowAnalyzer`'s own existing gate): the lateral component
(`hypot` of first-vs-last raw centroids) stays exactly as it is today. A new
scale component - first-vs-last raw bbox diagonal delta, deliberately
mirroring the lateral calculation's own simplicity rather than introducing
a different averaging convention in this one spot - is combined into the
sent value via the same `hypot(lateral, scale)` quadrature combination.

**No new tunable constants.** Both gates (`MIN_DISPLACEMENT_PIXELS` in
Python, `minDisplacementFraction` in Kotlin) keep their current values
unchanged - they're just now being fed a number that accounts for depth
motion, not a new threshold requiring separate calibration.

**No Kotlin/server changes at all.** `ClipFlowAnalyzer.kt`'s existing
displacement gate operates on whatever `displacement_pixels` value it
receives; since that value is now computed correctly on the Python side,
the existing Kotlin logic requires no modification.

## Testing

- **`tracking_bearing.py`**: new tests for `compute_bearing_degrees`
  covering: a synthetic near-centered, growing-bbox track (near-zero
  lateral, non-trivial scale growth) returning 180°; the shrinking-bbox
  equivalent returning 0°; confirmation that an existing real-lateral-motion
  test case's exact output is unaffected by the new `bboxes` parameter
  (regression); a track with neither meaningful lateral nor scale change
  still returning `None` (noise, not fabricated).
- **`pipeline.py`**: a new test with a synthetic head-on-approach track
  (small centroid movement, large bbox growth across the track) confirming
  the combined `displacement_pixels` sent in the response is large enough
  to have cleared `ClipFlowAnalyzer`'s existing floor (traced by hand
  against the existing Kotlin gate's formula, not a Kotlin test change).
- **Regression**: the full existing `test_bearing.py` and `test_pipeline.py`
  suites must pass unchanged, proving zero behavior change for every
  currently-covered lateral-motion scenario.

## Non-goals (explicitly out of scope)

- Broader camera-motion/optical-flow visual odometry (the originally
  envisioned, broader interpretation of "sub-project 4") - deferred, not
  ruled out, per the scope decision above.
- Any Kotlin/server-side code change.
- `ByteTrack`'s own track-continuity behavior - a track lost after only 1-2
  observed frames (e.g. due to rapid occlusion) remains a separate,
  unaddressed problem. This fix only helps tracks that *are* observed long
  enough to build a trustworthy early/late sample, just with a form of
  motion the old lateral-only formula couldn't see at all.
- Retroactively re-analyzing already-submitted reports - this fix applies
  to future analysis only, same as every other video-analysis change in
  this project's history.

## Verification

1. **Unit tests** (above) prove the mechanism in isolation with synthetic
   data.
2. **Production replay**: after deployment, re-run analysis on report
   `55f7f82a` (the same technique used to verify sub-project 3 - extracting
   the stored video and resubmitting it, or re-running the video-analysis
   service directly) and confirm the previously-invisible head-on motorcycle
   now produces a real track with a non-`None` bearing near 180° and a
   `displacement_pixels` value that clears `ClipFlowAnalyzer`'s existing
   floor - closing the exact gap this sub-project exists to fix.
