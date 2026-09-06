# Counter-Flow Wrong-Way Detection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Flag a vehicle riding against traffic straight toward the camera, using a short track fragment plus a perspective-immune "moves against the clip's own flow" signal, without needing a clean long track or OSM one-way data.

**Architecture:** Three layers. (1) `video-analysis` lowers the motorcycle detection-confidence floor so the head-on rider is detected continuously. (2) `video-analysis` computes a new per-track `flow_alignment ∈ [−1, 1]` — the track's distance-gated frame-space velocity dotted against the clip's displacement-weighted dominant flow — plus clip-level `dominant_flow_degrees` / `flow_coherence`. (3) the server gains a third REJECTED→CONFIRMED fallback, `tryCounterFlowDetection`, that fires only for a lone well-detected vehicle moving hard (`flow_alignment ≤ −0.6`) against a large coherent forward stream; and its existing stationary-approach fallback is widened to all `Unknown` OSM resolutions.

**Tech Stack:** Python 3.11 (FastAPI, ultralytics, supervision, numpy, pytest) for `video-analysis`; Kotlin / Spring Boot / JUnit5 / MockK for `server`.

**Spec:** `docs/superpowers/specs/2026-09-06-counter-flow-wrong-way-detection-design.md`

## Global Constraints

- New `VehicleResult` / `AnalyzeResponse` fields and new `VehicleAnalysisResult` / `VideoAnalysisResponse` fields are all **nullable with a null (or 0.0 / "flat"-style) default** — an old service response and an old client must both keep parsing. No existing field changes type or meaning.
- `bearing_degrees`, `bearing_source`, `resolve_bearing`, `scale_trend`, corridor logic: **unchanged**. `flow_alignment` is purely additive.
- The counter-flow path and the stationary-approach path **only ever upgrade an already-REJECTED outcome to CONFIRMED**; they never downgrade or alter a CONFIRMED/REJECTED reached by the main path. Both return `null` to leave the outcome untouched.
- Counter-flow and widened-approach eligibility: `OneWay` and **all** `DirectionResolution.Unknown` reasons. `NotFound` / `LookupFailed` / `TwoWay` stay ineligible.
- Exact new config values (spec §Layer 2, §Layer 3b): `motorcycle_min_confidence = 0.25`, `approach_min_frames` 30 → `20`, `counter_flow_min_coherence = 0.60` (**controller ruling 2026-09-06**, revised down from the spec's 0.75: `flow_coherence` is the mean resultant length R over *all* directional tracks, so an N-forward / 1-counter split gives R = (N−1)/(N+1) — 0.75 would need ~8 forward tracks; 0.60 matches the project's existing `analysis.consensus-min-resultant-length` "coherent directional stream" bar and still lets a quiet-road 5-forward + 1-wrong-way case (R ≈ 0.667) fire, while a genuine two-way head-on split (R ≈ 0) still never fires), `counter_flow_max_alignment = -0.6`, `counter_flow_min_frames = 12`, `counter_flow_min_with_flow = 5`.
- `video-analysis` tests run under `video-analysis/.venv` (Python 3.11); system Python lacks `ultralytics`.
- No change to `frame_stride` (1), `detection_imgsz` (960), the `analysisExecutor` single-thread pinning, or `read-timeout-ms` (300000).
- Commit trailers: `Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>` / `Claude-Session: https://claude.ai/code/session_01Cvu9QTCTbr7Cbvu6PxzPvh`.

## File Structure

| File | Task | Responsibility |
|---|---|---|
| `video-analysis/app/config.py` | 1 | new `motorcycle_min_confidence` setting |
| `video-analysis/app/detection.py` | 1 | per-class confidence floor in `_detect_frame` |
| `video-analysis/tests/test_detection.py` | 1 | floor is class-specific |
| `video-analysis/app/tracking_bearing.py` | 2 | `windowed_velocity` (distance-gated unit velocity of one track) |
| `video-analysis/app/pipeline.py` | 2 | clip dominant-flow aggregation + per-track `flow_alignment`; new response fields |
| `video-analysis/app/schemas.py` | 2 | `VehicleResult.flow_alignment`, `AnalyzeResponse.dominant_flow_degrees` / `flow_coherence` |
| `video-analysis/tests/test_flow_alignment.py` (new) | 2 | flow-alignment maths + edge cases |
| `video-analysis/tests/test_pipeline.py` | 2 | `flow_alignment` present end-to-end |
| `server/.../videoanalysis/dto/VideoAnalysisDtos.kt` | 3 | `flowAlignment` / `dominantFlowDegrees` / `flowCoherence` (nullable) |
| `server/.../videoanalysis/VideoAnalysisClientTest.kt` | 3 | absent fields → null |
| `server/.../reports/AnalysisProperties.kt` | 4, 5 | `approachMinFrames` 20; four `counterFlow*` props |
| `server/src/main/resources/application.yml` | 4, 5 | matching yml keys |
| `server/.../reports/ReportAnalysisJob.kt` | 4, 5 | widen `approachEligible`; `tryCounterFlowDetection` + `CounterFlowEvidenceBreakdown` |
| `server/.../reports/ReportAnalysisJobTest.kt` | 4, 5 | approach-widening + counter-flow cases |

---

### Task 1: Motorcycle detection-confidence floor

**Files:**
- Modify: `video-analysis/app/config.py`, `video-analysis/app/detection.py:115-125` (`_detect_frame`)
- Test: `video-analysis/tests/test_detection.py`

**Interfaces:**
- Produces: `Settings.motorcycle_min_confidence: float` (default `0.25`). No signature changes — `_detect_frame` still `(frame, frame_index) -> Iterator[TrackedFrame]`.

- [ ] **Step 1: Write the failing test**

In `video-analysis/tests/test_detection.py`, add (the file already patches `app.detection.YOLO` / `sv.Detections.from_ultralytics` — follow the pattern around its existing lines 150-215):

