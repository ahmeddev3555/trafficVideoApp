# OSM Street/Direction Resolution Accuracy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Scale the server's OSM Overpass search radius to a report's GPS
accuracy, make the nearest-way selection ambiguity-aware (GPS uncertainty)
and divided-carriageway-aware (same-street uncertainty), and keep the
lat/lon cache correct once the radius varies per report.

**Architecture:** All changes live inside
`server/src/main/kotlin/com/trafficwatch/server/geo/StreetDirectionResolver.kt`
and its immediate collaborators (`OverpassClient`, `OsmProperties`,
`OsmLookupCache`) - no new services, no new external API calls. Three
tasks: (1) thread the report's accuracy through to a computed search
radius, (2) rewrite the nearest-way selection to be ambiguity-aware and
divided-carriageway-aware, (3) make the lat/lon cache radius-aware so a
narrow-radius cached result is never wrongly served to a report that needs
a wider search.

**Tech Stack:** Kotlin, Spring Boot, Spring `RestClient`, JUnit 5, MockK,
AssertJ, WireMock (stubs Overpass/Nominatim - no real network calls in
tests), Flyway migrations.

## Global Constraints

- Radius formula: `searchRadius = clamp(accuracyMeters * radiusAccuracyMultiplier, searchRadiusMeters, maxSearchRadiusMeters)`, with `radiusAccuracyMultiplier = 2.0`, `searchRadiusMeters = 50.0` (unchanged existing floor), `maxSearchRadiusMeters = 200.0`.
- Ambiguity rule: a second candidate way is "not clearly the same street" as the best match when the two do NOT share the same non-null `name` tag (different names, or either missing a name, both count as "not clearly the same"). When not clearly the same street AND the distance gap between them is smaller than the report's own accuracy in meters, return `Unknown(bestMatch.streetName)` instead of confidently picking one.
- Divided-carriageway rule: after landing on a confident `OneWay` result, if any OTHER candidate way is also `oneway`-tagged (`yes`/`true`/`1`/`-1`/`reverse`), its own bearing is anti-parallel to the chosen way's legal bearing (angular difference > 135°, i.e. within ±45° of exactly 180°), and its own distance-to-point is within 30m of the chosen way's distance-to-point, downgrade the result to `Unknown(streetName)`.
- `Unknown` (never `TwoWay`) is the correct downgrade target in both cases above - it matches this resolver's existing "OSM tag isn't trustworthy enough to assert a legal direction on its own, but clip-consensus/history evidence can still confirm or reject normally" semantics used elsewhere.
- Cache correctness: `OsmLookupCache` stores the search radius that produced each row. A cached row is only reused if its stored radius is `>=` the radius the current lookup needs; otherwise it's a miss, gets re-resolved with the wider radius, and the cache row is overwritten.

---

### Task 1: Thread report accuracy through to a computed, config-bounded search radius

**Files:**
- Modify: `server/src/main/kotlin/com/trafficwatch/server/geo/OsmProperties.kt`
- Modify: `server/src/main/kotlin/com/trafficwatch/server/geo/OverpassClient.kt`
- Modify: `server/src/main/kotlin/com/trafficwatch/server/geo/StreetDirectionResolver.kt`
- Modify: `server/src/main/kotlin/com/trafficwatch/server/reports/ReportAnalysisJob.kt:97`
- Modify: `server/src/main/resources/application.yml`
- Test (new): `server/src/test/kotlin/com/trafficwatch/server/geo/SearchRadiusTest.kt`
- Test: `server/src/test/kotlin/com/trafficwatch/server/geo/OverpassClientTest.kt`
- Test: `server/src/test/kotlin/com/trafficwatch/server/geo/StreetDirectionResolverTest.kt`
- Test: `server/src/test/kotlin/com/trafficwatch/server/reports/ReportAnalysisJobTest.kt`

**Interfaces:**
- Consumes: `Report.accuracy: BigDecimal` (already exists on the entity, non-nullable).
- Produces: `internal fun computeSearchRadius(accuracyMeters: Double, floorMeters: Double, multiplier: Double, capMeters: Double): Double` in `StreetDirectionResolver.kt` (top-level, package-private to `com.trafficwatch.server.geo`). `OverpassClient.findNearbyWays(lat: Double, lon: Double, radiusMeters: Double): List<OverpassElement>`. `StreetDirectionResolver.resolve(latitude: BigDecimal, longitude: BigDecimal, accuracyMeters: Double): DirectionResolution` (was 2-arg, now 3-arg). Task 2 and Task 3 build on both of these.

- [ ] **Step 1: Write the failing test for the pure radius-computation function**

Create `server/src/test/kotlin/com/trafficwatch/server/geo/SearchRadiusTest.kt`:

