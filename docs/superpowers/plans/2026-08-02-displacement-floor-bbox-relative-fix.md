# Displacement Floor Bbox-Relative Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix `ClipFlowAnalyzer.qualifyVehicles()` so a tracked vehicle's minimum-displacement quality floor is measured against its own bounding-box diagonal instead of the whole video frame's diagonal - a vehicle close to the camera needs far fewer absolute pixels of movement to represent real motion than a distant one, and the frame-relative floor was silently dropping genuinely moving, close-to-camera vehicles (confirmed: a real wrong-way motorcycle) before they were ever evaluated as candidates.

**Architecture:** A single, self-contained change inside one Kotlin function (`ClipFlowAnalyzer.kt`), plus promoting its threshold constant to a configurable `AnalysisProperties` field (matching its sibling tuning knobs). No Python/wire-format changes - `boundingBox` is already present in the data.

**Tech Stack:** Kotlin, Spring Boot `@ConfigurationProperties`, JUnit 5.

## Global Constraints

- `MIN_TRACK_FRAMES = 3` is unchanged - not in scope.
- No wire-format or Python changes - `VehicleAnalysisResult.boundingBox` already exists and is unused for this purpose today.
- The new threshold (`minDisplacementFraction`, default `0.15`) must be configurable via `AnalysisProperties`/`app.analysis.*`, matching `wrongWayToleranceDegrees`, `weakEvidenceFloor`, `consensusMinResultantLength`, etc. - not a hardcoded private constant like the value it replaces.
- A vehicle with no bounding box is dropped (same graceful-degradation convention as null `corridorId`/`corridorCohesion`/`trackFrameCount`/`displacementPixels` in this same function).

---

### Task 1: Bbox-relative displacement floor in `ClipFlowAnalyzer`

**Files:**
- Modify: `server/src/main/kotlin/com/trafficwatch/server/reports/AnalysisProperties.kt`
- Modify: `server/src/main/kotlin/com/trafficwatch/server/geo/ClipFlowAnalyzer.kt:58-88`
- Modify: `server/src/main/resources/application.yml` (`app.analysis` section, currently lines 42-50)
- Test: `server/src/test/kotlin/com/trafficwatch/server/geo/ClipFlowAnalyzerTest.kt`

**Interfaces:**
- Consumes: `VehicleAnalysisResult.boundingBox: BoundingBox?` (unchanged, already exists in `server/src/main/kotlin/com/trafficwatch/server/videoanalysis/dto/VideoAnalysisDtos.kt`, with `BoundingBox(x1: Double, y1: Double, x2: Double, y2: Double)`).
- Produces: `AnalysisProperties.minDisplacementFraction: Double` (default `0.15`) - a new configuration field. `ClipFlowAnalyzer.qualifyVehicles(...)`'s signature and return type (`List<FlowVehicle>`) are unchanged; only its internal displacement-floor computation changes. No other file calls `qualifyVehicles` today except `ReportAnalysisJob.kt` (unaffected call site, same 4-argument signature).

- [ ] **Step 1: Add the new config field**

In `server/src/main/kotlin/com/trafficwatch/server/reports/AnalysisProperties.kt`, replace the whole file with:

```kotlin
package com.trafficwatch.server.reports

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * Binds `app.analysis.*` configuration for [ReportAnalysisJob]. Includes direction validation
 * thresholds ([wrongWayToleranceDegrees], [agreementToleranceDegrees]), evidence-fusion
 * controls ([confirmationThreshold], [weakEvidenceFloor], [consensusMinResultantLength]),
 * track-quality gating ([minDisplacementFraction]), and learned-history maturity gates
 * ([historyMinObservations], [historyMinDistinctReporters], [historyMinResultantLength]).
 */
@Component
@ConfigurationProperties(prefix = "app.analysis")
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
    // A track's displacement must clear this fraction of its OWN bounding-box diagonal to
    // count as real motion rather than detection jitter - scaled to the vehicle's own
    // apparent size so nearby (large-in-frame) and distant (small-in-frame) vehicles are
    // held to a comparable standard.
    var minDisplacementFraction: Double = 0.15,
    // Learned-history maturity gates - ALL must hold before history testifies.
    var historyMinObservations: Int = 5,
    var historyMinDistinctReporters: Int = 3,
    var historyMinResultantLength: Double = 0.8,
)
```

- [ ] **Step 2: Add `min-displacement-fraction` to `application.yml`**

In `server/src/main/resources/application.yml`, find the `app.analysis` section:

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

Replace it with:

```yaml
  analysis:
    wrong-way-tolerance-degrees: 60
    confirmation-threshold: 0.5
    agreement-tolerance-degrees: 45
    weak-evidence-floor: 0.2
    consensus-min-resultant-length: 0.6
    min-displacement-fraction: 0.15
    history-min-observations: 5
    history-min-distinct-reporters: 3
    history-min-resultant-length: 0.8
```

- [ ] **Step 3: Write the failing tests**

In `server/src/test/kotlin/com/trafficwatch/server/geo/ClipFlowAnalyzerTest.kt`, add the `BoundingBox` import alongside the existing one:

```kotlin
import com.trafficwatch.server.videoanalysis.dto.BoundingBox
import com.trafficwatch.server.videoanalysis.dto.VehicleAnalysisResult
```

Replace the `vehicle()` helper (currently lines 15-34):

```kotlin
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
```

with:

```kotlin
    private fun vehicle(
        trackId: Long,
        bearing: Double?,
        corridorId: Long? = 0L,
        cohesion: Double? = 1.0,
        frames: Int? = 10,
        displacement: Double? = 200.0,
        detectionConfidence: Double = 0.9,
        // 50x50 -> diagonal ~70.7px, so the 0.15 floor is ~10.6px; the default
        // displacement of 200.0 clears it trivially, same as it cleared the old
        // frame-relative floor, so unrelated tests need no other changes.
        boundingBox: BoundingBox? = BoundingBox(x1 = 0.0, y1 = 0.0, x2 = 50.0, y2 = 50.0),
    ) = VehicleAnalysisResult(
        trackId = trackId,
        vehicleType = "car",
        detectionConfidence = detectionConfidence,
        bearingDegrees = bearing,
        plateText = null,
        plateConfidence = null,
        boundingBox = boundingBox,
        corridorId = corridorId,
        corridorCohesion = cohesion,
        trackFrameCount = frames,
        displacementPixels = displacement,
    )
```

Replace the `qualifyVehicles drops null bearings null corridor fields and null frame dims` test (currently lines 46-50):

```kotlin
    @Test
    fun `qualifyVehicles drops null bearings null corridor fields and null frame dims`() {
        assertTrue(analyzer.qualifyVehicles(listOf(vehicle(1, bearing = null)), 0.0, 1920, 1080).isEmpty())
        assertTrue(analyzer.qualifyVehicles(listOf(vehicle(1, 90.0, corridorId = null)), 0.0, 1920, 1080).isEmpty())
        assertTrue(analyzer.qualifyVehicles(listOf(vehicle(1, 90.0)), 0.0, null, null).isEmpty())
    }
```

with:

```kotlin
    @Test
    fun `qualifyVehicles drops null bearings null corridor fields null bounding box and null frame dims`() {
        assertTrue(analyzer.qualifyVehicles(listOf(vehicle(1, bearing = null)), 0.0, 1920, 1080).isEmpty())
        assertTrue(analyzer.qualifyVehicles(listOf(vehicle(1, 90.0, corridorId = null)), 0.0, 1920, 1080).isEmpty())
        assertTrue(analyzer.qualifyVehicles(listOf(vehicle(1, 90.0, boundingBox = null)), 0.0, 1920, 1080).isEmpty())
        assertTrue(analyzer.qualifyVehicles(listOf(vehicle(1, 90.0)), 0.0, null, null).isEmpty())
    }
```

Replace the `qualifyVehicles enforces the quality floor` test (currently lines 52-58):

```kotlin
    @Test
    fun `qualifyVehicles enforces the quality floor`() {
        // Too few frames.
        assertTrue(analyzer.qualifyVehicles(listOf(vehicle(1, 90.0, frames = 2)), 0.0, 1920, 1080).isEmpty())
        // Displacement under 5% of diagonal (~110.1px).
        assertTrue(analyzer.qualifyVehicles(listOf(vehicle(1, 90.0, displacement = 50.0)), 0.0, 1920, 1080).isEmpty())
    }
```

with:

```kotlin
    @Test
    fun `qualifyVehicles enforces the quality floor`() {
        // Too few frames.
        assertTrue(analyzer.qualifyVehicles(listOf(vehicle(1, 90.0, frames = 2)), 0.0, 1920, 1080).isEmpty())
        // Displacement under 15% of the vehicle's own bbox diagonal (default 50x50 bbox,
        // diagonal ~70.7px, floor ~10.6px).
        assertTrue(analyzer.qualifyVehicles(listOf(vehicle(1, 90.0, displacement = 5.0)), 0.0, 1920, 1080).isEmpty())
    }
```

Add a new test immediately after it, proving the fix's actual effect on a real-shaped case:

```kotlin
    @Test
    fun `a small close-up vehicle qualifies on modest absolute displacement relative to its own size`() {
        // Mirrors a real production case: a motorcycle close to the camera with a small
        // bounding box (~100.1px diagonal) and a displacement (34.6px) that would have
        // failed the OLD frame-relative floor (5% of a 1920x1080 frame's ~2202.9px
        // diagonal = ~110.1px) but is ~35% of its OWN bbox diagonal - clearly real
        // motion, not jitter.
        val closeBbox = BoundingBox(x1 = 453.6, y1 = 1049.4, x2 = 508.8, y2 = 1132.9)
        val result = analyzer.qualifyVehicles(
            listOf(vehicle(1, 171.0, displacement = 34.6, boundingBox = closeBbox)), 0.0, 1920, 1080
        )
        assertEquals(1, result.size)
    }
```

- [ ] **Step 4: Run the tests to verify the new/changed ones fail**

