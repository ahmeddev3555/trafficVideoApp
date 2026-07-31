# Video-Inferred Legal Direction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let reports confirm wrong-way violations even where OSM has no `oneway` tag, by inferring legal direction from the traffic in the clip (corridor flow consensus), accumulating those observations into a learned per-location database, and fusing all direction evidence with confidence scoring.

**Architecture:** Python owns frame-space geometry only (corridor clustering over the track trajectories it already holds); Kotlin owns all statistics, fusion, persistence, and decisions. A new `DirectionEvidenceResolver` fuses OSM tag / clip consensus / learned history into `(legalBearing, directionConfidence)` or "insufficient"; `ReportAnalysisJob`'s `Unknown`/`NotFound`/`LookupFailed` branches fall through to it instead of terminally rejecting. Every analysis stores a full evidence breakdown (JSONB) that the Android debug build renders.

**Tech Stack:** Python 3.11 (pydantic, numpy already present), Kotlin/Spring Boot 3.3 (JPA/Hibernate 6.5, Flyway, Jackson), Android (Gson, Room, Compose).

## Global Constraints

- Final formula, exactly: `wrongWayConfidence = directionConfidence × candidateQuality × detectionConfidence × bearingMatchScore`; CONFIRMED requires `>= confirmationThreshold` (default 0.5).
- `candidateQuality = trackQuality × corridorCohesion`; `trackQuality = min(trackFrameCount/5, 1) × min(displacementPixels/(0.05 × frameDiagonal), 1)`.
- Clip evidence: `clipConfidence = (n/(n+2)) × R × meanCohesion` over the candidate corridor's consensus members (candidate excluded).
- History evidence: `historyConfidence = (obsCount/(obsCount+5)) × R_hist`; qualifies only when obsCount >= 5 AND distinct reporters >= 3 AND R_hist >= 0.8.
- Fusion: drop sources with confidence < 0.2 (`weak-evidence-floor`); survivors agree iff bearings differ <= 45° (`agreement-tolerance-degrees`); all agree → noisy-OR `1 − ∏(1−cᵢ)` + confidence-weighted circular mean; ANY disagreement → insufficient (conflict). OSM `oneway` tag = confidence 1.0.
- Corridor consensus requires mean resultant length `R >= 0.6` (`consensus-min-resultant-length`); quality floor for consensus membership: `trackFrameCount >= 3` AND `displacementPixels >= 0.05 × frameDiagonal`.
- Candidate gates: a candidate moving WITH its own corridor's consensus (angular diff <= 45°) is NEVER a violator; a violator moves against its corridor's consensus, or against the fused bearing when alone in its corridor.
- Ingestion: one `flow_observations` row per corridor per report where consensus (excluding the evaluated candidate) has >= 2 members and R >= 0.6; ingested after EVERY analysis regardless of outcome; write failures logged, never affecting the report.
- Exact rejection messages: `"Legal traffic direction could not be established for this street"`, `"Conflicting direction evidence for this street"`, `"Possible wrong-way vehicle detected, but confidence was too low to confirm"`. Existing messages (compass, two-way, video-service-down, no-vehicles-against) unchanged.
- OSM `TwoWay` stays terminal. `Unknown`/`NotFound`/`LookupFailed` fall through with the OSM source absent.
- Python stays stateless and compass-blind: new wire fields are raw frame-space facts only.
- No new `ReportStatus` values. Android release UI unchanged; breakdown card is `BuildConfig.DEBUG` only.
- Python commands MUST use `video-analysis/.venv/Scripts/python.exe` (never bare `python`/`pytest` — system Python is 3.14 and incompatible).
- Server/Android build commands need `JAVA_HOME` = `C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot`.

---

### Task 1: Python — corridor clustering module

**Files:**
- Create: `video-analysis/app/corridors.py`
- Test: `video-analysis/tests/test_corridors.py`

**Interfaces:**
- Produces: `path_distance(a: Sequence[Point], b: Sequence[Point]) -> float`; `cluster_tracks(paths: dict[int, Sequence[Point]], threshold_px: float) -> dict[int, int]` (track_id → corridor_id, corridor ids are 0..k−1 ordered by smallest member track_id); `corridor_cohesion(track_id: int, paths, assignments, threshold_px) -> float`. Consumed by Task 2.

- [ ] **Step 1: Write the failing tests**

```python
# video-analysis/tests/test_corridors.py
from __future__ import annotations

from app.corridors import cluster_tracks, corridor_cohesion, path_distance


def _line(x: float, y_start: float, y_end: float, n: int = 10) -> list[tuple[float, float]]:
    """Vertical path at fixed x from y_start to y_end with n points."""
    step = (y_end - y_start) / (n - 1)
    return [(x, y_start + i * step) for i in range(n)]


def test_path_distance_is_zero_for_identical_paths():
    a = _line(50.0, 0.0, 100.0)
    assert path_distance(a, a) == 0.0


def test_path_distance_is_symmetric():
    a = _line(50.0, 0.0, 100.0)
    b = _line(60.0, 0.0, 100.0)
    assert abs(path_distance(a, b) - path_distance(b, a)) < 1e-9


def test_same_lane_paths_cluster_together():
    paths = {1: _line(50.0, 0.0, 100.0), 2: _line(52.0, 0.0, 100.0)}
    assignments = cluster_tracks(paths, threshold_px=10.0)
    assert assignments[1] == assignments[2]


def test_reversed_path_joins_same_corridor():
    # A wrong-way vehicle drives the same corridor in reverse - point-set
    # comparison must ignore travel direction entirely.
    paths = {1: _line(50.0, 0.0, 100.0), 2: _line(51.0, 100.0, 0.0)}
    assignments = cluster_tracks(paths, threshold_px=10.0)
    assert assignments[1] == assignments[2]


def test_separated_streams_form_distinct_corridors():
    paths = {1: _line(50.0, 0.0, 100.0), 2: _line(300.0, 0.0, 100.0)}
    assignments = cluster_tracks(paths, threshold_px=10.0)
    assert assignments[1] != assignments[2]


def test_corridor_ids_are_dense_and_ordered_by_smallest_track_id():
    paths = {
        7: _line(300.0, 0.0, 100.0),
        3: _line(50.0, 0.0, 100.0),
        9: _line(52.0, 0.0, 100.0),
    }
    assignments = cluster_tracks(paths, threshold_px=10.0)
    # Track 3's cluster contains the smallest track id overall -> corridor 0.
    assert assignments[3] == 0
    assert assignments[9] == 0
    assert assignments[7] == 1


def test_single_member_corridor_has_cohesion_one():
    paths = {1: _line(50.0, 0.0, 100.0)}
    assignments = cluster_tracks(paths, threshold_px=10.0)
    assert corridor_cohesion(1, paths, assignments, threshold_px=10.0) == 1.0


def test_cohesion_decreases_with_distance_from_corridor_mates():
    paths = {
        1: _line(50.0, 0.0, 100.0),
        2: _line(51.0, 0.0, 100.0),
        3: _line(58.0, 0.0, 100.0),  # same cluster via single-linkage chain, but farther out
    }
    assignments = cluster_tracks(paths, threshold_px=10.0)
    assert assignments[1] == assignments[3]
    assert corridor_cohesion(2, paths, assignments, 10.0) > corridor_cohesion(3, paths, assignments, 10.0)


def test_cohesion_is_clamped_to_zero_floor():
    # Chained single-linkage can include a member whose mean distance to the
    # others exceeds the threshold; cohesion must clamp at 0, never negative.
    paths = {
        1: _line(50.0, 0.0, 100.0),
        2: _line(59.0, 0.0, 100.0),
        3: _line(68.0, 0.0, 100.0),
    }
    assignments = cluster_tracks(paths, threshold_px=10.0)
    assert assignments[1] == assignments[3]
    assert corridor_cohesion(3, paths, assignments, 10.0) >= 0.0
```

- [ ] **Step 2: Run tests to verify they fail**

Run (from `video-analysis/`): `.venv/Scripts/python.exe -m pytest tests/test_corridors.py -v`
Expected: FAIL / collection error — `app.corridors` does not exist.

- [ ] **Step 3: Implement the module**

```python
# video-analysis/app/corridors.py
from __future__ import annotations

import math
from typing import Sequence, Tuple

Point = Tuple[float, float]


def path_distance(a: Sequence[Point], b: Sequence[Point]) -> float:
    """Symmetric mean nearest-point distance between two paths (Chamfer-style).

    Paths are compared as point SETS - travel direction is deliberately ignored,
    so a wrong-way vehicle lands in the same corridor as the oncoming traffic it
    opposes. O(len(a) * len(b)); track lengths are small (tens of points).
    """

    def mean_nearest(src: Sequence[Point], dst: Sequence[Point]) -> float:
        total = 0.0
        for (sx, sy) in src:
            total += min(math.hypot(sx - dx, sy - dy) for (dx, dy) in dst)
        return total / len(src)

    return (mean_nearest(a, b) + mean_nearest(b, a)) / 2.0


def cluster_tracks(paths: dict[int, Sequence[Point]], threshold_px: float) -> dict[int, int]:
    """Single-linkage agglomerative clustering: tracks whose paths run within
    `threshold_px` of each other (directly or through a chain) share a corridor.

    Returns track_id -> corridor_id, with corridor ids renumbered 0..k-1 in
    order of each cluster's smallest track_id (deterministic across runs).
    """
    track_ids = sorted(paths.keys())
    parent: dict[int, int] = {tid: tid for tid in track_ids}

    def find(tid: int) -> int:
        while parent[tid] != tid:
            parent[tid] = parent[parent[tid]]
            tid = parent[tid]
        return tid

    def union(a: int, b: int) -> None:
        ra, rb = find(a), find(b)
        if ra != rb:
            # Root at the smaller id so cluster roots are stable/deterministic.
            parent[max(ra, rb)] = min(ra, rb)

    for i, tid_a in enumerate(track_ids):
        for tid_b in track_ids[i + 1:]:
            if path_distance(paths[tid_a], paths[tid_b]) <= threshold_px:
                union(tid_a, tid_b)

    roots_in_order = sorted({find(tid) for tid in track_ids})
    corridor_of_root = {root: i for i, root in enumerate(roots_in_order)}
    return {tid: corridor_of_root[find(tid)] for tid in track_ids}


def corridor_cohesion(
    track_id: int,
    paths: dict[int, Sequence[Point]],
    assignments: dict[int, int],
    threshold_px: float,
) -> float:
    """1 - (mean path distance to the corridor's other members / threshold),
    clamped to [0, 1]. Single-member corridors get 1.0 by definition (harmless:
    their consensus size is 1 downstream, so this can never inflate a score).
    """
    corridor_id = assignments[track_id]
    others = [tid for tid, cid in assignments.items() if cid == corridor_id and tid != track_id]
    if not others:
        return 1.0

    mean_distance = sum(path_distance(paths[track_id], paths[o]) for o in others) / len(others)
    return max(0.0, min(1.0, 1.0 - mean_distance / threshold_px))
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.venv/Scripts/python.exe -m pytest tests/test_corridors.py -v`
Expected: all PASS.

- [ ] **Step 5: Run the full Python suite** (`.venv/Scripts/python.exe -m pytest -v`) — expected: all pass, nothing else touched.

- [ ] **Step 6: Commit**

```bash
git add video-analysis/app/corridors.py video-analysis/tests/test_corridors.py
git commit -m "feat(video-analysis): direction-agnostic corridor clustering for track paths"
```

---

### Task 2: Python — wire corridors + track facts + frame dims into the pipeline and API

**Files:**
- Modify: `video-analysis/app/config.py`
- Modify: `video-analysis/app/schemas.py`
- Modify: `video-analysis/app/pipeline.py`
- Modify: `video-analysis/app/main.py`
- Test: `video-analysis/tests/test_pipeline.py`