```kotlin
package com.trafficwatch.server.geo

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SearchRadiusTest {

    @Test
    fun `scales linearly with accuracy between the floor and cap`() {
        assertThat(computeSearchRadius(accuracyMeters = 80.0, floorMeters = 50.0, multiplier = 2.0, capMeters = 200.0))
            .isEqualTo(160.0)
    }

    @Test
    fun `never goes below the floor for very precise accuracy`() {
        assertThat(computeSearchRadius(accuracyMeters = 2.0, floorMeters = 50.0, multiplier = 2.0, capMeters = 200.0))
            .isEqualTo(50.0)
    }

    @Test
    fun `never exceeds the cap for very poor accuracy`() {
        assertThat(computeSearchRadius(accuracyMeters = 500.0, floorMeters = 50.0, multiplier = 2.0, capMeters = 200.0))
            .isEqualTo(200.0)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew.bat :server:test --tests "com.trafficwatch.server.geo.SearchRadiusTest"`
Expected: FAILURE - compile error, `computeSearchRadius` is unresolved.

- [ ] **Step 3: Add the two new config properties**

In `server/src/main/kotlin/com/trafficwatch/server/geo/OsmProperties.kt`, add two fields after `searchRadiusMeters`:

```kotlin
    var searchRadiusMeters: Int = 50,
    var maxSearchRadiusMeters: Int = 200,
    var radiusAccuracyMultiplier: Double = 2.0,
    var lookupRetryAttempts: Int = 3,
```

(Only the three lines shown change - `searchRadiusMeters` and `lookupRetryAttempts` already exist and bracket the two new lines; leave the rest of the file as-is.)

- [ ] **Step 4: Implement `computeSearchRadius`**

At the bottom of `server/src/main/kotlin/com/trafficwatch/server/geo/StreetDirectionResolver.kt`, next to the existing `private fun Double.toBigDecimal()` helper, add:

```kotlin
/**
 * Search radius scaled from the report's GPS accuracy - wider accuracy uncertainty means a
 * wider net is needed to have any chance of including the true street as a candidate.
 * Clamped between [floorMeters] (today's fixed default, so a very precise fix still gets a
 * sane minimum search area) and [capMeters] (bounds Overpass query cost/latency for a very
 * poor GPS fix).
 */
internal fun computeSearchRadius(accuracyMeters: Double, floorMeters: Double, multiplier: Double, capMeters: Double): Double =
    (accuracyMeters * multiplier).coerceIn(floorMeters, capMeters)
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew.bat :server:test --tests "com.trafficwatch.server.geo.SearchRadiusTest"`
Expected: BUILD SUCCESSFUL, 3 tests passed.

- [ ] **Step 6: Add the new properties to `application.yml`**

In `server/src/main/resources/application.yml`, change:

```yaml
    search-radius-meters: 50
    lookup-retry-attempts: 3
```

to:

```yaml
    search-radius-meters: 50
    max-search-radius-meters: 200
    radius-accuracy-multiplier: 2.0
    lookup-retry-attempts: 3
```

- [ ] **Step 7: Thread the radius through `OverpassClient`**

In `server/src/main/kotlin/com/trafficwatch/server/geo/OverpassClient.kt`, change the method signature and query body:

```kotlin
    /** Ways with a `highway` tag within [radiusMeters] of [lat]/[lon]. */
    fun findNearbyWays(lat: Double, lon: Double, radiusMeters: Double): List<OverpassElement> = withOsmRetry(osmProperties) {
        val query = """
            [out:json];
            way(around:$radiusMeters,$lat,$lon)["highway"];
            out geom;
        """.trimIndent()
```

