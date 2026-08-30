# Stationary-Camera Approach Detection Design

## Context

Five user-confirmed wrong-way reports on خیبان جناح / Khayaban-e-Jinnah,
Lahore (`101aef9e...759cd`, `9e44e167...24908`, `7d578a63...a5275`,
`91ce999b...50bcc6`, `e4d53e59...71f78`) all filmed the same class of
event: a stationary dashcam inside a car, pointed down the road, filming a
motorcycle riding straight at the camera against traffic. None was
CONFIRMED. Investigation over 2026-08-30 traced a chain of independent
gaps:

1. **OSM matched the wrong carriageway** on a divided road (northbound
   legal 11 deg where the traffic is southbound ~193 deg), from an
   incomplete Overpass response -> "conflicting evidence" reject. Fixed
   separately:
   `docs/superpowers/specs/2026-08-30-overpass-multi-source-resolution-design.md`.
2. **The wrong-way rider's frame bearing is understated.** Riding close to
   the camera and near the frame edge, its centroid sweeps far enough
   (parallax) that `tracking_bearing.py` returns a `"centroid"` bearing
   only 20-74 deg off the traffic flow, never ~180 deg opposite. Was to be
   fixed by `docs/superpowers/specs/2026-08-30-near-camera-approach-bearing-fix-design.md`
   ("Fix A") - **that spec is superseded by this one** (see Design section 5).
3. **A spatially isolated lone violator has nothing to score against.** The
   rider hugs the median, so it is alone in its clip corridor;
   `evaluateCandidates` only scores a candidate against its own corridor's
   consensus, OSM, or learned history, and with OSM `Unknown` and sparse
   history there is no evidence source -> `Insufficient` -> not scored.
4. **The frame-to-world bearing conversion is unreliable.** A 2026-08-30
   spike traced report `759cd`: the camera heading (rotation samples,
   steady ~170 deg) agrees with the true road direction (~193 deg), but
   `absoluteBearing = orientation + frameBearing` produced a clip consensus
   of ~98 deg - a ~92 deg error - because receding traffic tracks toward an
   off-axis vanishing point (frame bearing ~290 deg), not straight up the
   frame (0 deg) as the conversion assumes.

Gaps 3 and 4 mean no amount of OSM or bearing fixing reliably confirms
these reports. This design sidesteps both.

## Scope decision (confirmed with the user during brainstorming - do not re-litigate)

- **Approach C, not a full direction-pipeline rewrite.** For a stationary
  camera, "is this vehicle approaching or receding" (bounding-box scale
  trend) is a cleaner wrong-way signal than any bearing: no compass, no
  vanishing-point model, no per-carriageway OSM bearing. On a one-way
  street where the majority of vehicles recede (legal traffic going
  downstream, camera pointed downstream), a vehicle that clearly and
  sustainedly approaches is going the wrong way.
- **Additive fallback that can only upgrade REJECTED -> CONFIRMED, never
  the reverse (Q2 option a).** The existing bearing-based
  `evaluateCandidates` runs first and unchanged. This path is consulted
  only when that path already produced REJECTED on a non-two-way street.
  Every existing test asserts the same outcome it does today.
- **Hard gates, not a tuned score.** A 2026-08-30 prototype run of the
  `scale_trend` classifier against all five real videos (see Appendix)
  showed the discriminating signal is clean: real violators grow their
  bbox by 0.93 to 2.24 over 34 to 80 frames, while every non-violator that
  registered any growth topped out at 0.44 (one at 0.70, over only 14
  frames). So the wrong-way determination is a set of conservative gates;
  the "confidence" is just the detection confidence.
- **Fix A is folded in and its spec superseded** (Design section 5).
- **B (clip-flow-relative bearing) is future work**, documented in Design
  section 6 - it covers what C cannot: moving-camera clips (`71f78`), and a
  wrong-way vehicle that drives *past* the camera or crosses laterally
  without a strong scale change (`50bcc6`).