Run (from `server/`): `./gradlew.bat test --tests "com.trafficwatch.server.geo.ClipFlowAnalyzerTest"`
Expected: FAIL, for two reasons - the current `qualifyVehicles()` doesn't look at `boundingBox` at all yet:
- The null-bbox assertion in the renamed "drops null..." test fails: passing `boundingBox = null` has no effect on the unmodified function (nothing checks it), so the vehicle still qualifies and the list isn't empty.
- The new "small close-up vehicle qualifies" test fails: the unmodified function still measures displacement against the *frame* diagonal (~2202.9px, floor ~110.1px), and 34.6px is well below that, so the vehicle is dropped and `result.size` is `0`, not the expected `1`.

- [ ] **Step 5: Replace `qualifyVehicles()`**

In `server/src/main/kotlin/com/trafficwatch/server/geo/ClipFlowAnalyzer.kt`, replace the whole function (currently lines 58-88):

```kotlin
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
```

with:

```kotlin
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

        return vehicles.mapNotNull { vehicle ->
            val frameBearing = vehicle.bearingDegrees ?: return@mapNotNull null
            val corridorId = vehicle.corridorId ?: return@mapNotNull null
            val cohesion = vehicle.corridorCohesion ?: return@mapNotNull null
            val frames = vehicle.trackFrameCount ?: return@mapNotNull null
            val displacement = vehicle.displacementPixels ?: return@mapNotNull null
            val bbox = vehicle.boundingBox ?: return@mapNotNull null

            val bboxDiagonal = hypot(bbox.x2 - bbox.x1, bbox.y2 - bbox.y1)
            val minDisplacement = properties.minDisplacementFraction * bboxDiagonal

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
```

Then remove the now-unused file-level constant near the top of the same file:

```kotlin
/** Fraction of the frame diagonal below which a track's displacement is noise. */
private const val MIN_DISPLACEMENT_FRACTION = 0.05
```

(Delete this line entirely - it's superseded by `properties.minDisplacementFraction`. `MIN_TRACK_FRAMES` and `TRACK_FRAMES_SATURATION`, the two other file-level constants, are untouched.)

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew.bat test --tests "com.trafficwatch.server.geo.ClipFlowAnalyzerTest"`
Expected: PASS (10 tests: the file's original 9 `@Test` methods, 2 of them modified in place, plus 1 new one added in Step 3).

- [ ] **Step 7: Run the full server test suite to confirm no regressions**

Run (from `server/`): `./gradlew.bat test`
Expected: all tests PASS. `ReportAnalysisJobTest.kt` and `ReportAnalysisIntegrationTest.kt` construct their own `AnalysisProperties()` with defaults or WireMock-stubbed video-analysis responses - check whether any existing test there hand-constructs a `VehicleAnalysisResult`-equivalent fixture without a bounding box that was previously relying on the frame-relative floor passing by coincidence. If any such test fails, add a bounding box to its fixture data large enough to clear `0.15 * bboxDiagonal` given that fixture's existing displacement value - do not change the production code to accommodate a test fixture.

- [ ] **Step 8: Commit**

```bash
git add server/src/main/kotlin/com/trafficwatch/server/reports/AnalysisProperties.kt server/src/main/kotlin/com/trafficwatch/server/geo/ClipFlowAnalyzer.kt server/src/main/resources/application.yml server/src/test/kotlin/com/trafficwatch/server/geo/ClipFlowAnalyzerTest.kt
git commit -m "fix(server): scale displacement quality floor to each vehicle's own bbox size

qualifyVehicles() measured a track's minimum-displacement floor against
the whole frame's diagonal, penalizing vehicles close to the camera
(which need far fewer absolute pixels of movement to represent real
motion than distant ones). A confirmed real wrong-way motorcycle was
silently dropped this way before ever being evaluated. Normalize
against the vehicle's own bounding-box diagonal instead, and promote
the threshold to AnalysisProperties alongside its sibling knobs."
```

- [ ] **Step 9: Deploy to production and manually verify**

The server isn't deployed via a simple file copy like the Python video-analysis service - it needs a full rebuild and redeploy of the `trafficwatch-server` container:

```bash
ssh -i ~/.ssh/trafficwatch_ovh ubuntu@137.74.173.97 "cd ~/trafficwatch && docker compose -f docker-compose.prod.yml up -d --build server"
```

Resubmit the same real clip used throughout this diagnosis (already on the production server, pulled during earlier diagnosis) through the full pipeline via `POST /v1/reports`, poll `GET /v1/reports/{id}/status` until terminal, and inspect the `evidence_breakdown` in the response.
Expected: track 11 (the confirmed wrong-way motorcycle, bearing 171 degrees) now appears in the qualified flow vehicles and is evaluated as a candidate - check whether the report now reaches `CONFIRMED` given its bearing is well within tolerance of the wrong-way direction implied by the corridor consensus. If it doesn't reach `CONFIRMED`, capture the new `evidence_breakdown`/message to understand what specifically still blocks it (a different, separately-scoped issue, not a failure of this task).