(Replace the doc comment's `[OsmProperties.searchRadiusMeters]` reference and the `${osmProperties.searchRadiusMeters}` interpolation in the query - everything else in the function body is unchanged.)

- [ ] **Step 8: Update `OverpassClientTest.kt`'s three existing calls and add a radius-assertion test**

In `server/src/test/kotlin/com/trafficwatch/server/geo/OverpassClientTest.kt`, change all three existing calls from `client().findNearbyWays(31.5, 74.3)` to `client().findNearbyWays(31.5, 74.3, 50.0)` (lines 53, 63, 73 - the retry-attempts variant on line 73 keeps its `retryAttempts = 3` argument, just add the new positional radius argument).

Add the import `import com.github.tomakehurst.wiremock.client.WireMock.containing` and `import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor` alongside the existing WireMock imports, then add a new test:

```kotlin
    @Test
    fun `findNearbyWays includes the given radius in the query`() {
        wireMockServer.stubFor(post(urlPathEqualTo("/")).willReturn(okJson("""{"elements": []}""")))

        client().findNearbyWays(31.5, 74.3, 120.0)

        wireMockServer.verify(
            postRequestedFor(urlPathEqualTo("/")).withRequestBody(containing("around:120.0,31.5,74.3")),
        )
    }
```

- [ ] **Step 9: Run `OverpassClientTest`, verify it passes**

Run: `./gradlew.bat :server:test --tests "com.trafficwatch.server.geo.OverpassClientTest"`
Expected: BUILD SUCCESSFUL, 4 tests passed.

- [ ] **Step 10: Thread `accuracyMeters` through `StreetDirectionResolver.resolve()`**

In `server/src/main/kotlin/com/trafficwatch/server/geo/StreetDirectionResolver.kt`:

Add `private val osmProperties: OsmProperties` to the constructor:

```kotlin
@Component
class StreetDirectionResolver(
    private val nominatimClient: NominatimClient,
    private val overpassClient: OverpassClient,
    private val cacheRepository: OsmLookupCacheRepository,
    private val osmProperties: OsmProperties,
) {
```

Change `resolve` and `resolveFresh` (the cache-gate and `persist()` call stay exactly as they are today - only the new parameter and the radius computation are added; Task 3 will change the cache-gate itself):

```kotlin
    fun resolve(latitude: BigDecimal, longitude: BigDecimal, accuracyMeters: Double): DirectionResolution {
        val latBucket = roundToBucket(latitude)
        val lonBucket = roundToBucket(longitude)
        val searchRadius = computeSearchRadius(
            accuracyMeters,
            osmProperties.searchRadiusMeters.toDouble(),
            osmProperties.radiusAccuracyMultiplier,
            osmProperties.maxSearchRadiusMeters.toDouble(),
        )

        cacheRepository.findByLatBucketAndLonBucket(latBucket, lonBucket)?.let {
            return it.toDirectionResolution()
        }

        val resolution = try {
            resolveFresh(latitude.toDouble(), longitude.toDouble(), searchRadius)
        } catch (ex: OsmLookupException) {
            return DirectionResolution.LookupFailed(ex.message ?: "OSM lookup failed")
        }

        persist(latBucket, lonBucket, resolution)
        return resolution
    }

    private fun resolveFresh(lat: Double, lon: Double, searchRadius: Double): DirectionResolution {
        val ways = overpassClient.findNearbyWays(lat, lon, searchRadius)
        if (ways.isEmpty()) {
            return DirectionResolution.NotFound
        }
        // ... rest of the function body is UNCHANGED from its current form ...
```

(Only the signature and the `overpassClient.findNearbyWays` call change in this step - the candidate-selection body inside `resolveFresh` is rewritten in Task 2, not here.)

- [ ] **Step 11: Update `StreetDirectionResolverTest.kt`'s seven existing `resolve()` calls**

In `server/src/test/kotlin/com/trafficwatch/server/geo/StreetDirectionResolverTest.kt`, add `, accuracyMeters = 10.0` to every existing `streetDirectionResolver.resolve(...)` call (there are 7: one per test method, except the "reverse direction" test which has 2). For example:

```kotlin
        val result = streetDirectionResolver.resolve(BigDecimal("31.520000"), BigDecimal("74.350000"), accuracyMeters = 10.0)
```

Apply the same pattern to every other `resolve(...)` call in the file (lines 70, 82, 87, 101, 112, 124, 135, 136, 145).

- [ ] **Step 12: Add a test asserting the radius scales with accuracy**

Add this test to `StreetDirectionResolverTest.kt` (needs `import com.github.tomakehurst.wiremock.client.WireMock.containing` added alongside the existing WireMock imports):

```kotlin
    @Test
    fun `scales the Overpass search radius with the report's accuracy`() {
        wireMockServer.stubFor(post(urlMatching(".*")).willReturn(okJson("""{"elements": []}""")))

        streetDirectionResolver.resolve(BigDecimal("60.000000"), BigDecimal("60.000000"), accuracyMeters = 80.0)

        wireMockServer.verify(
            postRequestedFor(urlMatching(".*")).withRequestBody(containing("around:160.0,")),
        )
    }
```

(80.0 accuracy * 2.0 multiplier = 160.0, comfortably inside the 50-200 clamp range.)

- [ ] **Step 13: Run `StreetDirectionResolverTest`, verify it passes**

Run: `./gradlew.bat :server:test --tests "com.trafficwatch.server.geo.StreetDirectionResolverTest"`
Expected: BUILD SUCCESSFUL, 8 tests passed.

- [ ] **Step 14: Update `ReportAnalysisJob.kt`'s call site**

In `server/src/main/kotlin/com/trafficwatch/server/reports/ReportAnalysisJob.kt:97`, change:

```kotlin
        val resolution = streetDirectionResolver.resolve(report.latitude, report.longitude)
```

to:

```kotlin
        val resolution = streetDirectionResolver.resolve(report.latitude, report.longitude, report.accuracy.toDouble())
```

- [ ] **Step 15: Bulk-update `ReportAnalysisJobTest.kt`'s 31 mock call sites**

Every occurrence in this file is the exact literal string
`streetDirectionResolver.resolve(report.latitude, report.longitude)` (verified: no
variant call sites exist). Run this PowerShell command from the repo root to update
all 31 occurrences in one pass:

```powershell
$path = "server/src/test/kotlin/com/trafficwatch/server/reports/ReportAnalysisJobTest.kt"
(Get-Content $path -Raw) -replace [regex]::Escape('streetDirectionResolver.resolve(report.latitude, report.longitude)'), 'streetDirectionResolver.resolve(report.latitude, report.longitude, report.accuracy.toDouble())' | Set-Content $path
```

Then verify the replacement was complete and exact:

```powershell
(Select-String -Path $path -Pattern 'resolve\(report\.latitude, report\.longitude\)$').Count
(Select-String -Path $path -Pattern 'resolve\(report\.latitude, report\.longitude, report\.accuracy\.toDouble\(\)\)').Count
```

Expected: first command prints `0` (no old-signature calls remain - the `$` anchor
means a partial match inside the new 3-arg form does not count), second prints `31`.

- [ ] **Step 16: Run `ReportAnalysisJobTest`, verify it passes**

Run: `./gradlew.bat :server:test --tests "com.trafficwatch.server.reports.ReportAnalysisJobTest"`
Expected: BUILD SUCCESSFUL, all tests passed (same count as before this change - no
test behavior changed, only the mock signature).

- [ ] **Step 17: Run the full server test suite**

Run: `./gradlew.bat :server:test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 18: Commit**