**Interfaces:**
- Consumes: Task 1's `cluster_tracks` / `corridor_cohesion`.
- Produces (wire, consumed by Task 4's Kotlin DTOs): `VehicleResult` gains `corridor_id: int`, `corridor_cohesion: float`, `track_frame_count: int`, `displacement_pixels: float`; `AnalyzeResponse` gains `frame_width: int`, `frame_height: int`. `AnalysisPipeline.analyze` now returns `AnalyzeResponse` (was `list[VehicleResult]`).

- [ ] **Step 1: Add the clustering threshold setting**

In `video-analysis/app/config.py`, add after `plate_confidence_floor`:

```python
    # Corridor clustering: two tracks share a corridor when their paths run within
    # this fraction of the frame diagonal of each other (see app/corridors.py).
    corridor_cluster_threshold_fraction: float = 0.05
```

- [ ] **Step 2: Extend the schemas**

In `video-analysis/app/schemas.py`, add to `VehicleResult` after `frame_jpeg_base64`:

```python
    # Corridor assignment: tracks whose paths trace the same physical corridor of
    # the frame share a corridor_id (direction-agnostic - see app/corridors.py).
    # Raw frame-space facts only; all consensus/flow judgment is the Kotlin
    # server's job.
    corridor_id: int = 0
    corridor_cohesion: float = 1.0
    track_frame_count: int = 0
    displacement_pixels: float = 0.0
```

And change `AnalyzeResponse` to:

```python
class AnalyzeResponse(BaseModel):
    vehicles: list[VehicleResult] = Field(default_factory=list)
    # Source video frame dimensions in pixels - lets the server normalize
    # displacement_pixels by frame diagonal. 0 x 0 when the video had no frames.
    frame_width: int = 0
    frame_height: int = 0
```

- [ ] **Step 3: Update the failing pipeline test first**

In `video-analysis/tests/test_pipeline.py`, update the existing test's result handling (the `analyze` return type changes) and add corridor/dimension assertions. Replace the body of `test_summarize_track_attaches_bounding_box_and_frame_from_the_largest_bbox_frame` from `results = pipeline.analyze("unused.mp4")` down with:

```python
    response = pipeline.analyze("unused.mp4")

    assert response.frame_width == 100
    assert response.frame_height == 100
    assert len(response.vehicles) == 1
    vehicle = response.vehicles[0]
    assert vehicle.bounding_box is not None
    assert vehicle.bounding_box.x1 == 5.0
    assert vehicle.bounding_box.y1 == 5.0
    assert vehicle.bounding_box.x2 == 30.0
    assert vehicle.bounding_box.y2 == 30.0
    assert vehicle.frame_jpeg_base64 is not None
    assert len(vehicle.frame_jpeg_base64) > 0
    assert vehicle.track_frame_count == 2
    assert vehicle.displacement_pixels > 0.0
```

Then append two new tests:

```python
def test_same_lane_tracks_share_a_corridor_and_opposite_lane_does_not():
    frames = []
    # Tracks 1 and 2: near-identical vertical paths around x=20.
    for i in range(6):
        frames.append(_make_frame(track_id=1, frame_index=i, bbox=(15.0, 10.0 * i, 25.0, 10.0 * i + 10.0)))
        frames.append(_make_frame(track_id=2, frame_index=i, bbox=(17.0, 10.0 * i, 27.0, 10.0 * i + 10.0)))
    # Track 3: vertical path far away around x=80 (distance > 5% of the 100x100
    # frame's diagonal, ~7.07px threshold).
    for i in range(6):
        frames.append(_make_frame(track_id=3, frame_index=i, bbox=(75.0, 10.0 * i, 85.0, 10.0 * i + 10.0)))

    pipeline = AnalysisPipeline(
        settings=_fake_settings(), detector=FakeDetector(frames), plate_reader=FakePlateReader()
    )
    response = pipeline.analyze("unused.mp4")

    by_track = {v.track_id: v for v in response.vehicles}
    assert by_track[1].corridor_id == by_track[2].corridor_id
    assert by_track[3].corridor_id != by_track[1].corridor_id
    assert 0.0 <= by_track[1].corridor_cohesion <= 1.0


def test_empty_video_returns_empty_response_with_zero_dimensions():
    pipeline = AnalysisPipeline(
        settings=_fake_settings(), detector=FakeDetector([]), plate_reader=FakePlateReader()
    )
    response = pipeline.analyze("unused.mp4")
    assert response.vehicles == []
    assert response.frame_width == 0
    assert response.frame_height == 0
```

- [ ] **Step 4: Run tests to verify they fail**

Run: `.venv/Scripts/python.exe -m pytest tests/test_pipeline.py -v`
Expected: FAIL — `analyze` still returns a list; corridor fields missing.

- [ ] **Step 5: Rewire the pipeline**

In `video-analysis/app/pipeline.py`:

Add imports: `import math`, `from app.corridors import cluster_tracks, corridor_cohesion`, and add `AnalyzeResponse` to the `app.schemas` import.

Replace `analyze` with:

```python
    def analyze(self, video_path: str) -> AnalyzeResponse:
        tracks: dict[int, list["TrackedFrame"]] = defaultdict(list)
        for tracked_frame in self._detector.track_video(video_path):
            tracks[tracked_frame.track_id].append(tracked_frame)

        if not tracks:
            return AnalyzeResponse()

        # All frames share the source video's dimensions; read them off any one.
        any_frame = next(iter(tracks.values()))[0].frame
        frame_height, frame_width = any_frame.shape[:2]

        paths = {
            track_id: [f.centroid for f in sorted(frames, key=lambda f: f.frame_index)]
            for track_id, frames in tracks.items()
        }
        threshold_px = self._settings.corridor_cluster_threshold_fraction * math.hypot(
            frame_width, frame_height
        )
        assignments = cluster_tracks(paths, threshold_px)

        vehicles = [
            self._summarize_track(
                track_id,
                frames,
                corridor_id=assignments[track_id],
                cohesion=corridor_cohesion(track_id, paths, assignments, threshold_px),
            )
            for track_id, frames in tracks.items()
        ]
        return AnalyzeResponse(vehicles=vehicles, frame_width=frame_width, frame_height=frame_height)
```

Change `_summarize_track`'s signature to
`def _summarize_track(self, track_id: int, frames: list["TrackedFrame"], corridor_id: int, cohesion: float) -> VehicleResult:`
and inside it, after `centroids = ...`, add:

```python
        displacement = math.hypot(
            centroids[-1][0] - centroids[0][0], centroids[-1][1] - centroids[0][1]
        )
```

and extend the returned `VehicleResult(...)` with:

```python
            corridor_id=corridor_id,
            corridor_cohesion=cohesion,
            track_frame_count=len(frames_sorted),
            displacement_pixels=displacement,
```

In `video-analysis/app/main.py`, replace the last two lines of `analyze` (`vehicles = pipeline.analyze(...)` / `return AnalyzeResponse(vehicles=vehicles)`) with:

```python
        response = pipeline.analyze(tmp_file.name)

    return response
```

- [ ] **Step 6: Run the full Python suite** — `.venv/Scripts/python.exe -m pytest -v` — expected: all PASS.

- [ ] **Step 7: Commit**

```bash
git add video-analysis/app/config.py video-analysis/app/schemas.py video-analysis/app/pipeline.py video-analysis/app/main.py video-analysis/tests/test_pipeline.py
git commit -m "feat(video-analysis): corridor assignments, track quality facts, and frame dimensions in the analyze response"
```

---

### Task 3: Server — migration, `Report.directionEvidence`, `FlowObservation` entity + repository

**Files:**
- Create: `server/src/main/resources/db/migration/V6__add_flow_observations_and_direction_evidence.sql`
- Modify: `server/src/main/kotlin/com/trafficwatch/server/reports/Report.kt`
- Create: `server/src/main/kotlin/com/trafficwatch/server/geo/FlowObservation.kt`
- Create: `server/src/main/kotlin/com/trafficwatch/server/geo/FlowObservationRepository.kt`

**Interfaces:**
- Produces: `Report.directionEvidence: String?` (JSON text, consumed by Tasks 10-11); `FlowObservation(latBucket, lonBucket, bearingDegrees, vehicleCount, resultantLength, reporterId, reportId, createdAt, id)`; `FlowObservationRepository.findByLatBucketAndLonBucket(latBucket: BigDecimal, lonBucket: BigDecimal): List<FlowObservation>` (consumed by Task 9).

- [ ] **Step 1: Write the migration**

```sql
-- server/src/main/resources/db/migration/V6__add_flow_observations_and_direction_evidence.sql
CREATE TABLE flow_observations (
    id UUID PRIMARY KEY,
    lat_bucket NUMERIC(8,4) NOT NULL,
    lon_bucket NUMERIC(8,4) NOT NULL,
    bearing_degrees NUMERIC(6,2) NOT NULL,
    vehicle_count INT NOT NULL,
    resultant_length NUMERIC(4,3) NOT NULL,
    reporter_id UUID NOT NULL,
    report_id UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_flow_observations_bucket ON flow_observations (lat_bucket, lon_bucket);

ALTER TABLE reports ADD COLUMN direction_evidence JSONB;
```

- [ ] **Step 2: Add the entity field**

In `Report.kt`, add after the `wrongWayConfidence` property (and add imports `org.hibernate.annotations.JdbcTypeCode`, `org.hibernate.type.SqlTypes`):

```kotlin
    // Full direction-evidence breakdown for this analysis (sources, fates, fused
    // values, per-factor scores) as JSON - always computed and stored; only the
    // Android debug build renders it. See ReportAnalysisJob for the shape.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "direction_evidence", columnDefinition = "jsonb")
    var directionEvidence: String? = null,
```

- [ ] **Step 3: Create the entity and repository**

```kotlin
// server/src/main/kotlin/com/trafficwatch/server/geo/FlowObservation.kt
package com.trafficwatch.server.geo

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

/**
 * One corridor-consensus flow observed in one report's clip: "at this ~11m
 * lat/lon bucket, [vehicleCount] vehicles were seen flowing along
 * [bearingDegrees] with tightness [resultantLength]". Ingestion rules (>=2
 * vehicles, R >= threshold, evaluated candidate never included) live in
 * FlowObservationService - only qualifying consensuses ever become rows.
 */
@Entity
@Table(name = "flow_observations")
class FlowObservation(
    @Column(name = "lat_bucket", nullable = false)
    var latBucket: BigDecimal,

    @Column(name = "lon_bucket", nullable = false)
    var lonBucket: BigDecimal,

    @Column(name = "bearing_degrees", nullable = false)
    var bearingDegrees: BigDecimal,

    @Column(name = "vehicle_count", nullable = false)
    var vehicleCount: Int,

    @Column(name = "resultant_length", nullable = false)
    var resultantLength: BigDecimal,

    @Column(name = "reporter_id", nullable = false)
    var reporterId: UUID,

    @Column(name = "report_id", nullable = false)
    var reportId: UUID,

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    var id: UUID? = null,
)
```

```kotlin
// server/src/main/kotlin/com/trafficwatch/server/geo/FlowObservationRepository.kt
package com.trafficwatch.server.geo

import org.springframework.data.jpa.repository.JpaRepository
import java.math.BigDecimal
import java.util.UUID

interface FlowObservationRepository : JpaRepository<FlowObservation, UUID> {
    fun findByLatBucketAndLonBucket(latBucket: BigDecimal, lonBucket: BigDecimal): List<FlowObservation>
}
```

- [ ] **Step 4: Build to verify it compiles**

Run (from `server/`): `./gradlew.bat compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Run the server test suite** — `./gradlew.bat test` — expected: all existing tests still pass (integration tests apply V6 automatically via Flyway).

- [ ] **Step 6: Commit**

```bash
git add server/src/main/resources/db/migration/V6__add_flow_observations_and_direction_evidence.sql server/src/main/kotlin/com/trafficwatch/server/reports/Report.kt server/src/main/kotlin/com/trafficwatch/server/geo/FlowObservation.kt server/src/main/kotlin/com/trafficwatch/server/geo/FlowObservationRepository.kt
git commit -m "feat(server): flow_observations table and reports.direction_evidence column"
```

---

### Task 4: Server — video-analysis DTO additions

**Files:**
- Modify: `server/src/main/kotlin/com/trafficwatch/server/videoanalysis/dto/VideoAnalysisDtos.kt`

**Interfaces:**
- Produces (consumed by Tasks 7 and 10): `VehicleAnalysisResult.corridorId: Long?`, `.corridorCohesion: Double?`, `.trackFrameCount: Int?`, `.displacementPixels: Double?`; `VideoAnalysisResponse.frameWidth: Int?`, `.frameHeight: Int?`. All nullable with null defaults — an older Python service simply yields nulls and the clip-consensus source stays absent (spec's graceful-degradation requirement).

- [ ] **Step 1: Extend the DTOs**

In `VideoAnalysisDtos.kt`, change `VideoAnalysisResponse` to:

```kotlin
data class VideoAnalysisResponse(
    val vehicles: List<VehicleAnalysisResult> = emptyList(),
    // Source video dimensions - null when talking to an older service version,
    // in which case corridor/flow analysis is skipped entirely.
    val frameWidth: Int? = null,
    val frameHeight: Int? = null,
)
```

and add to `VehicleAnalysisResult` after `frameJpegBase64`:

```kotlin
    // Corridor flow facts from app/corridors.py - null from older service
    // versions; ClipFlowAnalyzer treats null as "vehicle not usable for flow".
    val corridorId: Long? = null,
    val corridorCohesion: Double? = null,
    val trackFrameCount: Int? = null,
    val displacementPixels: Double? = null,
```

- [ ] **Step 2: Build + test** — `./gradlew.bat compileKotlin test` — expected: BUILD SUCCESSFUL, all pass.

- [ ] **Step 3: Commit**

```bash
git add server/src/main/kotlin/com/trafficwatch/server/videoanalysis/dto/VideoAnalysisDtos.kt
git commit -m "feat(server): corridor and frame-dimension fields on video-analysis DTOs"
```

---

### Task 5: Server — circular statistics in `BearingMath`

**Files:**
- Modify: `server/src/main/kotlin/com/trafficwatch/server/geo/BearingMath.kt`
- Test: `server/src/test/kotlin/com/trafficwatch/server/geo/BearingMathTest.kt` (extend)

**Interfaces:**
- Produces (consumed by Tasks 7-9): `data class CircularStats(val meanDegrees: Double, val resultantLength: Double)`; `BearingMath.circularStats(bearingsDegrees: List<Double>): CircularStats?` (null for empty input); `BearingMath.weightedCircularMeanDegrees(bearingsDegrees: List<Double>, weights: List<Double>): Double?` (null for empty/zero-weight input).

- [ ] **Step 1: Write the failing tests**

Append to `BearingMathTest.kt` (inside the existing test class):

```kotlin
    @Test
    fun `circularStats of identical bearings has that mean and resultant length one`() {
        val stats = BearingMath.circularStats(listOf(90.0, 90.0, 90.0))!!
        assertEquals(90.0, stats.meanDegrees, 1e-9)
        assertEquals(1.0, stats.resultantLength, 1e-9)
    }

    @Test
    fun `circularStats averages correctly across the zero-360 wraparound`() {
        val stats = BearingMath.circularStats(listOf(350.0, 10.0))!!
        assertEquals(0.0, stats.meanDegrees, 1e-6)
        assertTrue(stats.resultantLength > 0.9)
    }

    @Test
    fun `circularStats of opposing bearings has near-zero resultant length`() {
        val stats = BearingMath.circularStats(listOf(0.0, 180.0))!!
        assertTrue(stats.resultantLength < 1e-9)
    }

    @Test
    fun `circularStats of empty list is null`() {
        assertNull(BearingMath.circularStats(emptyList()))
    }

    @Test
    fun `weightedCircularMeanDegrees weights the heavier bearing closer`() {
        val mean = BearingMath.weightedCircularMeanDegrees(listOf(0.0, 90.0), listOf(3.0, 1.0))!!
        assertTrue(mean > 0.0 && mean < 45.0)
    }

    @Test
    fun `weightedCircularMeanDegrees handles wraparound`() {
        val mean = BearingMath.weightedCircularMeanDegrees(listOf(350.0, 10.0), listOf(1.0, 1.0))!!
        assertEquals(0.0, mean, 1e-6)
    }

    @Test
    fun `weightedCircularMeanDegrees of empty or zero-weight input is null`() {
        assertNull(BearingMath.weightedCircularMeanDegrees(emptyList(), emptyList()))
        assertNull(BearingMath.weightedCircularMeanDegrees(listOf(10.0), listOf(0.0)))
    }
```

(Ensure imports `org.junit.jupiter.api.Assertions.assertNull` / `assertTrue` / `assertEquals` are present.)

- [ ] **Step 2: Run to verify they fail** — `./gradlew.bat test --tests "com.trafficwatch.server.geo.BearingMathTest"` — expected: compilation FAILURE (functions don't exist).

- [ ] **Step 3: Implement**

In `BearingMath.kt`, add at file top level (after the `object BearingMath { ... }` closing brace or inside — put the data class OUTSIDE the object, in the same file):

```kotlin
/** Circular mean bearing and mean resultant length R (1.0 = perfectly aligned, 0 = dispersed). */
data class CircularStats(val meanDegrees: Double, val resultantLength: Double)
```

and inside the `BearingMath` object:

```kotlin
    /**
     * Circular mean + mean resultant length over [bearingsDegrees]. R near 1 means the
     * bearings agree tightly; near 0 means dispersed or bimodal (e.g. two opposing
     * traffic streams). Null for empty input - never a fabricated statistic.
     */
    fun circularStats(bearingsDegrees: List<Double>): CircularStats? {
        if (bearingsDegrees.isEmpty()) return null
        val sumSin = bearingsDegrees.sumOf { sin(Math.toRadians(it)) }
        val sumCos = bearingsDegrees.sumOf { cos(Math.toRadians(it)) }
        val n = bearingsDegrees.size.toDouble()
        val mean = (Math.toDegrees(atan2(sumSin, sumCos)) + 360.0) % 360.0
        val resultantLength = sqrt(sumSin * sumSin + sumCos * sumCos) / n
        return CircularStats(mean, resultantLength)
    }

    /** Confidence-weighted circular mean; null when inputs are empty or all weights are zero. */
    fun weightedCircularMeanDegrees(bearingsDegrees: List<Double>, weights: List<Double>): Double? {
        if (bearingsDegrees.isEmpty() || bearingsDegrees.size != weights.size) return null
        var sumSin = 0.0
        var sumCos = 0.0
        for (i in bearingsDegrees.indices) {
            sumSin += weights[i] * sin(Math.toRadians(bearingsDegrees[i]))
            sumCos += weights[i] * cos(Math.toRadians(bearingsDegrees[i]))
        }
        if (sumSin == 0.0 && sumCos == 0.0) return null
        return (Math.toDegrees(atan2(sumSin, sumCos)) + 360.0) % 360.0
    }
```

- [ ] **Step 4: Run to verify they pass** — same command — expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add server/src/main/kotlin/com/trafficwatch/server/geo/BearingMath.kt server/src/test/kotlin/com/trafficwatch/server/geo/BearingMathTest.kt
git commit -m "feat(server): circular statistics (mean resultant length, weighted circular mean) in BearingMath"
```

---

### Task 6: Server — analysis config properties

**Files:**
- Modify: `server/src/main/kotlin/com/trafficwatch/server/reports/AnalysisProperties.kt`
- Modify: `server/src/main/resources/application.yml`

**Interfaces:**
- Produces (consumed by Tasks 7-10): the seven new `AnalysisProperties` fields below, exact names and defaults.

- [ ] **Step 1: Extend the properties class**

Replace `AnalysisProperties`'s body with:

```kotlin
data class AnalysisProperties(
    var wrongWayToleranceDegrees: Double = 60.0,
    // CONFIRMED requires the final four-factor product to reach this value.
    var confirmationThreshold: Double = 0.5,
    // Two direction-evidence bearings "agree" when within this many degrees.
    var agreementToleranceDegrees: Double = 45.0,
    // Evidence sources below this confidence are dropped before fusion.
    var weakEvidenceFloor: Double = 0.2,
    // A corridor's consensus requires at least this mean resultant length R.
    var consensusMinResultantLength: Double = 0.6,
    // Learned-history maturity gates - ALL must hold before history testifies.
    var historyMinObservations: Int = 5,
    var historyMinDistinctReporters: Int = 3,
    var historyMinResultantLength: Double = 0.8,
)
```

- [ ] **Step 2: Add the yml defaults**

In `application.yml`, replace the existing `analysis:` block under `app:` with:

```yaml
  analysis:
    wrong-way-tolerance-degrees: 60
    confirmation-threshold: 0.5
    agreement-tolerance-degrees: 45
    weak-evidence-floor: 0.2
    consensus-min-resultant-length: 0.6
    history-min-observations: 5
    history-min-distinct-reporters: 3
    history-min-resultant-length: 0.8
```

- [ ] **Step 3: Build + test** — `./gradlew.bat compileKotlin test` — expected: pass.

- [ ] **Step 4: Commit**

```bash
git add server/src/main/kotlin/com/trafficwatch/server/reports/AnalysisProperties.kt server/src/main/resources/application.yml
git commit -m "feat(server): fusion and history-maturity config in AnalysisProperties"
```

---

### Task 7: Server — `ClipFlowAnalyzer`

**Files:**
- Create: `server/src/main/kotlin/com/trafficwatch/server/geo/ClipFlowAnalyzer.kt`
- Test: `server/src/test/kotlin/com/trafficwatch/server/geo/ClipFlowAnalyzerTest.kt`

**Interfaces:**
- Consumes: `VehicleAnalysisResult` (Task 4 fields), `BearingMath.circularStats` (Task 5), `AnalysisProperties` (Task 6).
- Produces (consumed by Tasks 9-10):

```kotlin
data class FlowVehicle(
    val vehicle: VehicleAnalysisResult,
    val absoluteBearingDegrees: Double,
    val trackQuality: Double,
    val corridorId: Long,
    val corridorCohesion: Double,
) { val candidateQuality: Double get() = trackQuality * corridorCohesion }

data class CorridorConsensus(
    val corridorId: Long,
    val bearingDegrees: Double,
    val resultantLength: Double,
    val memberCount: Int,
    val meanCohesion: Double,
) { val clipConfidence: Double get() = (memberCount / (memberCount + 2.0)) * resultantLength * meanCohesion }

class ClipFlowAnalyzer(properties) {
    fun qualifyVehicles(vehicles, compassHeadingDegrees, frameWidth: Int?, frameHeight: Int?): List<FlowVehicle>
    fun corridorConsensus(flowVehicles: List<FlowVehicle>, corridorId: Long, excluding: FlowVehicle?): CorridorConsensus?
    fun movesWith(candidate: FlowVehicle, consensus: CorridorConsensus): Boolean
}
```

- [ ] **Step 1: Write the failing tests**

```kotlin
// server/src/test/kotlin/com/trafficwatch/server/geo/ClipFlowAnalyzerTest.kt
package com.trafficwatch.server.geo

import com.trafficwatch.server.reports.AnalysisProperties
import com.trafficwatch.server.videoanalysis.dto.VehicleAnalysisResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClipFlowAnalyzerTest {

    private val analyzer = ClipFlowAnalyzer(AnalysisProperties())

    private fun vehicle(
        trackId: Long,
        bearing: Double?,
        corridorId: Long? = 0L,
        cohesion: Double? = 1.0,
        frames: Int? = 10,
        displacement: Double? = 200.0,
        detectionConfidence: Double = 0.9,
    ) = VehicleAnalysisResult(
        trackId = trackId,
        vehicleType = "car",
        detectionConfidence = detectionConfidence,
        bearingDegrees = bearing,
        plateText = null,
        plateConfidence = null,
        corridorId = corridorId,
        corridorCohesion = cohesion,
        trackFrameCount = frames,
        displacementPixels = displacement,
    )

    // frame 1920x1080 -> diagonal ~2202.9, 5% floor ~110.1px

    @Test
    fun `qualifyVehicles converts frame bearing to absolute with compass heading`() {
        val result = analyzer.qualifyVehicles(listOf(vehicle(1, bearing = 90.0)), 45.0, 1920, 1080)
        assertEquals(1, result.size)
        assertEquals(135.0, result[0].absoluteBearingDegrees, 1e-9)
    }

    @Test
    fun `qualifyVehicles drops null bearings null corridor fields and null frame dims`() {
        assertTrue(analyzer.qualifyVehicles(listOf(vehicle(1, bearing = null)), 0.0, 1920, 1080).isEmpty())
        assertTrue(analyzer.qualifyVehicles(listOf(vehicle(1, 90.0, corridorId = null)), 0.0, 1920, 1080).isEmpty())
        assertTrue(analyzer.qualifyVehicles(listOf(vehicle(1, 90.0)), 0.0, null, null).isEmpty())
    }

    @Test
    fun `qualifyVehicles enforces the quality floor`() {
        // Too few frames.
        assertTrue(analyzer.qualifyVehicles(listOf(vehicle(1, 90.0, frames = 2)), 0.0, 1920, 1080).isEmpty())
        // Displacement under 5% of diagonal (~110.1px).
        assertTrue(analyzer.qualifyVehicles(listOf(vehicle(1, 90.0, displacement = 50.0)), 0.0, 1920, 1080).isEmpty())
    }

    @Test
    fun `trackQuality saturates at one and scales below the saturation points`() {
        val full = analyzer.qualifyVehicles(listOf(vehicle(1, 90.0, frames = 10, displacement = 500.0)), 0.0, 1920, 1080)
        assertEquals(1.0, full[0].trackQuality, 1e-9)

        val partial = analyzer.qualifyVehicles(listOf(vehicle(1, 90.0, frames = 4, displacement = 500.0)), 0.0, 1920, 1080)
        assertEquals(0.8, partial[0].trackQuality, 1e-9) // min(4/5, 1) * 1
    }

    @Test
    fun `unimodal corridor yields consensus with expected stats`() {
        val flow = analyzer.qualifyVehicles(
            listOf(vehicle(1, 88.0), vehicle(2, 92.0), vehicle(3, 90.0)), 0.0, 1920, 1080
        )
        val consensus = analyzer.corridorConsensus(flow, corridorId = 0L, excluding = null)!!
        assertEquals(3, consensus.memberCount)
        assertEquals(90.0, consensus.bearingDegrees, 0.5)
        assertTrue(consensus.resultantLength > 0.99)
        // clipConfidence = (3/5) * R * meanCohesion ~ 0.6
        assertEquals(0.6, consensus.clipConfidence, 0.01)
    }

    @Test
    fun `bimodal corridor yields no consensus`() {
        val flow = analyzer.qualifyVehicles(
            listOf(vehicle(1, 90.0), vehicle(2, 270.0)), 0.0, 1920, 1080
        )
        assertNull(analyzer.corridorConsensus(flow, corridorId = 0L, excluding = null))
    }

    @Test
    fun `consensus excludes the candidate under evaluation`() {
        val flow = analyzer.qualifyVehicles(
            listOf(vehicle(1, 90.0), vehicle(2, 90.0), vehicle(3, 270.0)), 0.0, 1920, 1080
        )
        val candidate = flow.first { it.vehicle.trackId == 3L }
        val consensus = analyzer.corridorConsensus(flow, 0L, excluding = candidate)!!
        assertEquals(2, consensus.memberCount)
        assertEquals(90.0, consensus.bearingDegrees, 1e-6)
    }

    @Test
    fun `movesWith is true within agreement tolerance and false when opposing`() {
        val flow = analyzer.qualifyVehicles(
            listOf(vehicle(1, 90.0), vehicle(2, 92.0), vehicle(3, 268.0)), 0.0, 1920, 1080
        )
        val with = flow.first { it.vehicle.trackId == 2L }
        val against = flow.first { it.vehicle.trackId == 3L }
        val consensus = analyzer.corridorConsensus(flow, 0L, excluding = null)!!
        assertTrue(analyzer.movesWith(with, consensus))
        assertFalse(analyzer.movesWith(against, consensus))
    }

    @Test
    fun `consensus of an empty corridor is null`() {
        assertNull(analyzer.corridorConsensus(emptyList(), 0L, excluding = null))
    }
}
```

- [ ] **Step 2: Run to verify failure** — `./gradlew.bat test --tests "com.trafficwatch.server.geo.ClipFlowAnalyzerTest"` — expected: compilation FAILURE.

- [ ] **Step 3: Implement**

```kotlin
// server/src/main/kotlin/com/trafficwatch/server/geo/ClipFlowAnalyzer.kt
package com.trafficwatch.server.geo

import com.trafficwatch.server.reports.AnalysisProperties
import com.trafficwatch.server.videoanalysis.dto.VehicleAnalysisResult
import org.springframework.stereotype.Component
import kotlin.math.hypot
import kotlin.math.min

/** Fraction of the frame diagonal below which a track's displacement is noise. */
private const val MIN_DISPLACEMENT_FRACTION = 0.05

/** Minimum observed frames for a track to vote in a consensus. */
private const val MIN_TRACK_FRAMES = 3

/** Frame count at which trackQuality's frame factor saturates to 1.0. */
private const val TRACK_FRAMES_SATURATION = 5.0

/** A vehicle qualified for flow analysis: absolute bearing + quality facts. */
data class FlowVehicle(
    val vehicle: VehicleAnalysisResult,
    val absoluteBearingDegrees: Double,
    val trackQuality: Double,
    val corridorId: Long,
    val corridorCohesion: Double,
) {
    val candidateQuality: Double get() = trackQuality * corridorCohesion
}

/** One corridor's flow consensus (computed excluding any evaluated candidate). */
data class CorridorConsensus(
    val corridorId: Long,
    val bearingDegrees: Double,
    val resultantLength: Double,
    val memberCount: Int,
    val meanCohesion: Double,
) {
    /** Spec: clipConfidence = (n/(n+2)) x R x meanCohesion. */
    val clipConfidence: Double
        get() = (memberCount / (memberCount + 2.0)) * resultantLength * meanCohesion
}

/**
 * Pure clip-flow statistics over corridor-annotated vehicles - no I/O, mirrors
 * [BearingMath]'s testability contract. Which corridor a vehicle is in is
 * Python's (frame-space geometry) answer; everything with judgment in it -
 * who qualifies, what counts as consensus, who opposes whom - is decided here.
 */
@Component
class ClipFlowAnalyzer(
    private val properties: AnalysisProperties,
) {

    /**
     * Vehicles usable for flow analysis: corridor-annotated, with a real bearing,
     * above the quality floor. Requires frame dimensions (null = older Python
     * service = no flow analysis at all, per the graceful-degradation contract).
     */
    fun qualifyVehicles(
        vehicles: List<VehicleAnalysisResult>,
        compassHeadingDegrees: Double,
        frameWidth: Int?,
        frameHeight: Int?,
    ): List<FlowVehicle> {
        if (frameWidth == null || frameHeight == null || frameWidth <= 0 || frameHeight <= 0) {
            return emptyList()
        }
        val diagonal = hypot(frameWidth.toDouble(), frameHeight.toDouble())
        val minDisplacement = MIN_DISPLACEMENT_FRACTION * diagonal

        return vehicles.mapNotNull { vehicle ->
            val frameBearing = vehicle.bearingDegrees ?: return@mapNotNull null
            val corridorId = vehicle.corridorId ?: return@mapNotNull null
            val cohesion = vehicle.corridorCohesion ?: return@mapNotNull null
            val frames = vehicle.trackFrameCount ?: return@mapNotNull null
            val displacement = vehicle.displacementPixels ?: return@mapNotNull null

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

    /**
     * The consensus of corridor [corridorId]'s members (minus [excluding], the
     * candidate under evaluation - a suspected violator must never vote in the
     * consensus it is judged against, nor ever be ingested from it). Null when
     * no members remain or the bearings are too dispersed/bimodal
     * (R < consensus-min-resultant-length) - a split flow never elects a winner.
     */
    fun corridorConsensus(
        flowVehicles: List<FlowVehicle>,
        corridorId: Long,
        excluding: FlowVehicle?,
    ): CorridorConsensus? {
        val members = flowVehicles.filter { it.corridorId == corridorId && it !== excluding }
        if (members.isEmpty()) return null

        val stats = BearingMath.circularStats(members.map { it.absoluteBearingDegrees }) ?: return null
        if (stats.resultantLength < properties.consensusMinResultantLength) return null

        return CorridorConsensus(
            corridorId = corridorId,
            bearingDegrees = stats.meanDegrees,
            resultantLength = stats.resultantLength,
            memberCount = members.size,
            meanCohesion = members.sumOf { it.corridorCohesion } / members.size,
        )
    }

    /** True when [candidate] flows in the same direction as [consensus] (within agreement tolerance). */
    fun movesWith(candidate: FlowVehicle, consensus: CorridorConsensus): Boolean =
        BearingMath.angularDifferenceDegrees(candidate.absoluteBearingDegrees, consensus.bearingDegrees) <=
            properties.agreementToleranceDegrees
}
```

- [ ] **Step 4: Run to verify pass** — same command — expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add server/src/main/kotlin/com/trafficwatch/server/geo/ClipFlowAnalyzer.kt server/src/test/kotlin/com/trafficwatch/server/geo/ClipFlowAnalyzerTest.kt
git commit -m "feat(server): ClipFlowAnalyzer - corridor consensus and candidate qualification"
```

---

### Task 8: Server — `DirectionEvidenceResolver`

**Files:**
- Create: `server/src/main/kotlin/com/trafficwatch/server/geo/DirectionEvidenceResolver.kt`
- Test: `server/src/test/kotlin/com/trafficwatch/server/geo/DirectionEvidenceResolverTest.kt`

**Interfaces:**
- Consumes: `BearingMath` (Task 5), `AnalysisProperties` (Task 6).
- Produces (consumed by Tasks 9-10):

```kotlin
enum class EvidenceKind { OSM_TAG, CLIP_CONSENSUS, LEARNED_HISTORY }
enum class EvidenceFate { ACCEPTED, DROPPED_WEAK, CONFLICT }
data class DirectionEvidence(val kind: EvidenceKind, val bearingDegrees: Double, val confidence: Double)
data class EvidenceEntry(val kind: EvidenceKind, val bearingDegrees: Double, val confidence: Double, val fate: EvidenceFate)
sealed class FusionResult {
    data class Fused(val bearingDegrees: Double, val directionConfidence: Double, val entries: List<EvidenceEntry>) : FusionResult()
    data class Insufficient(val conflict: Boolean, val entries: List<EvidenceEntry>) : FusionResult()
}
class DirectionEvidenceResolver(properties) { fun fuse(sources: List<DirectionEvidence>): FusionResult }
```

- [ ] **Step 1: Write the failing tests**

```kotlin
// server/src/test/kotlin/com/trafficwatch/server/geo/DirectionEvidenceResolverTest.kt
package com.trafficwatch.server.geo

import com.trafficwatch.server.reports.AnalysisProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DirectionEvidenceResolverTest {

    private val resolver = DirectionEvidenceResolver(AnalysisProperties())

    @Test
    fun `osm alone fuses to its bearing with confidence one`() {
        val result = resolver.fuse(listOf(DirectionEvidence(EvidenceKind.OSM_TAG, 34.0, 1.0)))
        result as FusionResult.Fused
        assertEquals(34.0, result.bearingDegrees, 1e-9)
        assertEquals(1.0, result.directionConfidence, 1e-9)
        assertEquals(EvidenceFate.ACCEPTED, result.entries.single().fate)
    }

    @Test
    fun `agreeing sources combine by noisy-or and weighted circular mean`() {
        val result = resolver.fuse(
            listOf(
                DirectionEvidence(EvidenceKind.CLIP_CONSENSUS, 80.0, 0.6),
                DirectionEvidence(EvidenceKind.LEARNED_HISTORY, 100.0, 0.5),
            )
        )
        result as FusionResult.Fused
        // noisy-OR: 1 - 0.4*0.5 = 0.8
        assertEquals(0.8, result.directionConfidence, 1e-9)
        // Weighted mean pulled toward the higher-confidence source (80 side).
        assertTrue(result.bearingDegrees > 80.0 && result.bearingDegrees < 90.0)
        assertTrue(result.entries.all { it.fate == EvidenceFate.ACCEPTED })
    }

    @Test
    fun `strong disagreement forces insufficient with conflict`() {
        val result = resolver.fuse(
            listOf(
                DirectionEvidence(EvidenceKind.OSM_TAG, 0.0, 1.0),
                DirectionEvidence(EvidenceKind.CLIP_CONSENSUS, 180.0, 0.7),
            )
        )
        result as FusionResult.Insufficient
        assertTrue(result.conflict)
        assertTrue(result.entries.all { it.fate == EvidenceFate.CONFLICT })
    }

    @Test
    fun `weak source is dropped without vetoing`() {
        val result = resolver.fuse(
            listOf(
                DirectionEvidence(EvidenceKind.OSM_TAG, 0.0, 1.0),
                DirectionEvidence(EvidenceKind.CLIP_CONSENSUS, 180.0, 0.1), // below 0.2 floor
            )
        )
        result as FusionResult.Fused
        assertEquals(0.0, result.bearingDegrees, 1e-9)
        assertEquals(
            EvidenceFate.DROPPED_WEAK,
            result.entries.first { it.kind == EvidenceKind.CLIP_CONSENSUS }.fate,
        )
    }

    @Test
    fun `no sources is insufficient without conflict`() {
        val result = resolver.fuse(emptyList())
        result as FusionResult.Insufficient
        assertFalse(result.conflict)
        assertTrue(result.entries.isEmpty())
    }

    @Test
    fun `all sources weak is insufficient without conflict`() {
        val result = resolver.fuse(listOf(DirectionEvidence(EvidenceKind.CLIP_CONSENSUS, 90.0, 0.15)))
        result as FusionResult.Insufficient
        assertFalse(result.conflict)
        assertEquals(EvidenceFate.DROPPED_WEAK, result.entries.single().fate)
    }

    @Test
    fun `agreement works across the zero-360 wraparound`() {
        val result = resolver.fuse(
            listOf(
                DirectionEvidence(EvidenceKind.OSM_TAG, 350.0, 1.0),
                DirectionEvidence(EvidenceKind.CLIP_CONSENSUS, 10.0, 0.6),
            )
        )
        assertTrue(result is FusionResult.Fused)
    }
}
```

- [ ] **Step 2: Run to verify failure** — `./gradlew.bat test --tests "com.trafficwatch.server.geo.DirectionEvidenceResolverTest"` — expected: compilation FAILURE.

- [ ] **Step 3: Implement**

```kotlin
// server/src/main/kotlin/com/trafficwatch/server/geo/DirectionEvidenceResolver.kt
package com.trafficwatch.server.geo

import com.trafficwatch.server.reports.AnalysisProperties
import org.springframework.stereotype.Component

/**
 * The evidence sources the fusion layer understands today. Deliberately open for
 * extension: user direction-attestation (see the spec's "future source" note)
 * becomes a fourth constant here and nothing else in the interface changes.
 */
enum class EvidenceKind { OSM_TAG, CLIP_CONSENSUS, LEARNED_HISTORY }

enum class EvidenceFate { ACCEPTED, DROPPED_WEAK, CONFLICT }

data class DirectionEvidence(
    val kind: EvidenceKind,
    val bearingDegrees: Double,
    val confidence: Double,
)

/** A source plus what fusion did with it - persisted into the evidence breakdown. */
data class EvidenceEntry(
    val kind: EvidenceKind,
    val bearingDegrees: Double,
    val confidence: Double,
    val fate: EvidenceFate,
)

sealed class FusionResult {
    abstract val entries: List<EvidenceEntry>

    data class Fused(
        val bearingDegrees: Double,
        val directionConfidence: Double,
        override val entries: List<EvidenceEntry>,
    ) : FusionResult()

    data class Insufficient(
        val conflict: Boolean,
        override val entries: List<EvidenceEntry>,
    ) : FusionResult()
}

/**
 * Fuses whatever direction-evidence sources are present into one
 * (bearing, confidence) - or refuses. Rules (spec "Evidence fusion"):
 * weak sources (< weak-evidence-floor) are dropped first and never veto;
 * all survivors must pairwise agree (<= agreement-tolerance-degrees) or the
 * whole result is insufficient-with-conflict - including against the OSM tag
 * (the cross-check: strong observed flow contradicting the map means stale
 * data, and this resolver never guesses); agreeing survivors combine by
 * noisy-OR and confidence-weighted circular mean.
 */
@Component
class DirectionEvidenceResolver(
    private val properties: AnalysisProperties,
) {

    fun fuse(sources: List<DirectionEvidence>): FusionResult {
        val (weak, survivors) = sources.partition { it.confidence < properties.weakEvidenceFloor }
        val weakEntries = weak.map { it.toEntry(EvidenceFate.DROPPED_WEAK) }

        if (survivors.isEmpty()) {
            return FusionResult.Insufficient(conflict = false, entries = weakEntries)
        }

        val anyDisagreement = survivors.indices.any { i ->
            (i + 1 until survivors.size).any { j ->
                BearingMath.angularDifferenceDegrees(
                    survivors[i].bearingDegrees,
                    survivors[j].bearingDegrees,
                ) > properties.agreementToleranceDegrees
            }
        }
        if (anyDisagreement) {
            return FusionResult.Insufficient(
                conflict = true,
                entries = weakEntries + survivors.map { it.toEntry(EvidenceFate.CONFLICT) },
            )
        }

        val fusedBearing = requireNotNull(
            BearingMath.weightedCircularMeanDegrees(
                survivors.map { it.bearingDegrees },
                survivors.map { it.confidence },
            ),
        ) { "survivors is non-empty with positive weights" }
        val directionConfidence = 1.0 - survivors.fold(1.0) { acc, s -> acc * (1.0 - s.confidence) }

        return FusionResult.Fused(
            bearingDegrees = fusedBearing,
            directionConfidence = directionConfidence,
            entries = weakEntries + survivors.map { it.toEntry(EvidenceFate.ACCEPTED) },
        )
    }

    private fun DirectionEvidence.toEntry(fate: EvidenceFate) =
        EvidenceEntry(kind, bearingDegrees, confidence, fate)
}
```

- [ ] **Step 4: Run to verify pass** — same command — expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add server/src/main/kotlin/com/trafficwatch/server/geo/DirectionEvidenceResolver.kt server/src/test/kotlin/com/trafficwatch/server/geo/DirectionEvidenceResolverTest.kt
git commit -m "feat(server): DirectionEvidenceResolver - noisy-OR fusion with conflict veto"
```

---

### Task 9: Server — `FlowObservationService` (ingestion + history evidence)

**Files:**
- Create: `server/src/main/kotlin/com/trafficwatch/server/geo/FlowObservationService.kt`
- Test: `server/src/test/kotlin/com/trafficwatch/server/geo/FlowObservationServiceTest.kt`

**Interfaces:**
- Consumes: `FlowObservationRepository` (Task 3), `CorridorConsensus` (Task 7), `DirectionEvidence`/`EvidenceKind` (Task 8), `AnalysisProperties` (Task 6).
- Produces (consumed by Task 10): `FlowObservationService.ingest(report: Report, consensuses: List<CorridorConsensus>)`; `FlowObservationService.historyEvidence(latitude: BigDecimal, longitude: BigDecimal): DirectionEvidence?`.

- [ ] **Step 1: Write the failing tests**

```kotlin
// server/src/test/kotlin/com/trafficwatch/server/geo/FlowObservationServiceTest.kt
package com.trafficwatch.server.geo

import com.trafficwatch.server.reports.AnalysisProperties
import com.trafficwatch.server.reports.Report
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

class FlowObservationServiceTest {

    private val repository = mock<FlowObservationRepository>()
    private val service = FlowObservationService(repository, AnalysisProperties())

    private fun report(lat: String = "31.4685846", lon: String = "74.4057830") = Report(
        userId = UUID.randomUUID(),
        videoPath = "v.mp4",
        latitude = BigDecimal(lat),
        longitude = BigDecimal(lon),
        accuracy = BigDecimal.ONE,
        altitude = BigDecimal.ZERO,
        bearing = BigDecimal.ZERO,
        speed = BigDecimal.ZERO,
        recordedAt = LocalDateTime.now(),
        durationMs = 5000,
        deviceId = "d",
        id = UUID.randomUUID(),
    )

    private fun consensus(members: Int, r: Double = 0.95, bearing: Double = 90.0) =
        CorridorConsensus(corridorId = 0, bearingDegrees = bearing, resultantLength = r, memberCount = members, meanCohesion = 1.0)

    private fun observation(bearing: Double, reporter: UUID = UUID.randomUUID()) = FlowObservation(
        latBucket = BigDecimal("31.4686"),
        lonBucket = BigDecimal("74.4058"),
        bearingDegrees = BigDecimal.valueOf(bearing),
        vehicleCount = 3,
        resultantLength = BigDecimal("0.950"),
        reporterId = reporter,
        reportId = UUID.randomUUID(),
    )

    @Test
    fun `ingest writes one row per qualifying consensus with bucketed coordinates`() {
        service.ingest(report(), listOf(consensus(members = 3)))

        val captor = argumentCaptor<FlowObservation>()
        verify(repository).save(captor.capture())
        val row = captor.firstValue
        assertEquals(BigDecimal("31.4686"), row.latBucket)
        assertEquals(BigDecimal("74.4058"), row.lonBucket)
        assertEquals(3, row.vehicleCount)
    }

    @Test
    fun `ingest skips consensuses with fewer than two members`() {
        service.ingest(report(), listOf(consensus(members = 1)))
        verify(repository, never()).save(any())
    }

    @Test
    fun `ingest skips consensuses below the resultant length gate`() {
        service.ingest(report(), listOf(consensus(members = 3, r = 0.5)))
        verify(repository, never()).save(any())
    }

    @Test
    fun `history evidence requires minimum observation count`() {
        whenever(repository.findByLatBucketAndLonBucket(any(), any()))
            .thenReturn(List(4) { observation(90.0) }) // below historyMinObservations = 5
        assertNull(service.historyEvidence(BigDecimal("31.4685846"), BigDecimal("74.4057830")))
    }

    @Test
    fun `history evidence requires distinct reporters`() {
        val oneReporter = UUID.randomUUID()
        whenever(repository.findByLatBucketAndLonBucket(any(), any()))
            .thenReturn(List(6) { observation(90.0, reporter = oneReporter) })
        assertNull(service.historyEvidence(BigDecimal("31.4685846"), BigDecimal("74.4057830")))
    }

    @Test
    fun `history evidence requires unimodal distribution`() {
        val rows = List(3) { observation(90.0) } + List(3) { observation(270.0) }
        whenever(repository.findByLatBucketAndLonBucket(any(), any())).thenReturn(rows)
        assertNull(service.historyEvidence(BigDecimal("31.4685846"), BigDecimal("74.4057830")))
    }

    @Test
    fun `mature history yields evidence with the documented confidence curve`() {
        whenever(repository.findByLatBucketAndLonBucket(any(), any()))
            .thenReturn(List(5) { observation(90.0) })
        val evidence = service.historyEvidence(BigDecimal("31.4685846"), BigDecimal("74.4057830"))

        assertNotNull(evidence)
        assertEquals(EvidenceKind.LEARNED_HISTORY, evidence!!.kind)
        assertEquals(90.0, evidence.bearingDegrees, 1e-6)
        // (5/(5+5)) * 1.0 = 0.5
        assertEquals(0.5, evidence.confidence, 1e-9)
        assertTrue(evidence.confidence < 0.9)
    }
}
```

- [ ] **Step 2: Run to verify failure** — `./gradlew.bat test --tests "com.trafficwatch.server.geo.FlowObservationServiceTest"` — expected: compilation FAILURE.

- [ ] **Step 3: Implement**

```kotlin
// server/src/main/kotlin/com/trafficwatch/server/geo/FlowObservationService.kt
package com.trafficwatch.server.geo

import com.trafficwatch.server.reports.AnalysisProperties
import com.trafficwatch.server.reports.Report
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/** Minimum consensus members for an observation to be worth teaching the DB. */
private const val MIN_INGEST_MEMBERS = 2

/** Curve constant: historyConfidence = (n/(n+5)) x R_hist - never reaches 1.0. */
private const val MATURITY_CURVE_CONSTANT = 5.0

/**
 * The learned per-location direction database. Ingestion is consensus-only
 * (>= 2 agreeing vehicles; the evaluated candidate is excluded upstream in
 * ClipFlowAnalyzer.corridorConsensus) so a violator - real or staged - never
 * teaches it. History testifies only at maturity: enough observations, enough
 * DISTINCT reporters (one user can never mature a location alone), and a
 * unimodal accumulated distribution. A bimodal bucket (divided road filmed
 * from one vantage, or genuinely mixed flow) yields nothing - never a
 * fabricated "two-way" verdict.
 */
@Service
class FlowObservationService(
    private val repository: FlowObservationRepository,
    private val properties: AnalysisProperties,
) {
    private val logger = LoggerFactory.getLogger(FlowObservationService::class.java)

    /**
     * Persists each qualifying corridor consensus as one observation row.
     * Never throws: a storage failure is logged and must not affect the
     * report's outcome (same error contract as frame annotation).
     */
    fun ingest(report: Report, consensuses: List<CorridorConsensus>) {
        val reportId = report.id ?: return
        for (consensus in consensuses) {
            if (consensus.memberCount < MIN_INGEST_MEMBERS) continue
            if (consensus.resultantLength < properties.consensusMinResultantLength) continue

            try {
                repository.save(
                    FlowObservation(
                        latBucket = roundToBucket(report.latitude),
                        lonBucket = roundToBucket(report.longitude),
                        bearingDegrees = BigDecimal.valueOf(consensus.bearingDegrees)
                            .setScale(2, RoundingMode.HALF_UP),
                        vehicleCount = consensus.memberCount,
                        resultantLength = BigDecimal.valueOf(consensus.resultantLength)
                            .setScale(3, RoundingMode.HALF_UP),
                        reporterId = report.userId,
                        reportId = reportId,
                    ),
                )
            } catch (ex: Exception) {
                logger.warn("FlowObservationService: failed to ingest observation for report {}", reportId, ex)
            }
        }
    }

    /**
     * The bucket's learned direction as a fusion source, or null before
     * maturity. Maturity requires ALL of: >= history-min-observations rows,
     * >= history-min-distinct-reporters distinct reporters, and
     * R_hist >= history-min-resultant-length across the rows' bearings.
     */
    fun historyEvidence(latitude: BigDecimal, longitude: BigDecimal): DirectionEvidence? {
        val rows = repository.findByLatBucketAndLonBucket(roundToBucket(latitude), roundToBucket(longitude))
        if (rows.size < properties.historyMinObservations) return null

        val distinctReporters = rows.map(FlowObservation::reporterId).toSet()
        if (distinctReporters.size < properties.historyMinDistinctReporters) return null

        val stats = BearingMath.circularStats(rows.map { it.bearingDegrees.toDouble() }) ?: return null
        if (stats.resultantLength < properties.historyMinResultantLength) return null

        val confidence = (rows.size / (rows.size + MATURITY_CURVE_CONSTANT)) * stats.resultantLength
        return DirectionEvidence(EvidenceKind.LEARNED_HISTORY, stats.meanDegrees, confidence)
    }

    /** Same 4-decimal (~11m) bucketing convention as StreetDirectionResolver. */
    private fun roundToBucket(value: BigDecimal): BigDecimal = value.setScale(4, RoundingMode.HALF_UP)
}
```

Note: the test expects one distinct-reporter case to fail maturity even with 6 rows — `historyMinDistinctReporters` defaults to 3 and all 6 rows share one reporter, so it correctly returns null.

- [ ] **Step 4: Run to verify pass** — same command — expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add server/src/main/kotlin/com/trafficwatch/server/geo/FlowObservationService.kt server/src/test/kotlin/com/trafficwatch/server/geo/FlowObservationServiceTest.kt
git commit -m "feat(server): FlowObservationService - consensus-only ingestion and maturity-gated history evidence"
```

---

### Task 10: Server — `ReportAnalysisJob` evidence-fusion rewrite

**Files:**
- Modify: `server/src/main/kotlin/com/trafficwatch/server/reports/ReportAnalysisJob.kt`
- Test: `server/src/test/kotlin/com/trafficwatch/server/reports/ReportAnalysisJobTest.kt` (extend/adjust)

**Interfaces:**
- Consumes: everything from Tasks 3-9.
- Produces: `AnalysisOutcome` gains `directionEvidenceJson: String? = null`; `applyOutcome` persists it to `report.directionEvidence`. Breakdown JSON shape (snake_case via the app ObjectMapper), consumed by Task 11's status DTO and Task 14's debug card:

```json
{
  "sources": [{"kind": "CLIP_CONSENSUS", "bearing_degrees": 85.0, "confidence": 0.6, "fate": "ACCEPTED"}],
  "fused_bearing_degrees": 85.0,
  "direction_confidence": 0.6,
  "candidate_quality": 0.95,
  "detection_confidence": 0.9,
  "bearing_match_score": 0.98,
  "final_score": 0.5,
  "confirmation_threshold": 0.5
}
```

(`fused_bearing_degrees`/`direction_confidence` null when fusion was insufficient; the four factor fields and `final_score` null when no candidate was scored.)

- [ ] **Step 1: Add the breakdown DTO and rewrite `determineOutcome`**

In `ReportAnalysisJob.kt`: add imports `com.fasterxml.jackson.databind.ObjectMapper`, `com.trafficwatch.server.geo.ClipFlowAnalyzer`, `com.trafficwatch.server.geo.CorridorConsensus`, `com.trafficwatch.server.geo.DirectionEvidence`, `com.trafficwatch.server.geo.DirectionEvidenceResolver`, `com.trafficwatch.server.geo.EvidenceEntry`, `com.trafficwatch.server.geo.EvidenceKind`, `com.trafficwatch.server.geo.FlowObservationService`, `com.trafficwatch.server.geo.FlowVehicle`, `com.trafficwatch.server.geo.FusionResult`.

Extend the constructor with four new parameters (after `wrongWayFrameStorageService`):

```kotlin
    private val clipFlowAnalyzer: ClipFlowAnalyzer,
    private val directionEvidenceResolver: DirectionEvidenceResolver,
    private val flowObservationService: FlowObservationService,
    private val objectMapper: ObjectMapper,
```

Add at the bottom of the file (alongside `AnalysisOutcome`):

```kotlin
/** Serialized (snake_case) into reports.direction_evidence - see the plan's breakdown shape. */
internal data class EvidenceBreakdown(
    val sources: List<EvidenceEntry>,
    val fusedBearingDegrees: Double?,
    val directionConfidence: Double?,
    val candidateQuality: Double?,
    val detectionConfidence: Double?,
    val bearingMatchScore: Double?,
    val finalScore: Double?,
    val confirmationThreshold: Double,
)

/** One fully-evaluated violation candidate. */
internal data class ScoredCandidate(
    val flowVehicle: FlowVehicle,
    val fusion: FusionResult.Fused,
    val angularDistanceDegrees: Double,
    val bearingMatchScore: Double,
    val finalScore: Double,
)
```

Add `directionEvidenceJson: String? = null` as the last property of `AnalysisOutcome` (with its `rejected` factory gaining `directionEvidenceJson: String? = null` passed through), and in `applyOutcome` add `report.directionEvidence = outcome.directionEvidenceJson` alongside the other assignments.

Replace `determineOutcome` and `findBestWrongWayVehicle` with:

```kotlin
    private fun determineOutcome(report: Report): AnalysisOutcome {
        val compassHeadingDegrees = report.compassHeadingDegrees
            ?: return AnalysisOutcome.rejected("Device compass heading unavailable for this report")

        val resolution = streetDirectionResolver.resolve(report.latitude, report.longitude)

        // TwoWay is the one terminal OSM outcome: an explicit oneway=no means
        // opposing traffic is legal, and video inference must never run (the
        // quiet-two-way-street false-positive guard). Everything else - Unknown,
        // NotFound, LookupFailed - just means the OSM evidence source is absent.
        if (resolution is DirectionResolution.TwoWay) {
            return AnalysisOutcome.rejected(
                "Street is two-way; no wrong-way violation is possible here",
                resolution.streetName,
            )
        }
        val osmEvidence = (resolution as? DirectionResolution.OneWay)?.let {
            DirectionEvidence(EvidenceKind.OSM_TAG, it.legalBearingDegrees, 1.0)
        }
        val streetName = when (resolution) {
            is DirectionResolution.OneWay -> resolution.streetName
            is DirectionResolution.Unknown -> resolution.streetName
            else -> null
        }

        val analysis = try {
            videoAnalysisClient.analyze(
                videoStorageService.resolve(report.videoPath),
                requireNotNull(report.id) { "Report must have a generated id before analysis" },
            )
        } catch (ex: VideoAnalysisException) {
            return AnalysisOutcome.rejected("Video analysis service unavailable: ${ex.message}", streetName)
        }

        val flowVehicles = clipFlowAnalyzer.qualifyVehicles(
            analysis.vehicles,
            compassHeadingDegrees.toDouble(),
            analysis.frameWidth,
            analysis.frameHeight,
        )
        val historyEvidence = flowObservationService.historyEvidence(report.latitude, report.longitude)

        val evaluation = evaluateCandidates(flowVehicles, osmEvidence, historyEvidence)

        val outcome = buildOutcome(report, evaluation, osmEvidence, historyEvidence, flowVehicles, streetName)

        ingestObservations(report, flowVehicles, evaluation.best?.flowVehicle)

        return outcome
    }

    private data class CandidateEvaluation(
        val best: ScoredCandidate?,
        val sawConflict: Boolean,
        val sawInsufficient: Boolean,
    )

    /**
     * Evaluates every qualified vehicle as a potential violator. Per spec:
     * a candidate moving WITH its own corridor's consensus is never a violator
     * (legal opposing stream on a divided road); a violator moves against its
     * corridor's consensus, or against the fused legal bearing when alone.
     * Fusion is per-candidate because the clip-consensus source is the
     * candidate's own corridor (excluding the candidate itself).
     */
    private fun evaluateCandidates(
        flowVehicles: List<FlowVehicle>,
        osmEvidence: DirectionEvidence?,
        historyEvidence: DirectionEvidence?,
    ): CandidateEvaluation {
        var best: ScoredCandidate? = null
        var sawConflict = false
        var sawInsufficient = false

        for (candidate in flowVehicles) {
            val consensus = clipFlowAnalyzer.corridorConsensus(flowVehicles, candidate.corridorId, candidate)
            if (consensus != null && clipFlowAnalyzer.movesWith(candidate, consensus)) {
                continue // gate 1: flows with its own corridor - never a violator
            }
            val clipEvidence = consensus?.let {
                DirectionEvidence(EvidenceKind.CLIP_CONSENSUS, it.bearingDegrees, it.clipConfidence)
            }

            when (val fusion = directionEvidenceResolver.fuse(listOfNotNull(osmEvidence, clipEvidence, historyEvidence))) {
                is FusionResult.Insufficient -> {
                    if (fusion.conflict) sawConflict = true else sawInsufficient = true
                }
                is FusionResult.Fused -> {
                    val illegalBearing = (fusion.bearingDegrees + 180.0) % 360.0
                    val angularDistance = BearingMath.angularDifferenceDegrees(
                        candidate.absoluteBearingDegrees,
                        illegalBearing,
                    )
                    if (angularDistance > analysisProperties.wrongWayToleranceDegrees) continue

                    val bearingMatchScore = 1.0 - (angularDistance / analysisProperties.wrongWayToleranceDegrees)
                    val finalScore = fusion.directionConfidence *
                        candidate.candidateQuality *
                        candidate.vehicle.detectionConfidence *
                        bearingMatchScore

                    if (best == null || finalScore > best!!.finalScore) {
                        best = ScoredCandidate(candidate, fusion, angularDistance, bearingMatchScore, finalScore)
                    }
                }
            }
        }
        return CandidateEvaluation(best, sawConflict, sawInsufficient)
    }

    private fun buildOutcome(
        report: Report,
        evaluation: CandidateEvaluation,
        osmEvidence: DirectionEvidence?,
        historyEvidence: DirectionEvidence?,
        flowVehicles: List<FlowVehicle>,
        streetName: String?,
    ): AnalysisOutcome {
        val best = evaluation.best
        if (best != null && best.finalScore >= analysisProperties.confirmationThreshold) {
            return AnalysisOutcome(
                status = ReportStatus.CONFIRMED,
                licensePlate = best.flowVehicle.vehicle.plateText,
                confidence = best.flowVehicle.vehicle.plateConfidence?.let { BigDecimal.valueOf(it) },
                message = "Wrong-way vehicle detected on ${streetName ?: "this street"}",
                streetName = streetName,
                wrongWayConfidence = BigDecimal.valueOf(best.finalScore),
                wrongWayFramePath = annotateAndStoreFrame(
                    best.flowVehicle.vehicle,
                    requireNotNull(report.id) { "Report must have a generated id before analysis" },
                ),
                directionEvidenceJson = breakdownJson(best.fusion.entries, best),
            )
        }

        // No confirmation. The fallback fusion must still include the clip's
        // strongest corridor consensus (computed with NO exclusion): when every
        // vehicle flows legally with its corridor, no per-candidate fusion ever
        // ran - yet a corridor unanimously opposing the OSM tag is exactly the
        // cross-check case, and the conflict veto must still surface here.
        val strongestClipEvidence = flowVehicles.map { it.corridorId }.distinct()
            .mapNotNull { clipFlowAnalyzer.corridorConsensus(flowVehicles, it, excluding = null) }
            .maxByOrNull { it.clipConfidence }
            ?.let { DirectionEvidence(EvidenceKind.CLIP_CONSENSUS, it.bearingDegrees, it.clipConfidence) }
        val fallbackFusion = directionEvidenceResolver.fuse(
            listOfNotNull(osmEvidence, strongestClipEvidence, historyEvidence),
        )
        val entries = best?.fusion?.entries ?: fallbackFusion.entries

        val message = when {
            best != null -> "Possible wrong-way vehicle detected, but confidence was too low to confirm"
            evaluation.sawConflict || (fallbackFusion as? FusionResult.Insufficient)?.conflict == true ->
                "Conflicting direction evidence for this street"
            fallbackFusion is FusionResult.Fused -> "No vehicles detected moving against the legal direction"
            else -> "Legal traffic direction could not be established for this street"
        }
        return AnalysisOutcome.rejected(
            message,
            streetName,
            directionEvidenceJson = breakdownJson(entries, best),
        )
    }

    /**
     * Ingestion happens for every analyzed report regardless of outcome - each
     * corridor's consensus computed EXCLUDING the evaluated (winning) candidate,
     * so a violator never teaches the learned DB. Failures are logged inside
     * FlowObservationService and never affect the report.
     */
    private fun ingestObservations(report: Report, flowVehicles: List<FlowVehicle>, excluded: FlowVehicle?) {
        val consensuses = flowVehicles.map { it.corridorId }.distinct().mapNotNull { corridorId ->
            clipFlowAnalyzer.corridorConsensus(flowVehicles, corridorId, excluded)
        }
        flowObservationService.ingest(report, consensuses)
    }

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
            ),
        )
    } catch (ex: Exception) {
        logger.warn("ReportAnalysisJob: failed to serialize evidence breakdown", ex)
        null
    }
