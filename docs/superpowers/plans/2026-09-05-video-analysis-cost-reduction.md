# Video-Analysis Cost Reduction (Skip OCR/Frame-Encoding on Unqualifiable Tracks) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop running EasyOCR plate reads and JPEG/base64 frame encoding for any detected track with fewer than `MIN_OBSERVATIONS` (12) frames, since such a track can never be scored, confirmed, or selected by the server regardless of what those calls would have returned.

**Architecture:** One-file change to `video-analysis/app/pipeline.py`'s `AnalysisPipeline._summarize_track`: gate the existing `self._read_best_plate(...)` and `encode_frame_to_base64_jpeg(...)` calls behind `len(frames_sorted) >= MIN_OBSERVATIONS` (imported from `app.tracking_bearing`, the module that already defines and enforces this exact threshold for `resolve_bearing`/`scale_trend`), substituting `None`/`None`/`None` for the three fields those calls populate when the track is too short. No schema, API, or server-side change — all three fields are already `Optional` end-to-end.

**Tech Stack:** Python 3.11, pytest, existing `FakeDetector`/`FakePlateReader`/`_make_frame`/`_fake_settings` test fixtures in `video-analysis/tests/test_pipeline.py`.

**Spec:** `docs/superpowers/specs/2026-09-05-video-analysis-cost-reduction-design.md`

## Global Constraints

- The filter is frame-count only (`< MIN_OBSERVATIONS`) — never gate on "bearing is None" or "no scale trend", since a long-but-stationary track (`>= MIN_OBSERVATIONS` frames, no resolvable bearing) may still matter to the approach-path's scale-trend signal at `>= approachMinFrames (30)` and must keep its OCR/frame data.
- `bounding_box` is never skipped, for tracks of any length — it's cheap and already computed from in-memory data.
- No change to `frame_stride`, `imgsz`, `OCR_CROPS_PER_TRACK`, `plate_confidence_floor`, or the 180s server-side read timeout.
- No `VehicleResult`/`schemas.py` change and no Kotlin-side change — `plate_text`, `plate_confidence`, `frame_jpeg_base64` are already `Optional[...] = None` on `VehicleResult` and `String? = null` on the Kotlin `VehicleAnalysisResult`; verify, don't touch.
- Import `MIN_OBSERVATIONS` from `app.tracking_bearing` — do not redefine or hardcode `12` in `pipeline.py`.

---

### Task 1: Skip OCR and frame-encoding for tracks below `MIN_OBSERVATIONS`

**Files:**
- Modify: `video-analysis/app/pipeline.py:11-18` (import), `video-analysis/app/pipeline.py:114-164` (`_summarize_track`)
- Test: `video-analysis/tests/test_pipeline.py`

**Interfaces:**
- Consumes: `MIN_OBSERVATIONS` (int, value `12`) from `app.tracking_bearing` — already imported elsewhere in the codebase from that module, just add it to `pipeline.py`'s existing `from app.tracking_bearing import (...)` block.
- Produces: no new public interface. `AnalysisPipeline._summarize_track`'s return type (`VehicleResult`) and `AnalysisPipeline.analyze`'s signature are unchanged — this task only changes internal behavior for short tracks.

- [ ] **Step 1: Write the failing tests**

Add these test doubles and test cases to `video-analysis/tests/test_pipeline.py`. Add the import `import app.pipeline as pipeline_module` near the top (alongside the existing `from app.pipeline import AnalysisPipeline`), and add a `SpyPlateReader` class near the existing `FakePlateReader`:

```python
class SpyPlateReader:
    """Like FakePlateReader, but counts calls so a test can assert read_plate was never
    invoked for a track this change is supposed to skip entirely."""

    def __init__(self):
        self.call_count = 0

    def read_plate(self, crop):
        self.call_count += 1
        return "ABC-123", 0.75
```

Then add these three test functions at the end of the file:

```python
def test_summarize_track_skips_ocr_and_frame_encoding_below_min_observations(monkeypatch):
    # MIN_OBSERVATIONS is 12 - one frame short must skip both OCR and frame-encoding
    # entirely, since such a track can never clear resolve_bearing's own MIN_OBSERVATIONS
    # gate and therefore can never be scored or selected by the server.
    frames = [
        _make_frame(track_id=1, frame_index=i, bbox=(10.0, 10.0, 20.0, 20.0))
        for i in range(11)
    ]
    spy_plate_reader = SpyPlateReader()

    def _fail_if_called(frame):
        raise AssertionError("encode_frame_to_base64_jpeg must not be called for a track below MIN_OBSERVATIONS")

    monkeypatch.setattr(pipeline_module, "encode_frame_to_base64_jpeg", _fail_if_called)

    pipeline = AnalysisPipeline(
        settings=_fake_settings(), detector=FakeDetector(frames), plate_reader=spy_plate_reader
    )

    response = pipeline.analyze("unused.mp4")

    vehicle = response.vehicles[0]
    assert spy_plate_reader.call_count == 0
    assert vehicle.plate_text is None
    assert vehicle.plate_confidence is None
    assert vehicle.frame_jpeg_base64 is None
    # Cheap fields still populated regardless of track length.
    assert vehicle.bounding_box is not None
    assert vehicle.track_frame_count == 11


def test_summarize_track_runs_ocr_and_frame_encoding_at_exactly_min_observations():
    # Boundary case: exactly MIN_OBSERVATIONS (12) frames must still run OCR/encoding -
    # this is an off-by-one check against the >= vs > choice in the gate.
    frames = [
        _make_frame(track_id=1, frame_index=i, bbox=(10.0, 10.0, 20.0, 20.0))
        for i in range(12)
    ]
    spy_plate_reader = SpyPlateReader()

    pipeline = AnalysisPipeline(
        settings=_fake_settings(), detector=FakeDetector(frames), plate_reader=spy_plate_reader
    )

    response = pipeline.analyze("unused.mp4")

    vehicle = response.vehicles[0]
    assert spy_plate_reader.call_count > 0
    assert vehicle.plate_text == "ABC-123"
    assert vehicle.plate_confidence == 0.75
    assert vehicle.frame_jpeg_base64 is not None
    assert len(vehicle.frame_jpeg_base64) > 0


def test_summarize_track_runs_ocr_and_frame_encoding_for_long_track_with_no_resolvable_bearing():
    # A track that clears MIN_OBSERVATIONS but has neither lateral motion nor a bbox-scale
    # change (a genuinely stationary detection) gets bearing_degrees=None from
    # resolve_bearing - but the filter in this plan is frame-count only, NOT "has a
    # bearing", so OCR/encoding must still run. A long stationary track can still matter to
    # the server's approach-path scale-trend signal at >= approachMinFrames (30); this test
    # only needs to clear MIN_OBSERVATIONS (12) to prove the frame-count gate alone lets it
    # through.
    frames = [
        _make_frame(track_id=1, frame_index=i, bbox=(10.0, 10.0, 20.0, 20.0))
        for i in range(15)
    ]
    spy_plate_reader = SpyPlateReader()

    pipeline = AnalysisPipeline(
        settings=_fake_settings(), detector=FakeDetector(frames), plate_reader=spy_plate_reader
    )

    response = pipeline.analyze("unused.mp4")

    vehicle = response.vehicles[0]
    assert vehicle.bearing_degrees is None
    assert spy_plate_reader.call_count > 0
    assert vehicle.plate_text == "ABC-123"
    assert vehicle.frame_jpeg_base64 is not None
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd video-analysis && python -m pytest tests/test_pipeline.py -v -k "skips_ocr_and_frame_encoding_below_min_observations or runs_ocr_and_frame_encoding_at_exactly_min_observations or runs_ocr_and_frame_encoding_for_long_track_with_no_resolvable_bearing"`

Expected: the first new test (`..._below_min_observations`) FAILS — today's code calls OCR/encoding unconditionally, so `spy_plate_reader.call_count == 0` and `vehicle.plate_text is None` both fail. The other two new tests PASS already (today's code already runs OCR/encoding for every track) — that's expected and fine; they exist to guard the boundary and the "no bearing but long enough" case against a future regression, not to prove today's code broken.