```python
@patch("app.detection.YOLO")
@patch("app.detection.cv2.VideoCapture")
def test_motorcycle_uses_lower_confidence_floor_than_cars(mock_video_capture, mock_yolo):
    """A motorcycle at 0.3 confidence is kept (head-on motorcycles read low); a car at
    0.3 is dropped; both are kept at 0.5. Motorcycle class id is 3, car is 2."""
    frame = np.zeros((100, 100, 3), dtype=np.uint8)
    mock_capture = MagicMock()
    mock_capture.read.side_effect = [(True, frame), (False, None)]
    mock_video_capture.return_value = mock_capture

    def fake_from_ultralytics(result):
        return sv.Detections(
            xyxy=np.array([[10, 10, 30, 30], [40, 40, 60, 60], [70, 70, 90, 90], [15, 15, 25, 25]], dtype=float),
            confidence=np.array([0.3, 0.3, 0.5, 0.5], dtype=np.float32),
            class_id=np.array([3, 2, 3, 2]),  # moto@0.3, car@0.3, moto@0.5, car@0.5
        )

    from app.detection import VehicleDetector

    with patch("app.detection.sv.Detections.from_ultralytics", side_effect=fake_from_ultralytics):
        detector = VehicleDetector(_fake_settings())
        detector._tracker.update_with_detections = lambda d: d
        detector._moto_tracker.update_with_detections = lambda d: d
        # tracker_id is None on the passthrough detections, so _tracked_frames_from yields
        # nothing; assert on what reached the trackers instead.
        seen = {}
        real_car = detector._tracker.update_with_detections
        real_moto = detector._moto_tracker.update_with_detections
        detector._tracker.update_with_detections = lambda d: seen.setdefault("car", d) or d
        detector._moto_tracker.update_with_detections = lambda d: seen.setdefault("moto", d) or d
        list(detector.track_video("x.mp4"))

    assert sorted(seen["moto"].confidence.tolist()) == [0.3, 0.5]   # both motos kept
    assert seen["car"].confidence.tolist() == [0.5]                 # only the 0.5 car
```

> If mutating the tracker methods proves awkward against the real `sv.ByteTrack`, instead assert on the yielded `TrackedFrame`s by giving `fake_from_ultralytics` detections that carry `tracker_id`s (see the existing `test_*` that does this around line 200) — the requirement to verify is "moto floor 0.25, car floor 0.4", by whatever assertion is cleanest against the real code.

- [ ] **Step 2: Run it, verify it fails**

Run: `cd video-analysis && .venv/Scripts/python.exe -m pytest tests/test_detection.py -k motorcycle_uses_lower -v`
Expected: FAIL — today the 0.3 motorcycle is dropped by the single `min_detection_confidence` (0.4) gate.

- [ ] **Step 3: Add the setting**

`video-analysis/app/config.py`, in `Settings`, right after `min_detection_confidence`:

```python
    min_detection_confidence: float = 0.4

    # A head-on / near-camera motorcycle is mostly occluded by its rider and reads at
    # ~0.25-0.45 confidence (a wrong-way rider approaching a stationary camera is the
    # motivating case - see the 2026-09-06 counter-flow spec). Car/bus/truck stay at
    # min_detection_confidence. The extra low-confidence motorcycle tracks this admits
    # are filtered downstream by ByteTrack track confirmation and the server's
    # MIN_TRACK_FRAMES / MIN_OBSERVATIONS gates, and sub-12-frame tracks skip OCR since
    # the 2026-09-05 change, so they are cheap.
    motorcycle_min_confidence: float = 0.25
```

- [ ] **Step 4: Apply the per-class floor**

`video-analysis/app/detection.py`, `_detect_frame`, replace the `confidence_mask` block:

```python
        vehicle_mask = np.isin(detections.class_id, list(VEHICLE_CLASS_IDS.keys()))
        is_motorcycle = detections.class_id == MOTORCYCLE_CLASS_ID
        floor = np.where(
            is_motorcycle,
            self._settings.motorcycle_min_confidence,
            self._settings.min_detection_confidence,
        )
        detections = detections[vehicle_mask & (detections.confidence >= floor)]
```

(`MOTORCYCLE_CLASS_ID` is already imported/defined in this file.)

- [ ] **Step 5: Run the test + the detection suite**

Run: `cd video-analysis && .venv/Scripts/python.exe -m pytest tests/test_detection.py -v`
Expected: all pass, including the new test and every pre-existing detection test.

- [ ] **Step 6: Commit**

```bash
cd video-analysis
git add app/config.py app/detection.py tests/test_detection.py
git commit -m "feat: lower the detection-confidence floor for motorcycles to 0.25

Head-on / near-camera motorcycles read at ~0.25-0.45 (the rider occludes the
bike). Car/bus/truck keep the 0.4 floor. See the 2026-09-06 counter-flow spec.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Cvu9QTCTbr7Cbvu6PxzPvh"
```

---

### Task 2: `flow_alignment` signal in video-analysis

**Files:**
- Modify: `video-analysis/app/tracking_bearing.py` (add `windowed_velocity`), `video-analysis/app/pipeline.py` (`analyze` + `_summarize_track`), `video-analysis/app/schemas.py`
- Test: `video-analysis/tests/test_flow_alignment.py` (new), `video-analysis/tests/test_pipeline.py`

**Interfaces:**
- Consumes: `MIN_DISPLACEMENT_PIXELS`, `bbox_diagonal` (existing, `tracking_bearing.py`).
- Produces:
  - `tracking_bearing.windowed_velocity(centroids, bboxes, min_displacement_pixels=MIN_DISPLACEMENT_PIXELS) -> tuple[tuple[float, float], float] | None` — unit `(vx, vy)` (pixel-space, y down) and the windowed displacement in pixels, computed over the frames whose bbox diagonal is in the smallest 40%; `None` when that window's net displacement `< min_displacement_pixels` or fewer than 4 frames.
  - `schemas.VehicleResult.flow_alignment: float | None = None`
  - `schemas.AnalyzeResponse.dominant_flow_degrees: float | None = None`, `schemas.AnalyzeResponse.flow_coherence: float = 0.0`
  - `pipeline` computes these; `_summarize_track` gains a `flow_alignment: float | None` parameter it just passes into `VehicleResult`.

