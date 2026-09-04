# Corridor-Cohesion Candidate-Scoring Fix — Design

**Status:** draft for review

Addresses the `docs/improvements-backlog.md` `## Vehicle detection / tracking` item
**"[PRIORITY RAISED 2026-08-30 - recurred] A long, cleanly-detected wrong-way vehicle can be
denied confirmation purely by low `corridor_cohesion`, especially on a single-corridor road."**

## Context

`ReportAnalysisJob.evaluateCandidates` scores every qualified vehicle:

```
finalScore = fusion.directionConfidence
           × candidate.candidateQuality        // ← the problem
           × candidate.vehicle.detectionConfidence
           × bearingMatchScore
```

and `FlowVehicle.candidateQuality` is:

```kotlin
val candidateQuality: Double get() = trackQuality * corridorCohesion
```

where `trackQuality = min(frames / TRACK_FRAMES_SATURATION, 1.0) × min(displacement / minDisplacement, 1.0)`.

The second `trackQuality` term is **dead**: `qualifyVehicles` already drops any vehicle with
`displacement < minDisplacement`, so `min(displacement / minDisplacement, 1.0)` is always
`1.0` by the time a `FlowVehicle` exists. So for any track with ≥ `TRACK_FRAMES_SATURATION`
(15) frames — every well-tracked vehicle — `candidateQuality` collapses to exactly
`corridorCohesion`.