## Expected outcome on the five reports

From the Appendix prototype run:

| report | fires? | result | reason |
|---|---|---|---|
| `759cd` | yes | **CONFIRMED** (conf 0.90) | violator: growth 1.52 / 72 frames |
| `24908` | yes | **CONFIRMED** (conf 0.89) | violator: growth 2.24 / 80 frames; 2 decoy growers fail the strong-grower gate |
| `a5275` | yes | **CONFIRMED** (conf 0.78) | violator: growth 0.93 / 34 frames; shrinking 3, `3 >= 3*1` |
| `50bcc6` | no | REJECTED (unchanged) | no strong grower - its motorcycle drives *past* the camera (grows then recedes -> `flat`). Approach detection fundamentally cannot catch a violator that passes the camera. |
| `71f78` | no | REJECTED (unchanged) | camera speed 1.19 m/s > 1.0 -> gate 1 fails. Deferred to B. |

3 of 5. The two misses are structural, not tuning: one violator passes the
camera; one clip has a moving camera.

## Design

### 1. video-analysis: `scale_trend` and `scale_growth_fraction`

`tracking_bearing.py` gains a three-way classifier generalising Fix A's
`_has_sustained_growth`, over the track's bounding-box diagonals split into
three equal time-ordered segments (means `s1`, `s2`, `s3`; the pipeline
only calls this for tracks with `>= MIN_OBSERVATIONS` frames, so each
segment has `>= 4`):

```python
def scale_trend(bboxes: Sequence[Tuple[float, float, float, float]]) -> tuple[str, float]:
    """Returns (trend, growth_fraction).
    trend: 'growing' (approaching the camera), 'shrinking' (receding), or
    'flat' (lateral / far / stable, or grows-then-shrinks). Growth/shrink
    must be monotonic across all three segments AND clear
    MIN_SCALE_CHANGE_FRACTION overall, so a single blown-up or dropped-out
    frame does not register as a trend, and a vehicle that passes the
    camera (grows then recedes) is 'flat'.
    growth_fraction: (s3 - s1) / s1 when 'growing', else 0.0."""
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

- New fields on `VehicleResult` (`app/schemas.py`): `scale_trend: str`
  (default `"flat"`), `scale_growth_fraction: float` (default `0.0`).
  Populated in `pipeline.py`'s `_summarize_track` from the track's full
  bbox list; `"flat"` / `0.0` when the track has fewer than
  `MIN_OBSERVATIONS` frames.
- New fields on `VehicleAnalysisResult`
  (`server/.../videoanalysis/dto/VideoAnalysisDtos.kt`): `scaleTrend:
  String = "flat"`, `scaleGrowthFraction: Double = 0.0`. Jackson
  snake_case maps them; defaults cover an older service version (graceful
  degradation, matching every other optional field there).
- `resolve_bearing`'s Fix A behaviour is expressed against this: when
  lateral displacement clears the floor, `if scale_trend(bboxes)[0] ==
  "growing": return (180.0, "scale")`, else the existing `"centroid"`
  return. The negligible-lateral scale branch below is unchanged.

### 2. `OrientationTimeline.wasStationaryThroughout`

```kotlin
/**
 * True only when location_samples exist AND every one reports a GPS speed
 * at or below MIN_SPEED_FOR_RELIABLE_BEARING_MPS (1.0 m/s, ~walking pace).
 * Gates the stationary-approach path: bbox growth is only safely
 * attributable to the OTHER vehicle's motion when the recording vehicle
 * itself did not move for the whole clip. No location_samples -> cannot
 * verify -> false (conservative).
 */
fun wasStationaryThroughout(): Boolean =
    locationSamples.isNotEmpty() &&
        locationSamples.all { it.speed <= MIN_SPEED_FOR_RELIABLE_BEARING_MPS }
