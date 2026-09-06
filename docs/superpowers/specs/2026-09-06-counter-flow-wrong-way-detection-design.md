# Counter-Flow Wrong-Way Detection (head-on / near-camera approach) — Design

**Status:** draft for review

## Problem

A motorcycle riding **against traffic, straight toward the camera**, is not flagged.
Confirmed concrete case: report `14872a1a` (Jinnah Ave, Lahore; stationary dashcam).
A rider in a black shirt on a red Honda comes head-on against the downstream flow
from ~6.4 s to ~8.5 s of the clip. The stored outcome is REJECTED.

Three failures compound (all verified against the real clip, 2026-09-06):

1. **Detection is unstable head-on.** As the bike closes on the lens, the rider's
   body occludes almost all of it — only the front wheel/forks show. YOLOv8n's
   `motorcycle` confidence falls to ~0.44 and for ~4-6 frames drops out entirely
   (mislabeled `bicycle` 0.43-0.59). The **rider** (`person`) is detected at
   0.70-0.91 throughout, but `person` is not a vehicle class.

2. **ByteTrack fragments the rider.** He is on screen ~64 frames; the longest
   coherent motorcycle track through that window is **16 frames** — just the tail
   as he swerves past the camera. Near-camera + near-zero image-plane velocity
   (aimed at the lens) + dense surrounding traffic defeats association. Lowering
   the detection floor to 0.25 makes detections continuous but does **not** fix
   the fragmentation (tested); person-anchored "rider unit" tracking produced a
   longer track but ID-swapped two different riders (tested). A larger YOLO model
   would stabilise detection but is ~3x slower — straight back into the 180 s
   timeout this project just escaped.

3. **The surviving signal has nowhere to land.** The 16-frame fragment *was*
   scored — the 2026-09-05 re-run shows a candidate at `bearing_match_score`
   **0.94**. It still REJECTED because:
   - `14872a1a` resolves to `Unknown(AMBIGUOUS_NEAREST_STREET)` → no OSM legal
     bearing → `direction_confidence` only 0.38 (CLIP_CONSENSUS alone) →
     `final_score` 0.19 vs the 0.5 bar.
   - `ReportAnalysisJob.determineOutcome`'s stationary-approach fallback
     (`tryStationaryApproachDetection`) is gated by `approachEligible` to
     `OneWay` or `Unknown(DIVIDED_CARRIAGEWAY)` only — `AMBIGUOUS_NEAREST_STREET`
     is excluded, so the fallback never runs.
   - Even if it ran, `approachMinFrames` is 30 and the fragment is 16.

## Approach

Do **not** chase a clean ≥30-frame track — testing says it is not reliably
obtainable here without a heavier model. Instead, make a **short fragment of a
counter-flowing vehicle sufficient**, using a signal that survives perspective
distortion: the vehicle's frame-space velocity **relative to the clip's own
dominant traffic flow**.

Three layers, smallest first:

### Layer 1 — detection floor (supporting)

`video-analysis/app/config.py`, `app/detection.py`.

Add `motorcycle_min_confidence: float = 0.25` to `Settings`. In
`VehicleDetector._detect_frame`, apply it to `motorcycle`-class detections
(COCO id 3, `MOTORCYCLE_CLASS_ID`) in place of `min_detection_confidence`;
car/bus/truck keep the 0.4 floor. No `bicycle` handling — out of scope by
decision; the ~4-6 frame `bicycle`-mislabel gap is left for ByteTrack to coast
(its motorcycle instance's `lost_track_buffer` already covers a gap that size).

```python
vehicle_mask = np.isin(detections.class_id, list(VEHICLE_CLASS_IDS.keys()))
is_motorcycle = detections.class_id == MOTORCYCLE_CLASS_ID
floor = np.where(is_motorcycle,
                 self._settings.motorcycle_min_confidence,
                 self._settings.min_detection_confidence)
detections = detections[vehicle_mask & (detections.confidence >= floor)]
```

Purpose: give Layers 2 and 3 a continuous, if noisy, detection stream for the
head-on rider. Not sufficient alone.

**FP cost:** more low-confidence motorcycle detections clip-wide. Absorbed by
(a) ByteTrack's own track-confirmation (a one-off 0.26 blob never becomes a
confirmed track), (b) the server's `MIN_TRACK_FRAMES` (9) / `MIN_OBSERVATIONS`
(12) gates, (c) the 2026-09-05 change that makes sub-12-frame tracks skip
OCR/frame-encoding so the extra tracks are cheap.