```

Update `AnalysisOutcome.rejected` to:

```kotlin
        fun rejected(message: String, streetName: String? = null, directionEvidenceJson: String? = null) =
            AnalysisOutcome(
                status = ReportStatus.REJECTED,
                licensePlate = null,
                confidence = null,
                message = message,
                streetName = streetName,
                directionEvidenceJson = directionEvidenceJson,
            )
```

Delete the now-unused `findBestWrongWayVehicle` and `WrongWayCandidate` (the `ScoredCandidate` replaces it).

- [ ] **Step 2: Update the tests**

`ReportAnalysisJobTest.kt` currently constructs the job with 6 dependencies and stubs vehicles without corridor fields. Update:

- Construct with the four new dependencies: real `ClipFlowAnalyzer(AnalysisProperties())`, real `DirectionEvidenceResolver(AnalysisProperties())`, a `mock<FlowObservationService>()` (default: `historyEvidence` returns null, `ingest` no-op), and a real `ObjectMapper` configured snake_case: `ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).registerKotlinModule()` (imports `com.fasterxml.jackson.databind.PropertyNamingStrategies`, `com.fasterxml.jackson.module.kotlin.registerKotlinModule`).
- Update the `VideoAnalysisResponse` stubs to include `frameWidth = 1920, frameHeight = 1080` and give each stubbed `VehicleAnalysisResult` corridor fields: `corridorId = 0L, corridorCohesion = 1.0, trackFrameCount = 10, displacementPixels = 300.0`.
- Existing OneWay-path tests: a lone vehicle within tolerance of the illegal bearing now scores `1.0 (osm) × 1.0 (quality) × detectionConfidence × bearingMatchScore` — same numeric value as the old `detectionConfidence × bearingMatchScore`, so existing assertions on `wrongWayConfidence` remain valid. Existing `Unknown` / `NotFound` / `LookupFailed` tests change expectation: with no flow vehicles or history the message becomes `"Legal traffic direction could not be established for this street"` (for Unknown/NotFound/LookupFailed alike).

Add these new tests:

```kotlin
    @Test
    fun `unknown street with strong clip consensus confirms with breakdown`() {
        // 3 consensus vehicles east (~90), violator west (270) in the same corridor.
        stubResolution(DirectionResolution.Unknown("Khayaban-e-Jinnah"))
        stubVehicles(
            listOf(
                vehicle(1, bearing = 88.0, detection = 0.95),
                vehicle(2, bearing = 90.0, detection = 0.95),
                vehicle(3, bearing = 92.0, detection = 0.95),
                vehicle(4, bearing = 270.0, detection = 0.95, plate = "LEA-1234", plateConf = 0.8),
            ),
        )

        val report = pendingReport(compassHeading = BigDecimal.ZERO)
        job.applyOutcome(report)

        assertEquals(ReportStatus.CONFIRMED, report.status)
        assertEquals("LEA-1234", report.licensePlate)
        assertNotNull(report.directionEvidence)
        assertTrue(report.directionEvidence!!.contains("CLIP_CONSENSUS"))
        // clipConfidence = (3/5)*~1*1 = ~0.6; score = 0.6*1*0.95*~1 = ~0.57 >= 0.5
        assertTrue(report.wrongWayConfidence!!.toDouble() >= 0.5)
    }

    @Test
    fun `candidate moving with its corridor is not a violator even against osm`() {
        // OSM says legal=90, so illegal=270. All three vehicles flow 270 together -
        // a legal opposing stream (divided road), NOT three violators.
        stubResolution(DirectionResolution.OneWay("Main Blvd", 90.0))
        stubVehicles(
            listOf(
                vehicle(1, bearing = 268.0, detection = 0.95),
                vehicle(2, bearing = 270.0, detection = 0.95),
                vehicle(3, bearing = 272.0, detection = 0.95),
            ),
        )

        val report = pendingReport(compassHeading = BigDecimal.ZERO)
        job.applyOutcome(report)

        assertEquals(ReportStatus.REJECTED, report.status)
        // The corridor's consensus (270) conflicts with OSM (90) -> conflict message.
        assertEquals("Conflicting direction evidence for this street", report.analysisMessage)
    }

    @Test
    fun `below-threshold candidate rejects with the too-low message`() {
        // Single consensus partner -> clipConfidence = (1/3)*1*1 = 0.33; score
        // = 0.33 * 1 * 0.9 * 1 = ~0.3 < 0.5 threshold.
        stubResolution(DirectionResolution.Unknown(null))
        stubVehicles(
            listOf(
                vehicle(1, bearing = 90.0, detection = 0.9),
                vehicle(2, bearing = 270.0, detection = 0.9),
            ),
        )

        val report = pendingReport(compassHeading = BigDecimal.ZERO)
        job.applyOutcome(report)

        assertEquals(ReportStatus.REJECTED, report.status)
        assertEquals(
            "Possible wrong-way vehicle detected, but confidence was too low to confirm",
            report.analysisMessage,
        )
        assertNotNull(report.directionEvidence)
    }

    @Test
    fun `lookup failure with mature history proceeds to evaluation`() {
        stubResolution(DirectionResolution.LookupFailed("Overpass lookup failed"))
        whenever(flowObservationService.historyEvidence(any(), any()))
            .thenReturn(DirectionEvidence(EvidenceKind.LEARNED_HISTORY, 90.0, 0.6))
        stubVehicles(listOf(vehicle(1, bearing = 270.0, detection = 0.95)))

        val report = pendingReport(compassHeading = BigDecimal.ZERO)
        job.applyOutcome(report)

        // score = 0.6 * 1 * 0.95 * 1.0 = 0.57 >= 0.5 -> confirmed despite the OSM outage.
        assertEquals(ReportStatus.CONFIRMED, report.status)
    }

    @Test
    fun `observations are ingested for rejected reports too`() {
        stubResolution(DirectionResolution.Unknown(null))
        stubVehicles(
            listOf(
                vehicle(1, bearing = 88.0, detection = 0.95),
                vehicle(2, bearing = 92.0, detection = 0.95),
            ),
        )

        val report = pendingReport(compassHeading = BigDecimal.ZERO)
        job.applyOutcome(report)

        assertEquals(ReportStatus.REJECTED, report.status) // nobody moves against the flow
        verify(flowObservationService).ingest(eq(report), argThat { isNotEmpty() })
    }
