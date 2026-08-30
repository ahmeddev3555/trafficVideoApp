# Stationary-Camera Approach Detection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Confirm a wrong-way vehicle that rides straight at a stationary camera, by detecting sustained bounding-box growth against a receding-traffic majority — a signal that needs no compass, no vanishing-point model, and no per-carriageway OSM bearing.

**Architecture:** The `video-analysis` service classifies each track's bounding-box size trend (`growing` / `shrinking` / `flat`) and emits it on the wire. The Kotlin server adds one additive fallback branch to `ReportAnalysisJob`: when the existing bearing-based analysis has already produced `REJECTED` on a non-two-way street, and the camera was verified stationary for the whole clip, a vehicle whose bbox grew sustainedly (≥ 0.8 over ≥ 30 frames) while ≥ 3 others receded is confirmed wrong-way. The branch can only upgrade `REJECTED` → `CONFIRMED`, never the reverse, so every existing test asserts the same outcome.

**Tech Stack:** Python 3.11 (FastAPI service, `pytest`), Kotlin / Spring Boot (`server/`, JUnit 5 + MockK + AssertJ, `./gradlew test` against H2).

**Spec:** `docs/superpowers/specs/2026-08-30-stationary-approach-detection-design.md`

## Global Constraints

- **No new video-analysis wire fields beyond `scale_trend` (string) and `scale_growth_fraction` (float).** Both default (`"flat"` / `0.0`) for older-service responses.
- **No change to the existing bearing path** (`resolve_bearing`'s `"centroid"` return, `ClipFlowAnalyzer.qualifyVehicles`, `evaluateCandidates`, `buildOutcome`, `DirectionEvidenceResolver`) beyond the one Fix-A pre-check in Task 2.
- **New tunable constants live on `AnalysisProperties`** bound from `app.analysis.*`, matching the existing pattern: `approachGrowthMin = 0.8`, `approachMinFrames = 30`, `approachMinDetection = 0.5`.
- **Reuse, do not duplicate:** `MIN_SCALE_CHANGE_FRACTION` (0.15) in `tracking_bearing.py`, `MIN_OBSERVATIONS` (12) in `tracking_bearing.py`, `MIN_TRACK_FRAMES` (9) in `ClipFlowAnalyzer.kt`, `MIN_SPEED_FOR_RELIABLE_BEARING_MPS` (1.0) in `OrientationTimeline.kt`, `AnalysisProperties.confirmationThreshold` (0.5), `ReportAnalysisJob.annotateAndStoreFrame`.
- **The fallback path never ingests `flow_observations`** (it has no world bearing) and never runs when `resolution is DirectionResolution.TwoWay`.
- **Commit after every task** with the exact message given.

---

## Task 1: `scale_trend` classifier in `tracking_bearing.py`

**Files:**
- Modify: `video-analysis/app/tracking_bearing.py`
- Test: `video-analysis/tests/test_bearing.py`

**Interfaces:**
- Consumes: `bbox_diagonal(bbox)` (existing), `MIN_OBSERVATIONS` (existing, 12), `MIN_SCALE_CHANGE_FRACTION` (existing, 0.15).
- Produces: `scale_trend(bboxes: Sequence[tuple[float, float, float, float]]) -> tuple[str, float]` — returns `("growing", fraction)`, `("shrinking", 0.0)`, or `("flat", 0.0)`. `fraction = (s3 - s1) / s1` where `s1`/`s3` are the mean bbox diagonal of the first/last third of the track.

- [ ] **Step 1: Write the failing tests**

Add to `video-analysis/tests/test_bearing.py`:

```python
from app.tracking_bearing import scale_trend


def _growing_bboxes(n=24, start=30.0, end=120.0):
    # square bboxes whose side grows linearly start -> end
    return [
        (0.0, 0.0, s, s)
        for s in (start + (end - start) * i / (n - 1) for i in range(n))
    ]


def test_scale_trend_growing_monotonic_over_threshold():
    trend, frac = scale_trend(_growing_bboxes(24, 30.0, 120.0))
    assert trend == "growing"
    assert frac == pytest.approx((120.0 - 30.0) / 30.0, rel=1e-6)


def test_scale_trend_shrinking_monotonic_over_threshold():
    trend, frac = scale_trend(_growing_bboxes(24, 120.0, 30.0))
    assert trend == "shrinking"
    assert frac == 0.0


def test_scale_trend_flat_when_growth_below_threshold():
    # grows only ~10% end-to-end, under MIN_SCALE_CHANGE_FRACTION (0.15)
    trend, frac = scale_trend(_growing_bboxes(24, 100.0, 110.0))
    assert trend == "flat"
    assert frac == 0.0


def test_scale_trend_flat_when_not_monotonic_across_thirds():
    # stable, stable, then one-frame spike -> middle third mean not > first third mean
    stable = [(0.0, 0.0, 40.0, 40.0)] * 22
    spike = [(0.0, 0.0, 400.0, 400.0)] * 2
    trend, frac = scale_trend(stable + spike)
    assert trend == "flat"


def test_scale_trend_flat_when_vehicle_passes_the_camera():
    # grows for the first half, then recedes -> grows-then-shrinks -> flat
    grow = _growing_bboxes(12, 30.0, 120.0)
    shrink = _growing_bboxes(12, 120.0, 30.0)
    trend, frac = scale_trend(grow + shrink)
    assert trend == "flat"


def test_scale_trend_flat_for_short_track():
    trend, frac = scale_trend(_growing_bboxes(8, 30.0, 200.0))
    assert trend == "flat"
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd video-analysis && .venv/Scripts/python.exe -m pytest tests/test_bearing.py -k scale_trend -v`
Expected: FAIL — `ImportError: cannot import name 'scale_trend'`

- [ ] **Step 3: Implement `scale_trend`**

In `video-analysis/app/tracking_bearing.py`, after `bbox_diagonal` (around line 38):

```python
def scale_trend(
    bboxes: Sequence[Tuple[float, float, float, float]],
) -> Tuple[str, float]:
    """Classifies a track's apparent-size change over time.

    Splits the track's bounding-box diagonals into three equal time-ordered
    segments (means s1, s2, s3) and returns:
    - ("growing", (s3 - s1) / s1) when s1 < s2 < s3 and the total growth
      clears MIN_SCALE_CHANGE_FRACTION - the vehicle is approaching the camera.
    - ("shrinking", 0.0) when s1 > s2 > s3 and the total shrink clears
      MIN_SCALE_CHANGE_FRACTION - the vehicle is receding.
    - ("flat", 0.0) otherwise: stable, jittering, changing by less than the
      threshold, non-monotonic (a one-frame size spike), or grows-then-shrinks
      (a vehicle that passes the camera). Also "flat" for a track with fewer
      than MIN_OBSERVATIONS frames - too brief to trust a trend.

    The monotonic-across-thirds test (rather than just first-vs-last) is what
    rejects a single blown-up detection box and a pass-the-camera track.
    """
    if len(bboxes) < MIN_OBSERVATIONS:
        return "flat", 0.0
    diagonals = [bbox_diagonal(b) for b in bboxes]
    k = len(diagonals) // 3
    if k == 0:
        return "flat", 0.0
    s1 = sum(diagonals[:k]) / k
    s2 = sum(diagonals[k : 2 * k]) / k
    s3 = sum(diagonals[2 * k :]) / (len(diagonals) - 2 * k)
    if s1 <= 0:
        return "flat", 0.0
    if s1 < s2 < s3 and (s3 - s1) / s1 >= MIN_SCALE_CHANGE_FRACTION:
        return "growing", (s3 - s1) / s1
    if s1 > s2 > s3 and (s1 - s3) / s1 >= MIN_SCALE_CHANGE_FRACTION:
        return "shrinking", 0.0
    return "flat", 0.0
```

- [ ] **Step 4: Run to verify they pass**

Run: `cd video-analysis && .venv/Scripts/python.exe -m pytest tests/test_bearing.py -v`
Expected: PASS (all, including the pre-existing cases)

- [ ] **Step 5: Commit**

```bash
git add video-analysis/app/tracking_bearing.py video-analysis/tests/test_bearing.py
git commit -m "feat(video-analysis): add scale_trend track-size classifier"
```

---

## Task 2: route the Fix-A bearing override through `scale_trend`

**Files:**
- Modify: `video-analysis/app/tracking_bearing.py` (`resolve_bearing`, the `lateral_displacement >= min_displacement_pixels` block, ~line 95)
- Test: `video-analysis/tests/test_bearing.py`

**Interfaces:**
- Consumes: `scale_trend` (Task 1).
- Produces: no new symbol — `resolve_bearing` now returns `(180.0, "scale")` for a track that has real lateral motion **and** `scale_trend == "growing"`, instead of the `"centroid"` bearing.

- [ ] **Step 1: Write the failing tests**

Add to `video-analysis/tests/test_bearing.py`:

```python
def test_resolve_bearing_growing_near_camera_track_returns_scale_180():
    # large lateral sweep AND a steadily growing bbox -> approaching, not "with flow"
    centroids = _linear_track((900.0, 1400.0), (200.0, 1900.0), steps=24)
    bboxes = _growing_bboxes(24, 40.0, 160.0)
    assert resolve_bearing(centroids, bboxes) == (180.0, "scale")


def test_resolve_bearing_large_lateral_shrinking_track_stays_centroid():
    centroids = _linear_track((900.0, 1400.0), (200.0, 1900.0), steps=24)
    bboxes = _growing_bboxes(24, 160.0, 40.0)  # shrinking
    result = resolve_bearing(centroids, bboxes)
    assert result is not None
    assert result[1] == "centroid"


def test_resolve_bearing_large_lateral_flat_bbox_stays_centroid():
    centroids = _linear_track((900.0, 1400.0), (200.0, 1900.0), steps=24)
    bboxes = [(0.0, 0.0, 80.0, 80.0)] * 24  # stable size
    result = resolve_bearing(centroids, bboxes)
    assert result is not None
    assert result[1] == "centroid"
```

- [ ] **Step 2: Run to verify the first fails**

Run: `cd video-analysis && .venv/Scripts/python.exe -m pytest tests/test_bearing.py -k "growing_near_camera or large_lateral" -v`
Expected: `test_resolve_bearing_growing_near_camera_track_returns_scale_180` FAILs (gets a `"centroid"` tuple); the other two already pass.

- [ ] **Step 3: Add the pre-check**

In `video-analysis/app/tracking_bearing.py`, replace the existing block:

```python
    if lateral_displacement >= min_displacement_pixels:
        return (math.degrees(math.atan2(dx, -dy)) % 360.0, "centroid")
```

with:

```python
    if lateral_displacement >= min_displacement_pixels:
        if scale_trend(bboxes if bboxes is not None else [])[0] == "growing":
            # A vehicle that both sweeps laterally AND grows steadily is passing
            # close to the camera on its way toward it - an approach, not motion
            # along the flow. See the 2026-08-30 stationary-approach-detection spec.
            return (180.0, "scale")
        return (math.degrees(math.atan2(dx, -dy)) % 360.0, "centroid")
```

- [ ] **Step 4: Run the full bearing suite**

Run: `cd video-analysis && .venv/Scripts/python.exe -m pytest tests/test_bearing.py -v`
Expected: PASS (all, including the pre-existing `resolve_bearing` / `compute_bearing_degrees` cases — they use `bboxes=None` or small stable boxes, so `scale_trend` returns `"flat"` and the `"centroid"` path is unchanged)

- [ ] **Step 5: Commit**

```bash
git add video-analysis/app/tracking_bearing.py video-analysis/tests/test_bearing.py
git commit -m "feat(video-analysis): resolve_bearing returns scale/180 for a growing near-camera track"
```

---

## Task 3: emit `scale_trend` / `scale_growth_fraction` on `VehicleResult`

**Files:**
- Modify: `video-analysis/app/schemas.py` (`VehicleResult`)
- Modify: `video-analysis/app/pipeline.py` (`_summarize_track`, ~line 122-159)
- Test: `video-analysis/tests/test_pipeline.py`

**Interfaces:**
- Consumes: `scale_trend` (Task 1).
- Produces: `VehicleResult.scale_trend: str` (default `"flat"`), `VehicleResult.scale_growth_fraction: float` (default `0.0`), populated from the track's full bbox list.

- [ ] **Step 1: Write the failing test**

Add to `video-analysis/tests/test_pipeline.py` (it already has `FakeDetector`, `FakePlateReader`, `_make_frame`, `_fake_settings`):

```python
def test_summarize_track_emits_scale_trend_growing_for_an_approaching_vehicle():
    frames = [
        _make_frame(track_id=1, frame_index=i, bbox=(0.0, 0.0, s, s))
        for i, s in enumerate(30.0 + 5.0 * i for i in range(24))
    ]
    pipeline = AnalysisPipeline(
        settings=_fake_settings(), detector=FakeDetector(frames), plate_reader=FakePlateReader()
    )
    result = pipeline.analyze("unused.mp4")
    v = result.vehicles[0]
    assert v.scale_trend == "growing"
    assert v.scale_growth_fraction > 0.15


def test_summarize_track_emits_flat_for_a_stable_size_vehicle():
    frames = [
        _make_frame(track_id=1, frame_index=i, bbox=(0.0, 0.0, 40.0, 40.0))
        for i in range(24)
    ]
    pipeline = AnalysisPipeline(
        settings=_fake_settings(), detector=FakeDetector(frames), plate_reader=FakePlateReader()
    )
    v = pipeline.analyze("unused.mp4").vehicles[0]
    assert v.scale_trend == "flat"
    assert v.scale_growth_fraction == 0.0
```

(If `_make_frame` in `test_pipeline.py` ignores its `bbox` centroid, also set a distinct centroid per frame so the track still qualifies; check the helper — it currently derives centroid from bbox, which is fine.)

- [ ] **Step 2: Run to verify it fails**

Run: `cd video-analysis && .venv/Scripts/python.exe -m pytest tests/test_pipeline.py -k scale_trend -v`
Expected: FAIL — `AttributeError: 'VehicleResult' object has no attribute 'scale_trend'`

- [ ] **Step 3: Add the schema fields**

In `video-analysis/app/schemas.py`, `VehicleResult`, after `track_midpoint_ms`:

```python
    # Apparent-size trend over the track (see tracking_bearing.scale_trend). "growing" =
    # the vehicle approached the camera, "shrinking" = it receded, "flat" = neither /
    # too brief. scale_growth_fraction is the fractional bbox-diagonal growth when
    # "growing", else 0.0. The Kotlin server's stationary-approach detection path
    # (ReportAnalysisJob) uses these; harmless to ignore otherwise.
    scale_trend: str = "flat"
    scale_growth_fraction: float = 0.0
```

- [ ] **Step 4: Populate in `_summarize_track`**

In `video-analysis/app/pipeline.py`, add the import at the top:

```python
from app.tracking_bearing import (
    MIN_DISPLACEMENT_PIXELS,
    bbox_diagonal,
    compute_displacement_pixels,
    compute_track_midpoint_ms,
    resolve_bearing,
    scale_trend,
)
```

In `_summarize_track`, after `bboxes = [f.bbox for f in frames_sorted]`:

```python
        trend, growth_fraction = scale_trend(bboxes)
```

and in the returned `VehicleResult(...)`, add:

```python
            scale_trend=trend,
            scale_growth_fraction=growth_fraction,
```

- [ ] **Step 5: Run pipeline + bearing suites**

Run: `cd video-analysis && .venv/Scripts/python.exe -m pytest tests/ -v`
Expected: PASS (all)

- [ ] **Step 6: Commit**

```bash
git add video-analysis/app/schemas.py video-analysis/app/pipeline.py video-analysis/tests/test_pipeline.py
git commit -m "feat(video-analysis): emit scale_trend and scale_growth_fraction on VehicleResult"
```

---

## Task 4: `OrientationTimeline.wasStationaryThroughout()`

**Files:**
- Modify: `server/src/main/kotlin/com/trafficwatch/server/geo/OrientationTimeline.kt`
- Test: `server/src/test/kotlin/com/trafficwatch/server/geo/OrientationTimelineTest.kt`

**Interfaces:**
- Consumes: `locationSamples` (existing constructor arg), `MIN_SPEED_FOR_RELIABLE_BEARING_MPS` (existing, 1.0).
- Produces: `OrientationTimeline.wasStationaryThroughout(): Boolean`.

- [ ] **Step 1: Write the failing tests**

Add to `OrientationTimelineTest.kt` (check its existing helpers for building `LocationSampleDto` lists; construct directly if there is none):

```kotlin
@Test
fun `wasStationaryThroughout is true when every location sample is at or below walking pace`() {
    val timeline = OrientationTimeline(
        locationSamples = listOf(
            locationSample(capturedAt = 0, speed = 0.0),
            locationSample(capturedAt = 1000, speed = 0.4),
            locationSample(capturedAt = 2000, speed = 0.9),
        ),
        rotationSamples = emptyList(),
    )
    assertThat(timeline.wasStationaryThroughout()).isTrue()
}

@Test
fun `wasStationaryThroughout is false when any location sample shows real motion`() {
    val timeline = OrientationTimeline(
        locationSamples = listOf(
            locationSample(capturedAt = 0, speed = 0.0),
            locationSample(capturedAt = 1000, speed = 3.0),
        ),
        rotationSamples = emptyList(),
    )
    assertThat(timeline.wasStationaryThroughout()).isFalse()
}

@Test
fun `wasStationaryThroughout is false when there are no location samples`() {
    val timeline = OrientationTimeline(locationSamples = emptyList(), rotationSamples = emptyList())
    assertThat(timeline.wasStationaryThroughout()).isFalse()
}
```

Add a `locationSample` helper to the test file if absent:

```kotlin
private fun locationSample(capturedAt: Long, speed: Double, bearing: Double = 0.0) =
    LocationSampleDto(
        latitude = 31.52, longitude = 74.35, accuracy = 5.0, altitude = 210.0,
        bearing = bearing, speed = speed, capturedAt = capturedAt,
    )
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd server && ./gradlew test --tests "com.trafficwatch.server.geo.OrientationTimelineTest"`
Expected: FAIL — `wasStationaryThroughout` unresolved reference

- [ ] **Step 3: Implement**

In `OrientationTimeline.kt`, add after `recordingSpeedMetersPerSecondAt`:

```kotlin
    /**
     * True only when location_samples exist AND every one reports a GPS speed at or below
     * [MIN_SPEED_FOR_RELIABLE_BEARING_MPS] (1.0 m/s, ~walking pace). Gates the
     * stationary-approach detection path in ReportAnalysisJob: a growing bounding box is
     * only safely attributable to the OTHER vehicle's motion when the recording vehicle
     * itself did not move for the whole clip. No location_samples -> cannot verify -> false.
     */
    fun wasStationaryThroughout(): Boolean =
        locationSamples.isNotEmpty() &&
            locationSamples.all { it.speed <= MIN_SPEED_FOR_RELIABLE_BEARING_MPS }
```

- [ ] **Step 4: Run to verify they pass**

Run: `cd server && ./gradlew test --tests "com.trafficwatch.server.geo.OrientationTimelineTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server/src/main/kotlin/com/trafficwatch/server/geo/OrientationTimeline.kt server/src/test/kotlin/com/trafficwatch/server/geo/OrientationTimelineTest.kt
git commit -m "feat(server): OrientationTimeline.wasStationaryThroughout"
```

---

## Task 5: `scaleTrend` / `scaleGrowthFraction` on `VehicleAnalysisResult`

**Files:**
- Modify: `server/src/main/kotlin/com/trafficwatch/server/videoanalysis/dto/VideoAnalysisDtos.kt` (`VehicleAnalysisResult`)
- Test: `server/src/test/kotlin/com/trafficwatch/server/videoanalysis/` (the existing client / DTO deserialization test — find it with `grep -rl VehicleAnalysisResult server/src/test`)

**Interfaces:**
- Produces: `VehicleAnalysisResult.scaleTrend: String` (default `"flat"`), `VehicleAnalysisResult.scaleGrowthFraction: Double` (default `0.0`). Jackson `SNAKE_CASE` maps `scale_trend` / `scale_growth_fraction`.

- [ ] **Step 1: Write the failing test**

In the DTO/client deserialization test, add a case that a response body with `"scale_trend": "growing", "scale_growth_fraction": 1.4` deserializes to `scaleTrend == "growing"` / `scaleGrowthFraction == 1.4`, and that a body omitting both yields `"flat"` / `0.0`. Match the file's existing style (it likely uses a raw JSON string + the configured `ObjectMapper`, or WireMock).

- [ ] **Step 2: Run to verify it fails**

Run: `cd server && ./gradlew test --tests "*VideoAnalysis*"`
Expected: FAIL — unresolved `scaleTrend`

- [ ] **Step 3: Add the fields**

In `VideoAnalysisDtos.kt`, `VehicleAnalysisResult`, after `trackMidpointMs`:

```kotlin
    // Apparent-size trend of the track from the video-analysis service (app/schemas.py):
    // "growing" (approached the camera), "shrinking" (receded), "flat" (neither / too
    // brief). Default "flat" / 0.0 for a response from a service version predating this
    // field. Consumed only by ReportAnalysisJob's stationary-approach detection path.
    val scaleTrend: String = "flat",
    val scaleGrowthFraction: Double = 0.0,
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd server && ./gradlew test --tests "*VideoAnalysis*"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server/src/main/kotlin/com/trafficwatch/server/videoanalysis/dto/VideoAnalysisDtos.kt server/src/test/
git commit -m "feat(server): deserialize scale_trend / scale_growth_fraction from video-analysis"
```

---

## Task 6: new `AnalysisProperties` constants + config

**Files:**
- Modify: `server/src/main/kotlin/com/trafficwatch/server/reports/AnalysisProperties.kt`
- Modify: `server/src/main/resources/application.yml` (the `app.analysis:` block)
- Test: none of its own — exercised by Task 7.

**Interfaces:**
- Produces: `AnalysisProperties.approachGrowthMin: Double` (0.8), `approachMinFrames: Int` (30), `approachMinDetection: Double` (0.5).

- [ ] **Step 1: Add the properties**

In `AnalysisProperties.kt`, add to the `data class` constructor:

```kotlin
    // Stationary-approach detection (ReportAnalysisJob.tryStationaryApproachDetection):
    // a "strong grower" - a wrong-way candidate - must have grown its bbox by at least
    // this fraction, over at least this many tracked frames, with at least this detection
    // confidence. Calibrated 2026-08-30 against five real reports (see the design spec's
    // Appendix): real violators grew 0.93-2.24; every non-violator grower topped out at 0.44.
    var approachGrowthMin: Double = 0.8,
    var approachMinFrames: Int = 30,
    var approachMinDetection: Double = 0.5,
```

- [ ] **Step 2: Mirror in `application.yml`**

In `server/src/main/resources/application.yml`, under `app: analysis:`, add:

```yaml
    approach-growth-min: 0.8
    approach-min-frames: 30
    approach-min-detection: 0.5
```

- [ ] **Step 3: Verify it still boots / binds**

Run: `cd server && ./gradlew compileKotlin test --tests "com.trafficwatch.server.reports.ReportAnalysisJobTest"`
Expected: PASS (no behaviour change yet)

- [ ] **Step 4: Commit**

```bash
git add server/src/main/kotlin/com/trafficwatch/server/reports/AnalysisProperties.kt server/src/main/resources/application.yml
git commit -m "feat(server): add approach-detection tunables to AnalysisProperties"
```

---

## Task 7: the stationary-approach fallback in `ReportAnalysisJob`

**Files:**
- Modify: `server/src/main/kotlin/com/trafficwatch/server/reports/ReportAnalysisJob.kt` (`determineOutcome` ~line 176-184; new private method; `EvidenceBreakdown` area ~line 413-424)
- Test: `server/src/test/kotlin/com/trafficwatch/server/reports/ReportAnalysisJobTest.kt`

**Interfaces:**
- Consumes: `VehicleAnalysisResult.scaleTrend` / `.scaleGrowthFraction` (Task 5), `OrientationTimeline.wasStationaryThroughout` (Task 4), `AnalysisProperties.approachGrowthMin` / `.approachMinFrames` / `.approachMinDetection` (Task 6), `annotateAndStoreFrame(vehicle, reportId)` (existing), `AnalysisProperties.confirmationThreshold` (existing), `MIN_TRACK_FRAMES`… note this constant is `private` in `ClipFlowAnalyzer.kt` — use the literal `9` with a comment, or add a shared `internal const val`. **Decision: use the literal `9` with a `// == ClipFlowAnalyzer.MIN_TRACK_FRAMES` comment**, matching how the codebase already duplicates that intent between Python and Kotlin.
- Produces: `AnalysisOutcome` (existing type) with `status = CONFIRMED` or the branch returns null.

- [ ] **Step 1: Write the failing tests**

Add to `ReportAnalysisJobTest.kt`. First extend the `vehicle(...)` helper with two params (keep defaults so existing callers are unaffected):

```kotlin
        scaleTrend: String = "flat",
        scaleGrowthFraction: Double = 0.0,
```

and pass them through to `VehicleAnalysisResult(... scaleTrend = scaleTrend, scaleGrowthFraction = scaleGrowthFraction)`.

Add a stationary-report helper and a JSON builder for location samples:

```kotlin
    private fun stationaryLocationSamplesJson(count: Int = 4): String =
        (0 until count).joinToString(prefix = "[", postfix = "]") { i ->
            """{"latitude":31.52,"longitude":74.35,"accuracy":5.0,"altitude":210.0,"bearing":0.0,"speed":0.0,"captured_at":${i * 1000}}"""
        }
```

Tests:

```kotlin
@Test
fun `stationary clip with a receding majority and a strong approaching vehicle is CONFIRMED via the approach path`() {
    val report = sampleReport(locationSamples = stationaryLocationSamplesJson())
    every {
        streetDirectionResolver.resolve(report.latitude, report.longitude, report.accuracy.toDouble())
    } returns DirectionResolution.OneWay("Khayaban-e-Jinnah", 190.0)
    // No vehicle opposes the OSM legal bearing on the bearing path -> that path REJECTS,
    // then the approach path runs. 4 receding + 1 strong grower.
    every { videoAnalysisClient.analyze(fakeVideoPath, any(), any()) } returns analysisResponse(
        listOf(
            vehicle(trackId = 1, bearingDegrees = 190.0, scaleTrend = "shrinking", trackFrameCount = 40),
            vehicle(trackId = 2, bearingDegrees = 190.0, scaleTrend = "shrinking", trackFrameCount = 40),
            vehicle(trackId = 3, bearingDegrees = 190.0, scaleTrend = "shrinking", trackFrameCount = 40),
            vehicle(trackId = 4, bearingDegrees = 190.0, scaleTrend = "shrinking", trackFrameCount = 40),
            vehicle(
                trackId = 5, bearingDegrees = 185.0, detectionConfidence = 0.9,
                plateText = "LEA-9999", plateConfidence = 0.7,
                scaleTrend = "growing", scaleGrowthFraction = 1.4, trackFrameCount = 60,
            ),
        ),
    )
    every { wrongWayFrameStorageService.store(any(), any()) } returns "frames/x.jpg"
    every { reportRepository.save(any()) } answers { firstArg() }

    job.applyOutcome(report)

    assertThat(report.status).isEqualTo(ReportStatus.CONFIRMED)
    assertThat(report.licensePlate).isEqualTo("LEA-9999")
    assertThat(report.wrongWayConfidence!!.toDouble()).isEqualTo(0.9)
    assertThat(report.analysisMessage).contains("approaching a stationary camera")
    assertThat(report.directionEvidence).contains("stationary_approach")
}

@Test
fun `approach path does not run on a two-way street`() {
    val report = sampleReport(locationSamples = stationaryLocationSamplesJson())
    every {
        streetDirectionResolver.resolve(report.latitude, report.longitude, report.accuracy.toDouble())
    } returns DirectionResolution.TwoWay("Khayaban-e-Jinnah")
    every { videoAnalysisClient.analyze(fakeVideoPath, any(), any()) } returns analysisResponse(
        listOf(
            vehicle(trackId = 1, scaleTrend = "shrinking", trackFrameCount = 40),
            vehicle(trackId = 2, scaleTrend = "shrinking", trackFrameCount = 40),
            vehicle(trackId = 3, scaleTrend = "shrinking", trackFrameCount = 40),
            vehicle(trackId = 5, scaleTrend = "growing", scaleGrowthFraction = 1.4, trackFrameCount = 60),
        ),
    )
    every { reportRepository.save(any()) } answers { firstArg() }

    job.applyOutcome(report)

    assertThat(report.status).isEqualTo(ReportStatus.REJECTED)
}

@Test
fun `approach path does not run when the camera was moving`() {
    val movingSamples = """[{"latitude":31.52,"longitude":74.35,"accuracy":5.0,"altitude":210.0,"bearing":0.0,"speed":3.0,"captured_at":0}]"""
    val report = sampleReport(locationSamples = movingSamples)
    every {
        streetDirectionResolver.resolve(report.latitude, report.longitude, report.accuracy.toDouble())
    } returns DirectionResolution.OneWay("Khayaban-e-Jinnah", 190.0)
    every { videoAnalysisClient.analyze(fakeVideoPath, any(), any()) } returns analysisResponse(
        listOf(
            vehicle(trackId = 1, bearingDegrees = 190.0, scaleTrend = "shrinking", trackFrameCount = 40),
            vehicle(trackId = 2, bearingDegrees = 190.0, scaleTrend = "shrinking", trackFrameCount = 40),
            vehicle(trackId = 3, bearingDegrees = 190.0, scaleTrend = "shrinking", trackFrameCount = 40),
            vehicle(trackId = 5, bearingDegrees = 185.0, scaleTrend = "growing", scaleGrowthFraction = 1.4, trackFrameCount = 60),
        ),
    )
    every { reportRepository.save(any()) } answers { firstArg() }

    job.applyOutcome(report)

    assertThat(report.status).isEqualTo(ReportStatus.REJECTED)
}

@Test
fun `approach path needs at least three receding vehicles`() {
    val report = sampleReport(locationSamples = stationaryLocationSamplesJson())
    every {
        streetDirectionResolver.resolve(report.latitude, report.longitude, report.accuracy.toDouble())
    } returns DirectionResolution.OneWay("Khayaban-e-Jinnah", 190.0)
    every { videoAnalysisClient.analyze(fakeVideoPath, any(), any()) } returns analysisResponse(
        listOf(
            vehicle(trackId = 1, bearingDegrees = 190.0, scaleTrend = "shrinking", trackFrameCount = 40),
            vehicle(trackId = 2, bearingDegrees = 190.0, scaleTrend = "shrinking", trackFrameCount = 40),
            vehicle(trackId = 5, bearingDegrees = 185.0, scaleTrend = "growing", scaleGrowthFraction = 1.4, trackFrameCount = 60),
        ),
    )
    every { reportRepository.save(any()) } answers { firstArg() }

    job.applyOutcome(report)

    assertThat(report.status).isEqualTo(ReportStatus.REJECTED)
}

@Test
fun `approach path needs receding to outnumber strong growers three to one`() {
    val report = sampleReport(locationSamples = stationaryLocationSamplesJson())
    every {
        streetDirectionResolver.resolve(report.latitude, report.longitude, report.accuracy.toDouble())
    } returns DirectionResolution.OneWay("Khayaban-e-Jinnah", 190.0)
    every { videoAnalysisClient.analyze(fakeVideoPath, any(), any()) } returns analysisResponse(
        listOf(
            vehicle(trackId = 1, bearingDegrees = 190.0, scaleTrend = "shrinking", trackFrameCount = 40),
            vehicle(trackId = 2, bearingDegrees = 190.0, scaleTrend = "shrinking", trackFrameCount = 40),
            vehicle(trackId = 3, bearingDegrees = 190.0, scaleTrend = "shrinking", trackFrameCount = 40),
            vehicle(trackId = 5, bearingDegrees = 185.0, scaleTrend = "growing", scaleGrowthFraction = 1.4, trackFrameCount = 60),
            vehicle(trackId = 6, bearingDegrees = 185.0, scaleTrend = "growing", scaleGrowthFraction = 1.4, trackFrameCount = 60),
        ),
    )
    every { reportRepository.save(any()) } answers { firstArg() }

    job.applyOutcome(report)

    assertThat(report.status).isEqualTo(ReportStatus.REJECTED) // 3 !>= 3 * 2
}

@Test
fun `approach path ignores a grower below the growth or frame thresholds`() {
    val report = sampleReport(locationSamples = stationaryLocationSamplesJson())
    every {
        streetDirectionResolver.resolve(report.latitude, report.longitude, report.accuracy.toDouble())
    } returns DirectionResolution.OneWay("Khayaban-e-Jinnah", 190.0)
    every { videoAnalysisClient.analyze(fakeVideoPath, any(), any()) } returns analysisResponse(
        listOf(
            vehicle(trackId = 1, bearingDegrees = 190.0, scaleTrend = "shrinking", trackFrameCount = 40),
            vehicle(trackId = 2, bearingDegrees = 190.0, scaleTrend = "shrinking", trackFrameCount = 40),
            vehicle(trackId = 3, bearingDegrees = 190.0, scaleTrend = "shrinking", trackFrameCount = 40),
            vehicle(trackId = 5, bearingDegrees = 185.0, scaleTrend = "growing", scaleGrowthFraction = 0.5, trackFrameCount = 60),
            vehicle(trackId = 6, bearingDegrees = 185.0, scaleTrend = "growing", scaleGrowthFraction = 1.4, trackFrameCount = 20),
        ),
    )
    every { reportRepository.save(any()) } answers { firstArg() }

    job.applyOutcome(report)

    assertThat(report.status).isEqualTo(ReportStatus.REJECTED)
}

@Test
fun `approach path rejects when the strong grower's detection confidence is below the confirmation threshold`() {
    val report = sampleReport(locationSamples = stationaryLocationSamplesJson())
    every {
        streetDirectionResolver.resolve(report.latitude, report.longitude, report.accuracy.toDouble())
    } returns DirectionResolution.OneWay("Khayaban-e-Jinnah", 190.0)
    every { videoAnalysisClient.analyze(fakeVideoPath, any(), any()) } returns analysisResponse(
        listOf(
            vehicle(trackId = 1, bearingDegrees = 190.0, scaleTrend = "shrinking", trackFrameCount = 40),
            vehicle(trackId = 2, bearingDegrees = 190.0, scaleTrend = "shrinking", trackFrameCount = 40),
            vehicle(trackId = 3, bearingDegrees = 190.0, scaleTrend = "shrinking", trackFrameCount = 40),
            vehicle(trackId = 5, bearingDegrees = 185.0, detectionConfidence = 0.45, scaleTrend = "growing", scaleGrowthFraction = 1.4, trackFrameCount = 60),
        ),
    )
    every { reportRepository.save(any()) } answers { firstArg() }

    job.applyOutcome(report)

    assertThat(report.status).isEqualTo(ReportStatus.REJECTED)
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd server && ./gradlew test --tests "com.trafficwatch.server.reports.ReportAnalysisJobTest"`
Expected: the 7 new tests FAIL (report stays `REJECTED` / message differs / no `stationary_approach` in evidence); all pre-existing tests still PASS.

- [ ] **Step 3: Add the fallback branch**

In `ReportAnalysisJob.kt` `determineOutcome`, change the tail (currently ~line 176-184):

```kotlin
        val outcome = buildOutcome(
            report, evaluation, osmEvidence, historyEvidence, flowVehicles, streetName,
            orientationMissing = !hasOrientationSource,
            orientationUnresolvedForAllVehicles = orientationUnresolvedForAllVehicles,
        )

        ingestObservations(report, flowVehicles, evaluation.best?.flowVehicle)

        if (outcome.status == ReportStatus.REJECTED && resolution !is DirectionResolution.TwoWay) {
            tryStationaryApproachDetection(report, analysis.vehicles, orientationTimeline, streetName)
                ?.let { return it }
        }

        return outcome
```

Add the private method (near `buildOutcome`):

```kotlin
    /**
     * Additive fallback (see the 2026-08-30 stationary-approach-detection spec): on a
     * verified-stationary camera pointed down a one-way (or unknown, non-two-way) street,
     * a vehicle whose bounding box grew sustainedly while at least three others receded is
     * driving the wrong way - a signal that needs no compass or OSM legal bearing. Only
     * ever upgrades an already-REJECTED outcome to CONFIRMED; returns null to leave the
     * REJECTED outcome untouched.
     */
    private fun tryStationaryApproachDetection(
        report: Report,
        vehicles: List<VehicleAnalysisResult>,
        orientationTimeline: OrientationTimeline,
        streetName: String?,
    ): AnalysisOutcome? {
        if (!orientationTimeline.wasStationaryThroughout()) return null

        val minTrackFrames = 9 // == ClipFlowAnalyzer.MIN_TRACK_FRAMES
        val shrinking = vehicles.count {
            it.scaleTrend == "shrinking" && (it.trackFrameCount ?: 0) >= minTrackFrames
        }
        if (shrinking < 3) return null

        val strongGrowers = vehicles.filter {
            it.scaleTrend == "growing" &&
                it.scaleGrowthFraction >= analysisProperties.approachGrowthMin &&
                (it.trackFrameCount ?: 0) >= analysisProperties.approachMinFrames &&
                it.detectionConfidence >= analysisProperties.approachMinDetection
        }
        if (strongGrowers.isEmpty()) return null
        if (shrinking < 3 * strongGrowers.size) return null

        val best = strongGrowers.maxByOrNull { it.detectionConfidence } ?: return null
        if (best.detectionConfidence < analysisProperties.confirmationThreshold) return null

        return AnalysisOutcome(
            status = ReportStatus.CONFIRMED,
            licensePlate = best.plateText,
            confidence = best.plateConfidence?.let { BigDecimal.valueOf(it) },
            message = "Wrong-way vehicle approaching a stationary camera on ${streetName ?: "this street"}",
            streetName = streetName,
            wrongWayConfidence = BigDecimal.valueOf(best.detectionConfidence),
            wrongWayFramePath = annotateAndStoreFrame(
                best, requireNotNull(report.id) { "Report must have a generated id before analysis" },
            ),
            directionEvidenceJson = approachBreakdownJson(best, shrinking, strongGrowers.size),
        )
    }

    private fun approachBreakdownJson(best: VehicleAnalysisResult, recedingCount: Int, strongGrowerCount: Int): String? = try {
        objectMapper.writeValueAsString(
            ApproachEvidenceBreakdown(
                recedingCount = recedingCount,
                strongGrowerCount = strongGrowerCount,
                growthFraction = best.scaleGrowthFraction,
                trackFrames = best.trackFrameCount ?: 0,
                detectionConfidence = best.detectionConfidence,
                confirmationThreshold = analysisProperties.confirmationThreshold,
            ),
        )
    } catch (ex: Exception) {
        logger.warn("ReportAnalysisJob: failed to serialize approach evidence breakdown", ex)
        null
    }
```

Add the breakdown data class next to `EvidenceBreakdown` (~line 413):

```kotlin
/**
 * Serialized (snake_case) into reports.direction_evidence when a report is confirmed by
 * the stationary-approach path instead of the bearing path. The `method` field is the
 * discriminator that tells a reader which shape this is.
 */
internal data class ApproachEvidenceBreakdown(
    val method: String = "stationary_approach",
    val recedingCount: Int,
    val strongGrowerCount: Int,
    val growthFraction: Double,
    val trackFrames: Int,
    val detectionConfidence: Double,
    val confirmationThreshold: Double,
)
```

Ensure the imports at the top of `ReportAnalysisJob.kt` cover `DirectionResolution` (already imported via `com.trafficwatch.server.geo`) — verify `import com.trafficwatch.server.geo.DirectionResolution` is present; the file already references `DirectionResolution.TwoWay` / `.OneWay` in `determineOutcome`, so it is.

- [ ] **Step 4: Run the full `ReportAnalysisJobTest`**

Run: `cd server && ./gradlew test --tests "com.trafficwatch.server.reports.ReportAnalysisJobTest"`
Expected: PASS — the 7 new tests and **every pre-existing test unchanged**. If any pre-existing test flipped to `CONFIRMED`: it must have `location_samples` all-stationary + a `"growing"` vehicle; the `vehicle(...)` default is `scaleTrend = "flat"`, so this should not happen — investigate before proceeding.

- [ ] **Step 5: Run the whole server suite**

Run: `cd server && ./gradlew test`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add server/src/main/kotlin/com/trafficwatch/server/reports/ReportAnalysisJob.kt server/src/test/kotlin/com/trafficwatch/server/reports/ReportAnalysisJobTest.kt
git commit -m "feat(server): stationary-approach fallback in ReportAnalysisJob"
```

---

## Task 8: render the new evidence shape on the report detail screen

**Files:**
- Read first: `app/src/main/java/com/trafficwatch/app/feature/history/ReportDetailScreen.kt`
- Modify (only if it formats known evidence keys): same file
- Test: `app/src/test/java/com/trafficwatch/app/feature/history/` if a matching test exists

**Interfaces:**
- Consumes: `ReportStatusResponse.evidenceBreakdown: JsonObject?` (unchanged DTO), now sometimes carrying `{"method": "stationary_approach", ...}`.

- [ ] **Step 1: Read the screen's current handling**

Run: `grep -n "evidenceBreakdown\|evidence_breakdown\|CLIP_CONSENSUS\|sources\|finalScore\|final_score" app/src/main/java/com/trafficwatch/app/feature/history/ReportDetailScreen.kt`

Determine: does it iterate a known `sources` array / read specific keys (then a missing-key path could NPE or show blanks), or does it dump the object generically?

- [ ] **Step 2a: If it degrades gracefully already**

No code change. Add a one-line comment where `evidenceBreakdown` is consumed:

```kotlin
// evidence_breakdown may also be the {"method":"stationary_approach", ...} shape - see
// server ApproachEvidenceBreakdown. Rendered generically here.
```

Commit:

```bash
git add app/src/main/java/com/trafficwatch/app/feature/history/ReportDetailScreen.kt
git commit -m "docs(app): note the stationary_approach evidence_breakdown shape"
```

- [ ] **Step 2b: If it reads bearing-path keys unconditionally**

Add a branch at the top of the breakdown renderer:

```kotlin
if (evidenceBreakdown?.get("method")?.asString == "stationary_approach") {
    ApproachEvidenceSection(evidenceBreakdown)
    return
}
```

and a minimal composable that shows `receding_count`, `strong_grower_count`, `growth_fraction`, `track_frames`, `detection_confidence`, `confirmation_threshold` as labeled rows — mirroring the existing bearing-path section's layout. Write/extend a screen test asserting both shapes render without crashing.

Commit:

```bash
git add app/src/main/java/com/trafficwatch/app/feature/history/ReportDetailScreen.kt app/src/test/
git commit -m "feat(app): render the stationary_approach evidence breakdown"
```

---

## Task 9: end-to-end integration test

**Files:**
- Modify: `server/src/test/kotlin/com/trafficwatch/server/reports/ReportAnalysisIntegrationTest.kt`

**Interfaces:**
- Consumes: everything above, wired through the real async job path.

- [ ] **Step 1: Read the existing integration test**

Run: `grep -n "fun \`\|analyze\|OneWay\|wireMock\|stubFor\|scaleTrend\|CONFIRMED" server/src/test/kotlin/com/trafficwatch/server/reports/ReportAnalysisIntegrationTest.kt`

Learn how it stubs the video-analysis service response and OSM, and how it asserts the persisted `Report`.

- [ ] **Step 2: Add a failing test**

A report with `location_samples` all speed 0, OSM stubbed `OneWay`, and a video-analysis stub response whose vehicles include 4 with `"scale_trend": "shrinking"` (≥ 30 frames) and one with `"scale_trend": "growing"`, `"scale_growth_fraction": 1.4`, `"track_frame_count": 60`, `"detection_confidence": 0.9`, and **no vehicle that the bearing path would confirm**. Assert the persisted report is `CONFIRMED`, `analysis_message` contains `"approaching a stationary camera"`, and `direction_evidence` contains `"stationary_approach"`.

- [ ] **Step 3: Run it**

Run: `cd server && ./gradlew test --tests "com.trafficwatch.server.reports.ReportAnalysisIntegrationTest"`
Expected: PASS (Tasks 4-7 already make it work; this proves the async plumbing + JSON round-trip)

- [ ] **Step 4: Full suite**

Run: `cd server && ./gradlew test` and `cd video-analysis && .venv/Scripts/python.exe -m pytest tests/ -q`
Expected: PASS both

- [ ] **Step 5: Commit**

```bash
git add server/src/test/kotlin/com/trafficwatch/server/reports/ReportAnalysisIntegrationTest.kt
git commit -m "test(server): end-to-end stationary-approach confirmation"
```

---

## Task 10: production replay + backlog / spec bookkeeping

**Files:**
- Modify: `docs/improvements-backlog.md`
- Read: `docs/superpowers/specs/2026-08-30-stationary-approach-detection-design.md` (Production replay section)

- [ ] **Step 1: Replay the pipeline against the five report videos**

With the source videos pulled from the prod `trafficwatch-videos` volume (see the prod-VPS-access memory) into a scratch dir, run the updated `video-analysis` pipeline and check `scale_trend` / `scale_growth_fraction` per vehicle:

Run (adapt paths): `cd video-analysis && API_KEY=x .venv/Scripts/python.exe -c "from app.config import Settings; from app.detection import VehicleDetector; from app.ocr import PlateReader; from app.pipeline import AnalysisPipeline; import json; r=AnalysisPipeline(Settings(), VehicleDetector(Settings()), PlateReader()).analyze('<video>'); print(json.dumps([{'id':v.track_id,'trend':v.scale_trend,'growth':round(v.scale_growth_fraction,2),'frames':v.track_frame_count,'det':round(v.detection_confidence,2)} for v in r.vehicles], indent=1))"`

Expected, matching the spec Appendix:
- `759cd`: moto 1000001 `growing` ~1.5 / 72 fr; ≥ 3 `shrinking`.
- `24908`: moto 1000027 `growing` ~2.2 / 80 fr; ≥ 3 `shrinking`.
- `a5275`: moto 1000016 `growing` ~0.9 / 34 fr; 3 `shrinking`.
- `50bcc6`: no `growing` track clearing 0.8 / 30 fr.
- `71f78`: has a `growing` moto but the report's `location_samples` include speed 1.19 -> `wasStationaryThroughout` false.

- [ ] **Step 2: Trace the Kotlin outcome**

For `759cd` / `24908` / `a5275`, either add a temporary `ReportAnalysisJobTest` case fed the replayed vehicle list (stub OSM `OneWay`, `location_samples` all 0) and assert `CONFIRMED`, or hand-walk `tryStationaryApproachDetection`. Delete any temporary test after confirming. Record the resulting `wrongWayConfidence` for each.

- [ ] **Step 3: Update the backlog**

In `docs/improvements-backlog.md`, "Vehicle detection / tracking":
- Mark the `[HIGH]` oncoming-motorcycle entry as **addressed by** `docs/superpowers/plans/2026-08-30-stationary-approach-detection.md` for `759cd` / `24908` / `a5275`; note `50bcc6` (violator passes camera) and `71f78` (moving camera) remain open under the future "B" design.
- Add a short entry: **track fragmentation for very-close crossing motorcycles** still bites `a5275` (its rider split into an 8-frame + a 34-frame track); the approach path confirms it from the 34-frame fragment, but the fragmentation is its own ByteTrack-continuity gap.

- [ ] **Step 4: Commit**

```bash
git add docs/improvements-backlog.md
git commit -m "docs: record stationary-approach detection outcome in the backlog"
```

---

## Self-Review

**1. Spec coverage:**
- Design §1 (`scale_trend` + `scale_growth_fraction`) → Tasks 1, 3, 5.
- Design §1 (Fix-A override folded in) → Task 2.
- Design §2 (`wasStationaryThroughout`) → Task 4.
- Design §3 (fallback branch, gates 1-7) → Task 7.
- Design §3 (new `AnalysisProperties` constants) → Task 6.
- Design §4 (`ApproachEvidenceBreakdown` JSON, `ReportDetailScreen`) → Tasks 7, 8.
- Design §5 (Fix A superseded) → header note already committed in `6db2a6d`; no task needed.
- Design §6 (B future work) → out of scope, no task.
- Design "Testing" → Tasks 1-9.
- Design "Production replay" → Task 10.
- Design Appendix numbers → Task 6 constants, Task 10 verification.

**2. Placeholder scan:** Task 5 and Task 8 leave the exact test/edit to on-the-spot inspection because the target file's shape must be read first (a DTO test whose name/style is unknown; a Compose screen whose rendering strategy is unknown). Both give the concrete assertion to make and the exact fields — acceptable, but the executor must open the file before writing. Task 8 has explicit 2a/2b branches so neither outcome is "figure it out".

**3. Type consistency:** `scale_trend`/`scaleTrend` and `scale_growth_fraction`/`scaleGrowthFraction` consistent across Tasks 1/3 (Python) and 5/7 (Kotlin). `ApproachEvidenceBreakdown` fields (Task 7) match the JSON keys asserted in Tasks 7/9 (`stationary_approach`, `receding_count`, etc. via SNAKE_CASE). `tryStationaryApproachDetection` signature stable between the call site and the definition in Task 7. `wasStationaryThroughout` identical in Tasks 4 and 7.
