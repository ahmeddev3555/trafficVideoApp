# Approach/Recession Bearing Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make a vehicle approaching or receding nearly head-on toward the camera produce a real bearing and a real displacement value, instead of being silently dropped by lateral-pixel-only motion checks - closing a real gap confirmed on production report `55f7f82a`.

**Architecture:** Bounding-box scale change (growth = approaching, shrinkage = receding) is treated as an independent, OR'd form of proof-of-motion alongside the existing lateral pixel displacement, in two places that currently both gate on lateral motion alone: `compute_bearing_degrees` (Python, `video-analysis/app/tracking_bearing.py`) and `_summarize_track`'s `displacement_pixels` calculation (Python, `video-analysis/app/pipeline.py`). No Kotlin/server changes - the existing Kotlin gate just receives a smarter number.

**Tech Stack:** Python 3.11, pytest (existing `video-analysis/` service - no new dependencies).

## Global Constraints

- Existing lateral-motion behavior must be byte-for-byte unchanged when lateral displacement alone already clears `MIN_DISPLACEMENT_PIXELS` (8.0) - the new logic only activates below that floor.
- No new tunable constants - both `MIN_DISPLACEMENT_PIXELS` (Python) and `minDisplacementFraction` (Kotlin, untouched) keep their current values.
- Fallback bearing convention: `180°` for approaching (bbox grew), `0°` for receding (bbox shrank) - matches the existing `0°` = "up in frame" = "away from camera" convention documented in `compute_bearing_degrees`'s docstring.
- No Kotlin/server file may be modified by this plan.
- Combine lateral and scale displacement via `math.hypot(lateral, scale)` (quadrature) everywhere the combination is needed.

---

### Task 1: `compute_bearing_degrees` gains a bbox-aware fallback

**Files:**
- Modify: `video-analysis/app/tracking_bearing.py`
- Test: `video-analysis/tests/test_bearing.py`

**Interfaces:**
- Consumes: nothing new (self-contained pure-math change).
- Produces: `bbox_diagonal(bbox: Tuple[float, float, float, float]) -> float` (new, public - Task 2 imports and reuses this exact function, do not make it private/underscore-prefixed); `compute_bearing_degrees(centroids, bboxes: Sequence[Tuple[float, float, float, float]] | None = None, sample_size: int = DEFAULT_SAMPLE_SIZE) -> float | None` (new `bboxes` parameter, positioned second, after `centroids` and before `sample_size` - Task 2 calls this with both `centroids` and `bboxes` positionally).

- [ ] **Step 1: Write the failing tests**

Add to `video-analysis/tests/test_bearing.py` (after the existing tests, reusing the existing `import pytest` and `from app.tracking_bearing import compute_bearing_degrees` already at the top):

