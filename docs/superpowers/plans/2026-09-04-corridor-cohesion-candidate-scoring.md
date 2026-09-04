# Corridor-Cohesion Candidate-Scoring Fix — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A cleanly-detected wrong-way vehicle is no longer denied confirmation purely because a low `corridor_cohesion` (a frame-space path-centrality artifact for near-camera motorcycles) drags its `finalScore` under 0.50.

**Architecture:** `corridor_cohesion` is removed from the per-candidate `candidateQuality` multiplier (where it double-counts against `clipConfidence` and systematically penalises small/fast/frame-edge tracks) and replaced by a real per-candidate track-trust score — the product of a frame-count factor, a displacement-magnitude factor, and a bearing-source factor, all from fields already on the video-analysis wire. `corridor_cohesion` stays exactly as-is in `CorridorConsensus.meanCohesion` / `clipConfidence` (consensus strength). Server-only; no `video-analysis` change.

**Tech Stack:** Kotlin, Spring Boot, JUnit5, MockK, AssertJ. `server/` module only.

**Spec:** `docs/superpowers/specs/2026-09-04-corridor-cohesion-candidate-scoring-design.md`

## Global Constraints

- **`corridor_cohesion` is removed from per-candidate scoring ONLY.** `FlowVehicle.corridorCohesion` stays a field and a qualification gate (null → drop the vehicle, means old `video-analysis`); it still feeds `CorridorConsensus.meanCohesion`. `clipConfidence = (n/(n+2)) × R × meanCohesion` is UNCHANGED.
- **No `video-analysis` / wire change.** New signals come only from `trackFrameCount`, `displacementPixels`, `boundingBox`, `bearingSource` — all already on `VehicleAnalysisResult`.
- **No retroactive re-analysis.** Future analysis only.
- `direction_evidence`'s `candidate_quality` key is KEPT (schema stability); its value changes; three sub-factor fields are added.
- **Exact identifiers** (verbatim): `frameFactor`, `displacementFactor`, `bearingSourceFactor` on `FlowVehicle`; `displacementTrustDiagonals` (default `1.0`) and `scaleBearingTrustFactor` (default `1.0`) on `AnalysisProperties`; `displacement-trust-diagonals` / `scale-bearing-trust-factor` in `application.yml`; `EvidenceBreakdown` gains `trackFrameFactor` / `trackDisplacementFactor` / `trackBearingSourceFactor` (all `Double?`).
- `./gradlew test` from `server/` green, bar the known pre-existing `EndToEndFlowTest` real-network flake.

## Decisions taken (spec open questions — flag if you disagree)

1. **`scaleBearingTrustFactor` default = `1.0`** (identity). `"scale"` bearings already pass the recording-speed corroboration gate; a penalty is speculative and doesn't affect the target reports. The property is retained as a lever (and it surfaces `bearingSource` in the evidence) but is a no-op by default.
2. **`displacementFactor` is a linear ramp** to `displacementTrustDiagonals`. A `sqrt`/gentler curve is a later tweak if a moderately-moving track proves over-discounted in production.
3. **The three sub-factors ARE exposed** in `direction_evidence` — a scoring-model change should be auditable per-report without re-running (consistent with `corroboration_resultant_length` added in the 2026-08-31 work).

---

## File Structure

