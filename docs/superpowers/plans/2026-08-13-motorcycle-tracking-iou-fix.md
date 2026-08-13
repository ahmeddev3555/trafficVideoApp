# Motorcycle Tracking IoU Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix a real production bug where motorcycles near the recording
camera are detected by YOLO repeatedly and confidently but never form a
persisted track, because `supervision`'s `ByteTrack` has a hardcoded,
non-configurable 0.7 IoU threshold for confirming a brand-new track, and the
video-analysis service's `frame_stride=3` sampling makes real frame-to-frame
motorcycle IoU (measured 0.0-0.432 on a real production clip) fall below it.

**Architecture:** Split `VehicleDetector`'s single shared `ByteTrack`
instance into two - one for motorcycle-class detections (a fresh instance,
default parameters, which empirically produces stable tracks once fed
densely enough) and one for everything else (car/bus/truck, completely
unchanged parameters and behavior). Bump `frame_stride`'s default from 3 to
1 so the tracker actually receives every frame - the only lever that
reliably fixes the underlying IoU problem (config-level tuning of
`ByteTrack`'s public parameters does not touch the hardcoded gate). Offset
motorcycle track IDs by a large constant so they never collide with the
car/bus/truck tracker's independently-numbered IDs.

**Tech Stack:** Python, `supervision` 0.23.0 (`ByteTrack`), `ultralytics`
(YOLOv8), `pytest`.

## Global Constraints

- Motorcycle-specific fix only - the car/bus/truck tracker's own
  `ByteTrack` constructor parameters must not change, only the rate at
  which it receives input (a side effect of the global `frame_stride`
  change, not a targeted change to its own config).
- `frame_stride` default changes from 3 to 1 - accepted ~3x inference cost
  increase, confirmed to fit within the Kotlin server's 180s
  `read-timeout-ms` even at a full 10-second clip (measured ~146s worst
  case on the current no-GPU host).
- One shared YOLO detection pass per frame - never run YOLO twice on the
  same frame to serve two trackers.
- Motorcycle track IDs offset by `MOTORCYCLE_TRACK_ID_OFFSET = 1_000_000`
  before leaving `VehicleDetector`, so downstream `track_id`-based grouping
  in `pipeline.py` never conflates a motorcycle track with a car track that
  happens to share the same underlying numeric ID.
- No changes to any file downstream of `detection.py` (`pipeline.py`,
  `tracking_bearing.py`, `corridors.py`, the Kotlin server) - `TrackedFrame`
  objects from either tracker have an identical shape.

---

### Task 1: Split motorcycle tracking into a dedicated ByteTrack instance

**Files:**
- Modify: `video-analysis/app/detection.py`
- Modify: `video-analysis/app/config.py`
- Test: `video-analysis/tests/test_detection.py`

**Interfaces:**
- Consumes: nothing new - uses `sv.ByteTrack`, `sv.Detections`,
  `VEHICLE_CLASS_IDS`, `TrackedFrame`, and `Settings` exactly as they exist
  today.
- Produces: `VehicleDetector` continues to yield `TrackedFrame` objects with
  the same shape as today from `track_video()`. Motorcycle-sourced
  `TrackedFrame.track_id` values are now `>= 1_000_000`; every other
  vehicle type's `track_id` is unchanged (small positive integers, exactly
  as today).

- [ ] **Step 1: Write the failing tests**

Add to `video-analysis/tests/test_detection.py`:

```python
import numpy as np
import supervision as sv


def test_dense_sampling_persists_a_low_iou_motorcycle_track_that_sparse_sampling_drops():
    """Directly demonstrates the real bug mechanism against the real (un-mocked)
    ByteTrack, independent of VehicleDetector's own mocking - no code under test here
    yet, this documents WHY frame_stride must be 1, verified empirically against the
    exact library version this service uses (supervision 0.23.0). A brand-new
    ByteTrack track only auto-confirms if created on frame_id==1 of the tracker's
    lifetime; every other new track gets exactly one chance, on its very next
    reconsidered frame, to re-match with IoU >= 0.7 - a threshold hardcoded inside the
    library, not exposed via any constructor parameter (see the design spec). The same
    underlying motion, sampled densely (every real tick) vs sparsely (every 3rd tick,
    matching today's frame_stride=3), demonstrates the difference directly: small
    per-tick steps keep consecutive-frame IoU above 0.7; skipping two out of three
    ticks triples the apparent displacement the tracker sees between updates and
    collapses it below 0.7."""
    # A small (20x30px) box moving 8px per real tick - representative of a motorcycle
    # near a moving recording camera.
    true_ticks = [(10.0 + 8 * i, 10.0 + 2 * i, 30.0 + 8 * i, 40.0 + 2 * i) for i in range(18)]

    def run(boxes):
        tracker = sv.ByteTrack()
        results = []
        for x1, y1, x2, y2 in boxes:
            dets = sv.Detections(
                xyxy=np.array([[x1, y1, x2, y2]], dtype=np.float32),
                confidence=np.array([0.6], dtype=np.float32),
                class_id=np.array([3]),
            )
            post = tracker.update_with_detections(dets)
            results.append(post.tracker_id[0] if len(post) > 0 else None)
        return results

    dense = true_ticks[0::1][:6]  # every tick - frame_stride=1 equivalent
    sparse = true_ticks[0::3][:6]  # every 3rd tick - today's frame_stride=3 equivalent

    dense_ids = run(dense)
    sparse_ids = run(sparse)

    assert dense_ids.count(None) == 0, f"dense sampling should track every observation, got {dense_ids}"
    assert len(set(dense_ids)) == 1, f"dense sampling should be one continuous track, got {dense_ids}"
    assert sparse_ids[1:].count(None) == len(sparse_ids) - 1, (
        f"sparse sampling of the same motion should drop every observation after the first "
        f"(reproducing today's bug), got {sparse_ids}"
    )


@patch("app.detection.YOLO")
@patch("app.detection.cv2.VideoCapture")
def test_motorcycle_track_with_low_frame_to_frame_iou_still_persists(mock_video_capture, mock_yolo):
    """Integration-level regression test for the real production bug through the actual
    VehicleDetector: a small, fast-moving motorcycle sampled at frame_stride=1 via the
    dedicated motorcycle tracker must produce a persisted track - the old shared-tracker,
    frame_stride=3 code dropped this same class of motion entirely (see the sibling test
    above for the direct, un-mocked ByteTrack evidence of why)."""
    num_frames = 6
    dummy_frames = [np.zeros((10, 10, 3), dtype=np.uint8) for _ in range(num_frames)]
    mock_capture = MagicMock()
    mock_capture.read.side_effect = [(True, f) for f in dummy_frames] + [(False, None)]
    mock_video_capture.return_value = mock_capture

    # Same 8px-per-frame motion confirmed above to persist under dense sampling.
    moto_boxes = [(10.0 + 8 * i, 10.0 + 2 * i, 30.0 + 8 * i, 40.0 + 2 * i) for i in range(num_frames)]

    call_count = {"n": 0}

    def fake_model_call(frame, **kwargs):
        idx = call_count["n"]
        call_count["n"] += 1
        return [moto_boxes[idx]]  # result[0] below picks this back out

    mock_model_instance = MagicMock(side_effect=fake_model_call)
    mock_yolo.return_value = mock_model_instance

    def fake_from_ultralytics(result):
        x1, y1, x2, y2 = result
        return sv.Detections(
            xyxy=np.array([[x1, y1, x2, y2]], dtype=np.float32),
            confidence=np.array([0.6], dtype=np.float32),
            class_id=np.array([3]),  # motorcycle
        )

    from app.detection import VehicleDetector

    settings = _fake_settings()
    settings.frame_stride = 1

    with patch("app.detection.sv.Detections.from_ultralytics", side_effect=fake_from_ultralytics):
        detector = VehicleDetector(settings)
        results = list(detector.track_video("irrelevant.mp4"))

    motorcycle_frames = [r for r in results if r.vehicle_type == "motorcycle"]
    assert len(motorcycle_frames) == num_frames, (
        f"expected all {num_frames} observations to persist as one track, got "
        f"{len(motorcycle_frames)} - the old single-tracker code drops all but the first"
    )
    track_ids = {r.track_id for r in motorcycle_frames}
    assert len(track_ids) == 1, f"expected one persisted track, got {len(track_ids)} distinct ids"
    assert next(iter(track_ids)) >= 1_000_000, "motorcycle track_id must carry the offset"


@patch("app.detection.YOLO")
@patch("app.detection.cv2.VideoCapture")
def test_car_and_motorcycle_track_ids_never_collide(mock_video_capture, mock_yolo):
    """The two ByteTrack instances' internal id counters are not guaranteed to be
    independent per-instance (confirmed empirically: supervision 0.23.0's id assignment
    is not scoped to a single tracker instance within a process), so a car track and a
    motorcycle track could plausibly share the same raw underlying numeric id. The
    explicit offset applied to every motorcycle track_id must keep the two id spaces
    disjoint regardless of what the raw underlying values happen to be - this test
    checks the invariant, not specific hardcoded id values, since the raw values are
    not part of any documented, stable library contract."""
    dummy_frame = np.zeros((10, 10, 3), dtype=np.uint8)
    mock_capture = MagicMock()
    mock_capture.read.side_effect = [(True, dummy_frame), (False, None)]
    mock_video_capture.return_value = mock_capture

    def fake_model_call(frame, **kwargs):
        return ["one_frame"]  # single call - content unused, from_ultralytics is faked below

    mock_model_instance = MagicMock(side_effect=fake_model_call)
    mock_yolo.return_value = mock_model_instance

    def fake_from_ultralytics(result):
        # One car (class 2) and one motorcycle (class 3) in the same frame.
        return sv.Detections(
            xyxy=np.array([[0.0, 0.0, 50.0, 50.0], [60.0, 60.0, 90.0, 100.0]], dtype=np.float32),
            confidence=np.array([0.9, 0.6], dtype=np.float32),
            class_id=np.array([2, 3]),
        )

    from app.detection import VehicleDetector

    settings = _fake_settings()
    settings.frame_stride = 1

    with patch("app.detection.sv.Detections.from_ultralytics", side_effect=fake_from_ultralytics):
        detector = VehicleDetector(settings)
        results = list(detector.track_video("irrelevant.mp4"))

    by_type = {r.vehicle_type: r.track_id for r in results}
    assert by_type["car"] < 1_000_000, "car track_id should never carry the motorcycle offset"
    assert by_type["motorcycle"] >= 1_000_000, "motorcycle track_id must carry the offset"
    assert by_type["car"] != by_type["motorcycle"]
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `pytest video-analysis/tests/test_detection.py -v -k "low_iou_motorcycle_track or never_collide"`
Expected: `test_dense_sampling_persists_a_low_iou_motorcycle_track_that_sparse_sampling_drops`
PASSES already (it exercises real `ByteTrack` directly, with no dependency on this
plan's code changes - it's evidence/documentation, not a test of new code).
`test_motorcycle_track_with_low_frame_to_frame_iou_still_persists` FAILS because the
current single-tracker code only yields the first of the 6 observations (frame_id==1
auto-confirms; every later frame is dropped). `test_car_and_motorcycle_track_ids_never_collide`
FAILS because there is no `MOTORCYCLE_TRACK_ID_OFFSET` applied yet, so
`by_type["motorcycle"] >= 1_000_000` is false.

- [ ] **Step 3: Change `frame_stride`'s default in config.py**

Modify `video-analysis/app/config.py`, replacing the existing `frame_stride`
field and its comment:

```python
    # Process every Nth frame - the main knob for bounding CPU runtime on hardware with no
    # GPU assumed. Must stay at 1 (every frame) for reliable motorcycle tracking: ByteTrack's
    # hardcoded unconfirmed-track IoU threshold (0.7, not exposed via any constructor
    # parameter) fails for small/fast/near-camera objects once frame_stride widens the real
    # displacement between samples the tracker sees - confirmed empirically against a real
    # production clip (see
    # docs/superpowers/specs/2026-08-13-motorcycle-tracking-iou-fix-design.md). Car/bus/truck
    # tracking tolerates a wider stride fine since their boxes are larger relative to the
    # same absolute displacement, but this field is shared - there is no way to sample more
    # densely for one class without sampling more densely, period.
    frame_stride: int = 1