- [ ] **Step 1: Write `windowed_velocity` failing test**

`video-analysis/tests/test_flow_alignment.py` (new):

```python
from __future__ import annotations

import math

import pytest

from app.tracking_bearing import windowed_velocity


def _line(n, x0, y0, dx, dy, w0=20.0, dw=0.0):
    """n frames moving (dx,dy)/frame from (x0,y0), bbox side w0 growing by dw/frame."""
    cents = [(x0 + dx * i, y0 + dy * i) for i in range(n)]
    bxs = [(0.0, 0.0, w0 + dw * i, w0 + dw * i) for i in range(n)]
    return cents, bxs


def test_windowed_velocity_is_unit_and_points_along_motion():
    cents, bxs = _line(20, 100.0, 100.0, 5.0, 0.0)  # moving +x
    v, disp = windowed_velocity(cents, bxs)
    assert math.isclose(math.hypot(*v), 1.0, abs_tol=1e-6)
    assert v[0] > 0.99 and abs(v[1]) < 0.01
    assert disp > 8.0


def test_windowed_velocity_uses_the_small_bbox_window_not_the_late_swerve():
    # Frames 0-11: approaching straight (tiny lateral move), small box.
    # Frames 12-19: large box, big rightward swerve.
    cents = [(100.0, 100.0 + 0.1 * i) for i in range(12)] + [(100.0 + 40.0 * (i - 11), 200.0) for i in range(12, 20)]
    bxs = [(0.0, 0.0, 20.0, 20.0)] * 12 + [(0.0, 0.0, 200.0, 200.0)] * 8
    v = windowed_velocity(cents, bxs)
    # Small-bbox window is frames 0-11: near-vertical motion, not the horizontal swerve.
    assert v is not None
    assert abs(v[0][0]) < 0.3          # not dominated by the swerve's +x


def test_windowed_velocity_none_when_windowed_displacement_below_floor():
    cents, bxs = _line(20, 100.0, 100.0, 0.0, 0.0)  # stationary
    assert windowed_velocity(cents, bxs) is None


def test_windowed_velocity_none_for_a_too_short_track():
    cents, bxs = _line(3, 100.0, 100.0, 5.0, 0.0)
    assert windowed_velocity(cents, bxs) is None
```

- [ ] **Step 2: Run, verify import-fail**

Run: `cd video-analysis && .venv/Scripts/python.exe -m pytest tests/test_flow_alignment.py -v`
Expected: FAIL — `ImportError: cannot import name 'windowed_velocity'`.

- [ ] **Step 3: Implement `windowed_velocity`**

`video-analysis/app/tracking_bearing.py`, after `resolve_bearing` (reuse `bbox_diagonal`, `DEFAULT_SAMPLE_SIZE`, `MIN_DISPLACEMENT_PIXELS`):

```python
def windowed_velocity(
    centroids: Sequence[Tuple[float, float]],
    bboxes: Sequence[Tuple[float, float, float, float]],
    min_displacement_pixels: float = MIN_DISPLACEMENT_PIXELS,
) -> Tuple[Tuple[float, float], float] | None:
    """Unit frame-space velocity (x, y; y increases downward) of a track over the frames
    where it is FARTHEST from the camera - the smallest 40% of its bbox diagonals - plus
    that window's net pixel displacement. None when the window has < 4 frames or its net
    displacement is below `min_displacement_pixels`.

    Gating on the smallest-bbox frames (rather than the first N) keeps perspective
    distortion minimal and, for a vehicle that swerves past a near camera, measures its
    approach direction rather than the swerve. A track only ever seen mid-pass still has
    a smallest-40% window - its own earliest, least-swept frames - which is the best
    estimate available. Used by pipeline.py to compute each vehicle's flow_alignment.
    """
    n = len(centroids)
    if n < 4 or len(bboxes) != n:
        return None

    order = sorted(range(n), key=lambda i: bbox_diagonal(bboxes[i]))
    k = max(4, math.ceil(n * 0.4))
    window = sorted(order[:k])  # indices of the smallest-diagonal frames, back in time order

    half = max(1, len(window) // 2)
    early = window[:half]
    late = window[-half:]
    ex = sum(centroids[i][0] for i in early) / len(early)
    ey = sum(centroids[i][1] for i in early) / len(early)
    lx = sum(centroids[i][0] for i in late) / len(late)
    ly = sum(centroids[i][1] for i in late) / len(late)

    dx, dy = lx - ex, ly - ey
    disp = math.hypot(dx, dy)
    if disp < min_displacement_pixels:
        return None
    return (dx / disp, dy / disp), disp
```

- [ ] **Step 4: Run the new test, verify green**

Run: `cd video-analysis && .venv/Scripts/python.exe -m pytest tests/test_flow_alignment.py -v`
Expected: 4 pass.

- [ ] **Step 5: Add the schema fields**

`video-analysis/app/schemas.py`:

```python
    scale_trend: str = "flat"
    scale_growth_fraction: float = 0.0
    # Frame-space velocity of this track (over its most-distant window) dotted against the
    # clip's dominant traffic-flow direction: +1 = with the flow, -1 = straight against it,
    # 0 = perpendicular / no clear motion. None when the track has too little motion to
    # have a direction. Perspective-immune (all relative, no compass) - the Kotlin server's
    # counter-flow detection path uses it. See the 2026-09-06 counter-flow spec.
    flow_alignment: float | None = None
```

and on `AnalyzeResponse` (after `frame_height`):

```python
    # The clip's own dominant traffic direction, degrees clockwise from frame-up, and how
    # tightly the moving vehicles agree on it (mean resultant length R, 0..1). None / 0.0
    # when fewer than two vehicles have a resolvable direction.
    dominant_flow_degrees: float | None = None
    flow_coherence: float = 0.0
```