```

(Adapt `stubResolution` / `stubVehicles` / `vehicle` / `pendingReport` helper names to the file's existing helpers — extend them with the new parameters rather than inventing parallel ones. `vehicle()`'s new corridor defaults: `corridorId = 0L, corridorCohesion = 1.0, trackFrameCount = 10, displacementPixels = 300.0`.)

- [ ] **Step 3: Run the job tests** — `./gradlew.bat test --tests "com.trafficwatch.server.reports.ReportAnalysisJobTest"` — expected: all PASS.

- [ ] **Step 4: Run the full server suite** — `./gradlew.bat test` — expected: all PASS (fix any test still constructing `ReportAnalysisJob` with the old constructor).

- [ ] **Step 5: Commit**

```bash
git add server/src/main/kotlin/com/trafficwatch/server/reports/ReportAnalysisJob.kt server/src/test/kotlin/com/trafficwatch/server/reports/ReportAnalysisJobTest.kt
git commit -m "feat(server): evidence-fusion analysis flow with corridor gates, breakdown persistence, and observation ingestion"
```

---

### Task 11: Server — evidence breakdown in the status response

**Files:**
- Modify: `server/src/main/kotlin/com/trafficwatch/server/reports/dto/ReportDtos.kt`
- Modify: `server/src/main/kotlin/com/trafficwatch/server/reports/ReportService.kt`
- Test: `server/src/test/kotlin/com/trafficwatch/server/reports/ReportServiceTest.kt` (extend)

**Interfaces:**
- Produces: `ReportStatusResponse.evidenceBreakdown: JsonNode?` (serializes as `evidence_breakdown` on the wire; consumed by Task 13's Android DTO).

- [ ] **Step 1: Write the failing test**

Append to `ReportServiceTest.kt` (adapting to its existing helper/mock names):

```kotlin
    @Test
    fun `status response carries the parsed evidence breakdown`() {
        val report = savedReport() // existing helper - adapt name
        report.directionEvidence = """{"final_score":0.57,"sources":[]}"""
        whenever(reportRepository.findByIdAndUserId(any(), any())).thenReturn(report)

        val response = reportService.getStatus(reportId, userId)

        assertNotNull(response.evidenceBreakdown)
        assertEquals(0.57, response.evidenceBreakdown!!.get("final_score").asDouble(), 1e-9)
    }

    @Test
    fun `status response has null breakdown for reports without one and for malformed json`() {
        val report = savedReport()
        report.directionEvidence = null
        whenever(reportRepository.findByIdAndUserId(any(), any())).thenReturn(report)
        assertNull(reportService.getStatus(reportId, userId).evidenceBreakdown)

        report.directionEvidence = "{not json"
        assertNull(reportService.getStatus(reportId, userId).evidenceBreakdown)
    }