- [ ] **Step 3: Implement the gate**

In `video-analysis/app/pipeline.py`, add `MIN_OBSERVATIONS` to the existing import block:

```python
from app.tracking_bearing import (
    MIN_DISPLACEMENT_PIXELS,
    MIN_OBSERVATIONS,
    bbox_diagonal,
    compute_displacement_pixels,
    compute_track_midpoint_ms,
    resolve_bearing,
    scale_trend,
)
```

Replace the body of `_summarize_track` from the `plate_text, plate_confidence = self._read_best_plate(frames_sorted)` line through the `frame_jpeg_base64 = encode_frame_to_base64_jpeg(representative_frame.frame)` line with:

```python
        representative_frame = max(frames_sorted, key=_bbox_area)
        x1, y1, x2, y2 = representative_frame.bbox
        bounding_box = BoundingBox(x1=x1, y1=y1, x2=x2, y2=y2)

        # A track below MIN_OBSERVATIONS frames has bearing_degrees == None (resolve_bearing
        # requires >= MIN_OBSERVATIONS observations; scale_trend does too) and can therefore
        # never qualify as a FlowVehicle on the server - ClipFlowAnalyzer.qualifyVehicles's
        # first line requires a non-null bearing. It also can't be an approach-path grower
        # (that path additionally requires trackFrameCount >= approachMinFrames(30), well
        # above MIN_OBSERVATIONS). Such a track can never be the server's `best` candidate,
        # so its plate and frame are never read - skip the two most expensive per-track
        # operations rather than compute and discard them. See the 2026-09-05
        # video-analysis-cost-reduction design.
        if len(frames_sorted) >= MIN_OBSERVATIONS:
            plate_text, plate_confidence = self._read_best_plate(frames_sorted)
            frame_jpeg_base64 = encode_frame_to_base64_jpeg(representative_frame.frame)
        else:
            plate_text, plate_confidence = None, None
            frame_jpeg_base64 = None
```

Note this also moves `representative_frame`/`x1, y1, x2, y2`/`bounding_box` computation to sit right before the new gate (it was previously interleaved between the OCR call and the encoding call) — functionally identical, since neither of those three lines depends on OCR having run, and `bounding_box` must be computed regardless of track length per the Global Constraints.

The full resulting method body (for reference — reconcile against the actual current file rather than pasting over it blind, since line numbers may have shifted):

```python
    def _summarize_track(
        self,
        track_id: int,
        frames: list["TrackedFrame"],
        corridor_id: int,
        cohesion: float,
        fps: float | None,
        min_displacement_pixels: float,
    ) -> VehicleResult:
        frames_sorted = sorted(frames, key=lambda f: f.frame_index)
        centroids = [f.centroid for f in frames_sorted]
        bboxes = [f.bbox for f in frames_sorted]
        bearing_result = resolve_bearing(centroids, bboxes, min_displacement_pixels=min_displacement_pixels)
        bearing = bearing_result[0] if bearing_result else None
        bearing_source = bearing_result[1] if bearing_result else None
        track_midpoint_ms = compute_track_midpoint_ms(
            frames_sorted[0].frame_index, frames_sorted[-1].frame_index, fps
        )

        displacement = compute_displacement_pixels(centroids, bboxes, min_displacement_pixels=min_displacement_pixels)

        trend, growth_fraction = scale_trend(bboxes)

        vehicle_type = frames_sorted[0].vehicle_type
        detection_confidence = max(f.confidence for f in frames_sorted)

        representative_frame = max(frames_sorted, key=_bbox_area)
        x1, y1, x2, y2 = representative_frame.bbox
        bounding_box = BoundingBox(x1=x1, y1=y1, x2=x2, y2=y2)

        # A track below MIN_OBSERVATIONS frames has bearing_degrees == None (resolve_bearing
        # requires >= MIN_OBSERVATIONS observations; scale_trend does too) and can therefore
        # never qualify as a FlowVehicle on the server - ClipFlowAnalyzer.qualifyVehicles's
        # first line requires a non-null bearing. It also can't be an approach-path grower
        # (that path additionally requires trackFrameCount >= approachMinFrames(30), well
        # above MIN_OBSERVATIONS). Such a track can never be the server's `best` candidate,
        # so its plate and frame are never read - skip the two most expensive per-track
        # operations rather than compute and discard them. See the 2026-09-05
        # video-analysis-cost-reduction design.
        if len(frames_sorted) >= MIN_OBSERVATIONS:
            plate_text, plate_confidence = self._read_best_plate(frames_sorted)
            frame_jpeg_base64 = encode_frame_to_base64_jpeg(representative_frame.frame)
        else:
            plate_text, plate_confidence = None, None
            frame_jpeg_base64 = None

        return VehicleResult(
            track_id=track_id,
            vehicle_type=vehicle_type,
            detection_confidence=detection_confidence,
            bearing_degrees=bearing,
            bearing_source=bearing_source,
            plate_text=plate_text,
            plate_confidence=plate_confidence,
            bounding_box=bounding_box,
            frame_jpeg_base64=frame_jpeg_base64,
            corridor_id=corridor_id,
            corridor_cohesion=cohesion,
            track_frame_count=len(frames_sorted),
            displacement_pixels=displacement,
            track_midpoint_ms=track_midpoint_ms,
            scale_trend=trend,
            scale_growth_fraction=growth_fraction,
        )
```