- [ ] **Step 6: Write the pipeline failing test**

`video-analysis/tests/test_pipeline.py`, append (uses the existing `_make_frame` / `FakeDetector` / `_fake_settings` helpers):

```python
def test_analyze_reports_dominant_flow_and_per_vehicle_alignment():
    # 5 tracks drifting frame-right (x grows), 1 track drifting frame-left - the odd one
    # out is counter-flow. All get >= 12 frames and clear the displacement floor.
    frames = []
    for tid in range(5):
        for i in range(16):
            x = 100.0 + tid * 40 + 6.0 * i
            frames.append(_make_frame(track_id=tid, frame_index=i, bbox=(x, 200.0, x + 20, 220.0)))
    for i in range(16):
        x = 500.0 - 6.0 * i
        frames.append(_make_frame(track_id=99, frame_index=i, bbox=(x, 300.0, x + 20, 320.0)))

    pipeline = AnalysisPipeline(
        settings=_fake_settings(), detector=FakeDetector(frames), plate_reader=FakePlateReader()
    )
    resp = pipeline.analyze("unused.mp4")

    assert resp.dominant_flow_degrees is not None
    assert resp.flow_coherence > 0.8
    by_id = {v.track_id: v for v in resp.vehicles}
    assert by_id[99].flow_alignment is not None and by_id[99].flow_alignment < -0.8
    for tid in range(5):
        assert by_id[tid].flow_alignment is not None and by_id[tid].flow_alignment > 0.8


def test_analyze_flow_fields_are_null_when_nothing_moves_enough():
    frames = [_make_frame(track_id=1, frame_index=i, bbox=(10.0, 10.0, 20.0, 20.0)) for i in range(16)]
    pipeline = AnalysisPipeline(
        settings=_fake_settings(), detector=FakeDetector(frames), plate_reader=FakePlateReader()
    )
    resp = pipeline.analyze("unused.mp4")
    assert resp.dominant_flow_degrees is None
    assert resp.flow_coherence == 0.0
    assert resp.vehicles[0].flow_alignment is None
```

Run: `cd video-analysis && .venv/Scripts/python.exe -m pytest tests/test_pipeline.py -k "dominant_flow or flow_fields_are_null" -v` → FAIL (`AnalyzeResponse` has no such attrs / always None).

- [ ] **Step 7: Compute flow in `pipeline.analyze`**

`video-analysis/app/pipeline.py`. Import `windowed_velocity` from `app.tracking_bearing`. In `analyze`, after `vehicles = [...]` is built and before `return AnalyzeResponse(...)`, replace the return with:

```python
        per_track_velocity = {
            track_id: windowed_velocity(
                [f.centroid for f in sorted(frames, key=lambda f: f.frame_index)],
                [f.bbox for f in sorted(frames, key=lambda f: f.frame_index)],
                min_displacement_pixels=min_displacement_pixels,
            )
            for track_id, frames in tracks.items()
        }
        dominant_flow, coherence = _dominant_flow(per_track_velocity.values())

        vehicles = [
            v.model_copy(update={"flow_alignment": _alignment(per_track_velocity.get(v.track_id), dominant_flow)})
            for v in vehicles
        ]

        return AnalyzeResponse(
            vehicles=vehicles,
            frame_width=frame_width,
            frame_height=frame_height,
            dominant_flow_degrees=(
                None if dominant_flow is None
                else math.degrees(math.atan2(dominant_flow[0], -dominant_flow[1])) % 360.0
            ),
            flow_coherence=coherence,
        )
```

Add module-level helpers to `pipeline.py`:

```python
def _dominant_flow(velocities) -> tuple[tuple[float, float] | None, float]:
    """Displacement-weighted mean unit direction of all tracks that have one, and the
    mean resultant length R (0..1) of that weighting. (None, 0.0) below two directional
    tracks."""
    vs = [(uv, disp) for item in velocities if item is not None for uv, disp in (item,)]
    if len(vs) < 2:
        return None, 0.0
    sx = sum(uv[0] * disp for uv, disp in vs)
    sy = sum(uv[1] * disp for uv, disp in vs)
    total = sum(disp for _, disp in vs)
    r = math.hypot(sx, sy) / total if total else 0.0
    if r == 0.0:
        return None, 0.0
    return (sx / math.hypot(sx, sy), sy / math.hypot(sx, sy)), r


def _alignment(track_velocity, dominant_flow) -> float | None:
    if track_velocity is None or dominant_flow is None:
        return None
    uv, _ = track_velocity
    return max(-1.0, min(1.0, uv[0] * dominant_flow[0] + uv[1] * dominant_flow[1]))
```

> `VehicleResult.model_copy` is Pydantic v2 (the project pins pydantic 2.9) — `model_copy(update={...})` returns a new instance with the field set. If preferred, thread a `flow_alignment` parameter through `_summarize_track` instead and build the list once; either is fine as long as the field is populated.

- [ ] **Step 8: Run flow + pipeline + full video-analysis suite**

Run: `cd video-analysis && .venv/Scripts/python.exe -m pytest -v`
Expected: all pass — the two new pipeline tests, `test_flow_alignment.py`, and every pre-existing test (`test_head_on_approaching_track_gets_a_real_bearing...` etc. unaffected — `flow_alignment` is additive).

- [ ] **Step 9: Commit**

```bash
cd video-analysis
git add app/tracking_bearing.py app/pipeline.py app/schemas.py tests/test_flow_alignment.py tests/test_pipeline.py
git commit -m "feat: compute per-track flow_alignment vs the clip's dominant traffic flow

New VehicleResult.flow_alignment in [-1, 1] and AnalyzeResponse
dominant_flow_degrees / flow_coherence. Distance-gated frame-space velocity
dotted against the displacement-weighted mean flow - perspective-immune, no
compass. Additive; bearing/scale logic unchanged. See the 2026-09-06
counter-flow spec.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Cvu9QTCTbr7Cbvu6PxzPvh"
```