```

- [ ] **Step 2: Run to verify failure** — `./gradlew.bat test --tests "com.trafficwatch.server.reports.ReportServiceTest"` — expected: compilation FAILURE.

- [ ] **Step 3: Implement**

In `ReportDtos.kt`, add to `ReportStatusResponse` after `wrongWayConfidence` (import `com.fasterxml.jackson.databind.JsonNode`):

```kotlin
    // Full direction-evidence breakdown JSON (see ReportAnalysisJob's EvidenceBreakdown)
    // - always present when the analysis stored one; rendered only by debug builds.
    val evidenceBreakdown: JsonNode?,
```

In `ReportService.kt`: add constructor parameter `private val objectMapper: ObjectMapper` (import `com.fasterxml.jackson.databind.ObjectMapper`), and in `toStatusResponse()` add:

```kotlin
            evidenceBreakdown = directionEvidence?.let {
                try {
                    objectMapper.readTree(it)
                } catch (ex: Exception) {
                    null
                }
            },
```

- [ ] **Step 4: Run to verify pass**, then the full suite — `./gradlew.bat test` — expected: all PASS (update any other test constructing `ReportService` or `ReportStatusResponse` with the new parameter/field).

- [ ] **Step 5: Commit**

```bash
git add server/src/main/kotlin/com/trafficwatch/server/reports/dto/ReportDtos.kt server/src/main/kotlin/com/trafficwatch/server/reports/ReportService.kt server/src/test/kotlin/com/trafficwatch/server/reports/ReportServiceTest.kt
git commit -m "feat(server): evidence_breakdown in the report status response"
```

---

### Task 12: Server — integration test for the no-tag confirmation path

**Files:**
- Modify: `server/src/test/kotlin/com/trafficwatch/server/reports/ReportAnalysisIntegrationTest.kt`

**Interfaces:**
- Consumes: everything from Tasks 3-11. Follow the file's existing WireMock stub helpers and submission flow — extend, don't restructure.

- [ ] **Step 1: Add the integration test**

Following the file's existing stub/submit/poll patterns, add one test:

```kotlin
    @Test
    fun `report confirms from clip consensus alone when osm has no oneway tag and writes an observation`() {
        stubNominatimReverse(road = "Street No 06")           // adapt to existing helper
        stubOverpassWays(onewayTag = null)                     // way present, NO oneway tag -> Unknown
        stubVideoAnalysis(
            // adapt to the existing stub helper; body must be the new response shape:
            """
            {
              "vehicles": [
                {"track_id": 1, "vehicle_type": "car", "detection_confidence": 0.95, "bearing_degrees": 88.0,
                 "plate_text": null, "plate_confidence": null, "corridor_id": 0, "corridor_cohesion": 1.0,
                 "track_frame_count": 10, "displacement_pixels": 300.0},
                {"track_id": 2, "vehicle_type": "car", "detection_confidence": 0.95, "bearing_degrees": 90.0,
                 "plate_text": null, "plate_confidence": null, "corridor_id": 0, "corridor_cohesion": 1.0,
                 "track_frame_count": 10, "displacement_pixels": 300.0},
                {"track_id": 3, "vehicle_type": "car", "detection_confidence": 0.95, "bearing_degrees": 92.0,
                 "plate_text": null, "plate_confidence": null, "corridor_id": 0, "corridor_cohesion": 1.0,
                 "track_frame_count": 10, "displacement_pixels": 300.0},
                {"track_id": 4, "vehicle_type": "car", "detection_confidence": 0.95, "bearing_degrees": 270.0,
                 "plate_text": "LEB-5678", "plate_confidence": 0.8, "corridor_id": 0, "corridor_cohesion": 1.0,
                 "track_frame_count": 10, "displacement_pixels": 300.0}
              ],
              "frame_width": 1920,
              "frame_height": 1080
            }
            """.trimIndent(),
        )

        val reportId = submitReportWithCompassHeading(BigDecimal.ZERO)  // adapt to existing helper
        val status = awaitTerminalStatus(reportId)                      // adapt to existing helper

        assertEquals("CONFIRMED", status.get("status").asText())
        assertEquals("LEB-5678", status.get("license_plate").asText())
        assertTrue(status.get("wrong_way_confidence").asDouble() >= 0.5)
        assertFalse(status.get("evidence_breakdown").isNull)
        assertEquals(
            "CLIP_CONSENSUS",
            status.get("evidence_breakdown").get("sources").get(0).get("kind").asText(),
        )

        val observations = flowObservationRepository.findAll()
        assertEquals(1, observations.size)
        assertEquals(3, observations[0].vehicleCount)
    }