| File | Change | Task |
|---|---|---|
| `server/src/main/kotlin/com/trafficwatch/server/geo/ClipFlowAnalyzer.kt` | `FlowVehicle`: replace `trackQuality: Double` param with `frameFactor` / `displacementFactor` / `bearingSourceFactor` params; `trackQuality` + `candidateQuality` become getters; `qualifyVehicles` computes the three factors | 1 |
| `server/src/main/kotlin/com/trafficwatch/server/reports/AnalysisProperties.kt` | add `displacementTrustDiagonals: Double = 1.0`, `scaleBearingTrustFactor: Double = 1.0` | 1 |
| `server/src/main/resources/application.yml` | add `displacement-trust-diagonals: 1.0`, `scale-bearing-trust-factor: 1.0` under `app.analysis:` | 1 |
| `server/src/main/kotlin/com/trafficwatch/server/reports/ReportAnalysisJob.kt` | `EvidenceBreakdown` + 3 nullable fields; `breakdownJson` populates them from `best.flowVehicle` | 2 |
| `server/src/test/kotlin/com/trafficwatch/server/geo/ClipFlowAnalyzerTest.kt` | factor tests; cohesion-no-longer-in-candidateQuality test; `clipConfidence` test stays green | 1 |
| `server/src/test/kotlin/com/trafficwatch/server/reports/ReportAnalysisJobTest.kt` | low-cohesion-confirms regression test; short-track-still-rejects; scale-factor; evidence sub-fields | 2 |
| `server/src/test/kotlin/com/trafficwatch/server/reports/ReportAnalysisIntegrationTest.kt` | rescale any hardcoded `candidate_quality` / `final_score` | 2 |
| `docs/improvements-backlog.md` | mark the item addressed; note the calibration status | 3 |
| `~/.claude/projects/.../memory/feedback_cohesion_candidatequality_watch.md` + `MEMORY.md` | watch memory for the accepted residual risk | 3 |

---

## Task 1: Replace the `candidateQuality` cohesion term with a track-trust score

**Files:**
- Modify: `server/src/main/kotlin/com/trafficwatch/server/geo/ClipFlowAnalyzer.kt`
- Modify: `server/src/main/kotlin/com/trafficwatch/server/reports/AnalysisProperties.kt`
- Modify: `server/src/main/resources/application.yml`
- Test: `server/src/test/kotlin/com/trafficwatch/server/geo/ClipFlowAnalyzerTest.kt`

**Interfaces:**
- Produces: `FlowVehicle` with `val frameFactor: Double`, `val displacementFactor: Double`, `val bearingSourceFactor: Double` (constructor params); `val trackQuality: Double get() = frameFactor * displacementFactor * bearingSourceFactor`; `val candidateQuality: Double get() = trackQuality`
- Produces: `AnalysisProperties.displacementTrustDiagonals: Double` (1.0), `AnalysisProperties.scaleBearingTrustFactor: Double` (1.0)
- Consumes: existing `VehicleAnalysisResult.{trackFrameCount, displacementPixels, boundingBox, bearingSource, corridorCohesion}`, `MIN_TRACK_FRAMES`, `TRACK_FRAMES_SATURATION`, `properties.minDisplacementFraction`

- [ ] **Step 1: properties**

`AnalysisProperties` — after `minDisplacementFraction`:
```kotlin
    // Per-candidate track-trust scoring (ClipFlowAnalyzer.FlowVehicle.trackQuality). The
    // displacement-magnitude factor saturates to 1.0 when a track has translated this many of
    // its own largest bounding-box diagonals - "moved one full vehicle-length in frame -> its
    // bearing is dominated by real motion, not centroid jitter". corridor_cohesion is
    // deliberately NOT a factor here (it double-counts against clipConfidence and penalises
    // small/fast near-camera tracks - see the 2026-09-04 design).
    var displacementTrustDiagonals: Double = 1.0,
    // Multiplier applied to trackQuality for a "scale"-sourced (bbox-diagonal fallback,
    // near-head-on) bearing. 1.0 = identity; a lever if "scale" bearings prove over-generous.
    var scaleBearingTrustFactor: Double = 1.0,
```

`application.yml`, under `app.analysis:` (near `min-displacement-fraction`):
```yaml
    displacement-trust-diagonals: 1.0
    scale-bearing-trust-factor: 1.0
```

- [ ] **Step 2: failing test — `corridor_cohesion` no longer affects `candidateQuality`**

In `ClipFlowAnalyzerTest`:
```kotlin
@Test
fun `candidateQuality ignores corridor_cohesion`() {
    val low = analyzer.qualifyVehicles(
        listOf(vehicle(1, 90.0, frames = 30, displacement = 500.0, cohesion = 0.2)), 0.0, 1920, 1080,
    )
    val high = analyzer.qualifyVehicles(
        listOf(vehicle(1, 90.0, frames = 30, displacement = 500.0, cohesion = 0.95)), 0.0, 1920, 1080,
    )
    assertEquals(low[0].candidateQuality, high[0].candidateQuality, 1e-9)
    assertEquals(1.0, low[0].candidateQuality, 1e-9) // frames saturate, displacement saturates, centroid
}
```
Run: `./gradlew test --tests "*ClipFlowAnalyzerTest"` → FAIL (`candidateQuality` still `× 0.2` vs `× 0.95`).