```bash
git add server/src/main/kotlin/com/trafficwatch/server/geo/OsmProperties.kt server/src/main/kotlin/com/trafficwatch/server/geo/OverpassClient.kt server/src/main/kotlin/com/trafficwatch/server/geo/StreetDirectionResolver.kt server/src/main/kotlin/com/trafficwatch/server/reports/ReportAnalysisJob.kt server/src/main/resources/application.yml server/src/test/kotlin/com/trafficwatch/server/geo/SearchRadiusTest.kt server/src/test/kotlin/com/trafficwatch/server/geo/OverpassClientTest.kt server/src/test/kotlin/com/trafficwatch/server/geo/StreetDirectionResolverTest.kt server/src/test/kotlin/com/trafficwatch/server/reports/ReportAnalysisJobTest.kt
git commit -m "feat(server): scale OSM Overpass search radius by report GPS accuracy

StreetDirectionResolver.resolve() now takes the report's accuracy and
computes a search radius (clamped 50-200m) instead of always querying
a flat 50m. Way-selection logic is unchanged in this commit - only the
radius plumbing."
```

---

### Task 2: Ambiguity-aware and divided-carriageway-aware way selection

**Files:**
- Modify: `server/src/main/kotlin/com/trafficwatch/server/geo/StreetDirectionResolver.kt`
- Test: `server/src/test/kotlin/com/trafficwatch/server/geo/StreetDirectionResolverTest.kt`

**Interfaces:**
- Consumes: `computeSearchRadius` and the 3-arg `resolve()` from Task 1.
- Produces: `resolveFresh(lat: Double, lon: Double, searchRadius: Double, accuracyMeters: Double): DirectionResolution` (gains a 4th parameter). This is `private`, only called from `resolve()` within the same file, which Task 1 already threads `searchRadius` into - this task adds the `accuracyMeters` argument to that same call.

- [ ] **Step 1: Write the failing tests**

Add these four tests to `server/src/test/kotlin/com/trafficwatch/server/geo/StreetDirectionResolverTest.kt`. They use a new private helper (add it at the bottom of the test class, alongside the existing `overpassResponseJson` helper) that builds a two-way Overpass response with controlled east-west segments at controlled latitude offsets from a base point - this makes the resulting "distance to point" and "bearing" values fully predictable:

```kotlin
    private fun twoWayOverpassResponseJson(
        wayAId: Long, wayAName: String?, wayAOneway: String?, wayALatOffsetDegrees: Double, wayAWestToEast: Boolean,
        wayBId: Long, wayBName: String?, wayBOneway: String?, wayBLatOffsetDegrees: Double, wayBWestToEast: Boolean,
        baseLat: Double, baseLon: Double,
    ): String {
        fun wayJson(id: Long, name: String?, oneway: String?, latOffset: Double, westToEast: Boolean): String {
            val lat = baseLat + latOffset
            val (lon1, lon2) = if (westToEast) (baseLon - 0.0010) to (baseLon + 0.0010) else (baseLon + 0.0010) to (baseLon - 0.0010)
            val tagsJson = buildString {
                if (name != null) append(""""name": "$name"""")
                if (oneway != null) {
                    if (name != null) append(", ")
                    append(""""oneway": "$oneway"""")
                }
            }
            return """
                {
                  "type": "way",
                  "id": $id,
                  "tags": { $tagsJson },
                  "geometry": [
                    {"lat": $lat, "lon": $lon1},
                    {"lat": $lat, "lon": $lon2}
                  ]
                }
            """.trimIndent()
        }
        return """{"elements": [${wayJson(wayAId, wayAName, wayAOneway, wayALatOffsetDegrees, wayAWestToEast)}, ${wayJson(wayBId, wayBName, wayBOneway, wayBLatOffsetDegrees, wayBWestToEast)}]}"""
    }

    @Test
    fun `returns Unknown when two different-named ways are within accuracy meters of each other`() {
        // Way A ~11.1m away, Way B ~22.2m away - gap ~11.1m, smaller than the 15.0m accuracy below.
        wireMockServer.stubFor(
            post(urlMatching(".*")).willReturn(
                okJson(
                    twoWayOverpassResponseJson(
                        wayAId = 301, wayAName = "Street A", wayAOneway = "yes", wayALatOffsetDegrees = 0.000100, wayAWestToEast = true,
                        wayBId = 302, wayBName = "Street B", wayBOneway = null, wayBLatOffsetDegrees = 0.000200, wayBWestToEast = true,
                        baseLat = 61.000000, baseLon = 61.000000,
                    ),
                ),
            ),
        )

        val result = streetDirectionResolver.resolve(BigDecimal("61.000000"), BigDecimal("61.000000"), accuracyMeters = 15.0)

        assertThat(result).isInstanceOf(DirectionResolution.Unknown::class.java)
        assertThat((result as DirectionResolution.Unknown).streetName).isEqualTo("Street A")
    }

    @Test
    fun `does not treat two segments of the same named street as ambiguous`() {
        // Same gap (~11.1m) as the previous test, but both ways share a name.
        wireMockServer.stubFor(
            post(urlMatching(".*")).willReturn(
                okJson(
                    twoWayOverpassResponseJson(
                        wayAId = 303, wayAName = "Shared Street", wayAOneway = "yes", wayALatOffsetDegrees = 0.000100, wayAWestToEast = true,
                        wayBId = 304, wayBName = "Shared Street", wayBOneway = "yes", wayBLatOffsetDegrees = 0.000200, wayBWestToEast = true,
                        baseLat = 62.000000, baseLon = 62.000000,
                    ),
                ),
            ),
        )

        val result = streetDirectionResolver.resolve(BigDecimal("62.000000"), BigDecimal("62.000000"), accuracyMeters = 15.0)

        assertThat(result).isInstanceOf(DirectionResolution.OneWay::class.java)
        assertThat((result as DirectionResolution.OneWay).streetName).isEqualTo("Shared Street")
    }

    @Test
    fun `downgrades to Unknown when a nearby anti-parallel oneway way signals a divided carriageway`() {
        // Way A ~11.1m away bearing ~90 deg (east); Way B ~27.8m away bearing ~270 deg (west,
        // anti-parallel). Gap ~16.7m: bigger than the 5.0m accuracy below (so the ambiguity
        // check does NOT fire), smaller than the 30m divided-carriageway proximity cap.
        wireMockServer.stubFor(
            post(urlMatching(".*")).willReturn(
                okJson(
                    twoWayOverpassResponseJson(
                        wayAId = 305, wayAName = "Ring Road North", wayAOneway = "yes", wayALatOffsetDegrees = 0.000100, wayAWestToEast = true,
                        wayBId = 306, wayBName = "Ring Road South", wayBOneway = "yes", wayBLatOffsetDegrees = 0.000250, wayBWestToEast = false,
                        baseLat = 63.000000, baseLon = 63.000000,
                    ),
                ),
            ),
        )

        val result = streetDirectionResolver.resolve(BigDecimal("63.000000"), BigDecimal("63.000000"), accuracyMeters = 5.0)

        assertThat(result).isInstanceOf(DirectionResolution.Unknown::class.java)
        assertThat((result as DirectionResolution.Unknown).streetName).isEqualTo("Ring Road North")
    }

    @Test
    fun `does not downgrade for a distant anti-parallel oneway way outside the carriageway proximity cap`() {
        // Way A ~11.1m away bearing ~90 deg; Way B ~55.6m away bearing ~270 deg (anti-parallel,
        // but the ~44.5m gap exceeds the 30m divided-carriageway proximity cap).
        wireMockServer.stubFor(
            post(urlMatching(".*")).willReturn(
                okJson(
                    twoWayOverpassResponseJson(
                        wayAId = 307, wayAName = "Avenue A", wayAOneway = "yes", wayALatOffsetDegrees = 0.000100, wayAWestToEast = true,
                        wayBId = 308, wayBName = "Avenue B", wayBOneway = "yes", wayBLatOffsetDegrees = 0.000500, wayBWestToEast = false,
                        baseLat = 64.000000, baseLon = 64.000000,
                    ),
                ),
            ),
        )

        val result = streetDirectionResolver.resolve(BigDecimal("64.000000"), BigDecimal("64.000000"), accuracyMeters = 5.0)

        assertThat(result).isInstanceOf(DirectionResolution.OneWay::class.java)
        assertThat((result as DirectionResolution.OneWay).streetName).isEqualTo("Avenue A")
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew.bat :server:test --tests "com.trafficwatch.server.geo.StreetDirectionResolverTest"`
Expected: the 4 new tests FAIL (the resolver still does naive nearest-way selection, so
the "Unknown" expectations fail with an `OneWay`/wrong-street result instead).

- [ ] **Step 3: Rewrite `resolveFresh` with ambiguity and divided-carriageway detection**

In `server/src/main/kotlin/com/trafficwatch/server/geo/StreetDirectionResolver.kt`, add two
private constants near the top of the file (outside the class, alongside where
`computeSearchRadius` lives):

```kotlin
private const val DIVIDED_CARRIAGEWAY_ANTI_PARALLEL_TOLERANCE_DEGREES = 45.0
private const val DIVIDED_CARRIAGEWAY_MAX_DISTANCE_GAP_METERS = 30.0
```

Add a small private data class inside the class (or just above it, matching this file's
existing style of top-level private helpers):

```kotlin
private data class WayCandidate(
    val way: OverpassElement,
    val nodes: List<GeoPoint>,
    val segmentIndex: Int,
    val distanceMeters: Double,
)
```

