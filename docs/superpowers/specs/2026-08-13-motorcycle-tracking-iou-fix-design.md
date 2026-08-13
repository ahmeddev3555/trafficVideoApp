# Motorcycle Tracking IoU Fix - Design

## Context

While auditing report `d0a55799-85a2-4dc1-aa54-ee3d339ae123` (a confirmed
wrong-way report later traced to a separate false-positive cause - likely
compass interference from a power transmission tower in frame, documented
separately), a visual review of sampled frames surfaced two real motorcycles
passing near the recording camera that never appeared anywhere in the
video-analysis service's tracked-vehicle output at all - not a low-confidence
drop, a complete absence.

Root-caused by direct, empirical investigation against the real production
service and the real stored clip:

- YOLO detects both motorcycles repeatedly and confidently (0.48-0.81,
  comfortably above the `min_detection_confidence` floor of 0.4) across at
  least 9 consecutive frames the pipeline actually samples.
- `ByteTrack.update_with_detections()` (from the `supervision` library,
  version 0.23.0) drops every one of these detections, every single sampled
  frame - never assigning either motorcycle a `tracker_id`.
- Ruled out via direct testing: a `frame_rate`/`frame_stride` mismatch in the
  tracker's motion-prediction calibration (corrected it, no change);
  duplicate-detection/NMS artifacts (confirmed two genuinely separate,
  non-overlapping riders); accumulated tracker state from the other ~30 cars
  tracked earlier in the same clip (tested with a fresh, isolated tracker
  instance scoped only to the relevant window - still failed).
- Root cause, confirmed by reading `supervision`'s `ByteTrack`/`STrack`
  source directly: a brand-new (unconfirmed) track is only auto-confirmed if
  created on literally `frame_id == 1` of the tracker's whole lifetime.
  Every other new track gets exactly one chance, on its very next
  reconsidered frame, to re-match with IoU >= **0.7** against its own prior
  box - a threshold hardcoded as a literal inside the library's unconfirmed-
  track-promotion step, not exposed through any constructor parameter.
  Measured real frame-to-frame IoU for these motorcycles was 0.0-0.432,
  consistently below that hardcoded bar, because their small box size
  (52x63px up to ~139x126px) combined with the recording camera's own motion
  and `frame_stride=3` (tripling the real time, and thus real displacement,
  between samples the tracker sees) produces large apparent per-frame
  motion relative to their own box size. Cars, being larger and generally
  farther away, tolerate the same absolute pixel displacement fine because
  it's a much smaller fraction of their box size.
- Confirmed empirically that no combination of `ByteTrack`'s public
  constructor parameters (`minimum_matching_threshold`, `lost_track_buffer`)
  fixes this - they don't touch the hardcoded 0.7 gate. The only lever that
  worked: shrinking the real time between samples enough that consecutive
  frames naturally clear 0.7 IoU on their own (tested directly against the
  real clip - `frame_stride=1` produced two clean, continuous tracks
  spanning the motorcycles' full visibility window; `frame_stride=2`,
  even combined with parameter tuning, remained fragmented and unreliable).

This is not a one-off bug in this one clip - it's a structural weakness:
any small, fast-moving, or near-camera object is systematically prone to
being tracked away to nothing under the current sampling rate, independent
of whether it's a wrong-way violator or not. Motorcycles are the highest-risk
case in practice, given how common and maneuverable they are on the roads
this app targets.

## Scope & explicit decisions (confirmed with the user - do not re-litigate)

- **Motorcycle-specific fix only**, not a general small-object tracking
  overhaul. Car/bus/truck tracking is already working and must not have its
  own tuning/behavior touched - only the rate at which it receives input
  changes (see below), never its `ByteTrack` parameters.
- **`frame_stride` changes from 3 to 1 (every frame analyzed)**, accepted
  despite the ~3x inference cost increase this implies, because it's the
  only approach that reliably fixes the problem - `frame_stride=2` was
  tested directly (with and without additional tracker tuning) and remained
  fragmented/unreliable. A missed wrong-way violation is a worse outcome
  than slower analysis on a service whose read timeout (180s,
  `app.video-analysis.read-timeout-ms` on the Kotlin server side) has
  comfortable headroom even at the worst case (a full 10-second clip at
  `frame_stride=1` measures to roughly 146s of inference on the current
  no-GPU host, versus 180s available).
- **One shared detection pass, not a redundant second one.** `frame_stride`
  is a property of how often YOLO runs on the video at all - there's no way
  to sample more densely for one class without sampling more densely,
  period. Running a fully separate second YOLO pass just for motorcycles
  would mean re-processing the frames already covered by the main pass a
  second time (~4x total cost) for no benefit over doing it once at the
  higher rate for everyone. The car/bus/truck path's own tracker keeps its
  existing default parameters completely unchanged - only its input cadence
  increases, which given everything measured this session should only
  improve car-tracking stability, never harm it (denser input strictly adds
  information to an IoU-based matcher; it cannot make matching harder).
- **Two separate `ByteTrack` instances**, not one shared instance filtering
  by class internally. Isolates motorcycle-specific behavior (today:
  default parameters, since default parameters already work once fed at
  `frame_stride=1` - see Testing) from the car path's tracker state and ID
  space, and leaves room for independent motorcycle-specific tuning later
  without any risk to car tracking.