---

### Task 3: Server DTO — receive the new fields

**Files:**
- Modify: `server/src/main/kotlin/com/trafficwatch/server/videoanalysis/dto/VideoAnalysisDtos.kt`
- Test: `server/src/test/kotlin/com/trafficwatch/server/videoanalysis/VideoAnalysisClientTest.kt`

**Interfaces:**
- Produces: `VehicleAnalysisResult.flowAlignment: Double? = null`; `VideoAnalysisResponse.dominantFlowDegrees: Double? = null`, `VideoAnalysisResponse.flowCoherence: Double? = null`. Snake_case wire keys `flow_alignment` / `dominant_flow_degrees` / `flow_coherence` map automatically (global SNAKE_CASE Jackson).

- [ ] **Step 1: Write the failing test**

`VideoAnalysisClientTest.kt`, add:

```kotlin
    @Test
    fun `analyze reads flow_alignment and clip flow fields, tolerating their absence`() {
        mockServer.expect(requestTo("http://video-analysis.test/v1/analyze"))
            .andRespond(
                withSuccess(
                    """
                    {
                      "dominant_flow_degrees": 137.5,
                      "flow_coherence": 0.82,
                      "vehicles": [
                        { "track_id": 1, "vehicle_type": "motorcycle", "detection_confidence": 0.7,
                          "plate_text": null, "plate_confidence": null, "flow_alignment": -0.83 },
                        { "track_id": 2, "vehicle_type": "car", "detection_confidence": 0.9,
                          "plate_text": null, "plate_confidence": null }
                      ]
                    }
                    """.trimIndent(),
                    MediaType.APPLICATION_JSON,
                ),
            )

        val resp = client.analyze(fakeVideoPath, UUID.randomUUID(), zoomRatio = null)

        assertThat(resp.dominantFlowDegrees).isEqualTo(137.5)
        assertThat(resp.flowCoherence).isEqualTo(0.82)
        assertThat(resp.vehicles[0].flowAlignment).isEqualTo(-0.83)
        assertThat(resp.vehicles[1].flowAlignment).isNull()   // absent → null, no throw
        mockServer.verify()
    }
```

Run: `cd server && ./gradlew test --tests "*VideoAnalysisClientTest.analyze reads flow_alignment*"` → FAIL (unknown property / compile error).

- [ ] **Step 2: Add the fields**

`VideoAnalysisDtos.kt` — `VideoAnalysisResponse` after `frameHeight`:

```kotlin
    val frameHeight: Int? = null,
    // The clip's own dominant traffic direction (deg clockwise from frame-up) and how
    // tightly its moving vehicles agree (mean resultant length R). Null from a service
    // version predating the 2026-09-06 counter-flow signal, or when < 2 vehicles have a
    // resolvable direction.
    val dominantFlowDegrees: Double? = null,
    val flowCoherence: Double? = null,
```

`VehicleAnalysisResult` after `scaleGrowthFraction`:

```kotlin
    val scaleGrowthFraction: Double = 0.0,
    // This track's frame-space velocity (over its most-distant window) dotted against
    // dominantFlowDegrees: +1 with the flow, -1 straight against it, 0 perpendicular.
    // Null from an older service version or when the track had too little motion.
    // ReportAnalysisJob's counter-flow detection path consumes it.
    val flowAlignment: Double? = null,
```

- [ ] **Step 3: Run the test + the client suite**

Run: `cd server && ./gradlew test --tests "*VideoAnalysisClientTest"` → all pass.

- [ ] **Step 4: Commit**

```bash
git add server/src/main/kotlin/com/trafficwatch/server/videoanalysis/dto/VideoAnalysisDtos.kt server/src/test/kotlin/com/trafficwatch/server/videoanalysis/VideoAnalysisClientTest.kt
git commit -m "feat: parse flow_alignment / dominant_flow_degrees / flow_coherence from video-analysis

All nullable with null defaults - older service responses parse unchanged.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Cvu9QTCTbr7Cbvu6PxzPvh"
```

---

### Task 4: Widen the stationary-approach eligibility

**Files:**
- Modify: `server/.../reports/ReportAnalysisJob.kt` (`determineOutcome`, ~line 191-202), `server/.../reports/AnalysisProperties.kt:55`, `server/src/main/resources/application.yml` (`analysis.approach-min-frames`)
- Test: `server/src/test/kotlin/com/trafficwatch/server/reports/ReportAnalysisJobTest.kt`

**Interfaces:**
- Consumes: `DirectionResolution.Unknown` (any `reason`), the existing `tryStationaryApproachDetection` (unchanged).
- Produces: no new symbols; `approachMinFrames` default becomes `20`.

- [ ] **Step 1: Write the failing test**

In `ReportAnalysisJobTest.kt`, following the existing stationary-approach test style (search the file for `DIVIDED_CARRIAGEWAY` / `tryStationaryApproachDetection` / `approach` to find the fixtures and the mock setup for `streetDirectionResolver` / `clipFlowAnalyzer` / `videoAnalysisClient`), add:

```kotlin
    @Test
    fun `stationary-approach confirms on an AMBIGUOUS_NEAREST_STREET Unknown resolution`() {
        // Mirror an existing DIVIDED_CARRIAGEWAY stationary-approach confirm test, but with
        // reason = AMBIGUOUS_NEAREST_STREET. Camera stationary throughout; one strong
        // grower (scaleTrend "growing", growthFraction 1.5, 24 frames, detection 0.9);
        // >= 5 receding vehicles forming a consensus with memberCount >= 5 and R >= 0.9.
        // Expected: CONFIRMED with message "...approaching a stationary camera...",
        // directionEvidence.resolution_state == "UNKNOWN_AMBIGUOUS_NEAREST_STREET".
    }

    @Test
    fun `stationary-approach still does NOT run for a NotFound resolution`() {
        // Same vehicle fixture as above but streetDirectionResolver returns
        // DirectionResolution.NotFound. Expected: stays REJECTED (approachEligible false).
    }
```