### Layer 2 — widen the stationary-approach gate (supporting, also helps other reports)

`server/.../ReportAnalysisJob.kt`, `AnalysisProperties.kt`, `application.yml`.

- `approachEligible` (`determineOutcome`, ~line 191): extend from
  `Unknown.reason == DIVIDED_CARRIAGEWAY` to **any `DirectionResolution.Unknown`
  reason**. `OneWay` unchanged; `NotFound` / `LookupFailed` still excluded (no
  confidence we are even on a mapped road).
- The `Unknown`-branch corroboration gate inside `tryStationaryApproachDetection`
  is unchanged, verbatim: camera stationary throughout, exactly one strong
  grower, `scaleGrowthFraction >= approachGrowthMin` (0.8), `detectionConfidence
  >= approachMinDetection` (0.5), receding consensus `memberCount >=
  approachCorroborationMinMembers` (5) AND `resultantLength >=
  approachCorroborationMinResultantLength` (0.9). That gate is the safety
  mechanism; the reason-restriction was ship-time caution, not a load-bearing
  check.
- `approachMinFrames` 30 → **20** (`AnalysisProperties.kt` default and
  `application.yml`). The corroboration gate — not the frame count — is what
  makes this safe; 30 was a conservative first calibration. (20, not 16, so this
  layer does not by itself become the `14872a1a` confirm path — Layer 3 is that
  path, deliberately, because its counter-flow gate is stronger than a bare
  frame-count relaxation.)

**FP cost:** a genuinely two-way street that OSM failed to tag, with a camera
aimed at legal oncoming traffic, could now reach the approach path. Mitigation:
that scene shows *many* growers (all the oncoming cars), not one — the
"exactly one strong grower" + growth ≥0.8 + ≥20 frames gates reject it. The
≥5-member R≥0.9 receding-consensus requirement further requires a strong
*same-direction* stream, which a two-way street split down the middle of the
frame does not produce.

### Layer 3 — frame-space flow alignment + counter-flow confirm path (the fix)

#### 3a. New signal: `flow_alignment`

`video-analysis/app/tracking_bearing.py`, `app/pipeline.py`, `app/schemas.py`.

New `VehicleResult` field `flow_alignment: float | None = None` ∈ [−1, 1], or
`None` when the track has too little motion to have a direction.

Per track, in `pipeline.AnalysisPipeline`:

1. **Per-track velocity, distance-gated.** Take the frames where the track's
   bbox diagonal is in its smallest 40 % (the vehicle is farthest → least
   perspective distortion, and for an approacher this is before the near-camera
   swerve). Compute `v_i` = unit vector of (mean centroid of the *late* half of
   that window − mean centroid of the *early* half). If the windowed
   displacement is `< MIN_DISPLACEMENT_PIXELS`, `v_i = None` (no direction).
   Rationale for windowing on *smallest* bbox rather than first-N frames: a
   track that is only ever caught *during* a near-camera pass (the `14872a1a`
   fragment) has no "far" phase — its smallest-40 % window is still its own
   earliest, least-swept frames, which is the best available estimate.

2. **Clip dominant flow.** `F` = the direction maximising `Σ |d_i| · (v_i · F)`
   over all tracks with `v_i != None`, where `|d_i|` is that track's windowed
   pixel displacement (displacement-weighted circular mean of the `v_i`
   angles). Also compute `flow_coherence` = the mean resultant length R of
   those weighted `v_i` (0 = no agreement, 1 = perfect). Attach both to
   `AnalyzeResponse` as `dominant_flow_degrees: float | None` and
   `flow_coherence: float` (new response-level fields; `None`/0 when < 2
   directional tracks).

3. **Per-track alignment.** `flow_alignment_i = v_i · F` ∈ [−1, 1]. `None` when
   `v_i` is `None`.

No change to `bearing_degrees` / `bearing_source` / `resolve_bearing` — this is
an additive signal.

#### 3b. Server: counter-flow confirm path