Replace `resolveFresh` entirely with:

```kotlin
    private fun resolveFresh(lat: Double, lon: Double, searchRadius: Double, accuracyMeters: Double): DirectionResolution {
        val ways = overpassClient.findNearbyWays(lat, lon, searchRadius)
        val point = GeoPoint(lat, lon)

        val candidates = ways.mapNotNull { way ->
            val nodes = way.geometry?.map { GeoPoint(it.lat, it.lon) } ?: return@mapNotNull null
            val segmentIndex = BearingMath.nearestSegmentIndex(point, nodes) ?: return@mapNotNull null
            val distance = BearingMath.distanceToSegmentMeters(point, nodes[segmentIndex], nodes[segmentIndex + 1])
            WayCandidate(way, nodes, segmentIndex, distance)
        }.sortedBy { it.distanceMeters }

        val best = candidates.firstOrNull() ?: return DirectionResolution.NotFound
        val streetName = best.way.tags?.get("name") ?: reverseGeocodeStreetName(lat, lon)

        val runnerUp = candidates.getOrNull(1)
        if (runnerUp != null) {
            val bestNameTag = best.way.tags?.get("name")
            val runnerUpNameTag = runnerUp.way.tags?.get("name")
            val sameStreet = bestNameTag != null && runnerUpNameTag != null && bestNameTag == runnerUpNameTag
            if (!sameStreet && (runnerUp.distanceMeters - best.distanceMeters) < accuracyMeters) {
                return DirectionResolution.Unknown(streetName)
            }
        }

        val resolution = when (best.way.tags?.get("oneway")) {
            "yes", "true", "1" -> DirectionResolution.OneWay(
                streetName,
                BearingMath.initialBearingDegrees(best.nodes[best.segmentIndex], best.nodes[best.segmentIndex + 1]),
            )
            "-1", "reverse" -> DirectionResolution.OneWay(
                streetName,
                BearingMath.initialBearingDegrees(best.nodes[best.segmentIndex + 1], best.nodes[best.segmentIndex]),
            )
            "no" -> DirectionResolution.TwoWay(streetName)
            // Tag absent or an unrecognized value: OSM coverage here is too sparse to
            // safely assume "no tag" means "legally two-way" - see DirectionResolution's
            // doc comment.
            else -> DirectionResolution.Unknown(streetName)
        }

        if (resolution is DirectionResolution.OneWay && hasAntiParallelOneWayNeighbor(best, candidates)) {
            return DirectionResolution.Unknown(streetName)
        }
        return resolution
    }

    /**
     * True when another candidate way, also tagged `oneway`, has a legal bearing anti-parallel
     * to [best]'s (within [DIVIDED_CARRIAGEWAY_ANTI_PARALLEL_TOLERANCE_DEGREES] of exactly
     * 180 degrees apart) and sits within [DIVIDED_CARRIAGEWAY_MAX_DISTANCE_GAP_METERS] of
     * [best]'s own distance to the point - the physical signature of a divided road's two
     * separately-tagged, oppositely-legal carriageways.
     */
    private fun hasAntiParallelOneWayNeighbor(best: WayCandidate, candidates: List<WayCandidate>): Boolean {
        val onewayTags = setOf("yes", "true", "1", "-1", "reverse")
        fun legalBearing(candidate: WayCandidate): Double = when (candidate.way.tags?.get("oneway")) {
            "-1", "reverse" -> BearingMath.initialBearingDegrees(candidate.nodes[candidate.segmentIndex + 1], candidate.nodes[candidate.segmentIndex])
            else -> BearingMath.initialBearingDegrees(candidate.nodes[candidate.segmentIndex], candidate.nodes[candidate.segmentIndex + 1])
        }
        val bestBearing = legalBearing(best)

        return candidates.any { other ->
            other !== best &&
                other.way.tags?.get("oneway") in onewayTags &&
                kotlin.math.abs(other.distanceMeters - best.distanceMeters) <= DIVIDED_CARRIAGEWAY_MAX_DISTANCE_GAP_METERS &&
                BearingMath.angularDifferenceDegrees(legalBearing(other), bestBearing) > (180.0 - DIVIDED_CARRIAGEWAY_ANTI_PARALLEL_TOLERANCE_DEGREES)
        }
    }
```

Finally, update the one call site inside `resolve()` (from Task 1) to pass the new
4th argument:

```kotlin
        val resolution = try {
            resolveFresh(latitude.toDouble(), longitude.toDouble(), searchRadius, accuracyMeters)
        } catch (ex: OsmLookupException) {
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew.bat :server:test --tests "com.trafficwatch.server.geo.StreetDirectionResolverTest"`
Expected: BUILD SUCCESSFUL, 12 tests passed (8 from Task 1 + 4 new).

- [ ] **Step 5: Run the full server test suite**