`corridorCohesion` (from `video-analysis`'s `app/corridors.py`) is
`1 − mean-distance-to-direct-corridor-mates / threshold` — a frame-space measure of how
spatially central a track's path is among its corridor-mates. For a **near-camera motorcycle**
— jittery centroid, bounding box clipped at the frame edge, physically small next to the cars
sharing its corridor — this reads low (~0.2) as an **artifact of object size and frame
position, not a signal that the track's bearing is untrustworthy**. It then multiplies straight
into `finalScore`.

### The two production cases (user-confirmed true positives, both REJECTED)

Same wrong-way motorcycle on خیبان جناح (Jinnah Ave), ~48 s apart:

| Report | `final_score` | `direction_confidence` | `bearing_match_score` | `detection_confidence` | `corridor_cohesion` | track |
|---|---|---|---|---|---|---|
| `e4d53e59…71f78` | **0.39** vs 0.50 | 1.0 | 0.98 | 0.90 | 0.28 | 62 frames |
| `91ce999b…50bcc6` | **0.33** vs 0.50 | 1.0 | 0.96 | 0.81 | ≈0.19 | 179 frames, 475 px travel (`trackQuality` frame factor = 1.0) |

For `71f78`: `1.0 × (1.0 × 0.28) × 0.90 × 0.98 = 0.247`… the backlog records `candidate_quality`
`0.44` from the original run and `corridor_cohesion` `0.28` from a re-run, so the exact numbers
drift between runs, but the mechanism is unambiguous: **`candidateQuality` is the sole drag; every
other factor is ≥ 0.9, and `candidateQuality` == `corridorCohesion`.**

Note: `71f78` was also recorded from a **moving camera** — that is a separate, deferred backlog
item ("No correction for camera translation"). For *this* clip the pipeline still produced a
confident, well-matched bearing (`direction_confidence` 1.0, `bearing_match_score` 0.98); only
the cohesion drag rejected it. This design does not attempt the moving-camera problem — see
Non-goals.

## Root cause

`corridor_cohesion` plays **two roles** and is wrong in one of them:

1. **Consensus strength** — `CorridorConsensus.clipConfidence = (n/(n+2)) × R × meanCohesion`.
   Here it belongs: a corridor whose members' paths are spatially incoherent is genuinely a
   weaker basis for a flow consensus. **Unchanged by this design.**
2. **Per-candidate track trust** — the `× corridorCohesion` in `candidateQuality`. Here it is a
   poor proxy: it measures path centrality, not bearing reliability, and it systematically
   penalises exactly the vehicle class (small, fast, frame-edge) that this app most needs to
   confirm. It also **double-counts** — a low-cohesion corridor already produces weaker
   `clipConfidence`, which already flows into `finalScore` via `fusion.directionConfidence`.

## Scope decisions (confirm before planning)

- **Server-only.** No `video-analysis` / wire change. (The considered alternative — a new
  per-track bearing-stability signal computed in Python — is more correct but a cross-service
  change; deferred. Recorded in Non-goals.)
- **Remove `corridor_cohesion` from per-candidate scoring only.** It stays exactly as-is in
  `CorridorConsensus.meanCohesion` / `clipConfidence`. `FlowVehicle.corridorCohesion` remains a
  field (it still feeds `meanCohesion`) and a required qualification gate (a null value still
  means an old `video-analysis` version → drop the vehicle).
- **Replace it with a real track-trust score built from fields already on the wire** —
  `trackFrameCount`, `displacementPixels` (relative to the track's bbox), `bearingSource`.
- **No minimal cohesion floor/cap** (a band-aid), and **not** making cohesion direction-aware
  (score against same-direction peers only) — both were considered and rejected in the
  2026-08-30 brainstorm as more complex for less clarity.
- **No retroactive re-analysis.** Future analysis only, as with every change in this project.
- `direction_evidence`'s `candidate_quality` key is **kept** (schema stability — the Android
  detail screen renders it generically); its meaning changes and the design adds sub-factor
  fields for auditability.

## Design

### `FlowVehicle` — a real track-trust score

`trackQuality` becomes the complete per-candidate track-trust score, the product of three
factors each in `[0, 1]`, all derived in `qualifyVehicles` from data it already reads:

```kotlin
data class FlowVehicle(
    val vehicle: VehicleAnalysisResult,
    val absoluteBearingDegrees: Double,
    /** min(frames / TRACK_FRAMES_SATURATION, 1.0) — more centroid samples, more stable bearing. Unchanged. */
    val frameFactor: Double,
    /** min(displacementPixels / (displacementTrustDiagonals × largestBboxDiagonal), 1.0) —
        a track that translated many bounding-box widths has a bearing dominated by real
        motion, not centroid jitter. Replaces the dead `× min(displacement/minDisplacement,1)`. */
    val displacementFactor: Double,
    /** 1.0 for a "centroid" bearing (or a null/legacy bearingSource); [scaleBearingTrustFactor]
        for a "scale" bearing — the bbox-diagonal fallback is a coarse binary (0°/180°) even
        after the recording-speed corroboration gate lets it through. */
    val bearingSourceFactor: Double,
    val corridorId: Long,
    /** Retained: feeds CorridorConsensus.meanCohesion only. No longer in per-candidate scoring. */
    val corridorCohesion: Double,
    val orientationSource: OrientationSource? = null,
) {
    val trackQuality: Double get() = frameFactor * displacementFactor * bearingSourceFactor
    /** Retained for `direction_evidence` schema stability; identical to [trackQuality] now that
        corridor_cohesion no longer scores individual candidates (2026-09-04 design). */
    val candidateQuality: Double get() = trackQuality
}
```

`largestBboxDiagonal` = `hypot(bbox.x2−bbox.x1, bbox.y2−bbox.y1)` — the same value
`qualifyVehicles` already computes for `minDisplacement` (and the same "largest-area frame,
so this is conservative for a size-changing track" caveat the existing comment records —
conservative here means the displacement factor can only *understate*, which is safe).

`bearingSourceFactor`:

| `vehicle.bearingSource` | factor |
|---|---|
| `"centroid"` | `1.0` |
| `null` (legacy `video-analysis`) | `1.0` — never penalise an old-service response |
| `"scale"` | `analysisProperties.scaleBearingTrustFactor` (default `0.8`) |

### `evaluateCandidates`

`finalScore` line is textually unchanged (`× candidate.candidateQuality`); its value changes
because `candidateQuality` no longer multiplies in `corridorCohesion`. No other change to
candidate evaluation — the `movesWith` gate, the `hasPeerSupport` / `hasOtherCorridorMembers`
lone-bearing guard, and per-candidate fusion all stay.

### `AnalysisProperties` / `application.yml`

- New `var displacementTrustDiagonals: Double = 1.0` — the `displacementFactor` saturates when
  a track has travelled this many of its own largest-bbox diagonals. `1.0` ≈ "moved one full
  vehicle-length in frame → bearing fully trusted". **Calibrate against the two target
  reports' real `displacementPixels`/bbox in the plan's production-replay step** (see below) —
  `71f78` (62-frame, moving-camera) is the sensitive case.
- New `var scaleBearingTrustFactor: Double = 0.8`.
- `application.yml` `app.analysis:` gains `displacement-trust-diagonals: 1.0` and
  `scale-bearing-trust-factor: 0.8`.

### `direction_evidence` JSON (`EvidenceBreakdown`)

Keep `candidateQuality`. Add three nullable fields (populated only when there is a scored
`best`), so a confirmation/rejection is auditable without re-running:

```kotlin
val trackFrameFactor: Double?
val trackDisplacementFactor: Double?
val trackBearingSourceFactor: Double?
```

`corridorCohesion` is no longer in this breakdown (it never was directly — it was folded into
`candidateQuality`); the corridor's `meanCohesion` remains visible through the CLIP_CONSENSUS
evidence entry's confidence.

## Will this confirm `71f78` and `50bcc6`?

**`50bcc6` — yes, comfortably.** 179 frames → `frameFactor` 1.0. 475 px travel; a near-camera
motorcycle's largest bbox diagonal is ~250–400 px, so `displacementPixels / (1.0 × diagonal)`
≈ 1.2–1.9 → `displacementFactor` 1.0. The violator drives *past* the camera → real lateral
motion → `bearingSource` `"centroid"` → `bearingSourceFactor` 1.0. New `candidateQuality` ≈
1.0. `finalScore ≈ 1.0 × 1.0 × 0.81 × 0.96 = 0.78` → **CONFIRMED** (≥ 0.50).

**`71f78` — yes, with a margin that depends on `displacementTrustDiagonals`.** 62 frames →
`frameFactor` 1.0. `bearing_match_score` 0.98 + `direction_confidence` 1.0 means a clean
`"centroid"` bearing (needs ≥ `MIN_DISPLACEMENT_PIXELS` lateral motion) → `bearingSourceFactor`
1.0. The unknown is `displacementFactor`: at `displacementTrustDiagonals = 1.0`, if the track
travelled ≥ 1 diagonal → factor 1.0 → `finalScore ≈ 1.0 × 1.0 × 0.90 × 0.98 = 0.88`; if it
travelled ~0.5 diagonal → factor 0.5 → `finalScore ≈ 0.44` → still REJECTED. **The plan MUST
pull `71f78`'s actual `displacementPixels` and bbox** (a one-report diagnostic re-run, as done
for the divided-carriageway work) and set `displacementTrustDiagonals` so both reports confirm
with a clear margin — or accept that `71f78`, being moving-camera, may genuinely need the
deferred camera-translation work and land as "improved but still short".