Fill both in concretely against the existing fixtures. Run the first → FAIL (today `approachEligible` is false for `AMBIGUOUS_NEAREST_STREET`).

- [ ] **Step 2: Widen `approachEligible`**

`ReportAnalysisJob.kt`, `determineOutcome`:

```kotlin
            val approachEligible = when (resolution) {
                is DirectionResolution.OneWay -> true
                is DirectionResolution.Unknown -> true
                else -> false
            }
```

Update the comment above it to say all `Unknown` reasons are eligible and the corroboration gate inside `tryStationaryApproachDetection` is the safeguard; `NotFound` / `LookupFailed` remain ineligible (no confidence we are on a mapped road).

The `corroboration = (resolution as? DirectionResolution.Unknown)?.let { corroborationConsensus }` line already generalises correctly (it keyed on `Unknown`, not the reason).

- [ ] **Step 3: `approachMinFrames` 30 → 20**

`AnalysisProperties.kt:55`: `var approachMinFrames: Int = 20,` — and update its comment (the "Calibrated 2026-08-30 ... real violators grew 0.93-2.24" note stays; add that the frame floor was relaxed 30→20 on 2026-09-06 because the corroboration gate, not the frame count, is the safeguard).

`application.yml`: `approach-min-frames: 20`

- [ ] **Step 4: Run the approach tests + full server suite**

Run: `cd server && ./gradlew test`
Expected: BUILD SUCCESSFUL. New tests pass.

**One known-affected pre-existing test:** `approach path does not run when the street is not resolved to a one-way` (`ReportAnalysisJobTest.kt` ~line 1216) uses `Unknown(NO_ONEWAY_TAG)` + 4 shrinking + 1 strong grower + stationary camera and asserts REJECTED. After the widening it is *eligible*, but it still REJECTs because it has only 4 receding vehicles (< `approachCorroborationMinMembers` 5), so the corroboration gate returns null. The assertion stays valid — but its comment ("DIVIDED_CARRIAGEWAY is now the one Unknown reason that reaches the approach path … every other Unknown reason … is still excluded outright") is now wrong. Rewrite the comment to say the path is reached for any Unknown reason and it is the corroboration gate (4 < 5 members) that rejects here; consider renaming the test to `...is REJECTED when the receding consensus is too small on a non-divided Unknown street`.

**Also check** any other pre-existing test with a non-`DIVIDED_CARRIAGEWAY` Unknown resolution + a stationary-approach-shaped fixture: if one now flips to CONFIRMED because it *does* satisfy the corroboration gate, that is the widening working as intended — update its expectation and note it in the report. A `NotFound` / `LookupFailed` test that flips is a real bug — fix the eligibility, not the test.

- [ ] **Step 5: Commit**

```bash
git add server/src/main/kotlin/com/trafficwatch/server/reports/ReportAnalysisJob.kt server/src/main/kotlin/com/trafficwatch/server/reports/AnalysisProperties.kt server/src/main/resources/application.yml server/src/test/kotlin/com/trafficwatch/server/reports/ReportAnalysisJobTest.kt
git commit -m "feat: run stationary-approach detection for all Unknown OSM resolutions

Was gated to DIVIDED_CARRIAGEWAY; the corroboration gate (stationary camera,
lone grower, >=5-member R>=0.9 receding consensus) is the real safeguard, not
the reason restriction. approach-min-frames 30 -> 20 for the same reason.
NotFound / LookupFailed stay ineligible.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Cvu9QTCTbr7Cbvu6PxzPvh"
```

---

### Task 5: Counter-flow confirm path

**Files:**
- Modify: `server/.../reports/AnalysisProperties.kt`, `server/src/main/resources/application.yml`, `server/.../reports/ReportAnalysisJob.kt` (new `tryCounterFlowDetection` + `CounterFlowEvidenceBreakdown` + wire into `determineOutcome`)
- Test: `server/src/test/kotlin/com/trafficwatch/server/reports/ReportAnalysisJobTest.kt`

**Interfaces:**
- Consumes: `VideoAnalysisResponse.flowCoherence`, `VehicleAnalysisResult.flowAlignment` / `trackFrameCount` / `detectionConfidence` (Task 3); `ClipFlowAnalyzer.MIN_TRACK_FRAMES` (== 9); `analysisProperties.confirmationThreshold`.
- Produces: `AnalysisProperties.counterFlowMinCoherence: Double = 0.75`, `counterFlowMaxAlignment: Double = -0.6`, `counterFlowMinFrames: Int = 12`, `counterFlowMinWithFlow: Int = 5`; `ReportAnalysisJob.tryCounterFlowDetection(...)` (private, returns `AnalysisOutcome?`); `CounterFlowEvidenceBreakdown` (internal data class).

- [ ] **Step 1: Write the failing tests**

`ReportAnalysisJobTest.kt` — five cases (fill in against the existing `applyOutcome` / mock fixtures; the counter-flow candidate needs `flowAlignment`, `trackFrameCount`, `detectionConfidence` set on the `VehicleAnalysisResult`s, and `flowCoherence` on the `VideoAnalysisResponse`):