```

- [ ] **Step 4: Implement the two-tracker split in detection.py**

Modify `video-analysis/app/detection.py`. First, add the two new module-level
constants directly below `VEHICLE_CLASS_IDS`:

```python
# COCO class id for motorcycles - tracked separately from every other vehicle type
# (see the design spec) because small, fast, near-camera objects systematically fail
# ByteTrack's hardcoded unconfirmed-track IoU threshold under this service's sampling.
MOTORCYCLE_CLASS_ID = 3

# Added to every motorcycle track's id before it leaves VehicleDetector. The two
# ByteTrack instances' internal id counters are not guaranteed to be independent
# per-instance (confirmed empirically against supervision 0.23.0 - id assignment is
# not scoped to a single tracker instance within a process), so without this offset a
# motorcycle track and a car track could plausibly share the same raw numeric id and
# get conflated by pipeline.py's grouping-by-track_id. Chosen far larger than any
# plausible single-clip track count.
MOTORCYCLE_TRACK_ID_OFFSET = 1_000_000
```

Then replace the `__init__` method:

```python
    def __init__(self, settings: Settings):
        self._settings = settings
        self._model = YOLO(settings.yolo_model_path)
        self._tracker = sv.ByteTrack()
        self._moto_tracker = sv.ByteTrack()