```python
def test_near_centered_approaching_vehicle_returns_180_degrees():
    # Centroids barely move (lateral displacement well under the 8.0px floor), but the
    # bounding box grows dramatically - a vehicle driving straight at the camera.
    centroids = [(50.0, 50.0)] * 8
    early_bbox = (40.0, 40.0, 60.0, 60.0)   # diagonal ~28.28
    late_bbox = (0.0, 0.0, 100.0, 100.0)    # diagonal ~141.42
    bboxes = [early_bbox] * 4 + [late_bbox] * 4

    assert compute_bearing_degrees(centroids, bboxes) == 180.0


def test_near_centered_receding_vehicle_returns_0_degrees():
    centroids = [(50.0, 50.0)] * 8
    early_bbox = (0.0, 0.0, 100.0, 100.0)   # diagonal ~141.42
    late_bbox = (40.0, 40.0, 60.0, 60.0)    # diagonal ~28.28
    bboxes = [early_bbox] * 4 + [late_bbox] * 4

    assert compute_bearing_degrees(centroids, bboxes) == 0.0


def test_neither_lateral_nor_scale_change_is_significant_returns_none():
    centroids = [(50.0, 50.0)] * 4 + [(50.5, 50.0)] * 4  # lateral displacement 0.5px
    early_bbox = (40.0, 40.0, 60.0, 60.0)   # diagonal ~28.28
    late_bbox = (42.0, 42.0, 62.0, 62.0)    # same size, diagonal ~28.28 - no scale change
    bboxes = [early_bbox] * 4 + [late_bbox] * 4

    assert compute_bearing_degrees(centroids, bboxes) is None


def test_real_lateral_motion_is_unaffected_by_the_new_bboxes_parameter():
    track = _linear_track((50.0, 100.0), (300.0, 100.0))
    dummy_bboxes = [(0.0, 0.0, 10.0, 10.0)] * len(track)

    # Identical result whether bboxes is omitted (existing callers) or passed (new caller) -
    # lateral displacement alone already clears the floor, so the fallback never activates.
    assert compute_bearing_degrees(track) == pytest.approx(90.0, abs=1e-6)
    assert compute_bearing_degrees(track, dummy_bboxes) == pytest.approx(90.0, abs=1e-6)


def test_bbox_diagonal_computes_the_euclidean_diagonal():
    from app.tracking_bearing import bbox_diagonal

    assert bbox_diagonal((0.0, 0.0, 3.0, 4.0)) == pytest.approx(5.0, abs=1e-9)
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd video-analysis && python -m pytest tests/test_bearing.py -v`
Expected: FAIL - `compute_bearing_degrees() takes from 1 to 2 positional arguments but 3 were given` (or similar) for the new tests; `ImportError: cannot import name 'bbox_diagonal'` for the last one. Existing tests still pass.

- [ ] **Step 3: Implement `bbox_diagonal` and extend `compute_bearing_degrees`**

Replace the full contents of `video-analysis/app/tracking_bearing.py` with:

```python
from __future__ import annotations

import math
from typing import Sequence, Tuple

# Below this many observed frames, a track is too brief to trust a direction estimate.
MIN_OBSERVATIONS = 4

# Below this many pixels of net displacement, motion is treated as noise (a stationary or
# barely-moving vehicle), not a fabricated direction. Applies to lateral displacement, scale
# (bbox-diagonal) displacement, and their quadrature combination alike - one floor, three
# possible sources of "real motion happened."
MIN_DISPLACEMENT_PIXELS = 8.0

# How many frames at the start/end of a track to average when estimating displacement.
DEFAULT_SAMPLE_SIZE = 4


def bbox_diagonal(bbox: Tuple[float, float, float, float]) -> float:
    """Euclidean length of a bounding box's diagonal, in pixels. `bbox` is (x1, y1, x2, y2)."""
    x1, y1, x2, y2 = bbox
    return math.hypot(x2 - x1, y2 - y1)


def compute_bearing_degrees(
    centroids: Sequence[Tuple[float, float]],
    bboxes: Sequence[Tuple[float, float, float, float]] | None = None,
    sample_size: int = DEFAULT_SAMPLE_SIZE,
) -> float | None:
    """Frame-relative bearing in degrees [0, 360), clockwise from "up" in the frame.

    `centroids` is a track's (x, y) pixel centroids in temporal order (pixel y increases
    downward, as in OpenCV/most image coordinate systems). Averages the first and last
    `sample_size` observations (rather than just the first/last single frame) to smooth out
    per-frame detection jitter. Returns None - never a fabricated direction - when there
    are too few observations or the net displacement is too small to trust.

    `bboxes` (optional, parallel to `centroids`) lets a vehicle moving nearly head-on toward
    or away from the camera - which produces almost no LATERAL centroid displacement, since
    it's growing/shrinking in place rather than sliding across the frame - still produce a
    real bearing instead of a fabricated None. When lateral displacement alone clears
    MIN_DISPLACEMENT_PIXELS, bboxes is never consulted and behavior is identical to before
    this parameter existed. Only when lateral displacement is under that floor AND bboxes is
    provided does bounding-box scale change (bbox diagonal growing = approaching, shrinking =
    receding) get a chance to independently prove real motion happened, via a fallback of
    180 degrees (approaching - the reverse of "away", matching the "0 = up = away from
    camera" convention below) or 0 degrees (receding).
    """
    if len(centroids) < MIN_OBSERVATIONS:
        return None

    n = min(sample_size, len(centroids) // 2)
    if n == 0:
        return None

    early = centroids[:n]
    late = centroids[-n:]

    early_x = sum(p[0] for p in early) / len(early)
    early_y = sum(p[1] for p in early) / len(early)
    late_x = sum(p[0] for p in late) / len(late)
    late_y = sum(p[1] for p in late) / len(late)

    dx = late_x - early_x
    dy = late_y - early_y
    lateral_displacement = math.hypot(dx, dy)

    if lateral_displacement >= MIN_DISPLACEMENT_PIXELS:
        # atan2(dx, -dy): "up" in the frame (negative dy, since pixel y grows downward) maps to
        # 0 degrees, "right" (positive dx) maps to 90 degrees - the same clockwise-from-up
        # convention as a compass bearing, just frame-relative instead of true-north-relative.
        return math.degrees(math.atan2(dx, -dy)) % 360.0

    if bboxes is None or len(bboxes) < MIN_OBSERVATIONS:
        return None

    early_bboxes = bboxes[:n]
    late_bboxes = bboxes[-n:]
    early_diagonal = sum(bbox_diagonal(b) for b in early_bboxes) / len(early_bboxes)
    late_diagonal = sum(bbox_diagonal(b) for b in late_bboxes) / len(late_bboxes)
    scale_displacement = abs(late_diagonal - early_diagonal)

    combined_displacement = math.hypot(lateral_displacement, scale_displacement)
    if combined_displacement < MIN_DISPLACEMENT_PIXELS:
        return None

    return 180.0 if late_diagonal > early_diagonal else 0.0


def compute_track_midpoint_ms(
    first_frame_index: int,
    last_frame_index: int,
    fps: float | None,
) -> int | None:
    """Elapsed milliseconds from the analyzed clip's start to the midpoint between a
    track's first and last observed frame. None when fps is unavailable or non-positive -
    never a fabricated timestamp. Used by the Kotlin server to look up this vehicle's
    camera orientation at roughly the moment it was observed, instead of applying one
    static compass reading to every vehicle in the clip regardless of when it appeared."""
    if fps is None or fps <= 0:
        return None
    midpoint_frame = (first_frame_index + last_frame_index) / 2
    return round(midpoint_frame / fps * 1000)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd video-analysis && python -m pytest tests/test_bearing.py -v`
Expected: PASS (all tests, old and new)

- [ ] **Step 5: Commit**

```bash
git add video-analysis/app/tracking_bearing.py video-analysis/tests/test_bearing.py
git commit -m "feat(video-analysis): bbox-scale fallback for near-head-on bearing"
```

---

### Task 2: Wire bbox-aware displacement into the pipeline

**Files:**
- Modify: `video-analysis/app/pipeline.py`
- Test: `video-analysis/tests/test_pipeline.py`

**Interfaces:**
- Consumes: `bbox_diagonal(bbox) -> float` and `compute_bearing_degrees(centroids, bboxes, sample_size=...) -> float | None` (Task 1, `app/tracking_bearing.py`).
- Produces: nothing new for later tasks - this is the final task in the plan. `VehicleResult.displacement_pixels` now reflects the combined (lateral + scale) value described in the design spec; `VehicleResult.bearing_degrees` now uses the bbox-aware fallback for near-head-on tracks.

- [ ] **Step 1: Write the failing test**

Add to `video-analysis/tests/test_pipeline.py` (after the existing tests, reusing the existing `_make_frame`/`_fake_settings` helpers and `import pytest` already needed elsewhere in this file - add `import pytest` at the top if not already present):