`24908` and the other خیبان جناح divided-carriageway reports are a different mechanism
(approach-path gate) and out of scope here.

## Safety analysis — what did `corridor_cohesion` protect against?

`corridor_cohesion` in `candidateQuality` discounted a candidate whose frame-space path sits
far from its corridor-mates'. The two things that can cause:

1. **A genuinely mis-assigned track** (its path really belongs to a different flow). This is
   already handled by the *direction* logic, not by cohesion: a candidate whose bearing
   disagrees with its corridor's `corridorConsensus` is flagged as a potential violator and
   scored against the **fused** bearing (OSM + clip-consensus + learned history), and the
   `hasPeerSupport` / `hasOtherCorridorMembers` guard drops a lone coincidental bearing in an
   otherwise scattered corridor. Removing the cohesion multiplier changes none of that.
2. **The near-camera-motorcycle artifact** — small/fast/frame-edge. This is precisely the
   false negative this design fixes; the discount here was never protecting anything.

**Retained protections after this change:** the `movesWith` gate (a candidate flowing with its
corridor is never a violator); the lone-bearing guard; `clipConfidence`'s `meanCohesion` (a
spatially-incoherent corridor still yields weaker consensus evidence → lower
`fusion.directionConfidence`); `detectionConfidence` (a real YOLO signal, still a multiplier);
`bearingMatchScore` (the bearing must actually point against the fused illegal direction within
`wrongWayToleranceDegrees`); and the new `displacementFactor`, which discounts a track that
barely moved — the class most likely to carry a corrupt bearing.

**Accepted residual risk:** a fragmented / ID-swapped track that clears `frames ≥ 15` and the
displacement floor, carries a `"centroid"` bearing that coincidentally points against the fused
legal direction, and has high `detectionConfidence`. Such a track's motion is typically
erratic (low `displacementFactor`) and its bearing unstable, and it must still survive the
`movesWith` and peer-support gates — but the cohesion multiplier previously gave a second,
independent haircut. Mitigation: log it (a watch memory, like
`feedback_divided_carriageway_approach_watch.md`) — review production `CONFIRMED` reports whose
`track_displacement_factor` is low or whose corridor consensus was weak, for the first weeks
after deploy.