```

Then replace the entire `_detect_frame` method with:

```python
    def _detect_frame(self, frame: np.ndarray, frame_index: int) -> Iterator[TrackedFrame]:
        result = self._model(frame, verbose=False, imgsz=self._settings.detection_imgsz)[0]
        detections = sv.Detections.from_ultralytics(result)

        vehicle_mask = np.isin(detections.class_id, list(VEHICLE_CLASS_IDS.keys()))
        confidence_mask = detections.confidence >= self._settings.min_detection_confidence
        detections = detections[vehicle_mask & confidence_mask]

        moto_mask = detections.class_id == MOTORCYCLE_CLASS_ID
        moto_detections = self._moto_tracker.update_with_detections(detections[moto_mask])
        other_detections = self._tracker.update_with_detections(detections[~moto_mask])

        yield from self._tracked_frames_from(other_detections, frame, frame_index, id_offset=0)
        yield from self._tracked_frames_from(
            moto_detections, frame, frame_index, id_offset=MOTORCYCLE_TRACK_ID_OFFSET
        )

    def _tracked_frames_from(
        self, detections: sv.Detections, frame: np.ndarray, frame_index: int, id_offset: int
    ) -> Iterator[TrackedFrame]:
        for i in range(len(detections)):
            x1, y1, x2, y2 = detections.xyxy[i]
            class_id = int(detections.class_id[i])
            tracker_id = detections.tracker_id[i]
            if tracker_id is None:
                continue

            yield TrackedFrame(
                track_id=int(tracker_id) + id_offset,
                vehicle_type=VEHICLE_CLASS_IDS.get(class_id, "vehicle"),
                confidence=float(detections.confidence[i]),
                frame_index=frame_index,
                centroid=((float(x1) + float(x2)) / 2.0, (float(y1) + float(y2)) / 2.0),
                bbox=(float(x1), float(y1), float(x2), float(y2)),
                frame=frame,
            )
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `pytest video-analysis/tests/test_detection.py -v`
Expected: PASS - all tests in the file, including the two new ones and the three
pre-existing ones (`test_track_video_enables_orientation_auto_before_reading_frames`,
`test_read_fps_returns_the_capture_fps`, `test_read_fps_returns_none_for_zero_or_invalid_fps`).

- [ ] **Step 6: Run the full video-analysis test suite to confirm no regressions**

Run: `pytest video-analysis/tests/ -v`
Expected: PASS - every existing test in `test_pipeline.py`, `test_bearing.py`,
`test_corridors.py`, `test_api_health.py`, and `test_frame_encoding.py` continues to
pass unchanged, confirming `pipeline.py` and everything downstream of `detection.py`
is unaffected (they consume `TrackedFrame` objects directly via `FakeDetector` in
tests, never touching real `ByteTrack`).

- [ ] **Step 7: Commit**

```bash
git add video-analysis/app/detection.py video-analysis/app/config.py video-analysis/tests/test_detection.py
git commit -m "fix: track motorcycles in a dedicated ByteTrack instance fed at frame_stride=1

ByteTrack's unconfirmed-track promotion requires IoU >= 0.7 against a
literal hardcoded in the supervision library - not exposed via any
constructor parameter. Motorcycles near the recording camera have small
boxes that move a lot frame to frame (measured 0.0-0.432 IoU on a real
production clip), so they never clear that bar and get silently dropped,
even though YOLO detects them confidently every time. Splitting motorcycle
tracking into its own instance and raising frame_stride to 1 (the only
lever that actually raises real frame-to-frame IoU, since no ByteTrack
parameter touches the hardcoded gate) fixes it without touching
car/bus/truck tracking's own parameters."
```

- [ ] **Step 8: Manual verification against the real report that surfaced this bug**

This step requires SSH access to the production host and cannot be automated -
perform it yourself once Task 1 is merged, following the same replay pattern used to
diagnose this bug: copy report `d0a55799-85a2-4dc1-aa54-ee3d339ae123`'s stored video
off the server, run it through the updated `AnalysisPipeline.analyze()` (or a direct
`VehicleDetector.track_video()` call) with the new code, and confirm both real-world
motorcycles now appear in the tracked-vehicle output - matching what was measured live
during the original diagnosis in this session. Not part of the automated test suite;
this is the final confirmation that the fix works against the real clip that motivated
it, not just synthetic test data.