```

### 3. `ReportAnalysisJob` - the fallback branch

In `determineOutcome`, after `buildOutcome` and `ingestObservations`:

```kotlin
if (outcome.status == ReportStatus.REJECTED && resolution !is DirectionResolution.TwoWay) {
    tryStationaryApproachDetection(report, analysis.vehicles, orientationTimeline, streetName)
        ?.let { return it }
}
return outcome
```

`tryStationaryApproachDetection(report, vehicles, orientationTimeline, streetName): AnalysisOutcome?`:

1. `if (!orientationTimeline.wasStationaryThroughout()) return null`
2. `shrinking` = vehicles with `scaleTrend == "shrinking"` and
   `trackFrameCount >= MIN_TRACK_FRAMES` (9).
   `if (shrinking.size < 3) return null`
3. `strongGrowers` = vehicles with `scaleTrend == "growing"` AND
   `scaleGrowthFraction >= approachGrowthMin` (0.8) AND
   `trackFrameCount >= approachMinFrames` (30) AND
   `detectionConfidence >= approachMinDetection` (0.5).
   `if (strongGrowers.isEmpty()) return null`
4. `if (shrinking.size < 3 * strongGrowers.size) return null`
   (legal traffic must dominate - guards against flagging a stream of
   legal opposing traffic on a divided road's far carriageway).
5. `best` = `strongGrowers.maxByOrNull { it.detectionConfidence }!!`
6. `if (best.detectionConfidence < analysisProperties.confirmationThreshold) return null`
   (0.5 - reuses the existing threshold; the gates above already made the
   wrong-way determination, this is just "is it a real detection").
7. Return:
   ```kotlin
   AnalysisOutcome(
       status = ReportStatus.CONFIRMED,
       licensePlate = best.plateText,
       confidence = best.plateConfidence?.let(BigDecimal::valueOf),
       message = "Wrong-way vehicle approaching a stationary camera on ${streetName ?: "this street"}",
       streetName = streetName,
       wrongWayConfidence = BigDecimal.valueOf(best.detectionConfidence),
       wrongWayFramePath = annotateAndStoreFrame(best, requireNotNull(report.id)),
       directionEvidenceJson = approachBreakdownJson(best, shrinking.size, strongGrowers.size),
   )
   ```

`annotateAndStoreFrame` and `confirmationThreshold` are reused as-is. No
`flow_observations` ingestion from this path (no world bearing to
contribute - deliberate).

New constants on `AnalysisProperties` (config-bound via `app.analysis.*`,
matching the existing pattern): `approachGrowthMin = 0.8`,
`approachMinFrames = 30`, `approachMinDetection = 0.5`.

### 4. Persistence & `direction_evidence`

New breakdown JSON written to `reports.direction_evidence`:

```json
{ "method": "stationary_approach",
  "receding_count": 4, "strong_grower_count": 1,
  "growth_fraction": 1.52, "track_frames": 72,
  "detection_confidence": 0.90, "confirmation_threshold": 0.5 }