## Testing

### `ClipFlowAnalyzerTest`

- `trackQuality` = `frameFactor × displacementFactor × bearingSourceFactor`; each factor
  computed and exposed; `candidateQuality == trackQuality`.
- `frameFactor`: `frames = TRACK_FRAMES_SATURATION` → 1.0; `frames = MIN_TRACK_FRAMES` →
  `9/15`; saturates above.
- `displacementFactor`: `displacement = displacementTrustDiagonals × diagonal` → 1.0;
  half that → 0.5; well above → clamps to 1.0; the qualification floor
  (`minDisplacementFraction × diagonal`) still gates entry (a below-floor vehicle is dropped,
  not scored low).
- `bearingSourceFactor`: `"centroid"` → 1.0, `null` → 1.0, `"scale"` →
  `scaleBearingTrustFactor`.
- `corridorCohesion` no longer affects `trackQuality` / `candidateQuality` (a `FlowVehicle`
  with cohesion 0.2 and cohesion 0.9, all else equal, has identical `candidateQuality`).
- `CorridorConsensus.meanCohesion` / `clipConfidence` **unchanged** — the existing
  `clipConfidence = (n/(n+2)) × R × meanCohesion` test stays green.

### `ReportAnalysisJobTest` / `ReportAnalysisIntegrationTest`

- A candidate with `direction_confidence` 1.0, `bearing_match` ~0.97, `detection` ~0.85, a
  long clean `"centroid"` track, in a **low-cohesion** corridor → `finalScore ≥ 0.50` →
  CONFIRMED. (The regression: this is `71f78`/`50bcc6` in miniature — it REJECTS on current
  `main`.)
- Same but a **short, barely-moving** track → low `displacementFactor` → `finalScore < 0.50`
  → REJECTED (the residual-risk guard still bites).
- Same but a `"scale"` bearing → `finalScore` reduced by `scaleBearingTrustFactor`.
- `EvidenceBreakdown` carries the three new sub-factors on a scored outcome; `candidate_quality`
  equals their product.
- Rescale any hardcoded `candidate_quality` / `final_score` values in
  `ReportAnalysisIntegrationTest`.

### Production replay (plan step — before choosing the `displacementTrustDiagonals` default)

Re-run `71f78` and `50bcc6` through the real pipeline (throwaway-report method, as for the
divided-carriageway work). Capture each vehicle's `displacementPixels`, largest bbox diagonal,
`bearingSource`, `trackFrameCount`, and the resulting `frameFactor` / `displacementFactor` /
`bearingSourceFactor` / `candidateQuality` / `finalScore`. Set `displacementTrustDiagonals` so
both reports confirm with `finalScore ≥ ~0.6` (margin above the 0.50 bar). If `71f78` cannot
clear the bar at any sane value (its lateral displacement is genuinely small because the
camera was moving), record that in the backlog and leave it for the camera-translation work.

## Non-goals

- **Moving-camera / camera-translation correction** — `71f78`'s other problem; its own backlog
  item, deferred (visual odometry).
- **A Python-side per-track bearing-stability signal** (frame-to-frame bearing variance) — more
  correct than the displacement proxy but a cross-service wire change. If the displacement +
  frame + source factors prove insufficient in production, this is the follow-up.
- **Retuning `minDisplacementFraction`** (the qualification floor) — untouched; only the
  now-dead scoring term that referenced it is replaced.
- **The approach-path / divided-carriageway reports** (`759cd` / `24908` / `a5275`) — different
  mechanism, already handled.
- **Removing `corridor_cohesion` from `clipConfidence`** — it is correct there.

## Open questions for review

1. `scaleBearingTrustFactor` default — `0.8`, or leave `"scale"` bearings un-penalised (`1.0`)
   given they already pass the recording-speed corroboration gate? (`"scale"` bearings don't
   affect the two target reports either way.)
2. `displacementFactor` saturation shape — linear ramp to `displacementTrustDiagonals` (this
   design), or a gentler curve (`sqrt`) so a moderately-moving track isn't heavily discounted?
3. Expose the three sub-factors in `direction_evidence` (this design), or keep the JSON lean
   and rely on the watch memory + replay for auditing?