- **Track ID collision**: since the two trackers assign IDs from independent
  counters starting at 1, a motorcycle track and a car track could receive
  the same numeric ID. Fixed by offsetting motorcycle track IDs by a large
  constant before they leave `VehicleDetector` - downstream code
  (`pipeline.py`'s grouping-by-`track_id`) never needs to know two trackers
  were involved.

## Architecture overview

`detection.py`'s `VehicleDetector.track_video()` currently runs YOLO on
every 3rd frame and feeds every detected vehicle class into one shared
`sv.ByteTrack()` instance. This changes to:

1. `frame_stride` default changes from 3 to 1 (still a `Settings` field, not
   hardcoded - operationally retunable without a rebuild, same as today).
2. Per processed frame, after the existing class/confidence filtering
   (unchanged), detections are split by class: motorcycle-class (`class_id
   == 3`) detections go to a new `self._moto_tracker` (`sv.ByteTrack()`,
   default parameters); every other vehicle class goes to the existing
   `self._tracker`, completely unchanged.
3. Motorcycle-sourced `TrackedFrame`s get their `track_id` offset by a fixed
   constant (`MOTORCYCLE_TRACK_ID_OFFSET = 1_000_000`, chosen to be far
   larger than any plausible single-clip track count) before being yielded,
   so `pipeline.py`'s downstream grouping never conflates a motorcycle
   track with a car track that happens to share the same underlying numeric
   ID.
4. Everything downstream of `track_video()` - `pipeline.py`,
   `tracking_bearing.py`, `corridors.py`, the Kotlin server's
   `ClipFlowAnalyzer` - is completely unchanged. A motorcycle's `TrackedFrame`
   objects look identical in shape to a car's; the two-tracker split and ID
   offset are invisible past `VehicleDetector`.

## Data flow

```
VehicleDetector.track_video(video_path)
  cv2.VideoCapture + CAP_PROP_ORIENTATION_AUTO (unchanged)
  for every raw frame (frame_stride now 1, was 3):
    result = self._model(frame, imgsz=960)  # one inference call, unchanged
    detections = sv.Detections.from_ultralytics(result)
    vehicle_mask + confidence_mask (unchanged, >= min_detection_confidence)
    detections = detections[vehicle_mask & confidence_mask]

    moto_mask = detections.class_id == 3
    moto_detections = self._moto_tracker.update_with_detections(detections[moto_mask])
    other_detections = self._tracker.update_with_detections(detections[~moto_mask])

    for each entry in other_detections:
      yield TrackedFrame(track_id=tracker_id, ...)              # unchanged shape/values
    for each entry in moto_detections:
      yield TrackedFrame(track_id=tracker_id + MOTORCYCLE_TRACK_ID_OFFSET, ...)
```

`pipeline.py`'s `_summarize_track` groups everything by `track_id` exactly
as today - it never needs to know two trackers were involved.

## Testing

- **New unit test** (`tests/test_detection.py`): a synthetic frame sequence
  (using this codebase's existing `FakeDetector`-style stubbing pattern)
  where a small, fast-moving class-id-3 detection has low frame-to-frame
  IoU that would fail the real `ByteTrack`'s default confirmation gate under
  sparse sampling - assert it still produces a valid track (>=
  `MIN_TRACK_FRAMES` observations) once fed at `frame_stride=1`, where
  today's single-tracker, `frame_stride=3` code drops it entirely. This is
  the direct regression test for the bug this plan fixes.
- **Regression test**: existing car/bus/truck tracking tests continue to
  pass completely unchanged - proves the car path's tracker parameters and
  behavior are untouched.
- **New unit test**: a car track and a motorcycle track whose underlying
  tracker-assigned numeric IDs collide (e.g. both `1`) must not be
  conflated by `pipeline.py`'s grouping after the offset is applied - direct
  test of the ID-collision fix.
- **Manual verification**: re-run analysis against report
  `d0a55799-85a2-4dc1-aa54-ee3d339ae123`'s actual stored clip and confirm
  both real-world motorcycles now appear as tracked vehicles in the
  response - matching what was measured live against the real clip during
  this investigation.

## Non-goals (explicitly out of scope)

- Not a general "fix small/fast-object tracking for every class" - scoped
  specifically to `class_id == 3` (motorcycle), per the confirmed scope
  decision above.
- Not retuning `detection_imgsz`, `min_detection_confidence`, or the YOLO
  model itself - none of those were implicated by the diagnosis.
- Not changing the car/bus/truck tracker's own constructor parameters.
- Not addressing the separate, already-diagnosed false-positive cause on
  report `d0a55799…9ae123` itself (likely compass/magnetometer interference)
  - unrelated bug, tracked separately.
- Not retroactively re-analyzing already-submitted reports - this fix
  applies to future analysis only, consistent with how every other
  video-analysis change in this project's history has shipped.

## Files touched (summary)

**Python - modified**: `video-analysis/app/detection.py` (two-tracker split,
ID offset), `video-analysis/app/config.py` (`frame_stride` default 3 -> 1).
**Python - tests**: `video-analysis/tests/test_detection.py` (new
low-IoU-motorcycle-track test, new ID-collision test).
