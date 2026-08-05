# Continuous Orientation Fusion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fuse `location_samples`/`rotation_samples` into a continuous per-vehicle camera-orientation signal, replacing the single static `compassHeadingDegrees` scalar used today for every vehicle in a clip - the piece that actually closes the original moving-camera wrong-way-detection bug.

**Architecture:** Python gains a per-track `trackMidpointMs` (elapsed time from an FPS lookup). Kotlin gains a new pure `OrientationTimeline` that interpolates `rotation_samples` (preferred) or `location_samples` (fallback) at a given elapsed time. `ClipFlowAnalyzer` resolves each vehicle's orientation individually via its own `trackMidpointMs`, falling back to the report-level scalar, falling back to dropping the vehicle - the same three-tier graceful-degradation pattern already used for every other optional signal in this codebase.

**Tech Stack:** Kotlin/Spring Boot (server), Python/FastAPI (video-analysis), existing `BearingMath` circular-statistics helpers (no new dependencies).

## Global Constraints

- Rotation is always preferred over GPS when a report has any `rotation_samples`; the two are never blended, even when both exist for the same report (GPS bearing is unreliable at low/zero speed - see design spec).
- The clip-start anchor for elapsed-time-to-real-time correlation is the earliest `capturedAt` across `location_samples` and `rotation_samples` combined - never `report.recordedAt` (that field is the pre-trim raw recording start, a documented separate trap - see design spec's "Important, separately-confirmed correlation bug" note).
- No new `EvidenceKind` - this changes how `absoluteBearingDegrees` is computed internally, not what evidence sources exist.
- Every new nullable field (`trackMidpointMs`, `orientationTimeline`) must default gracefully (old Python service / no samples) to today's exact behavior - never throw, never silently misbehave.
- Pure math (`OrientationTimeline`, `compute_track_midpoint_ms`) has zero I/O and zero framework dependencies, mirroring `BearingMath`'s and `tracking_bearing.py`'s existing testability contract.

---

### Task 1: Python per-track timing

**Files:**
- Modify: `video-analysis/app/detection.py`
- Modify: `video-analysis/app/tracking_bearing.py`
- Modify: `video-analysis/app/pipeline.py`
- Modify: `video-analysis/app/schemas.py`
- Test: `video-analysis/tests/test_detection.py`
- Test: `video-analysis/tests/test_bearing.py`
- Test: `video-analysis/tests/test_pipeline.py`

**Interfaces:**
- Consumes: `VehicleDetector` (existing, `app/detection.py`), `TrackedFrame.frame_index` (existing field), `compute_bearing_degrees` (existing, `app/tracking_bearing.py`).
- Produces: `VehicleDetector.read_fps(video_path: str) -> float | None`; `compute_track_midpoint_ms(first_frame_index: int, last_frame_index: int, fps: float | None) -> int | None`; `VehicleResult.track_midpoint_ms: int | None` (JSON key `track_midpoint_ms`, matching Kotlin's `VehicleAnalysisResult.trackMidpointMs` via the server's snake_case Jackson mapping - Task 3 depends on this exact field name).

- [ ] **Step 1: Write the failing test for `read_fps`**

Add to `video-analysis/tests/test_detection.py`:

```python
@patch("app.detection.YOLO")
@patch("app.detection.cv2.VideoCapture")
def test_read_fps_returns_the_capture_fps(mock_video_capture, mock_yolo):
    mock_capture = MagicMock()
    mock_capture.get.return_value = 29.97
    mock_video_capture.return_value = mock_capture

    from app.detection import VehicleDetector

    detector = VehicleDetector(_fake_settings())
    fps = detector.read_fps("irrelevant.mp4")

    assert fps == pytest.approx(29.97)
    mock_capture.get.assert_called_once_with(cv2.CAP_PROP_FPS)
    mock_capture.release.assert_called_once()


@patch("app.detection.YOLO")
@patch("app.detection.cv2.VideoCapture")
def test_read_fps_returns_none_for_zero_or_invalid_fps(mock_video_capture, mock_yolo):
    mock_capture = MagicMock()
    mock_video_capture.return_value = mock_capture

    from app.detection import VehicleDetector

    detector = VehicleDetector(_fake_settings())

    mock_capture.get.return_value = 0.0
    assert detector.read_fps("irrelevant.mp4") is None

    mock_capture.get.return_value = float("nan")
    assert detector.read_fps("irrelevant.mp4") is None

    mock_capture.get.return_value = -1.0
    assert detector.read_fps("irrelevant.mp4") is None
```

Add `import pytest` to the top of `video-analysis/tests/test_detection.py` (not currently imported there).

- [ ] **Step 2: Run test to verify it fails**

Run: `cd video-analysis && python -m pytest tests/test_detection.py -v`
Expected: FAIL with `AttributeError: 'VehicleDetector' object has no attribute 'read_fps'`

- [ ] **Step 3: Implement `read_fps`**

In `video-analysis/app/detection.py`, add `import math` to the top imports (alongside the existing `from dataclasses import dataclass` etc.), then add this method to `VehicleDetector` (after `track_video`, before `_detect_frame`):

```python
    def read_fps(self, video_path: str) -> float | None:
        """Video frame rate in fps, or None if unavailable/invalid - some malformed or
        variable-frame-rate videos report 0, negative, or NaN from CAP_PROP_FPS. Never a
        fabricated value; callers (see compute_track_midpoint_ms) treat None as
        "timing unavailable for this clip", same graceful-degradation contract as every
        other optional signal in this service."""
        capture = cv2.VideoCapture(video_path)
        try:
            fps = capture.get(cv2.CAP_PROP_FPS)
        finally:
            capture.release()
        if fps is None or fps <= 0 or math.isnan(fps):
            return None
        return fps
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd video-analysis && python -m pytest tests/test_detection.py -v`
Expected: PASS (both new tests, plus the existing `test_track_video_enables_orientation_auto_before_reading_frames`)

- [ ] **Step 5: Commit**

```bash
git add video-analysis/app/detection.py video-analysis/tests/test_detection.py
git commit -m "feat(video-analysis): add VehicleDetector.read_fps"
```

- [ ] **Step 6: Write the failing test for `compute_track_midpoint_ms`**

Add to `video-analysis/tests/test_bearing.py` (after the existing tests, reuse the existing `import pytest` at the top):

```python
from app.tracking_bearing import compute_track_midpoint_ms


def test_track_midpoint_ms_at_30fps():
    # Frames 0..29 at 30fps span exactly 1 second (0..1000ms); midpoint frame 14.5 -> ~483ms.
    assert compute_track_midpoint_ms(0, 29, 30.0) == pytest.approx(483, abs=1)


def test_track_midpoint_ms_single_frame_track():
    assert compute_track_midpoint_ms(10, 10, 30.0) == pytest.approx(333, abs=1)


def test_track_midpoint_ms_returns_none_when_fps_is_none():
    assert compute_track_midpoint_ms(0, 29, None) is None


def test_track_midpoint_ms_returns_none_when_fps_is_zero_or_negative():
    assert compute_track_midpoint_ms(0, 29, 0.0) is None
    assert compute_track_midpoint_ms(0, 29, -5.0) is None
```

- [ ] **Step 7: Run test to verify it fails**

Run: `cd video-analysis && python -m pytest tests/test_bearing.py -v`
Expected: FAIL with `ImportError: cannot import name 'compute_track_midpoint_ms'`

- [ ] **Step 8: Implement `compute_track_midpoint_ms`**

Add to `video-analysis/app/tracking_bearing.py` (after `compute_bearing_degrees`):

```python
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

- [ ] **Step 9: Run test to verify it passes**

Run: `cd video-analysis && python -m pytest tests/test_bearing.py -v`
Expected: PASS (all tests, old and new)

- [ ] **Step 10: Commit**

```bash
git add video-analysis/app/tracking_bearing.py video-analysis/tests/test_bearing.py
git commit -m "feat(video-analysis): add compute_track_midpoint_ms"
```

- [ ] **Step 11: Add `track_midpoint_ms` to the response schema**

In `video-analysis/app/schemas.py`, add this field to `VehicleResult` (after the existing `displacement_pixels: float = 0.0` line):

```python
    # Elapsed ms from the clip's start to this track's observation midpoint - None when
    # FPS was unavailable (see VehicleDetector.read_fps). The Kotlin server uses this to
    # look up the camera's orientation at roughly this vehicle's own moment in the clip,
    # instead of one static reading for the whole video.
    track_midpoint_ms: int | None = None
```

- [ ] **Step 12: Write the failing test for pipeline wiring**

In `video-analysis/tests/test_pipeline.py`, first update `FakeDetector` to also implement `read_fps` (it's called by `AnalysisPipeline.analyze` starting in Step 14 below):

```python
class FakeDetector:
    def __init__(self, frames: list[TrackedFrame], fps: float | None = 30.0):
        self._frames = frames
        self._fps = fps

    def track_video(self, video_path: str):
        yield from self._frames

    def read_fps(self, video_path: str) -> float | None:
        return self._fps
```

Then add these new tests (after `test_summarize_track_attaches_bounding_box_and_frame_from_the_largest_bbox_frame`):

```python
def test_summarize_track_attaches_track_midpoint_ms_from_fps():
    # frame_index 0 and 9 at 30fps (FakeDetector's default) -> midpoint frame 4.5 -> 150ms.
    frames = [
        _make_frame(track_id=1, frame_index=0, bbox=(10.0, 10.0, 20.0, 20.0)),
        _make_frame(track_id=1, frame_index=9, bbox=(10.0, 10.0, 20.0, 20.0)),
    ]
    pipeline = AnalysisPipeline(
        settings=_fake_settings(), detector=FakeDetector(frames), plate_reader=FakePlateReader()
    )

    response = pipeline.analyze("unused.mp4")

    assert response.vehicles[0].track_midpoint_ms == pytest.approx(150, abs=1)


def test_summarize_track_track_midpoint_ms_is_none_when_fps_unavailable():
    frames = [
        _make_frame(track_id=1, frame_index=0, bbox=(10.0, 10.0, 20.0, 20.0)),
        _make_frame(track_id=1, frame_index=9, bbox=(10.0, 10.0, 20.0, 20.0)),
    ]
    pipeline = AnalysisPipeline(
        settings=_fake_settings(), detector=FakeDetector(frames, fps=None), plate_reader=FakePlateReader()
    )

    response = pipeline.analyze("unused.mp4")

    assert response.vehicles[0].track_midpoint_ms is None
```

Add `import pytest` to the top of `video-analysis/tests/test_pipeline.py` (not currently imported there).

- [ ] **Step 13: Run test to verify it fails**

Run: `cd video-analysis && python -m pytest tests/test_pipeline.py -v`
Expected: FAIL - `test_summarize_track_attaches_track_midpoint_ms_from_fps` and
`test_summarize_track_track_midpoint_ms_is_none_when_fps_unavailable` fail with
`AssertionError` (actual `track_midpoint_ms` is `None` because it's not wired yet,
while the first test expects `150`); all other tests still pass.

- [ ] **Step 14: Wire `read_fps` through `AnalysisPipeline`**

In `video-analysis/app/pipeline.py`:

1. Add the import at the top: `from app.tracking_bearing import compute_bearing_degrees, compute_track_midpoint_ms`
2. In `analyze()`, right after the `if not tracks: return AnalyzeResponse()` line, add:
   ```python
        fps = self._detector.read_fps(video_path)
   ```
3. Update the `_summarize_track` call site inside the `vehicles = [...]` list comprehension to pass `fps` through:
   ```python
        vehicles = [
            self._summarize_track(
                track_id,
                frames,
                corridor_id=assignments[track_id],
                cohesion=corridor_cohesion(track_id, paths, assignments, threshold_px),
                fps=fps,
            )
            for track_id, frames in tracks.items()
        ]
   ```
4. Update `_summarize_track`'s signature and body to accept and use `fps`:
   ```python
    def _summarize_track(
        self, track_id: int, frames: list["TrackedFrame"], corridor_id: int, cohesion: float, fps: float | None
    ) -> VehicleResult:
        frames_sorted = sorted(frames, key=lambda f: f.frame_index)
        centroids = [f.centroid for f in frames_sorted]
        bearing = compute_bearing_degrees(centroids)
        track_midpoint_ms = compute_track_midpoint_ms(
            frames_sorted[0].frame_index, frames_sorted[-1].frame_index, fps
        )
   ```
   (leave every other line of `_summarize_track`'s body unchanged - `displacement`, `vehicle_type`, `detection_confidence`, `plate_text`/`plate_confidence`, `representative_frame`, `bounding_box`, `frame_jpeg_base64` all stay exactly as they are)
5. Add `track_midpoint_ms=track_midpoint_ms,` to the `VehicleResult(...)` constructor call at the end of `_summarize_track` (alongside the existing `displacement_pixels=displacement,` line).

- [ ] **Step 15: Run test to verify it passes**

Run: `cd video-analysis && python -m pytest tests/test_pipeline.py -v`
Expected: PASS (all tests, old and new)

- [ ] **Step 16: Run the full Python test suite**

Run: `cd video-analysis && python -m pytest -v`
Expected: PASS (all tests across all files)

- [ ] **Step 17: Commit**

```bash
git add video-analysis/app/pipeline.py video-analysis/app/schemas.py video-analysis/tests/test_pipeline.py
git commit -m "feat(video-analysis): wire track_midpoint_ms through the analyze pipeline"
```

---

### Task 2: Kotlin `OrientationTimeline`

**Files:**
- Create: `server/src/main/kotlin/com/trafficwatch/server/geo/OrientationTimeline.kt`
- Test: `server/src/test/kotlin/com/trafficwatch/server/geo/OrientationTimelineTest.kt`

**Interfaces:**
- Consumes: `com.trafficwatch.server.reports.dto.LocationSampleDto` (existing: `latitude, longitude, accuracy, altitude, bearing, speed, capturedAt` - all `Double`/`Long`), `com.trafficwatch.server.reports.dto.RotationSampleDto` (existing: `headingDegrees: Double, capturedAt: Long`), `BearingMath.weightedCircularMeanDegrees(bearingsDegrees: List<Double>, weights: List<Double>): Double?` (existing, `com.trafficwatch.server.geo` package - no import needed, same package).
- Produces: `enum class OrientationSource { ROTATION, LOCATION }`; `data class ResolvedOrientation(val bearingDegrees: Double, val source: OrientationSource)`; `class OrientationTimeline(locationSamples: List<LocationSampleDto>, rotationSamples: List<RotationSampleDto>)` with `fun orientationAt(elapsedMs: Long): ResolvedOrientation?` - Task 3 depends on this exact class name, constructor signature, and method signature.

- [ ] **Step 1: Write the failing tests**

Create `server/src/test/kotlin/com/trafficwatch/server/geo/OrientationTimelineTest.kt`:

```kotlin
package com.trafficwatch.server.geo

import com.trafficwatch.server.reports.dto.LocationSampleDto
import com.trafficwatch.server.reports.dto.RotationSampleDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class OrientationTimelineTest {

    private fun rotation(capturedAt: Long, headingDegrees: Double) =
        RotationSampleDto(headingDegrees = headingDegrees, capturedAt = capturedAt)

    private fun location(capturedAt: Long, bearing: Double) = LocationSampleDto(
        latitude = 0.0, longitude = 0.0, accuracy = 5.0, altitude = 0.0,
        bearing = bearing, speed = 5.0, capturedAt = capturedAt,
    )

    @Test
    fun `interpolates between two bracketing rotation samples weighted by time distance`() {
        val timeline = OrientationTimeline(
            locationSamples = emptyList(),
            rotationSamples = listOf(rotation(1000L, 0.0), rotation(2000L, 90.0)),
        )

        // Anchor is the earliest sample (1000L). elapsedMs=500 -> target epoch 1500,
        // exactly halfway between the two samples -> exactly halfway between 0 and 90.
        val resolved = timeline.orientationAt(500L)

        assertEquals(45.0, resolved!!.bearingDegrees, 1e-6)
        assertEquals(OrientationSource.ROTATION, resolved.source)
    }

    @Test
    fun `uses the nearest sample unweighted when the target is before the first sample`() {
        val timeline = OrientationTimeline(
            locationSamples = emptyList(),
            rotationSamples = listOf(rotation(1000L, 10.0), rotation(2000L, 90.0)),
        )

        // elapsedMs=0 -> target epoch 1000, exactly the first (and thus also "before or at").
        assertEquals(10.0, timeline.orientationAt(0L)!!.bearingDegrees, 1e-6)
    }

    @Test
    fun `uses the nearest sample unweighted when the target is after the last sample`() {
        val timeline = OrientationTimeline(
            locationSamples = emptyList(),
            rotationSamples = listOf(rotation(1000L, 10.0), rotation(2000L, 90.0)),
        )

        // Anchor 1000L, elapsedMs=5000 -> target epoch 6000, well past the last sample (2000).
        assertEquals(90.0, timeline.orientationAt(5000L)!!.bearingDegrees, 1e-6)
    }

    @Test
    fun `falls back to location samples' bearing when there are no rotation samples`() {
        val timeline = OrientationTimeline(
            locationSamples = listOf(location(1000L, 0.0), location(2000L, 90.0)),
            rotationSamples = emptyList(),
        )

        val resolved = timeline.orientationAt(500L)

        assertEquals(45.0, resolved!!.bearingDegrees, 1e-6)
        assertEquals(OrientationSource.LOCATION, resolved.source)
    }

    @Test
    fun `never blends rotation and location samples even when both exist`() {
        val timeline = OrientationTimeline(
            locationSamples = listOf(location(1000L, 200.0)),
            rotationSamples = listOf(rotation(1000L, 10.0), rotation(2000L, 90.0)),
        )

        // Rotation exists, so location's very different bearing (200.0) must be ignored entirely.
        val resolved = timeline.orientationAt(500L)

        assertEquals(50.0, resolved!!.bearingDegrees, 1e-6)
        assertEquals(OrientationSource.ROTATION, resolved.source)
    }

    @Test
    fun `returns null when both sample lists are empty`() {
        val timeline = OrientationTimeline(locationSamples = emptyList(), rotationSamples = emptyList())

        assertNull(timeline.orientationAt(500L))
    }

    @Test
    fun `a single rotation sample is returned directly with no interpolation`() {
        val timeline = OrientationTimeline(
            locationSamples = emptyList(),
            rotationSamples = listOf(rotation(1000L, 42.0)),
        )

        assertEquals(42.0, timeline.orientationAt(999_999L)!!.bearingDegrees, 1e-6)
        assertEquals(42.0, timeline.orientationAt(0L)!!.bearingDegrees, 1e-6)
    }

    @Test
    fun `interpolates correctly through the 0-360 wraparound`() {
        // 350 degrees to 10 degrees should interpolate through 0/360 (landing near 0),
        // never through 180 (which a naive numeric average of 350 and 10 would give: 180.0).
        val timeline = OrientationTimeline(
            locationSamples = emptyList(),
            rotationSamples = listOf(rotation(1000L, 350.0), rotation(2000L, 10.0)),
        )

        val resolved = timeline.orientationAt(500L)!!.bearingDegrees

        assertEquals(0.0, resolved, 1e-6)
    }

    @Test
    fun `returns null for an exactly-antipodal weighted mean rather than a fabricated value`() {
        // 0 and 180 degrees weighted 50/50 is a genuine degenerate case for a circular
        // mean (the vectors cancel exactly) - BearingMath.weightedCircularMeanDegrees
        // returns null here, and OrientationTimeline must propagate that, not fabricate
        // an arbitrary answer.
        val timeline = OrientationTimeline(
            locationSamples = emptyList(),
            rotationSamples = listOf(rotation(1000L, 0.0), rotation(2000L, 180.0)),
        )

        assertNull(timeline.orientationAt(500L))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server && ./gradlew.bat test --tests "com.trafficwatch.server.geo.OrientationTimelineTest" --console=plain`
Expected: FAIL to compile - `OrientationTimeline`, `OrientationSource` do not exist.

- [ ] **Step 3: Implement `OrientationTimeline`**

Create `server/src/main/kotlin/com/trafficwatch/server/geo/OrientationTimeline.kt`:

```kotlin
package com.trafficwatch.server.geo

import com.trafficwatch.server.reports.dto.LocationSampleDto
import com.trafficwatch.server.reports.dto.RotationSampleDto

enum class OrientationSource { ROTATION, LOCATION }

data class ResolvedOrientation(val bearingDegrees: Double, val source: OrientationSource)

/**
 * Continuous per-timestamp camera orientation, fused from a report's
 * location_samples/rotation_samples - pure, no I/O, mirrors BearingMath's testability
 * contract. rotation_samples is always preferred when any exist; location_samples' GPS
 * bearing is used only when a report has zero rotation_samples (never blended - GPS
 * bearing is unreliable at low/zero speed, the exact failure mode that motivated
 * capturing rotation as an independent signal in the first place - see the
 * 2026-08-05 design spec).
 */
class OrientationTimeline(
    private val locationSamples: List<LocationSampleDto>,
    private val rotationSamples: List<RotationSampleDto>,
) {
    // Earliest timestamp across both lists approximates the trimmed clip's frame-0
    // wall-clock time - both lists are already filtered client-side to the trimmed
    // clip's window. Deliberately NOT report.recordedAt (that's the pre-trim raw
    // recording start - see the design spec's correlation-bug note).
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
     * (epochMs, bearingDegrees) bracketing [targetEpochMs], weighted by inverse
     * time-distance. At the edges (target before the first or after the last point),
     * returns that single nearest point unweighted. Null only for a genuine circular-mean
     * degeneracy (see BearingMath.weightedCircularMeanDegrees) - never a fabricated value.
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

- [ ] **Step 4: Run test to verify it passes**

Run: `cd server && ./gradlew.bat test --tests "com.trafficwatch.server.geo.OrientationTimelineTest" --console=plain`
Expected: PASS (all 9 tests)

- [ ] **Step 5: Commit**

```bash
git add server/src/main/kotlin/com/trafficwatch/server/geo/OrientationTimeline.kt server/src/test/kotlin/com/trafficwatch/server/geo/OrientationTimelineTest.kt
git commit -m "feat(server): add OrientationTimeline for continuous orientation fusion"
```

---

### Task 3: Wire into `ClipFlowAnalyzer` and `ReportAnalysisJob`

**Files:**
- Modify: `server/src/main/kotlin/com/trafficwatch/server/videoanalysis/dto/VideoAnalysisDtos.kt`
- Modify: `server/src/main/kotlin/com/trafficwatch/server/geo/ClipFlowAnalyzer.kt`
- Modify: `server/src/main/kotlin/com/trafficwatch/server/reports/ReportAnalysisJob.kt`
- Test: `server/src/test/kotlin/com/trafficwatch/server/geo/ClipFlowAnalyzerTest.kt`
- Test: `server/src/test/kotlin/com/trafficwatch/server/reports/ReportAnalysisJobTest.kt`

**Interfaces:**
- Consumes: `OrientationTimeline`, `OrientationSource`, `ResolvedOrientation` (Task 2, `com.trafficwatch.server.geo` package - same package as `ClipFlowAnalyzer`, no import needed there); `VehicleAnalysisResult` (existing); `Report.locationSamples`/`rotationSamples`/`compassHeadingDegrees` (existing entity fields); `LocationSampleDto`/`RotationSampleDto` (existing).
- Produces: `VehicleAnalysisResult.trackMidpointMs: Long?`; `ClipFlowAnalyzer.qualifyVehicles(vehicles, compassHeadingDegrees: Double?, frameWidth: Int?, frameHeight: Int?, orientationTimeline: OrientationTimeline? = null): List<FlowVehicle>` (new trailing optional parameter - deliberately placed last with a default so the 17 existing positional call sites in `ClipFlowAnalyzerTest.kt` keep compiling unchanged); `FlowVehicle.orientationSource: OrientationSource?`.

- [ ] **Step 1: Add `trackMidpointMs` to `VehicleAnalysisResult`**

In `server/src/main/kotlin/com/trafficwatch/server/videoanalysis/dto/VideoAnalysisDtos.kt`, add this field to `VehicleAnalysisResult` (after the existing `displacementPixels: Double? = null` line):

```kotlin
    // Elapsed ms from the clip's start to this track's observation midpoint - null from
    // older video-analysis service versions (no FPS lookup existed yet) or when FPS was
    // unavailable for this specific clip. Used to look up this vehicle's own camera
    // orientation from OrientationTimeline instead of applying one static reading to
    // every vehicle in the clip. Snake_case wire key: track_midpoint_ms.
    val trackMidpointMs: Long? = null,
```

- [ ] **Step 2: Write the failing `ClipFlowAnalyzer` tests**

Add to `server/src/test/kotlin/com/trafficwatch/server/geo/ClipFlowAnalyzerTest.kt`. First, update the file's `vehicle()` test-fixture function to accept and pass through a new `trackMidpointMs` parameter (add `trackMidpointMs: Long? = null` as the last parameter, and `trackMidpointMs = trackMidpointMs,` to the `VehicleAnalysisResult(...)` call). Then add these new tests (anywhere after the existing tests):

```kotlin
    @Test
    fun `qualifyVehicles resolves orientation per-vehicle from the orientation timeline when trackMidpointMs is set`() {
        val timeline = OrientationTimeline(
            locationSamples = emptyList(),
            rotationSamples = listOf(
                com.trafficwatch.server.reports.dto.RotationSampleDto(headingDegrees = 45.0, capturedAt = 1000L),
            ),
        )
        val result = analyzer.qualifyVehicles(
            listOf(vehicle(1, bearing = 90.0, trackMidpointMs = 500L)),
            compassHeadingDegrees = 0.0, // stale scalar - must be ignored since the timeline resolves
            frameWidth = 1920, frameHeight = 1080,
            orientationTimeline = timeline,
        )

        assertEquals(1, result.size)
        // 45.0 (from timeline, not the 0.0 scalar) + 90.0 frame bearing = 135.0.
        assertEquals(135.0, result[0].absoluteBearingDegrees, 1e-9)
        assertEquals(OrientationSource.ROTATION, result[0].orientationSource)
    }

    @Test
    fun `qualifyVehicles falls back to the compass scalar when trackMidpointMs is null`() {
        val timeline = OrientationTimeline(
            locationSamples = emptyList(),
            rotationSamples = listOf(
                com.trafficwatch.server.reports.dto.RotationSampleDto(headingDegrees = 45.0, capturedAt = 1000L),
            ),
        )
        val result = analyzer.qualifyVehicles(
            listOf(vehicle(1, bearing = 90.0, trackMidpointMs = null)), // old video-analysis service
            compassHeadingDegrees = 10.0,
            frameWidth = 1920, frameHeight = 1080,
            orientationTimeline = timeline,
        )

        assertEquals(1, result.size)
        assertEquals(100.0, result[0].absoluteBearingDegrees, 1e-9) // 10.0 (scalar) + 90.0
        assertNull(result[0].orientationSource)
    }

    @Test
    fun `qualifyVehicles falls back to the compass scalar when the timeline has no samples at all`() {
        val emptyTimeline = OrientationTimeline(locationSamples = emptyList(), rotationSamples = emptyList())
        val result = analyzer.qualifyVehicles(
            listOf(vehicle(1, bearing = 90.0, trackMidpointMs = 500L)),
            compassHeadingDegrees = 10.0,
            frameWidth = 1920, frameHeight = 1080,
            orientationTimeline = emptyTimeline,
        )

        assertEquals(1, result.size)
        assertEquals(100.0, result[0].absoluteBearingDegrees, 1e-9)
        assertNull(result[0].orientationSource)
    }

    @Test
    fun `qualifyVehicles drops a vehicle when neither the timeline nor the compass scalar can resolve an orientation`() {
        val emptyTimeline = OrientationTimeline(locationSamples = emptyList(), rotationSamples = emptyList())
        val result = analyzer.qualifyVehicles(
            listOf(vehicle(1, bearing = 90.0, trackMidpointMs = 500L)),
            compassHeadingDegrees = null,
            frameWidth = 1920, frameHeight = 1080,
            orientationTimeline = emptyTimeline,
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `qualifyVehicles still works exactly as before when orientationTimeline is omitted`() {
        val result = analyzer.qualifyVehicles(listOf(vehicle(1, bearing = 90.0)), 45.0, 1920, 1080)

        assertEquals(1, result.size)
        assertEquals(135.0, result[0].absoluteBearingDegrees, 1e-9)
        assertNull(result[0].orientationSource)
    }
```

Add these imports to the top of `ClipFlowAnalyzerTest.kt`:

```kotlin
import org.junit.jupiter.api.Assertions.assertNull
```

(`OrientationTimeline` and `OrientationSource` are in the same `com.trafficwatch.server.geo` package as this test file, so no import is needed for them - only the fully-qualified `RotationSampleDto` reference above needs no import either since it's used with its full path inline, matching how this plan avoids adding an extra import line for a single usage.)

- [ ] **Step 3: Run test to verify it fails**

Run: `cd server && ./gradlew.bat test --tests "com.trafficwatch.server.geo.ClipFlowAnalyzerTest" --console=plain`
Expected: FAIL to compile - `qualifyVehicles` has no `orientationTimeline` parameter yet, `vehicle()` has no `trackMidpointMs` parameter yet.

- [ ] **Step 4: Update `ClipFlowAnalyzer`**

In `server/src/main/kotlin/com/trafficwatch/server/geo/ClipFlowAnalyzer.kt`:

1. Add `orientationSource: OrientationSource? = null` to `FlowVehicle` (after the existing `corridorCohesion: Double,` line):

```kotlin
data class FlowVehicle(
    val vehicle: VehicleAnalysisResult,
    val absoluteBearingDegrees: Double,
    val trackQuality: Double,
    val corridorId: Long,
    val corridorCohesion: Double,
    val orientationSource: OrientationSource? = null,
) {
    val candidateQuality: Double get() = trackQuality * corridorCohesion
}
```

2. Replace `qualifyVehicles`'s signature and body:

```kotlin
    fun qualifyVehicles(
        vehicles: List<VehicleAnalysisResult>,
        compassHeadingDegrees: Double?,
        frameWidth: Int?,
        frameHeight: Int?,
        orientationTimeline: OrientationTimeline? = null,
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
            // Defensive: a zero-diagonal bbox (malformed upstream data - real YOLO boxes
            // are never degenerate) would otherwise make minDisplacement 0.0 and let a
            // zero-displacement vehicle through, then produce NaN in trackQuality's
            // division below (0.0/0.0), which can silently corrupt candidate scoring
            // downstream. Drop it here instead.
            if (bboxDiagonal <= 0.0) return@mapNotNull null
            // Note: vehicle.boundingBox is the track's LARGEST-area frame (see Python's
            // pipeline.py representative_frame selection), not a typical/average size -
            // for a vehicle whose apparent size changes a lot across the clip (e.g.
            // approaching or receding the camera), this makes the effective floor
            // stricter than "15% of typical size" might suggest. Worth knowing if
            // minDisplacementFraction is ever retuned.
            val minDisplacement = properties.minDisplacementFraction * bboxDiagonal

            if (frames < MIN_TRACK_FRAMES || displacement < minDisplacement) return@mapNotNull null

            // Per-vehicle orientation resolution, preferred-to-fallback: (1) the
            // continuous timeline at this vehicle's own observation midpoint, (2) the
            // report-level scalar (today's whole-clip behavior), (3) drop the vehicle -
            // same three-tier graceful degradation as every other optional signal here.
            val resolved = vehicle.trackMidpointMs?.let { orientationTimeline?.orientationAt(it) }
            val orientationDegrees = resolved?.bearingDegrees ?: compassHeadingDegrees ?: return@mapNotNull null

            FlowVehicle(
                vehicle = vehicle,
                absoluteBearingDegrees = (orientationDegrees + frameBearing) % 360.0,
                trackQuality = min(frames / TRACK_FRAMES_SATURATION, 1.0) *
                    min(displacement / minDisplacement, 1.0).coerceAtMost(1.0),
                corridorId = corridorId,
                corridorCohesion = cohesion,
                orientationSource = resolved?.source,
            )
        }
    }
```

(Everything else in the file - `corridorConsensus`, `movesWith`, `hasPeerSupport`, `CorridorConsensus`, the file-level constants - stays exactly as-is.)

- [ ] **Step 5: Run test to verify it passes**

Run: `cd server && ./gradlew.bat test --tests "com.trafficwatch.server.geo.ClipFlowAnalyzerTest" --console=plain`
Expected: PASS (all tests, old and new - the 17 pre-existing positional-call tests must pass unchanged, proving the trailing-default-parameter choice preserved backward compatibility)

- [ ] **Step 6: Commit**

```bash
git add server/src/main/kotlin/com/trafficwatch/server/videoanalysis/dto/VideoAnalysisDtos.kt server/src/main/kotlin/com/trafficwatch/server/geo/ClipFlowAnalyzer.kt server/src/test/kotlin/com/trafficwatch/server/geo/ClipFlowAnalyzerTest.kt
git commit -m "feat(server): resolve each vehicle's orientation from OrientationTimeline in ClipFlowAnalyzer"
```

- [ ] **Step 7: Write the failing `ReportAnalysisJob` tests**

In `server/src/test/kotlin/com/trafficwatch/server/reports/ReportAnalysisJobTest.kt`:

1. Add these imports at the top:

```kotlin
import com.trafficwatch.server.geo.OrientationSource
import com.trafficwatch.server.reports.dto.RotationSampleDto
```

2. Update the `sampleReport()` fixture to accept and pass through `locationSamples`/`rotationSamples`:

```kotlin
    private fun sampleReport(
        id: UUID = UUID.randomUUID(),
        compassHeadingDegrees: BigDecimal? = BigDecimal("90.0"),
        createdUpdatedAt: OffsetDateTime = OffsetDateTime.parse("2026-07-25T10:00:00Z"),
        locationSamples: String? = null,
        rotationSamples: String? = null,
    ) = Report(
        userId = UUID.randomUUID(),
        videoPath = "/videos/$id.mp4",
        latitude = BigDecimal("31.520370"),
        longitude = BigDecimal("74.358749"),
        accuracy = BigDecimal("5.00"),
        altitude = BigDecimal("210.50"),
        bearing = BigDecimal("87.30"),
        speed = BigDecimal("12.40"),
        recordedAt = LocalDateTime.of(2026, 7, 25, 10, 0, 0),
        durationMs = 15000L,
        deviceId = "device-123",
        status = ReportStatus.PENDING,
        compassHeadingDegrees = compassHeadingDegrees,
        locationSamples = locationSamples,
        rotationSamples = rotationSamples,
        updatedAt = createdUpdatedAt,
    ).apply { this.id = id }
```

3. Update the `vehicle()` fixture to accept and pass through `trackMidpointMs`:

```kotlin
    private fun vehicle(
        trackId: Long = 1,
        bearingDegrees: Double? = 90.0,
        detectionConfidence: Double = 0.8,
        plateText: String? = "LEA-1234",
        plateConfidence: Double? = 0.9,
        boundingBox: BoundingBox? = BoundingBox(x1 = 0.0, y1 = 0.0, x2 = 1414.0, y2 = 1414.0),
        frameJpegBase64: String? = null,
        corridorId: Long? = 0L,
        corridorCohesion: Double? = 1.0,
        trackFrameCount: Int? = 10,
        displacementPixels: Double? = 310.0,
        trackMidpointMs: Long? = null,
    ) = VehicleAnalysisResult(
        trackId = trackId,
        vehicleType = "car",
        detectionConfidence = detectionConfidence,
        bearingDegrees = bearingDegrees,
        plateText = plateText,
        plateConfidence = plateConfidence,
        boundingBox = boundingBox,
        frameJpegBase64 = frameJpegBase64,
        corridorId = corridorId,
        corridorCohesion = corridorCohesion,
        trackFrameCount = trackFrameCount,
        displacementPixels = displacementPixels,
        trackMidpointMs = trackMidpointMs,
    )
```

4. Update the existing test's assertion for the new generalized message text (this test currently asserts the OLD "Device compass heading unavailable..." message - it must now assert the NEW one, since this scenario - `compassHeadingDegrees = null` and no samples set - is exactly the "no orientation source at all" case):

```kotlin
    @Test
    fun `applyOutcome still resolves the street and calls video analysis when no orientation data is available, but rejects with a specific message`() {
        val report = sampleReport(compassHeadingDegrees = null)
        every {
            streetDirectionResolver.resolve(report.latitude, report.longitude)
        } returns DirectionResolution.OneWay("Main Boulevard", 0.0)
        every {
            videoAnalysisClient.analyze(fakeVideoPath, any())
        } returns analysisResponse(listOf(vehicle(bearingDegrees = 180.0)))
        every { reportRepository.save(any()) } answers { firstArg() }

        job.applyOutcome(report)

        assertThat(report.status).isEqualTo(ReportStatus.REJECTED)
        assertThat(report.analysisMessage)
            .isEqualTo("No orientation data available for this report")
        assertThat(report.streetName).isEqualTo("Main Boulevard")
        assertThat(report.licensePlate).isNull()
        assertThat(report.confidence).isNull()
        verify(exactly = 1) { streetDirectionResolver.resolve(report.latitude, report.longitude) }
        verify(exactly = 1) { videoAnalysisClient.analyze(fakeVideoPath, any()) }
    }
```

(This replaces the existing test of the same name/purpose - same test, updated name and assertion text.)

5. Add these new tests (anywhere after the updated test above):

```kotlin
    @Test
    fun `applyOutcome uses per-vehicle rotation-sample-derived orientation to confirm a violation the stale compass scalar alone would have missed`() {
        // The stale scalar (0.0) represents a single compass snapshot taken before the
        // camera physically rotated mid-clip. rotation_samples show the camera's real
        // orientation changing from 10.0 (early) to 90.0 (by the time this vehicle was
        // actually observed, at trackMidpointMs=8000 -> target epoch 1000+8000=9000,
        // which lands exactly on the second sample).
        val rotationSamplesJson = objectMapper.writeValueAsString(
            listOf(
                RotationSampleDto(headingDegrees = 10.0, capturedAt = 1000L),
                RotationSampleDto(headingDegrees = 90.0, capturedAt = 9000L),
            ),
        )
        val report = sampleReport(compassHeadingDegrees = BigDecimal("0.0"), rotationSamples = rotationSamplesJson)
        every {
            streetDirectionResolver.resolve(report.latitude, report.longitude)
        } returns DirectionResolution.OneWay("Main Boulevard", 0.0) // legal bearing 0, illegal 180
        every {
            videoAnalysisClient.analyze(fakeVideoPath, any())
        } returns analysisResponse(
            listOf(vehicle(bearingDegrees = 90.0, trackMidpointMs = 8000L, plateText = "LEA-1234", plateConfidence = 0.9)),
        )
        every { reportRepository.save(any()) } answers { firstArg() }

        job.applyOutcome(report)

        // Using the stale 0.0 scalar: absoluteBearing = (0+90)%360 = 90, 90 degrees from
        // illegal(180) - OUTSIDE the 60-degree tolerance, would have been REJECTED. Using
        // the rotation-sample-resolved 90.0: absoluteBearing = (90+90)%360 = 180 - exactly
        // the illegal bearing - CONFIRMED. Proves the fusion, not just the scalar, drove
        // this outcome.
        assertThat(report.status).isEqualTo(ReportStatus.CONFIRMED)
        assertThat(report.licensePlate).isEqualTo("LEA-1234")
        assertThat(report.streetName).isEqualTo("Main Boulevard")
    }

    @Test
    fun `applyOutcome resolves orientation from rotation samples even with no compass scalar at all`() {
        val rotationSamplesJson = objectMapper.writeValueAsString(
            listOf(
                RotationSampleDto(headingDegrees = 10.0, capturedAt = 1000L),
                RotationSampleDto(headingDegrees = 90.0, capturedAt = 9000L),
            ),
        )
        val report = sampleReport(compassHeadingDegrees = null, rotationSamples = rotationSamplesJson)
        every {
            streetDirectionResolver.resolve(report.latitude, report.longitude)
        } returns DirectionResolution.OneWay("Main Boulevard", 0.0)
        every {
            videoAnalysisClient.analyze(fakeVideoPath, any())
        } returns analysisResponse(listOf(vehicle(bearingDegrees = 90.0, trackMidpointMs = 8000L)))
        every { reportRepository.save(any()) } answers { firstArg() }

        job.applyOutcome(report)

        // Proves the compass scalar is no longer a hard requirement - samples alone are
        // enough to resolve an orientation and reach a real (non-"no orientation data") verdict.
        assertThat(report.status).isEqualTo(ReportStatus.CONFIRMED)
    }

    @Test
    fun `applyOutcome records the resolved orientation source in the evidence breakdown`() {
        val rotationSamplesJson = objectMapper.writeValueAsString(
            listOf(RotationSampleDto(headingDegrees = 90.0, capturedAt = 1000L)),
        )
        val report = sampleReport(compassHeadingDegrees = BigDecimal("0.0"), rotationSamples = rotationSamplesJson)
        every {
            streetDirectionResolver.resolve(report.latitude, report.longitude)
        } returns DirectionResolution.OneWay("Main Boulevard", 0.0)
        every {
            videoAnalysisClient.analyze(fakeVideoPath, any())
        } returns analysisResponse(listOf(vehicle(bearingDegrees = 90.0, trackMidpointMs = 0L)))
        every { reportRepository.save(any()) } answers { firstArg() }

        job.applyOutcome(report)

        assertThat(report.status).isEqualTo(ReportStatus.CONFIRMED)
        assertThat(report.directionEvidence).contains(OrientationSource.ROTATION.name)
    }
```

- [ ] **Step 8: Run test to verify it fails**

Run: `cd server && ./gradlew.bat test --tests "com.trafficwatch.server.reports.ReportAnalysisJobTest" --console=plain`
Expected: FAIL to compile (`Report(...)` has no `locationSamples`/`rotationSamples` constructor visibility issue - actually these already exist on `Report`, so this should compile; the NEW tests fail at runtime: the renamed test's message assertion fails against the still-old message text, and the 3 new tests fail because `ReportAnalysisJob` doesn't yet read samples or use `OrientationTimeline`)

- [ ] **Step 9: Update `ReportAnalysisJob`**

In `server/src/main/kotlin/com/trafficwatch/server/reports/ReportAnalysisJob.kt`:

1. Add these imports (alongside the existing `com.trafficwatch.server.geo.*` imports):

```kotlin
import com.trafficwatch.server.geo.OrientationTimeline
import com.trafficwatch.server.reports.dto.LocationSampleDto
import com.trafficwatch.server.reports.dto.RotationSampleDto
```

2. Replace the existing compass/flowVehicles block inside `determineOutcome` (currently lines 124-141: the comment block, `val compassHeadingDegrees = ...`, and the `val flowVehicles = if (...) { ... } else { emptyList() }`) with:

```kotlin
        // A report with no orientation source at all (no compass scalar, no samples)
        // means no vehicle's frame-relative bearing can be converted to a real-world
        // bearing - there is nothing to check against a legal direction. Rather than
        // bailing out before OSM/video analysis even run, street resolution and vehicle
        // detection above still happen and are reflected in the stored evidence
        // breakdown; only candidate direction-scoring is skipped, so this always lands
        // on REJECTED with a message that says why, distinct from "no violation found."
        val compassHeadingDegrees = report.compassHeadingDegrees
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
                analysis.frameWidth,
                analysis.frameHeight,
                orientationTimeline,
            )
        } else {
            emptyList()
        }
```

3. Update the `buildOutcome(...)` call site a few lines below (currently `compassMissing = compassHeadingDegrees == null,`) to:

```kotlin
            orientationMissing = !hasOrientationSource,
```

4. Rename `buildOutcome`'s `compassMissing: Boolean` parameter to `orientationMissing: Boolean`, and update its one usage inside the function's `when` block from:

```kotlin
            compassMissing -> "Device compass heading unavailable; cannot determine vehicle direction"
```

to:

```kotlin
            orientationMissing -> "No orientation data available for this report"
```

5. Add `val candidateOrientationSource: String?` to the `EvidenceBreakdown` data class (after the existing `finalScore: Double?,` line):

```kotlin
internal data class EvidenceBreakdown(
    val sources: List<EvidenceEntry>,
    val fusedBearingDegrees: Double?,
    val directionConfidence: Double?,
    val candidateQuality: Double?,
    val detectionConfidence: Double?,
    val bearingMatchScore: Double?,
    val finalScore: Double?,
    val confirmationThreshold: Double,
    val candidateOrientationSource: String?,
)
```

6. Update `breakdownJson(...)` to populate the new field:

```kotlin
    private fun breakdownJson(entries: List<EvidenceEntry>, best: ScoredCandidate?): String? = try {
        objectMapper.writeValueAsString(
            EvidenceBreakdown(
                sources = entries,
                fusedBearingDegrees = best?.fusion?.bearingDegrees,
                directionConfidence = best?.fusion?.directionConfidence,
                candidateQuality = best?.flowVehicle?.candidateQuality,
                detectionConfidence = best?.flowVehicle?.vehicle?.detectionConfidence,
                bearingMatchScore = best?.bearingMatchScore,
                finalScore = best?.finalScore,
                confirmationThreshold = analysisProperties.confirmationThreshold,
                candidateOrientationSource = best?.flowVehicle?.orientationSource?.name,
            ),
        )
    } catch (ex: Exception) {
        logger.warn("ReportAnalysisJob: failed to serialize evidence breakdown", ex)
        null
    }
```

7. Add these two new private helper functions (anywhere in the class, e.g. right after `breakdownJson`):

```kotlin
    /**
     * Unlike ReportService.submit()'s parsing of the same field (which must tolerate
     * malformed/oversized client input before it's ever stored), by the time this reads
     * report.locationSamples back out, that JSON has already been validated and capped
     * at write time - a parse failure here would mean corrupted DB data, a genuine bug,
     * not user input to tolerate. No defensive try/catch.
     */
    private fun parseLocationSamples(json: String?): List<LocationSampleDto> {
        if (json == null) return emptyList()
        val parsed: List<LocationSampleDto> = objectMapper.readValue(
            json,
            objectMapper.typeFactory.constructCollectionType(List::class.java, LocationSampleDto::class.java),
        )
        return parsed
    }

    /** See [parseLocationSamples] - same reasoning, same trust-the-stored-invariant contract. */
    private fun parseRotationSamples(json: String?): List<RotationSampleDto> {
        if (json == null) return emptyList()
        val parsed: List<RotationSampleDto> = objectMapper.readValue(
            json,
            objectMapper.typeFactory.constructCollectionType(List::class.java, RotationSampleDto::class.java),
        )
        return parsed
    }
```

- [ ] **Step 10: Run test to verify it passes**

Run: `cd server && ./gradlew.bat test --tests "com.trafficwatch.server.reports.ReportAnalysisJobTest" --console=plain`
Expected: PASS (all tests, old and new)

- [ ] **Step 11: Run the full server test suite**

Run: `cd server && ./gradlew.bat test --console=plain`
Expected: PASS (all tests across all files - in particular, confirm no other test anywhere in the module references the old "Device compass heading unavailable" message text or the old 4-argument `qualifyVehicles` call shape in a way that broke; the well-known pre-existing `EndToEndFlowTest` real-network flake, if it fails, is unrelated - verify by re-running just that test in isolation and checking it's the same known flaky assertion, not a new failure)

- [ ] **Step 12: Commit**

```bash
git add server/src/main/kotlin/com/trafficwatch/server/reports/ReportAnalysisJob.kt server/src/test/kotlin/com/trafficwatch/server/reports/ReportAnalysisJobTest.kt
git commit -m "feat(server): wire continuous orientation fusion into ReportAnalysisJob"
```