`server/.../videoanalysis/dto/VideoAnalysisDtos.kt`: add
`flowAlignment: Double? = null` to `VehicleAnalysisResult`, and
`dominantFlowDegrees: Double? = null` / `flowCoherence: Double? = null` to
`VideoAnalysisResponse` (all default-null → old service responses parse
unchanged).

`ReportAnalysisJob.determineOutcome`: after the existing stationary-approach
block, a third parallel fallback that only ever upgrades REJECTED → CONFIRMED,
`tryCounterFlowDetection`:

```
gate:
  - resolution eligible: OneWay OR Unknown(DIVIDED_CARRIAGEWAY) use the base gate below;
    every other Unknown reason (two-way in OSM) needs the strong gate:
      flowCoherence >= counterFlowStrongCoherence (0.85)
      && forwardStream >= counterFlowStrongMinWithFlow (8)
  - analysis.flowCoherence != null && >= counterFlowMinCoherence (0.60)
  - candidates = vehicles where
      flowAlignment != null
      && flowAlignment <= counterFlowMaxAlignment (-0.6)
      && (trackFrameCount ?: 0) >= counterFlowMinFrames (12)
      && detectionConfidence >= confirmationThreshold (0.5)
      && displacementPixels >= minDisplacementFraction (0.15) * bbox diagonal
  - candidates.size == 1                      // lone counter-flowing anomaly
  - count(vehicles with flowAlignment != null && flowAlignment >= 0.5
          && (trackFrameCount ?: 0) >= MIN_TRACK_FRAMES) >= counterFlowMinWithFlow (5)
                                              // a real, populated forward stream
outcome: CONFIRMED, message
  "Wrong-way vehicle moving against traffic on <street>",
  wrongWayConfidence = best.detectionConfidence,
  wrongWayFramePath = annotateAndStoreFrame(best, ...),
  directionEvidenceJson = counterFlowBreakdownJson(best, forwardCount,
      analysis.flowCoherence, best.flowAlignment)
```

Eligibility: runs for `OneWay` and **all `Unknown`** reasons (same widened set
as Layer 2), and — unlike the stationary-approach path — **does not require a
stationary camera**. A moving camera induces apparent motion on every vehicle
roughly equally, so `flow_alignment` (a *relative* measure) still separates a
counter-flowing vehicle; and a genuine head-on approacher on a moving camera
counter-flows even harder (closing speed = camera speed + its own). `NotFound` /
`LookupFailed` excluded, as in Layer 2.

New `AnalysisProperties` (all with `application.yml` entries):
`counterFlowMinCoherence = 0.60`, `counterFlowMaxAlignment = -0.6`,
`counterFlowMinFrames = 12`, `counterFlowMinWithFlow = 5`,
`counterFlowStrongCoherence = 0.85`, `counterFlowStrongMinWithFlow = 8`.

> **Amendment 2026-09-06 (implementation ruling):** `counterFlowMinCoherence`
> revised `0.75 → 0.60`. `flow_coherence` is the mean resultant length R over the
> ≥ `MIN_OBSERVATIONS` (12) directional tracks, so an N-forward / 1-counter split
> gives R = (N−1)/(N+1) — 0.75 would require ~8 forward tracks before the gate
> could ever fire. 0.60 matches the project's existing
> `consensus-min-resultant-length` and still fires on a quiet-road 5-forward +
> 1-wrong-way clip (R ≈ 0.667), while a genuine two-way head-on split (R ≈ 0)
> still never fires. The lone-anomaly / ≥5-forward-stream / ≥12-frame /
> ≥0.5-detection gates carry the FP protection; coherence is the secondary "is
> there a flow to be counter to" check. Production FP-watch tunes it further.