```

(Autowire `FlowObservationRepository` into the test class. If the existing helpers don't parametrize the oneway tag or the video-analysis body, extend them — keeping their existing call sites compiling — rather than duplicating stub code.)

- [ ] **Step 2: Run it** — `./gradlew.bat test --tests "com.trafficwatch.server.reports.ReportAnalysisIntegrationTest"` — expected: all PASS (existing tests in the file included).

- [ ] **Step 3: Run the full server suite** — `./gradlew.bat test` — expected: all PASS.

- [ ] **Step 4: Commit**

```bash
git add server/src/test/kotlin/com/trafficwatch/server/reports/ReportAnalysisIntegrationTest.kt
git commit -m "test(server): end-to-end clip-consensus confirmation with observation ingestion"
```

---

### Task 13: Android — sync the evidence breakdown into local data

**Files:**
- Modify: `app/src/main/java/com/trafficwatch/app/core/data/remote/dto/ReportDtos.kt`
- Modify: `app/src/main/java/com/trafficwatch/app/core/domain/model/Report.kt`
- Modify: `app/src/main/java/com/trafficwatch/app/core/data/local/entity/ReportEntity.kt`
- Modify: `app/src/main/java/com/trafficwatch/app/core/data/local/AppDatabase.kt`
- Modify: `app/src/main/java/com/trafficwatch/app/core/data/local/dao/ReportDao.kt`
- Modify: `app/src/main/java/com/trafficwatch/app/core/data/repository/ReportRepository.kt`
- Modify: `app/src/main/java/com/trafficwatch/app/core/data/remote/MockApiService.kt`

**Interfaces:**
- Produces: `Report.evidenceBreakdownJson: String?` — consumed by Task 14's debug card.

- [ ] **Step 1: DTO** — in the Android `ReportDtos.kt`, add to `ReportStatusResponse` (import `com.google.gson.JsonObject`):

```kotlin
    @SerializedName("evidence_breakdown") val evidenceBreakdown: JsonObject?