```kotlin
    // NOTE: these are server tests - flowCoherence / flowAlignment are set directly on
    // the mocked VideoAnalysisResponse / VehicleAnalysisResult, NOT computed. Use the
    // literal values below. Gate: counterFlowMinCoherence 0.6, counterFlowMaxAlignment
    // -0.6, counterFlowMinFrames 12, counterFlowMinWithFlow 5.

    @Test
    fun `counter-flow confirms a lone vehicle moving against a coherent forward stream`() {
        // resolution = Unknown(AMBIGUOUS_NEAREST_STREET); flowCoherence = 0.85.
        // 1 vehicle: flowAlignment -0.85, trackFrameCount 14, detectionConfidence 0.8.
        // 6 vehicles: flowAlignment 0.9, trackFrameCount 20, detectionConfidence 0.7.
        // Main path REJECTS (no OSM). Expected: CONFIRMED, message contains
        // "against traffic"; directionEvidence has method "counter_flow",
        // counter_flow_alignment -0.85, forward_stream_count 6.
    }

    @Test
    fun `counter-flow does not fire when flow_coherence is weak`() {
        // Same as above but flowCoherence = 0.5 (below counterFlowMinCoherence 0.6).
        // Expected: stays REJECTED.
    }

    @Test
    fun `counter-flow does not fire with two counter-flowing vehicles`() {
        // Two vehicles at flowAlignment -0.7 (both >= 12 frames, det >= 0.5).
        // Expected: stays REJECTED (not a lone anomaly).
    }

    @Test
    fun `counter-flow does not fire without a populated forward stream`() {
        // Lone counter-flower is fine, but only 3 vehicles with flowAlignment >= 0.5.
        // Expected: stays REJECTED.
    }

    @Test
    fun `counter-flow does not fire for a short counter-flow fragment`() {
        // Lone counter-flower at flowAlignment -0.9 but trackFrameCount 9.
        // Expected: stays REJECTED.
    }

    @Test
    fun `counter-flow does not run for NotFound and never downgrades a CONFIRMED`() {
        // (a) NotFound resolution + the confirming fixture from case 1 -> stays REJECTED.
        // (b) a fixture the main path already CONFIRMS -> unchanged (tryCounterFlowDetection
        //     only runs when outcome.status == REJECTED).
    }
```

Run → FAIL (`tryCounterFlowDetection` doesn't exist).

- [ ] **Step 2: Add the properties**

`AnalysisProperties.kt`, after the `approachCorroboration*` props:

```kotlin
    // Counter-flow detection (2026-09-06 spec): a lone, well-detected vehicle whose
    // frame-space flow_alignment is strongly negative against a large, coherent forward
    // stream is driving the wrong way - a signal that needs no compass, OSM bearing, or
    // stationary camera. Fires only for OneWay / any Unknown resolution.
    // counterFlowMinCoherence matches consensus-min-resultant-length (0.6): flow_coherence
    // is R over ALL directional tracks, so an N-forward / 1-counter split is R=(N-1)/(N+1).
    var counterFlowMinCoherence: Double = 0.6,
    var counterFlowMaxAlignment: Double = -0.6,
    var counterFlowMinFrames: Int = 12,
    var counterFlowMinWithFlow: Int = 5,
```

`application.yml` under `analysis:`:

```yaml
    counter-flow-min-coherence: 0.6
    counter-flow-max-alignment: -0.6
    counter-flow-min-frames: 12
    counter-flow-min-with-flow: 5
```

- [ ] **Step 3: Implement `tryCounterFlowDetection` + breakdown**

`ReportAnalysisJob.kt`:

```kotlin
    /**
     * Additive fallback (2026-09-06 counter-flow spec): a vehicle whose frame-space
     * velocity opposes the clip's own dominant traffic flow is driving the wrong way -
     * perspective-immune, needs no compass / OSM legal bearing / stationary camera. Fires
     * only when the clip has a large (>= counterFlowMinWithFlow), coherent
     * (flowCoherence >= counterFlowMinCoherence) forward stream AND exactly one vehicle,
     * tracked >= counterFlowMinFrames and detected >= confirmationThreshold, with
     * flowAlignment <= counterFlowMaxAlignment. Only upgrades REJECTED -> CONFIRMED.
     */
    private fun tryCounterFlowDetection(
        report: Report,
        analysis: VideoAnalysisResponse,
        streetName: String?,
        resolution: DirectionResolution,
    ): AnalysisOutcome? {
        val eligible = resolution is DirectionResolution.OneWay || resolution is DirectionResolution.Unknown
        if (!eligible) return null

        val coherence = analysis.flowCoherence ?: return null
        if (coherence < analysisProperties.counterFlowMinCoherence) return null

        val forwardStream = analysis.vehicles.count {
            (it.flowAlignment ?: return@count false) >= 0.5 &&
                (it.trackFrameCount ?: 0) >= ClipFlowAnalyzer.MIN_TRACK_FRAMES
        }
        if (forwardStream < analysisProperties.counterFlowMinWithFlow) return null

        val candidates = analysis.vehicles.filter {
            (it.flowAlignment ?: 1.0) <= analysisProperties.counterFlowMaxAlignment &&
                (it.trackFrameCount ?: 0) >= analysisProperties.counterFlowMinFrames &&
                it.detectionConfidence >= analysisProperties.confirmationThreshold
        }
        if (candidates.size != 1) return null
        val best = candidates.single()

        return AnalysisOutcome(
            status = ReportStatus.CONFIRMED,
            licensePlate = best.plateText,
            confidence = best.plateConfidence?.let { BigDecimal.valueOf(it) },
            message = "Wrong-way vehicle moving against traffic on ${streetName ?: "this street"}",
            streetName = streetName,
            wrongWayConfidence = BigDecimal.valueOf(best.detectionConfidence),
            wrongWayFramePath = annotateAndStoreFrame(
                best, requireNotNull(report.id) { "Report must have a generated id before analysis" },
            ),
            directionEvidenceJson = counterFlowBreakdownJson(best, forwardStream, coherence, resolution),
        )
    }

    private fun counterFlowBreakdownJson(
        best: VehicleAnalysisResult,
        forwardStreamCount: Int,
        flowCoherence: Double,
        resolution: DirectionResolution,
    ): String? = try {
        objectMapper.writeValueAsString(
            CounterFlowEvidenceBreakdown(
                resolutionState = when (resolution) {
                    is DirectionResolution.Unknown -> "UNKNOWN_${resolution.reason.name}"
                    is DirectionResolution.OneWay -> "ONE_WAY"
                    else -> "OTHER"
                },
                counterFlowAlignment = best.flowAlignment ?: 0.0,
                flowCoherence = flowCoherence,
                forwardStreamCount = forwardStreamCount,
                trackFrames = best.trackFrameCount ?: 0,
                detectionConfidence = best.detectionConfidence,
                confirmationThreshold = analysisProperties.confirmationThreshold,
            ),
        )
    } catch (ex: Exception) {
        logger.warn("ReportAnalysisJob: failed to serialize counter-flow evidence breakdown", ex)
        null
    }
```

Add near `ApproachEvidenceBreakdown`:

```kotlin
/**
 * Serialized (snake_case) into reports.direction_evidence when a report is confirmed by
 * the counter-flow path. `method` is the discriminator (see EvidenceBreakdown /
 * ApproachEvidenceBreakdown).
 */
internal data class CounterFlowEvidenceBreakdown(
    val method: String = "counter_flow",
    val resolutionState: String,
    val counterFlowAlignment: Double,
    val flowCoherence: Double,
    val forwardStreamCount: Int,
    val trackFrames: Int,
    val detectionConfidence: Double,
    val confirmationThreshold: Double,
)
```

- [ ] **Step 4: Wire it into `determineOutcome`**

In the `if (outcome.status == ReportStatus.REJECTED) { ... }` block, after the existing `if (approachEligible) { tryStationaryApproachDetection(...)?.let { return it } }`:

```kotlin
            tryCounterFlowDetection(report, analysis, streetName, resolution)?.let { return it }
```

(Its own eligibility check handles `OneWay` / `Unknown` vs the rest; it is deliberately outside the `approachEligible` block since it has no stationary-camera requirement.)

- [ ] **Step 5: Run the counter-flow tests + full server suite**

Run: `cd server && ./gradlew test`
Expected: BUILD SUCCESSFUL; the six counter-flow tests pass; every pre-existing test still passes (counter-flow only runs on an already-REJECTED outcome and only for `OneWay`/`Unknown`, so CONFIRMED paths and `TwoWay`/`NotFound` rejects are untouched — verify no pre-existing REJECTED-path test with `flowAlignment` unset now flips, which it cannot since `flowAlignment ?: 1.0` makes an unset candidate non-counter-flowing).

- [ ] **Step 6: Commit**

```bash
git add server/src/main/kotlin/com/trafficwatch/server/reports/AnalysisProperties.kt server/src/main/resources/application.yml server/src/main/kotlin/com/trafficwatch/server/reports/ReportAnalysisJob.kt server/src/test/kotlin/com/trafficwatch/server/reports/ReportAnalysisJobTest.kt
git commit -m "feat: counter-flow wrong-way confirm path

A lone, well-detected vehicle with flow_alignment <= -0.6 against a coherent
forward stream of >= 5 is confirmed wrong-way - no compass, OSM bearing, or
stationary camera needed. Additive REJECTED -> CONFIRMED fallback, third
alongside the bearing and stationary-approach paths. See the 2026-09-06 spec.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Cvu9QTCTbr7Cbvu6PxzPvh"
```

---

## Manual / production verification (after merge + deploy — not part of any task's automated tests)

Deploy order: `video-analysis` rebuild (Tasks 1-2), then `server` rebuild (Tasks 3-5). Then, via the throwaway re-submission flow (`docs` / [[prod-vps-access]]):

- **`14872a1a`** → expect CONFIRMED via `method: "counter_flow"`. Record actual `counter_flow_alignment` / `flow_coherence` / `forward_stream_count` in the backlog entry.
- **`71f78`, `50bcc6`** (moving-camera cohesion cases) → record whether counter-flow now confirms; either outcome is informative.
- **FP watch:** re-run `649b9a` (known false positive) and 2-3 recent production CONFIRMEDs → none should flip to a counter-flow CONFIRMED. If `649b9a` does, raise `counter-flow-max-alignment` toward `-0.75` or `counter-flow-min-coherence` toward `0.85` and re-verify before considering the change settled.
- **Perf:** one busy re-run's wall-clock stays under the 300 s budget (Layer 1's extra tracks are absorbed by serialised analysis + the sub-12-frame OCR skip, but confirm).
- Update `docs/improvements-backlog.md`: mark the `[HIGH]` "motorcycle riding straight at a stationary camera" entry shipped, with the `14872a1a` result and the final tuned `counter-flow-*` values.