> **Amendment 2026-09-06 (whole-branch review, C1):** counter-flow eligibility is
> split by resolution. `OneWay` and `Unknown(DIVIDED_CARRIAGEWAY)` assert
> one-wayness (explicitly, or structurally via a divided road) and keep the base
> gate above. Every *other* `Unknown` reason — `NO_ONEWAY_TAG`,
> `AMBIGUOUS_NEAREST_STREET`, `NOT_CROSS_CHECKED` — is **two-way in OSM
> semantics**: "5 forward + 1 oncoming" is legal two-way traffic and
> `flow_alignment` cannot tell it apart from a violation. Those reasons are
> eligible only behind a strong gate — `flow_coherence ≥ 0.85` (needs ~9+ coherent
> forward tracks) **and** `forwardStream ≥ 8` — i.e. a genuinely busy,
> overwhelmingly one-directional scene. The candidate filter also gained a
> size-relative displacement gate (`displacementPixels ≥ 0.15 × bbox diagonal`,
> reusing `minDisplacementFraction`), matching every other confirm path, so an
> 8-pixel reverse/creep no longer qualifies.

**Why this is safe:** it fires only when the clip contains a large (≥5),
coherent (R ≥ 0.60 base; R ≥ 0.85 for a non-one-way `Unknown`) forward stream AND
exactly one vehicle, tracked ≥12 frames, detected ≥0.5 and displaced ≥15 % of its
own bbox diagonal, moving strongly against it (alignment ≤ −0.6). On a resolution
that does not assert one-wayness (`NO_ONEWAY_TAG` / `AMBIGUOUS_NEAREST_STREET` /
`NOT_CROSS_CHECKED`) the strong gate (R ≥ 0.85 **and** ≥8 forward tracks) is what
keeps legal two-way traffic from confirming. A legally turning vehicle briefly
opposes the flow but (a) rarely reaches −0.6 over its distance-gated window, (b)
is usually not alone if it's a turn lane, (c) its detection often doesn't clear
0.5 through the turn. Cross-traffic at a junction is perpendicular (alignment ≈
0), not ≤ −0.6.

## `14872a1a` walk-through (expected)

- Layer 1: rider detected ~every frame f192-254 (floor 0.25).
- Tracking: still fragments; the `moto` swerve fragment is ~16 frames
  (f239-254), `cx` 635→1006, `cy` ~1033→1167.
- Layer 3a: that fragment's distance-gated `v_i` ≈ unit(+371, +134) ≈
  (0.94, 0.34). Clip dominant flow `F` ≈ unit(−0.7, −0.7) (all traffic toward
  the up-left vanishing point), `flow_coherence` high (one straight one-way
  carriageway, dozens of tracks). `flow_alignment` ≈ (0.94)(−0.7) +
  (0.34)(−0.7) ≈ **−0.9**.
- Layer 3b: `14872a1a` resolves to `Unknown(AMBIGUOUS_NEAREST_STREET)` — a
  non-one-way `Unknown`, so the **strong gate** applies. `flow_coherence` ≥ 0.85
  ✓ (one straight one-way carriageway, dozens of tracks); ≥8 forward-stream
  tracks ✓; one candidate with alignment −0.9, 16 ≥ 12 frames, detection ≥ 0.5,
  displacement ≥ 15 % of its bbox diagonal ✓; lone anomaly ✓ → **CONFIRMED**,
  "Wrong-way vehicle moving against traffic".

If tracking ever does yield a ≥20-frame growing fragment, Layer 2's widened
stationary-approach path confirms it too, as a second route.

## Testing

### video-analysis (`pytest`)

`tests/test_detection.py` (or wherever detection filtering is tested):
- a `motorcycle` detection at confidence 0.3 is **kept**; a `car` at 0.3 is
  **dropped**; both at 0.5 kept. (Layer 1)

`tests/test_pipeline.py` / a new `tests/test_flow_alignment.py`:
- Synthetic tracks: 6 tracks moving frame-down-right at varying speeds + 1
  moving frame-up-left → the 1 gets `flow_alignment` ≈ −1, the 6 get ≈ +1,
  `dominant_flow_degrees` points down-right, `flow_coherence` > 0.9.
- A track with total windowed displacement < `MIN_DISPLACEMENT_PIXELS` →
  `flow_alignment is None`.
- A clip with < 2 directional tracks → `dominant_flow_degrees is None`,
  `flow_coherence == 0.0`, every `flow_alignment is None`.
- The near-head-on approacher fixture from
  `test_head_on_approaching_track_gets_a_real_bearing...` → its
  `flow_alignment` is computed from the smallest-bbox window, not the whole
  track.