```

- [ ] **Step 2: Domain model** — in `Report.kt`, add directly after `wrongWayConfidence`:

```kotlin
    val evidenceBreakdownJson: String? = null,
```

- [ ] **Step 3: Room entity** — in `ReportEntity.kt`, add `val evidenceBreakdownJson: String?` after `wrongWayConfidence`, and map it in BOTH `toDomain()` (`evidenceBreakdownJson = evidenceBreakdownJson`) and `fromDomain()` (`evidenceBreakdownJson = report.evidenceBreakdownJson`).

- [ ] **Step 4: DB version** — in `AppDatabase.kt`, bump `version = 2` to `version = 3` (destructive migration already configured in `DatabaseModule`).

- [ ] **Step 5: DAO** — extend `ReportDao.updateAnalysisResult`'s `@Query` SET clause with `evidenceBreakdownJson = :evidenceBreakdownJson,` (before `updatedAt = :updatedAt`) and add parameter `evidenceBreakdownJson: String?` after `wrongWayConfidence`.

- [ ] **Step 6: Repository** — in `ReportRepository.syncPendingReports()`'s `updateAnalysisResult(...)` call, add:

```kotlin
                    evidenceBreakdownJson = response.evidenceBreakdown?.toString(),
```

- [ ] **Step 7: Mock** — in `MockApiService.kt`, add `evidenceBreakdown = null` to its `ReportStatusResponse` construction(s).

- [ ] **Step 8: Build** — `./gradlew.bat :app:assembleDebug` (repo root) — expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/trafficwatch/app/core/data/remote/dto/ReportDtos.kt app/src/main/java/com/trafficwatch/app/core/domain/model/Report.kt app/src/main/java/com/trafficwatch/app/core/data/local/entity/ReportEntity.kt app/src/main/java/com/trafficwatch/app/core/data/local/AppDatabase.kt app/src/main/java/com/trafficwatch/app/core/data/local/dao/ReportDao.kt app/src/main/java/com/trafficwatch/app/core/data/repository/ReportRepository.kt app/src/main/java/com/trafficwatch/app/core/data/remote/MockApiService.kt
git commit -m "feat(app): sync evidence_breakdown from the server into local data"
```