Run: `./gradlew.bat :server:test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add server/src/main/kotlin/com/trafficwatch/server/geo/StreetDirectionResolver.kt server/src/test/kotlin/com/trafficwatch/server/geo/StreetDirectionResolverTest.kt
git commit -m "feat(server): ambiguity-aware and divided-carriageway-aware street resolution

resolveFresh no longer just picks the single nearest way. When two
different-named candidates are within the report's own GPS accuracy
of each other in distance, the result downgrades to Unknown rather
than confidently (and possibly wrongly) picking the nearer one. When
the chosen way is oneway and another nearby way is also oneway but
anti-parallel to it (the divided-carriageway signature), the result
also downgrades to Unknown rather than risking a false wrong-way
confirmation against a legally-driving motorist on the far
carriageway."
```

---

### Task 3: Radius-aware caching

**Files:**
- Modify: `server/src/main/kotlin/com/trafficwatch/server/geo/OsmLookupCache.kt`
- Modify: `server/src/main/kotlin/com/trafficwatch/server/geo/StreetDirectionResolver.kt`
- Create: `server/src/main/resources/db/migration/V9__add_search_radius_to_osm_lookup_cache.sql`
- Test: `server/src/test/kotlin/com/trafficwatch/server/geo/StreetDirectionResolverTest.kt`

**Interfaces:**
- Consumes: `computeSearchRadius`, the 3-arg `resolve()`, and `resolveFresh`'s 4-arg
  signature from Tasks 1-2.
- Produces: `OsmLookupCache.searchRadiusMeters: BigDecimal` (new field). No other file
  depends on this task's output - it's the last task in this plan.

- [ ] **Step 1: Write the failing tests**

Add these two tests to `server/src/test/kotlin/com/trafficwatch/server/geo/StreetDirectionResolverTest.kt`:

```kotlin
    @Test
    fun `reuses a cached result when its stored radius covers the current lookup's needed radius`() {
        wireMockServer.stubFor(
            post(urlMatching(".*")).willReturn(okJson(overpassResponseJson(oneway = "yes", name = "Wide Search Street"))),
        )

        // accuracy 40.0 -> needs 80.0m radius; cache row is written with searchRadiusMeters = 80.0.
        streetDirectionResolver.resolve(BigDecimal("65.000000"), BigDecimal("65.000000"), accuracyMeters = 40.0)
        // accuracy 10.0 -> only needs 50.0m; 80.0 >= 50.0, so this must be served from cache.
        streetDirectionResolver.resolve(BigDecimal("65.000000"), BigDecimal("65.000000"), accuracyMeters = 10.0)

        wireMockServer.verify(1, postRequestedFor(urlMatching(".*")))
    }

    @Test
    fun `re-resolves and overwrites the cache when its stored radius is smaller than the current lookup needs`() {
        wireMockServer.stubFor(
            post(urlMatching(".*")).willReturn(okJson(overpassResponseJson(oneway = "yes", name = "Narrow Search Street"))),
        )

        // accuracy 5.0 -> needs only the 50.0m floor; cache row is written with searchRadiusMeters = 50.0.
        streetDirectionResolver.resolve(BigDecimal("66.000000"), BigDecimal("66.000000"), accuracyMeters = 5.0)
        // accuracy 80.0 -> needs 160.0m; 50.0 < 160.0, so this must NOT be served from the stale cache row.
        streetDirectionResolver.resolve(BigDecimal("66.000000"), BigDecimal("66.000000"), accuracyMeters = 80.0)

        wireMockServer.verify(2, postRequestedFor(urlMatching(".*")))
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew.bat :server:test --tests "com.trafficwatch.server.geo.StreetDirectionResolverTest"`
Expected: the first new test FAILS (today's cache-gate reuses ANY hit regardless of
radius, so it would actually already pass by coincidence) and the second new test FAILS
(today's resolver never issues a second Overpass call once a bucket is cached, so
`wireMockServer.verify(2, ...)` fails with only 1 request seen).

- [ ] **Step 3: Add the `searchRadiusMeters` column to the entity**

In `server/src/main/kotlin/com/trafficwatch/server/geo/OsmLookupCache.kt`, add a new
field right after `lonBucket` (before `streetName`):

```kotlin
    @Column(name = "lon_bucket", nullable = false)
    var lonBucket: BigDecimal,

    @Column(name = "search_radius_meters", nullable = false)
    var searchRadiusMeters: BigDecimal,

    @Column(name = "street_name")
    var streetName: String? = null,
```

- [ ] **Step 4: Create the migration**

Create `server/src/main/resources/db/migration/V9__add_search_radius_to_osm_lookup_cache.sql`:

```sql
ALTER TABLE osm_lookup_cache ADD COLUMN search_radius_meters NUMERIC(6,2) NOT NULL DEFAULT 50.0;
ALTER TABLE osm_lookup_cache ALTER COLUMN search_radius_meters DROP DEFAULT;
```

(The `DEFAULT 50.0` backfills every existing row to today's fixed radius, which is
accurate - all rows written before this change really were produced by a flat 50m
search. Dropping the default afterward matches this codebase's existing convention of
requiring every future insert to supply the column explicitly, rather than silently
relying on a schema default.)

- [ ] **Step 5: Make the cache gate and `persist()` radius-aware**

In `server/src/main/kotlin/com/trafficwatch/server/geo/StreetDirectionResolver.kt`,
change `resolve()`'s cache lookup from:

```kotlin
        cacheRepository.findByLatBucketAndLonBucket(latBucket, lonBucket)?.let {
            return it.toDirectionResolution()
        }
```

to:

```kotlin
        val cached = cacheRepository.findByLatBucketAndLonBucket(latBucket, lonBucket)
        if (cached != null && cached.searchRadiusMeters.toDouble() >= searchRadius) {
            return cached.toDirectionResolution()
        }
```

Change the `persist(latBucket, lonBucket, resolution)` call (a few lines below) to pass
`cached` through too:

```kotlin
        persist(cached, latBucket, lonBucket, searchRadius, resolution)
```

And update `persist()` itself. This is the important part of this step: a bucket that
was already cached (just with too-small a radius) has an existing row with a real,
non-null `id` - `cacheRepository.save()` on a *new* `OsmLookupCache(...)` instance
(`id` always null) would attempt an INSERT and crash on the table's
`uq_osm_lookup_cache_bucket` unique constraint, since a row for that bucket already
exists. `persist()` must mutate-and-save the *existing* row when there is one, so
Spring Data JPA performs an UPDATE (by primary key) instead:

```kotlin
    private fun persist(
        existing: OsmLookupCache?,
        latBucket: BigDecimal,
        lonBucket: BigDecimal,
        searchRadiusMeters: Double,
        resolution: DirectionResolution,
    ) {
        val entity = existing ?: OsmLookupCache(
            latBucket = latBucket,
            lonBucket = lonBucket,
            searchRadiusMeters = searchRadiusMeters.toBigDecimal(),
            directionState = resolution.toDirectionState(),
        )
        entity.searchRadiusMeters = searchRadiusMeters.toBigDecimal()
        entity.streetName = resolution.streetNameOrNull()
        entity.directionState = resolution.toDirectionState()
        entity.legalBearingDegrees = (resolution as? DirectionResolution.OneWay)
            ?.legalBearingDegrees
            ?.toBigDecimal()
        entity.updatedAt = OffsetDateTime.now()
        cacheRepository.save(entity)
    }
```

Add `import java.time.OffsetDateTime` to this file's imports (not previously needed
here). (`toBigDecimal()` is the existing private `Double.toBigDecimal()` extension
already at the bottom of this file - no new helper needed.)

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew.bat :server:test --tests "com.trafficwatch.server.geo.StreetDirectionResolverTest"`
Expected: BUILD SUCCESSFUL, 14 tests passed (12 from Tasks 1-2 + 2 new).

- [ ] **Step 7: Run the full server test suite**

Run: `./gradlew.bat :server:test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Confirm the server builds**

Run: `./gradlew.bat :server:build -x test`
Expected: BUILD SUCCESSFUL (this also validates the new Flyway migration applies
cleanly against the existing schema, via Hibernate's `ddl-auto: validate` config in
`application.yml`, which fails the build if the JPA entity and actual DB schema
disagree).

- [ ] **Step 9: Commit**

```bash
git add server/src/main/kotlin/com/trafficwatch/server/geo/OsmLookupCache.kt server/src/main/kotlin/com/trafficwatch/server/geo/StreetDirectionResolver.kt server/src/main/resources/db/migration/V9__add_search_radius_to_osm_lookup_cache.sql server/src/test/kotlin/com/trafficwatch/server/geo/StreetDirectionResolverTest.kt
git commit -m "feat(server): make the OSM lookup cache radius-aware

OsmLookupCache now stores the search radius that produced each row.
A cached row is only reused when its stored radius covers what the
current lookup needs; otherwise it's treated as a miss, re-resolved
with the wider radius, and overwritten - closing a staleness gap
introduced by scaling the search radius per report."
```

- [ ] **Step 10: Manual smoke test against the real OSM APIs**

This step has no automated verification - it's a one-time sanity check against the
real public Nominatim/Overpass endpoints (not the WireMock stubs used everywhere
else in this plan), matching this project's established pattern for OSM-facing code.

1. Pick a real coordinate on a known divided (dual-carriageway) one-way road - for
   example, a segment of a ring road or motorway near Lahore where OpenStreetMap
   already has both carriageways mapped as separate `oneway=yes` ways. Confirm the
   coordinate first with a direct query:
   ```bash
   curl -s "https://overpass-api.de/api/interpreter" \
     --data-urlencode 'data=[out:json];way(around:100,LAT,LON)["highway"];out geom;' \
     -H "User-Agent: TrafficWatch-Server/1.0 (your-real-contact@example.com)"
   ```
   (Replace `LAT`/`LON`, and set a real contact email - OSM's usage policy requires
   an identifying User-Agent.) Confirm the response contains two `oneway=yes` ways
   with roughly opposite bearings.
2. With the server running locally against this coordinate (via a real report
   submission, or a direct unit-style call if you have a REPL/test harness handy),
   confirm the resolver returns `Unknown`, not a confident `OneWay`, for that point.
3. Pick a second, ordinary (non-divided) one-way street coordinate and confirm it
   still resolves to `OneWay` as before - this plan's changes must not regress the
   common case.