## Self-Review

- **Spec coverage:** Layer 1 → Task 1. Layer 3a (`flow_alignment`, `windowed_velocity`, dominant flow) → Task 2. DTO plumbing → Task 3. Layer 2 (widen `approachEligible`, `approachMinFrames` 30→20) → Task 4. Layer 3b (`tryCounterFlowDetection`, breakdown, wiring, props) → Task 5. Spec's testing section → distributed across each task's steps + the Manual Verification block. Non-goals require no task.
- **Type consistency:** `windowed_velocity -> tuple[tuple[float,float], float] | None` produced in Task 2 Step 3, consumed in Task 2 Step 7 (`per_track_velocity.values()` → `_dominant_flow`, `_alignment`). `flow_alignment: float | None` (schemas, Task 2) ↔ `flowAlignment: Double? = null` (DTO, Task 3) ↔ `it.flowAlignment` reads in Task 5. `flowCoherence: Double? = null` (Task 3) ↔ `analysis.flowCoherence ?: return null` (Task 5). `ClipFlowAnalyzer.MIN_TRACK_FRAMES` referenced in Task 5 — confirm it is `internal`/accessible from `ReportAnalysisJob` (same package); if `private`, use the literal `9` with a `// == ClipFlowAnalyzer.MIN_TRACK_FRAMES` comment as the existing approach code at line 257 does.
- **Placeholder scan:** Task 4 and Task 5 test steps describe fixtures rather than pasting full Kotlin test bodies, because the exact mock setup (`streetDirectionResolver` / `clipFlowAnalyzer` / `videoAnalysisClient` stubbing) must match `ReportAnalysisJobTest`'s existing style, which the implementer reads. Each case's inputs and expected outcome are fully specified. This is the one deliberate deviation from "paste the code".