- Short (< `MIN_OBSERVATIONS`) one-directional fragments do NOT define or sway
  the dominant flow, nor inflate `flow_coherence` — only tracks of ≥ 12 frames
  get a vote; a short track still receives its own `flow_alignment` value
  against that flow. (C1/C2 arithmetic fix, whole-branch review.)

### server (`./gradlew test`)

`ReportAnalysisJobTest`:
- **Confirm (base gate):** `Unknown(DIVIDED_CARRIAGEWAY)`, `flow_coherence` 0.85,
  one vehicle `flow_alignment` −0.85 / 14 frames / det 0.8, six vehicles
  `flow_alignment` +0.9 / 20 frames → CONFIRMED via counter-flow, message,
  `directionEvidence` (incl. `dominant_flow_degrees`) asserted.
- **Strong gate (non-one-way `Unknown`):** `Unknown(AMBIGUOUS_NEAREST_STREET)`
  needs `flow_coherence ≥ 0.85` AND ≥8 forward tracks; the base-gate fixture
  (0.85 / 6 forward) → stays REJECTED; a 0.8 / 8-forward fixture → stays
  REJECTED; a 0.9 / 9-forward fixture (the `14872a1a` shape) → CONFIRMED.
- **No confirm — weak coherence:** base-gate fixture but `flow_coherence` 0.5 →
  stays REJECTED.
- **No confirm — two counter-flowers:** two vehicles at −0.7 → stays REJECTED
  (not a lone anomaly).
- **No confirm — thin forward stream:** only three forward tracks → stays
  REJECTED.
- **No confirm — short fragment:** the counter-flower has 9 frames → stays
  REJECTED.
- **No confirm — alignment magnitude / detection floor / short forward tracks /
  sub-diagonal displacement:** each of the four candidate-filter sub-gates pinned
  by a fixture that clears every other gate → stays REJECTED.
- **Layer 2:** `Unknown(AMBIGUOUS_NEAREST_STREET)` + stationary camera + one
  grower 22 frames / growth 1.5 / det 0.9 + five-member R 0.95 receding
  consensus → CONFIRMED via the (now-eligible) stationary-approach path.
- **Regression:** every existing `Unknown(NO_ONEWAY_TAG)` / `NotFound`
  REJECTED-path test still REJECTs (Layer 2 must not make `NotFound` eligible).

`ClipFlowAnalyzerTest` / DTO test: `flowAlignment` / `dominantFlowDegrees` /
`flowCoherence` absent from a response JSON → parse to `null`, no path throws.

### Production verification (after deploy)

- Re-run `14872a1a` → expect CONFIRMED via counter-flow. Pull
  `direction_evidence`, record `flow_alignment` / `flow_coherence` actuals in
  the backlog entry.
- Re-run `71f78`, `50bcc6` (the moving-camera cohesion cases) → check whether
  counter-flow now confirms them; record outcome either way.
- Re-run the FP watch set (`649b9a` known false positive, plus any recent
  production CONFIRMEDs) → confirm none flip to a counter-flow CONFIRMED. If
  `649b9a` does, tighten `counterFlowMaxAlignment` toward −0.75 or
  `counterFlowMinCoherence` toward 0.85 before shipping wider.
- Serialised analysis + the sub-12-frame OCR skip already absorb Layer 1's
  extra tracks; still spot-check one busy re-run's wall-clock against the 300 s
  budget.

## Non-goals

- **A clean ≥30-frame track of the head-on rider.** Not reliably obtainable
  with YOLOv8n + ByteTrack on this footage; the design is built to not need it.
- **`bicycle`-class handling of any kind** (relabel, rider-anchor, or track).
  Explicitly out by decision.
- **A larger detection model / two-stage detection.** ~3x runtime; reopens the
  timeout problem.
- **Person-anchored "rider unit" tracking.** Tested, ID-swapped two riders,
  abandoned.
- **Replacing the OSM/compass bearing path or the stationary-approach path.**
  Counter-flow is a third *additive* fallback; the other two are unchanged
  except Layer 2's eligibility widening.
- **Retro-analysis of historical reports.** Manual re-run only, as before.
- **`recorded_at` / retry-on-`VideoAnalysisException`** (deferred item 3 of the
  2026-09-06 concurrency spec) — still deferred, unrelated.