```

- `EvidenceBreakdown` (in `ReportAnalysisJob.kt`) becomes a two-variant
  shape keyed by a `method` discriminator - the existing bearing-path
  fields unchanged, this a sibling. `breakdownJson` (bearing path) is
  untouched; `approachBreakdownJson` is new.
- `app/.../feature/history/ReportDetailScreen.kt` reads `evidence_breakdown`
  as a generic `JsonObject`. Check current rendering; if it formats known
  keys, add a `method == "stationary_approach"` branch, otherwise confirm
  it degrades gracefully on the new shape.
- `ReportStatusResponse.evidenceBreakdown` (`app` + `server` DTOs) is
  already a free-form `JsonObject` - no DTO change.

### 5. Fix A is superseded

`docs/superpowers/specs/2026-08-30-near-camera-approach-bearing-fix-design.md`
described `_has_sustained_growth` and a `resolve_bearing` override. This
spec's `scale_trend` classifier is the generalisation of that helper, and
section 1 keeps the exact override behaviour. Implement once, here.

Action: add a header note to the Fix A spec file - *"Superseded by
2026-08-30-stationary-approach-detection-design.md; its mechanism
(sustained-growth detection and the resolve_bearing override) is folded
into that design's `scale_trend` classifier. Do not implement this spec
separately."* - keep the file for context.

The override still has independent value: it corrects the *bearing* of an
approaching vehicle in the ordinary bearing path too, so a near-camera
wrong-way rider that shares a corridor with traffic (not the isolated
case) is scored correctly there without needing this fallback.

### 6. Future work - B: clip-flow-relative bearing (NOT in this design)

For what C cannot reach:
- **Moving camera** (`71f78`, 1.19 m/s) - `wasStationaryThroughout` is
  false, and a moving camera makes bbox growth ambiguous ("did it approach
  me, or did I approach it").
- **A violator that drives past the camera** (`50bcc6`) - grows then
  recedes -> `flat`, never a strong grower.
- **A wrong-way vehicle crossing laterally**, far from the camera, with no
  strong scale change.

Idea: stop converting frame bearings to world bearings with the compass.
Calibrate from the clip itself - the dominant traffic flow's frame bearing
`F_flow` corresponds to the legal direction (most traffic is legal), so a
vehicle whose frame bearing is `~ F_flow + 180 deg` is wrong-way. The
compass error and the vanishing-point offset are both roughly constant
across the clip and cancel out of the difference. Known residual: the
perspective offset is not perfectly uniform across frame position, so
`F_flow + 180 deg` is an approximate target (a tolerance band). This
replaces `absoluteBearing` throughout `ClipFlowAnalyzer` /
`DirectionEvidenceResolver` / `evaluateCandidates` - a much larger change,
its own spec when C's coverage proves insufficient.

## Testing

### video-analysis (`tests/test_bearing.py`, `tests/test_pipeline.py`)

- `scale_trend`: synthetic tracks returning `("growing", frac)` (monotonic
  growth >= threshold), `("shrinking", 0.0)` (monotonic shrink >=
  threshold), `("flat", 0.0)` for: stable; below-threshold growth;
  one-frame spike breaking monotonicity; **grows-then-shrinks** (passes the
  camera); fewer than `MIN_OBSERVATIONS` frames.
- `growth_fraction` value correct for a known growing track.
- `pipeline.py`: a `VehicleResult` carries `scale_trend` and
  `scale_growth_fraction` end to end.
- Fix A's bearing-override cases re-expressed: large lateral sweep +
  `scale_trend == "growing"` -> `(180.0, "scale")`; `"shrinking"` /
  `"flat"` -> unchanged `"centroid"` bearing.
- Regression: full existing `test_bearing.py` / `test_pipeline.py` pass
  unchanged.

### Kotlin

- `OrientationTimelineTest.wasStationaryThroughout`: all samples <= 1.0 ->
  true; one sample at 3.0 -> false; empty -> false.
- `VideoAnalysisDtos` / client test: `scale_trend` /
  `scale_growth_fraction` deserialize; absent -> `"flat"` / `0.0`.
- `ReportAnalysisJobTest` (new cases), each starting from a bearing-path
  REJECTED, stationary `sampleReport` with `location_samples` all speed 0:
  - 4 shrinking + 1 strong grower (growth 1.0, 60 frames, det 0.9), OSM
    `OneWay` -> CONFIRMED via approach path; `wrongWayConfidence == 0.9`,
    plate from the grower, message is the new string, `direction_evidence`
    contains `"stationary_approach"`.
  - same but `resolution` is `TwoWay` -> REJECTED (branch gated).
  - same but one `location_sample` at 3.0 m/s -> REJECTED (not stationary).
  - same but `sampleReport` has no `location_samples` -> REJECTED.
  - 2 shrinking + 1 strong grower -> REJECTED (`shrinking < 3`).
  - 3 shrinking + 2 strong growers -> REJECTED (`3 < 3*2`).
  - 5 shrinking + 1 grower with growth 0.5 -> REJECTED (fails
    `approachGrowthMin`).
  - 5 shrinking + 1 grower with 20 frames -> REJECTED (fails
    `approachMinFrames`).
  - strong grower present, gates pass, but its `detectionConfidence` is
    0.45 -> REJECTED (below `confirmationThreshold`).
  - bearing path CONFIRMS -> approach path never consulted; plate,
    confidence, message, `wrong_way_frame_path` all from the bearing path.
  - **Regression:** every existing `ReportAnalysisJobTest` case asserts its
    current outcome unchanged - audit each; they are either not
    stationary-with-receding-majority, already CONFIRMED, `TwoWay`, or
    lack `location_samples` in `sampleReport`. If `sampleReport` gains
    `location_samples` by default, add `scaleTrend` defaults to the
    `vehicle(...)` test helper so existing vehicles are `"flat"`.
- `ReportAnalysisIntegrationTest`: one end-to-end stationary-approach
  fixture (video-analysis stub returns vehicles with `scale_trend` set)
  -> CONFIRMED, `direction_evidence` persisted.

### Production replay

Re-run the `video-analysis` pipeline (with `scale_trend` added) and trace
the Kotlin path for each report:
- `759cd`, `24908`, `a5275` -> CONFIRMED via the approach path, with the
  detection confidences in the table above.
- `50bcc6` -> still REJECTED; confirm no strong grower is produced.
- `71f78` -> still REJECTED; confirm `wasStationaryThroughout` is false.

## Non-goals (explicitly out of scope)

- **Moving-camera clips (`71f78`), violators that pass the camera
  (`50bcc6`), and lateral-crossing wrong-way vehicles** - the future B
  path (section 6).
- **`a5275` track fragmentation** - a separate ByteTrack-continuity item.
  C confirms `a5275` from the surviving 34-frame fragment; fixing the
  fragmentation is not needed for this design but remains its own backlog
  item.
- **`corridor_cohesion` under-confirmation** - its own backlog item; C does
  not touch it (and, per the prototype run, does not moot it for `50bcc6`
  either).
- **The Overpass multi-source fix** (`4a382be`) - still required and valid.
  C makes the *carriageway choice* irrelevant to stationary-approach
  detection (no world bearing used), but the bearing path and the
  two-way/one-way determination still depend on good OSM data.
- **`flow_observations` ingestion from this path** - deliberate; no world
  bearing.
- **Changing the confirmation threshold** (0.5) or the bearing path's
  scoring.
- **Retroactive re-analysis of already-submitted reports.**

## Appendix - 2026-08-30 prototype run

The `scale_trend` classifier (three-segment monotonic, `MIN_SCALE_CHANGE_
FRACTION` = 0.15) was run against all five report videos via the real
`VehicleDetector` tracking loop. Tracks with `>= 9` frames:

| report | growing | shrinking | flat | violator track | violator growth / frames / det |
|---|---|---|---|---|---|
| `759cd` | 1 | 4 | 9 | moto 1000001 | 1.52 / 72 / 0.895 |
| `24908` | 3 | 10 | 10 | moto 1000027 | 2.24 / 80 / 0.886 |
| `a5275` | 2 | 3 | 3 | moto 1000016 | 0.93 / 34 / 0.778 |
| `50bcc6` | 5 | 5 | 23 | (not growing) | - |
| `71f78` | 4 | 8 | 18 | moto 1000095 | 1.19 / 62 / 0.890 |

Non-violator "growing" tracks across all clips: growth fractions
0.16-0.44, except `71f78` id 8 at 0.70 over 14 frames. None clears
`growth >= 0.8 AND frames >= 30`.