```python
def test_head_on_approaching_track_gets_a_real_bearing_and_a_scale_dominated_displacement():
    # Centroid stays fixed (zero lateral motion) while the bounding box grows dramatically -
    # a vehicle driving straight at the camera. Before this fix, bearing_degrees would be
    # None and displacement_pixels would be 0.0 (lateral-only), silently dropping the vehicle
    # from all downstream flow analysis regardless of how obvious the approach was visually.
    frames = [
        _make_frame(track_id=1, frame_index=i, bbox=(40.0, 40.0, 60.0, 60.0))
        for i in range(4)
    ] + [
        _make_frame(track_id=1, frame_index=i, bbox=(0.0, 0.0, 100.0, 100.0))
        for i in range(4, 8)
    ]
    pipeline = AnalysisPipeline(
        settings=_fake_settings(), detector=FakeDetector(frames), plate_reader=FakePlateReader()
    )

    response = pipeline.analyze("unused.mp4")

    vehicle = response.vehicles[0]
    assert vehicle.bearing_degrees == 180.0
    # lateral displacement is 0 (all _make_frame calls use bbox center (50,50) as centroid,
    # since _make_frame derives centroid from the bbox passed in); scale displacement is
    # |diagonal(100,100 box) - diagonal(20,20 box)| = |141.42... - 28.28...| ~= 113.14,
    # combined via hypot(0, scale) = the same ~113.14 - large enough to clear
    # ClipFlowAnalyzer's existing displacement floor on the Kotlin side (unmodified by this
    # plan), where today's lateral-only 0.0 would not have.
    assert vehicle.displacement_pixels == pytest.approx(113.137, abs=0.01)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd video-analysis && python -m pytest tests/test_pipeline.py -v`
Expected: FAIL - `assert None == 180.0` (bearing) and/or `assert 0.0 == pytest.approx(113.137, ...)` (displacement), since neither `compute_bearing_degrees` is called with `bboxes` yet nor is `displacement` combined with scale yet.

- [ ] **Step 3: Wire bbox-aware bearing and displacement into `_summarize_track`**

In `video-analysis/app/pipeline.py`:

1. Update the import line to pull in `bbox_diagonal` alongside the existing imports:

```python
from app.tracking_bearing import bbox_diagonal, compute_bearing_degrees, compute_track_midpoint_ms
```

2. Replace `_summarize_track`'s body from the `centroids = [...]` line through the `displacement = ...` line with:

```python
        frames_sorted = sorted(frames, key=lambda f: f.frame_index)
        centroids = [f.centroid for f in frames_sorted]
        bboxes = [f.bbox for f in frames_sorted]
        bearing = compute_bearing_degrees(centroids, bboxes)
        track_midpoint_ms = compute_track_midpoint_ms(
            frames_sorted[0].frame_index, frames_sorted[-1].frame_index, fps
        )

        lateral_displacement = math.hypot(
            centroids[-1][0] - centroids[0][0], centroids[-1][1] - centroids[0][1]
        )
        scale_displacement = abs(bbox_diagonal(bboxes[-1]) - bbox_diagonal(bboxes[0]))
        displacement = math.hypot(lateral_displacement, scale_displacement)
```

   (leave every other line of `_summarize_track` - `vehicle_type`, `detection_confidence`,
   `plate_text`/`plate_confidence`, `representative_frame`, `bounding_box`,
   `frame_jpeg_base64`, and the final `VehicleResult(...)` construction - completely
   unchanged; `displacement` is still consumed as `displacement_pixels=displacement` exactly
   as it already is today)

- [ ] **Step 4: Run test to verify it passes**

Run: `cd video-analysis && python -m pytest tests/test_pipeline.py -v`
Expected: PASS (all tests, old and new)

- [ ] **Step 5: Run the full Python test suite**

Run: `cd video-analysis && python -m pytest -v`
Expected: PASS (all tests across all files - `test_bearing.py`, `test_pipeline.py`,
`test_detection.py`, `test_corridors.py`, `test_api_health.py`, `test_frame_encoding.py`)

- [ ] **Step 6: Commit**

```bash
git add video-analysis/app/pipeline.py video-analysis/tests/test_pipeline.py
git commit -m "feat(video-analysis): combine lateral and scale displacement in the pipeline"
```