- [ ] **Step 3: `FlowVehicle` shape**

```kotlin
/** A vehicle qualified for flow analysis: absolute bearing + track-trust facts. */
data class FlowVehicle(
    val vehicle: VehicleAnalysisResult,
    val absoluteBearingDegrees: Double,
    /** min(frames / TRACK_FRAMES_SATURATION, 1.0). */
    val frameFactor: Double,
    /** min(displacementPixels / (displacementTrustDiagonals × largestBboxDiagonal), 1.0). */
    val displacementFactor: Double,
    /** [AnalysisProperties.scaleBearingTrustFactor] for a "scale" bearing; 1.0 for "centroid" or a null/legacy source. */
    val bearingSourceFactor: Double,
    val corridorId: Long,
    /** Retained: feeds CorridorConsensus.meanCohesion only. NOT in per-candidate scoring (2026-09-04 design). */
    val corridorCohesion: Double,
    val orientationSource: OrientationSource? = null,
) {
    val trackQuality: Double get() = frameFactor * displacementFactor * bearingSourceFactor
    /** Retained for direction_evidence schema stability; identical to [trackQuality]. */
    val candidateQuality: Double get() = trackQuality
}
```

- [ ] **Step 4: compute the factors in `qualifyVehicles`**

Replace the `FlowVehicle(...)` construction's `trackQuality = ...` line. The block already has
`frames`, `displacement`, `bboxDiagonal` in scope:
```kotlin
            FlowVehicle(
                vehicle = vehicle,
                absoluteBearingDegrees = (orientationDegrees + frameBearing) % 360.0,
                frameFactor = min(frames / TRACK_FRAMES_SATURATION, 1.0),
                displacementFactor = min(
                    displacement / (properties.displacementTrustDiagonals * bboxDiagonal), 1.0,
                ),
                bearingSourceFactor =
                    if (vehicle.bearingSource == "scale") properties.scaleBearingTrustFactor else 1.0,
                corridorId = corridorId,
                corridorCohesion = cohesion,
                orientationSource = resolved?.source,
            )
```
(`frames` is `Int` — write `frames.toDouble() / TRACK_FRAMES_SATURATION` or rely on `min`'s
`Double` overload; match the existing code's style, which already did `frames / TRACK_FRAMES_SATURATION`.)

Run Step 2's test → PASS.

- [ ] **Step 5: factor unit tests**

```kotlin
@Test
fun `frameFactor scales with track length and saturates`() {
    fun q(frames: Int) = analyzer.qualifyVehicles(
        listOf(vehicle(1, 90.0, frames = frames, displacement = 5000.0)), 0.0, 1920, 1080,
    )[0]
    assertEquals(9.0 / 15.0, q(9).frameFactor, 1e-9)
    assertEquals(1.0, q(15).frameFactor, 1e-9)
    assertEquals(1.0, q(40).frameFactor, 1e-9)
}

@Test
fun `displacementFactor ramps to one at displacementTrustDiagonals bbox diagonals`() {
    // vehicle() bbox 50x50 -> diagonal 70.7107; displacementTrustDiagonals default 1.0
    val diag = 70.71067811865476
    fun q(disp: Double) = analyzer.qualifyVehicles(
        listOf(vehicle(1, 90.0, frames = 30, displacement = disp)), 0.0, 1920, 1080,
    )[0]
    assertEquals(1.0, q(diag).displacementFactor, 1e-6)
    assertEquals(0.5, q(diag / 2).displacementFactor, 1e-6)
    assertEquals(1.0, q(diag * 4).displacementFactor, 1e-6) // clamps
}

@Test
fun `bearingSourceFactor penalises only a scale bearing`() {
    val props = AnalysisProperties(scaleBearingTrustFactor = 0.5)
    val a = ClipFlowAnalyzer(props)
    // a "scale" bearing needs recording-speed corroboration to qualify - supply an
    // OrientationTimeline whose speed at the track midpoint is low, OR use the existing
    // test's pattern for scale-source qualification (check how other scale tests set this up).
    // Assert: scale -> 0.5, centroid -> 1.0, null -> 1.0.
}
```
(For the `bearingSourceFactor` scale case: `qualifyVehicles` drops a `"scale"` vehicle unless
`orientationTimeline.recordingSpeedMetersPerSecondAt(trackMidpointMs) <= 1.0`. Look at how any
existing `bearingSource = "scale"` test in this file sets that up and reuse it; if none exists,
build a minimal `OrientationTimeline` with a slow `location_samples` entry at the track
midpoint. If that proves heavy, assert `bearingSourceFactor` for `"centroid"` and `null` only
and note the `"scale"` path is covered indirectly by a `ReportAnalysisJobTest` case in Task 2.)

- [ ] **Step 6: confirm untouched behaviour**

- The existing `trackQuality saturates at one and scales below the saturation points` test:
  `frames = 12` → `frameFactor 0.8`; `displacement = 500` on a 70.7px diagonal →
  `displacementFactor 1.0`; null source → `1.0`. `trackQuality = 0.8`. **Stays green** — update
  only its stale `// min(4/5, 1)` comment to `// min(12/15, 1) * 1 * 1`.
- The `unimodal corridor yields consensus` test's `clipConfidence` assertion (`≈ 0.6`) is
  untouched — `meanCohesion` still comes from `corridorCohesion`.
- Every other `ClipFlowAnalyzerTest` case uses the default `cohesion = 1.0` / `displacement = 200`
  on a 70.7px diagonal → all factors 1.0 → no change.

- [ ] **Step 7: run + commit**

`./gradlew test --tests "*ClipFlowAnalyzerTest"` → green. Full `./gradlew test` → green except
the `EndToEndFlowTest` flake (note: `ReportAnalysisJobTest` / `ReportAnalysisIntegrationTest`
may now have failing `candidate_quality`/`final_score` assertions — that is Task 2's scope;
if any fail here, confirm they're only those and hand them to Task 2).

```bash
git add -A && git commit -m "feat: score candidate track trust from frames/displacement/bearing-source, not corridor_cohesion"
```

---

## Task 2: Expose the sub-factors in `direction_evidence`; fix the downstream tests

**Files:**
- Modify: `server/src/main/kotlin/com/trafficwatch/server/reports/ReportAnalysisJob.kt`
- Test: `server/src/test/kotlin/com/trafficwatch/server/reports/ReportAnalysisJobTest.kt`
- Test: `server/src/test/kotlin/com/trafficwatch/server/reports/ReportAnalysisIntegrationTest.kt`

**Interfaces:**
- Consumes: `FlowVehicle.frameFactor` / `displacementFactor` / `bearingSourceFactor` / `candidateQuality` (Task 1)
- Produces: `EvidenceBreakdown` with `trackFrameFactor: Double?`, `trackDisplacementFactor: Double?`, `trackBearingSourceFactor: Double?`

- [ ] **Step 1: `EvidenceBreakdown` fields**

Add after `candidateQuality`:
```kotlin
    val trackFrameFactor: Double?,
    val trackDisplacementFactor: Double?,
    val trackBearingSourceFactor: Double?,
```

- [ ] **Step 2: `breakdownJson` populates them**

```kotlin
                candidateQuality = best?.flowVehicle?.candidateQuality,
                trackFrameFactor = best?.flowVehicle?.frameFactor,
                trackDisplacementFactor = best?.flowVehicle?.displacementFactor,
                trackBearingSourceFactor = best?.flowVehicle?.bearingSourceFactor,
```
(`breakdownJson(entries, best)` — the only caller — and `EvidenceBreakdown` construction is at
`ReportAnalysisJob.kt` ~line 491. All three are `null` when `best` is `null`, matching the
existing `candidateQuality` pattern.)

- [ ] **Step 3: failing regression test — a low-cohesion clean track now CONFIRMS**

In `ReportAnalysisJobTest` (mirrors `71f78`/`50bcc6`):
```kotlin
@Test
fun `a long clean centroid track in a low-cohesion corridor is CONFIRMED`() {
    val report = sampleReport(compassHeadingDegrees = 0.0)
    every { streetDirectionResolver.resolve(any(), any(), any()) } returns
        DirectionResolution.OneWay("Khayaban-e-Jinnah", 0.0) // legal 0°, illegal 180°
    // 3 legal cars flowing at ~0°, cohesion 1.0; + one wrong-way motorcycle at ~180°,
    // long clean track, corridorCohesion 0.2 (near-camera artifact), same corridor.
    every { videoAnalysisClient.analyze(fakeVideoPath, any(), any()) } returns analysisResponse(
        listOf(
            vehicle(trackId = 1, bearingDegrees = 0.0, corridorCohesion = 1.0, trackFrameCount = 40, displacementPixels = 600.0),
            vehicle(trackId = 2, bearingDegrees = 2.0, corridorCohesion = 1.0, trackFrameCount = 40, displacementPixels = 600.0),
            vehicle(trackId = 3, bearingDegrees = 358.0, corridorCohesion = 1.0, trackFrameCount = 40, displacementPixels = 600.0),
            vehicle(
                trackId = 5, bearingDegrees = 180.0, detectionConfidence = 0.9,
                corridorCohesion = 0.2, trackFrameCount = 62, displacementPixels = 600.0,
                boundingBox = BoundingBox(0.0, 0.0, 300.0, 300.0), // diagonal ~424; 600px travel > 1 diagonal
            ),
        ),
    )
    every { wrongWayFrameStorageService.store(any(), any()) } returns "frames/x.jpg"
    every { reportRepository.save(any()) } answers { firstArg() }

    job.applyOutcome(report)

    assertThat(report.status).isEqualTo(ReportStatus.CONFIRMED)
    // candidate_quality is now ~1.0, not ~0.2
    assertThat(report.directionEvidence).contains("\"track_frame_factor\":1.0")
}
```
Check the `vehicle(...)` helper in `ReportAnalysisJobTest` — it has a `corridorCohesion` param
(default 1.0) and a `boundingBox`/`displacementPixels`; adjust the call syntax to match. If the
helper lacks `displacementPixels`/`boundingBox` params, add them (default to values that clear
the qualification floor for all existing tests — e.g. `boundingBox = BoundingBox(0.0,0.0,1414.0,1414.0)`, `displacementPixels = 310.0`, matching whatever the current defaults imply).

Run → RED (`finalScore ≈ 1.0 × 0.2 × 0.9 × ~0.97 ≈ 0.17` on current code → REJECTED).

- [ ] **Step 4: verify it GREENs from Task 1 alone**

Task 1 already removed the cohesion term. This test should pass once Task 1 is in. If it still
fails, the cause is in this diff's `EvidenceBreakdown` wiring — fix here. Run → GREEN.

- [ ] **Step 5: the residual-risk guard still bites**

```kotlin
@Test
fun `a short barely-moving track in a low-cohesion corridor is still REJECTED`() {
    // Same shape, but trackId 5: trackFrameCount = 10, displacementPixels just above the
    // qualification floor (minDisplacementFraction 0.15 × diagonal), bbox diagonal large.
    // frameFactor ~10/15=0.67, displacementFactor ~0.15/1.0=0.15 -> candidateQuality ~0.1
    // -> finalScore well under 0.50 -> REJECTED, message "Possible wrong-way vehicle
    // detected, but confidence was too low to confirm".
}
```

- [ ] **Step 6: `scale` bearing factor**

```kotlin
@Test
fun `a scale-sourced bearing is discounted by scaleBearingTrustFactor`() {
    // Build ReportAnalysisJob with AnalysisProperties(scaleBearingTrustFactor = 0.5).
    // Candidate: bearingSource = "scale", stationary camera (location_samples slow) so it
    // qualifies, long track, low cohesion. Assert finalScore is ~half what the same track
    // with bearingSource = "centroid" would score, and directionEvidence contains
    // "track_bearing_source_factor":0.5.
}
```

- [ ] **Step 7: `ReportAnalysisIntegrationTest`**

Run it; any test asserting a specific `candidate_quality` or `final_score` substring in
`direction_evidence` needs its expected value recomputed (cohesion no longer multiplies in).
Update to the new values.

- [ ] **Step 8: run + commit**

`./gradlew test` from `server/` → green bar the flake.
```bash
git add -A && git commit -m "feat: surface track-trust sub-factors in direction_evidence; update scoring tests"
```

---

## Task 3: Backlog + residual-risk watch memory

**Files:**
- Modify: `docs/improvements-backlog.md`
- Create: `~/.claude/projects/C--Users-AHussain-DevProjects-trafficVideoApp/memory/feedback_cohesion_candidatequality_watch.md`
- Modify: `~/.claude/projects/C--Users-AHussain-DevProjects-trafficVideoApp/memory/MEMORY.md`

- [ ] **Step 1: backlog**

The `[PRIORITY RAISED 2026-08-30 - recurred] … corridor_cohesion` entry — append a dated
`**Update 2026-09-04**` line in the file's style: `corridor_cohesion` removed from
per-candidate `candidateQuality` and replaced by `frameFactor × displacementFactor ×
bearingSourceFactor` (spec/plan `2026-09-04-corridor-cohesion-candidate-scoring`); it remains
in `clipConfidence` unchanged. Note the calibration status: `50bcc6` confirms with margin;
`71f78`'s margin depends on `displacement-trust-diagonals` and its real `displacementPixels` —
**pending the production replay** (Deploy step below). Keep the entry OPEN until that replay
confirms both, or downgrade it if `71f78` needs the deferred camera-translation work.

- [ ] **Step 2: watch memory**

`feedback_cohesion_candidatequality_watch.md` — frontmatter `name: cohesion-candidatequality-watch`,
`description: watch CONFIRMED reports with a low track_displacement_factor or weak corridor consensus for false positives after the 2026-09-04 cohesion-scoring change`, `metadata: type: feedback`.
Body: the change removed a second, independent haircut (`× corridor_cohesion`) from
`candidateQuality`. **Why:** a fragmented/ID-swapped track that clears `frames ≥ 15` + the
displacement floor with a coincidentally-against-flow `centroid` bearing and high
`detectionConfidence` now loses only the `displacementFactor` discount, not the cohesion one
too. **How to apply:** for the first few weeks post-deploy, review production `CONFIRMED`
reports whose `direction_evidence.track_displacement_factor` is below ~0.5 or whose
CLIP_CONSENSUS entry confidence is low; if the flagged vehicle is visibly legal, the
displacement/frame factors need tightening or the Python bearing-stability signal (spec
Non-goals) is needed. Link `[[cohesion-underconfirm-watch]]` (the predecessor) and
`[[divided-carriageway-approach-watch]]`.
Then add the one-line pointer to `MEMORY.md`.

- [ ] **Step 3: commit**

```bash
git add docs/improvements-backlog.md && git commit -m "docs: mark corridor_cohesion scoring item addressed (pending 71f78 replay)"
```
(Memory files are outside the repo — not committed.)

---

## Deploy & calibration

1. **Deploy the branch** to the VPS (`git archive <head>` → extract → `docker compose up -d --build server`). No DB migration; code + config only.
2. **Production replay** — re-run through the deployed pipeline (throwaway-report method, per the divided-carriageway runbook). From each report's stored `direction_evidence`, read `candidate_quality`, `track_frame_factor`, `track_displacement_factor`, `track_bearing_source_factor`, `candidate_corridor_cohesion`, `candidate_bearing_source`, `final_score`. Replay set:
   - `e4d53e59…71f78` and `91ce999b…50bcc6` — the two target true-positive misses; both must now confirm.
   - `a6877462-0675-482e-a2a8-a8d096649b9a` (`649b9a`, the known bearing-path false positive — a plainly rear-facing vehicle confirmed at `wrong_way_confidence` 0.5711). Check whether its `final_score` **RISES** under the new scoring: removing `corridor_cohesion` must not make a bad confirm worse. If it rises materially, note it against the residual-risk watch.
   - One previously-CONFIRMING bearing-path report from prod: `SELECT id FROM reports WHERE status='CONFIRMED' AND wrong_way_confidence IS NOT NULL AND direction_evidence LIKE '%CLIP_CONSENSUS%' ORDER BY created_at DESC LIMIT 3` — pick one and check it **still confirms** (regression guard on real data).
3. **Calibrate `displacement-trust-diagonals`:**
   - Both reports `final_score ≥ ~0.6` → done, ship `1.0`.
   - `71f78` short of the bar → lower `displacement-trust-diagonals` (e.g. `0.5`) via config and re-run. A track that moved half a bbox diagonal is still real motion; `0.5` is defensible.
   - `71f78` still short at `0.3` → it is genuinely a small-lateral-displacement clip (moving camera). Record in the backlog, leave `50bcc6` confirmed, and defer `71f78` to the camera-translation work.

   **Tuning `displacement-trust-diagonals` without a rebuild:** add `APP_ANALYSIS_DISPLACEMENT_TRUST_DIAGONALS: "0.5"` to the `server:` service's `environment:` block in `docker-compose.prod.yml` and `docker compose -f docker-compose.prod.yml up -d server` — Spring relaxed binding maps the env var onto `app.analysis.displacement-trust-diagonals`, no image rebuild. Editing `application.yml` itself needs a rebuild (it is baked into the jar).
4. **Clean up** the throwaway reports/users/videos (per the runbook).
5. Update the backlog entry with the final `displacement-trust-diagonals` value and which reports confirm.

---

## Self-Review

**Spec coverage:**
- Remove `corridor_cohesion` from `candidateQuality`, keep in `clipConfidence` → Task 1. ✅
- Replace with `frameFactor × displacementFactor × bearingSourceFactor` from wire fields → Task 1 Step 4. ✅
- `displacementFactor` = linear ramp to `displacementTrustDiagonals` bbox diagonals → Task 1 Step 4 (decision 2). ✅
- `bearingSourceFactor` = `scaleBearingTrustFactor` for `"scale"`, else 1.0; default 1.0 → Task 1 (decision 1). ✅
- Sub-factors in `direction_evidence` → Task 2 (decision 3). ✅
- Safety: `movesWith` / `hasPeerSupport` / `clipConfidence` meanCohesion untouched → no task changes them; Task 1 Step 6 confirms. ✅
- Residual-risk watch → Task 3. ✅
- Production replay to calibrate `displacementTrustDiagonals` (esp. `71f78`) → Deploy section. ✅

**Placeholder scan:** none. Task 1 Step 5's `bearingSourceFactor` scale test and Task 2 Steps 5-6 say "check how existing scale-source tests set up the `OrientationTimeline`" — that is a real lookup instruction (the qualification path for `"scale"` bearings is pre-existing), not a placeholder; a concrete fallback is given.

**Type consistency:** `frameFactor` / `displacementFactor` / `bearingSourceFactor` are `Double` on `FlowVehicle` (Task 1), read as `Double?` into `EvidenceBreakdown.trackFrameFactor` etc. (Task 2) via `best?.flowVehicle?.frameFactor`. `displacementTrustDiagonals` / `scaleBearingTrustFactor` are `Double` on `AnalysisProperties`, consumed in `qualifyVehicles` (Task 1) and one `ReportAnalysisJobTest` (Task 2 Step 6). `trackQuality` / `candidateQuality` are getters, no constructor param — `qualifyVehicles` is the only `FlowVehicle` construction site (verified: no direct `FlowVehicle(` in tests).

**Cross-task:** Task 1 changes `FlowVehicle` + scoring; Task 2 reads the new fields into JSON and fixes the tests Task 1's scoring change breaks (`ReportAnalysisJobTest`, `ReportAnalysisIntegrationTest` `candidate_quality`/`final_score` assertions). Task 1 Step 7 explicitly hands those failures to Task 2. Task 3 is docs only. No file is edited by two code tasks except none — Task 1 is `ClipFlowAnalyzer`/`AnalysisProperties`/yaml, Task 2 is `ReportAnalysisJob`/tests.