- [ ] **Step 4: Run the new tests to verify they pass**

Run: `cd video-analysis && python -m pytest tests/test_pipeline.py -v -k "skips_ocr_and_frame_encoding_below_min_observations or runs_ocr_and_frame_encoding_at_exactly_min_observations or runs_ocr_and_frame_encoding_for_long_track_with_no_resolvable_bearing"`

Expected: all three PASS.

- [ ] **Step 5: Run the full video-analysis test suite**

Run: `cd video-analysis && python -m pytest -v`

Expected: all tests PASS, including every pre-existing test in `test_pipeline.py` — in particular `test_summarize_track_attaches_bounding_box_and_frame_from_the_largest_bbox_frame` (2 frames, below `MIN_OBSERVATIONS`: re-read this test before treating a failure as a regression — it currently asserts `vehicle.frame_jpeg_base64 is not None`, which this change will flip to `None` since 2 frames is below the new gate. If it fails for that reason, update its assertions in this same task to match the new, correct behavior for a short track (`frame_jpeg_base64 is None`, `plate_text`/`plate_confidence` unaffected since it doesn't assert on them) — do not treat this as a hand-off to a later task; there is no later task in this plan, and leaving a known-wrong assertion in place would ship a red suite.

- [ ] **Step 6: Commit**

```bash
cd video-analysis
git add app/pipeline.py tests/test_pipeline.py
git commit -m "perf: skip OCR and frame-encoding for tracks below MIN_OBSERVATIONS

A track with fewer than MIN_OBSERVATIONS (12) frames already gets
bearing_degrees=None from resolve_bearing, and the server's
ClipFlowAnalyzer.qualifyVehicles requires a non-null bearing to ever
score a vehicle - such a track can never be confirmed or selected.
Every EasyOCR call and JPEG encode spent on one today was guaranteed
wasted work. Skip both for tracks below the threshold; bounding_box
stays populated regardless since it's free.

See docs/superpowers/specs/2026-09-05-video-analysis-cost-reduction-design.md

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Cvu9QTCTbr7Cbvu6PxzPvh"
```

---

## Manual / production verification (after merge + deploy — not part of Task 1's automated tests)

- Re-run reports `24908` and `71f78` (the two that have hit the 180s video-analysis timeout) post-deploy via the diagnostic re-submission script (see [[prod-vps-access]]) and note wall-clock time to completion.
- Given the resource-contention root cause documented in the spec, treat this as a distribution check, not a single pass/fail: the VPS's memory pressure means timing stays somewhat noisy regardless of this fix. Don't over-read one clean or one still-slow run.
- No re-analysis of historical CONFIRMED/REJECTED reports is needed or intended — this change only affects tracks that were already incapable of influencing an outcome.