---

### Task 14: Android — debug-only Score Breakdown card

**Files:**
- Modify: `app/src/main/java/com/trafficwatch/app/feature/history/ReportDetailScreen.kt`

**Interfaces:**
- Consumes: `Report.evidenceBreakdownJson` (Task 13), `BuildConfig.DEBUG` (existing import of `com.trafficwatch.app.BuildConfig` already present in this file).

- [ ] **Step 1: Add the card**

In `ReportDetailScreen.kt`, add imports:

```kotlin
import androidx.compose.ui.text.font.FontFamily
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
```

Add after the CONFIRMED-only block (as a sibling `if`, NOT inside the CONFIRMED gate — rejections are exactly when tuning needs the breakdown):

```kotlin
                // Debug builds only: the full evidence/score breakdown behind this
                // report's outcome, for threshold tuning. Release builds never
                // render this (and the data is harmless if present - it is the
                // user's own report's analysis detail).
                if (BuildConfig.DEBUG && r.evidenceBreakdownJson != null) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Score Breakdown (debug)", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                prettyJson(r.evidenceBreakdownJson),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
```

Add at the bottom of the file, next to `formatTs`:

```kotlin
private fun prettyJson(json: String): String = try {
    GsonBuilder().setPrettyPrinting().create().toJson(JsonParser.parseString(json))
} catch (e: Exception) {
    json
}
```

- [ ] **Step 2: Build** — `./gradlew.bat :app:assembleDebug` — expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/trafficwatch/app/feature/history/ReportDetailScreen.kt
git commit -m "feat(app): debug-only evidence score breakdown card on report detail"
```

---

### Task 15: Manual end-to-end verification

**Files:** none (verification only).

- [ ] **Step 1: Start the full stack**

From `server/`: `docker compose up -d`, then (PowerShell) `$env:JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true"; $env:SPRING_PROFILES_ACTIVE="local"; ./gradlew.bat bootRun` — the IPv4 flag is REQUIRED on this dev machine (overpass-api.de's IPv6 route is broken here and hangs the JVM's default dual-stack client).
From `video-analysis/`: `.venv/Scripts/python.exe -m uvicorn app.main:app --host 0.0.0.0 --port 8000`.

- [ ] **Step 2: Install the debug build on the connected device**

From repo root: `./gradlew.bat :app:installDebug`. If the device's Wi-Fi cannot reach this machine's LAN IP, use `adb reverse tcp:8080 tcp:8080` with `DEV_SERVER_URL=http://127.0.0.1:8080/v1/` in `local.properties` (the setup already used during prior verification).

- [ ] **Step 3: The no-tag street now works**

Record at the same location as report `f0798d95-...-b5e3b` (previously a guaranteed `Unknown` dead-end) with normal traffic visible plus, ideally, a wrong-way vehicle. Submit; open the report on the detail screen.
Expected: the Score Breakdown (debug) card shows a `CLIP_CONSENSUS` source; with >= 3 consensus vehicles and a genuine violator the report reaches CONFIRMED with `final_score >= 0.5`; with thin traffic it rejects with the "confidence too low" or "could not be established" message — visible in the breakdown either way.

- [ ] **Step 4: History accumulates**

Submit a second report at the same spot. Check `flow_observations`:
`docker exec trafficwatch-postgres psql -U trafficwatch -d trafficwatch -c "SELECT lat_bucket, lon_bucket, bearing_degrees, vehicle_count, reporter_id FROM flow_observations;"`
Expected: one row per qualifying corridor per report, same bucket, consistent bearings.

- [ ] **Step 5: OSM-tagged street still works and cross-checks**

Record at a street OSM tags `oneway=yes` (`خیبان جناح` near the test location). Expected: with traffic flowing legally + a violator, CONFIRMED with `OSM_TAG` (and possibly `CLIP_CONSENSUS`) sources in the breakdown; `direction_confidence` 1.0 or higher-than-either via noisy-OR.

- [ ] **Step 6: Old reports degrade gracefully**

Open a CONFIRMED report from before this feature. Expected: no Score Breakdown card (null breakdown), everything else renders as before, no crash.
