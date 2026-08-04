# OSM Lookup Retry and Contested-Corridor OSM Override Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix two independent, compounding bugs that let a real wrong-way motorcycle on a confirmed one-way street go undetected: (1) a single transient Overpass/Nominatim HTTP failure permanently loses that report's OSM evidence with no retry, and (2) a candidate in a corridor that also contains other vehicles is skipped before OSM evidence is ever consulted, whenever the corridor's overall bearing consensus is unavailable (bimodal) - even when the candidate's own direction is corroborated by real peers.

**Architecture:** Task 1 adds a small shared retry helper used by both `OverpassClient` and `NominatimClient`, configurable via `OsmProperties`. Task 2 adds a peer-support check to `ClipFlowAnalyzer` and uses it to replace `ReportAnalysisJob`'s blanket "contested corridor never elects a violator" skip with a more precise one that only blocks true lone outliers. The two tasks touch entirely separate files and have no interface dependency on each other.

**Tech Stack:** Kotlin, Spring Boot, Spring `RestClient`, MockK, JUnit 5, WireMock (already a test dependency).

## Global Constraints

- Retry only transient failures: no HTTP response at all (`RestClientException`), or a 5xx `RestClientResponseException`. Never retry a 4xx - that indicates a malformed request, not a transient condition, and retrying would mask a real bug.
- Retry config lives in `OsmProperties`: `lookupRetryAttempts: Int = 3`, `lookupRetryDelayMs: Long = 500` - both configurable, matching every other tuning knob in that class.
- The contested-corridor fix must NOT change the existing `ReportAnalysisJobTest.candidate in a contested (bimodal) corridor is skipped, never falsely confirmed` test's outcome - it is the regression guard for the "no peer support" case and must continue to pass unmodified.
- No changes to `DirectionEvidenceResolver.fuse()`, `corridors.py`/`corridor_cohesion()`, or `StreetDirectionResolver`'s caching behavior (`LookupFailed` still never cached).

---

### Task 1: Retry Overpass/Nominatim lookups on transient failure

**Files:**
- Modify: `server/src/main/kotlin/com/trafficwatch/server/geo/OsmLookupException.kt`
- Create: `server/src/main/kotlin/com/trafficwatch/server/geo/OsmRetry.kt`
- Modify: `server/src/main/kotlin/com/trafficwatch/server/geo/OsmProperties.kt`
- Modify: `server/src/main/kotlin/com/trafficwatch/server/geo/OverpassClient.kt`
- Modify: `server/src/main/kotlin/com/trafficwatch/server/geo/NominatimClient.kt`
- Modify: `server/src/main/resources/application.yml`
- Test: `server/src/test/kotlin/com/trafficwatch/server/geo/OverpassClientTest.kt` (new)
- Test: `server/src/test/kotlin/com/trafficwatch/server/geo/NominatimClientTest.kt` (new)

**Interfaces:**
- Produces: `OsmLookupException(message: String, cause: Throwable? = null, isRetryable: Boolean = true)` - `isRetryable` is a new, third, defaulted constructor parameter (existing two-arg call sites elsewhere are unaffected).
- Produces: `withOsmRetry(properties: OsmProperties, call: () -> T): T` (a top-level function in `OsmRetry.kt`) - retries `call` on a retryable `OsmLookupException`, per `properties.lookupRetryAttempts`/`lookupRetryDelayMs`; re-throws the last exception unchanged after exhausting attempts, or immediately if the exception is not retryable.
- Produces: `OsmProperties.lookupRetryAttempts: Int = 3`, `OsmProperties.lookupRetryDelayMs: Long = 500`.

- [ ] **Step 1: Write the failing test for `withOsmRetry`**

Create `server/src/test/kotlin/com/trafficwatch/server/geo/OverpassClientTest.kt`:

```kotlin
package com.trafficwatch.server.geo

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.github.tomakehurst.wiremock.stubbing.Scenario
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClient

class OverpassClientTest {

    private val wireMockServer = WireMockServer(WireMockConfiguration.options().dynamicPort())

    @BeforeEach
    fun startWireMock() {
        wireMockServer.start()
    }

    @AfterEach
    fun stopWireMock() {
        wireMockServer.stop()
    }

    private fun client(retryAttempts: Int = 3, retryDelayMs: Long = 1L): OverpassClient {
        val restClient = RestClient.builder().baseUrl("http://localhost:${wireMockServer.port()}").build()
        val properties = OsmProperties(lookupRetryAttempts = retryAttempts, lookupRetryDelayMs = retryDelayMs)
        return OverpassClient(restClient, properties)
    }

    @Test
    fun `findNearbyWays retries once on a 503 then returns the successful result`() {
        wireMockServer.stubFor(
            post(urlPathEqualTo("/"))
                .inScenario("retry")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("second attempt"),
        )
        wireMockServer.stubFor(
            post(urlPathEqualTo("/"))
                .inScenario("retry")
                .whenScenarioStateIs("second attempt")
                .willReturn(okJson("""{"elements": []}""")),
        )

        val result = client().findNearbyWays(31.5, 74.3)

        assertThat(result).isEmpty()
        assertThat(wireMockServer.allServeEvents).hasSize(2)
    }

    @Test
    fun `findNearbyWays does not retry a 400 and fails immediately`() {
        wireMockServer.stubFor(post(urlPathEqualTo("/")).willReturn(aResponse().withStatus(400)))

        assertThatThrownBy { client().findNearbyWays(31.5, 74.3) }
            .isInstanceOf(OsmLookupException::class.java)

        assertThat(wireMockServer.allServeEvents).hasSize(1)
    }

    @Test
    fun `findNearbyWays throws after exhausting all retry attempts`() {
        wireMockServer.stubFor(post(urlPathEqualTo("/")).willReturn(aResponse().withStatus(503)))

        assertThatThrownBy { client(retryAttempts = 3).findNearbyWays(31.5, 74.3) }
            .isInstanceOf(OsmLookupException::class.java)

        assertThat(wireMockServer.allServeEvents).hasSize(3)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run (from `server/`): `./gradlew.bat test --tests "com.trafficwatch.server.geo.OverpassClientTest"`
Expected: FAIL to compile - `OsmProperties(lookupRetryAttempts = ..., lookupRetryDelayMs = ...)` doesn't exist yet (no such constructor parameters).

- [ ] **Step 3: Add retry config to `OsmProperties`**

In `server/src/main/kotlin/com/trafficwatch/server/geo/OsmProperties.kt`, replace:

```kotlin
data class OsmProperties(
    var nominatimBaseUrl: String = "https://nominatim.openstreetmap.org",
    var overpassBaseUrl: String = "https://overpass-api.de/api/interpreter",
    var userAgent: String = "TrafficWatch-Server/1.0 (set a real contact in your environment)",
    var connectTimeoutMs: Int = 5000,
    var readTimeoutMs: Int = 8000,
    var searchRadiusMeters: Int = 50,
)
```

with:

```kotlin
data class OsmProperties(
    var nominatimBaseUrl: String = "https://nominatim.openstreetmap.org",
    var overpassBaseUrl: String = "https://overpass-api.de/api/interpreter",
    var userAgent: String = "TrafficWatch-Server/1.0 (set a real contact in your environment)",
    var connectTimeoutMs: Int = 5000,
    var readTimeoutMs: Int = 8000,
    var searchRadiusMeters: Int = 50,
    // A transient failure (network error, or a 5xx from the upstream API) is retried this
    // many times total before giving up - a 4xx is never retried, see withOsmRetry.
    var lookupRetryAttempts: Int = 3,
    var lookupRetryDelayMs: Long = 500,
)
```

- [ ] **Step 4: Add `isRetryable` to `OsmLookupException`**

In `server/src/main/kotlin/com/trafficwatch/server/geo/OsmLookupException.kt`, replace:

```kotlin
/**
 * Thrown by [NominatimClient]/[OverpassClient] on any HTTP/network/parsing failure, so
 * [StreetDirectionResolver] is the single place that decides fallback behavior
 * ([DirectionResolution.LookupFailed]) instead of each client swallowing errors differently.
 */
class OsmLookupException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
```

with:

```kotlin
/**
 * Thrown by [NominatimClient]/[OverpassClient] on any HTTP/network/parsing failure, so
 * [StreetDirectionResolver] is the single place that decides fallback behavior
 * ([DirectionResolution.LookupFailed]) instead of each client swallowing errors differently.
 *
 * [isRetryable] distinguishes a transient failure (no HTTP response at all, or a 5xx) - worth
 * retrying via [withOsmRetry] - from a 4xx, which indicates a malformed request and would
 * never succeed on retry.
 */
class OsmLookupException(
    message: String,
    cause: Throwable? = null,
    val isRetryable: Boolean = true,
) : RuntimeException(message, cause)
```

- [ ] **Step 5: Create the shared retry helper**

Create `server/src/main/kotlin/com/trafficwatch/server/geo/OsmRetry.kt`:

```kotlin
package com.trafficwatch.server.geo

/**
 * Retries [call] on a retryable [OsmLookupException] (see its doc comment), up to
 * [OsmProperties.lookupRetryAttempts] total attempts with a fixed
 * [OsmProperties.lookupRetryDelayMs] delay between them. A non-retryable exception (a 4xx -
 * a malformed request that will never succeed) is re-thrown immediately, on the first
 * attempt. Used identically by [OverpassClient] and [NominatimClient] to avoid duplicating
 * this loop in both.
 */
fun <T> withOsmRetry(properties: OsmProperties, call: () -> T): T {
    var lastException: OsmLookupException? = null
    repeat(properties.lookupRetryAttempts) { attempt ->
        try {
            return call()
        } catch (ex: OsmLookupException) {
            lastException = ex
            if (!ex.isRetryable || attempt == properties.lookupRetryAttempts - 1) throw ex
            Thread.sleep(properties.lookupRetryDelayMs)
        }
    }
    throw requireNotNull(lastException)
}
```

- [ ] **Step 6: Wire the retry helper into `OverpassClient`**

In `server/src/main/kotlin/com/trafficwatch/server/geo/OverpassClient.kt`, replace:

```kotlin
    /** Ways with a `highway` tag within [OsmProperties.searchRadiusMeters] of [lat]/[lon]. */
    fun findNearbyWays(lat: Double, lon: Double): List<OverpassElement> {
        val query = """
            [out:json];
            way(around:${osmProperties.searchRadiusMeters},$lat,$lon)["highway"];
            out geom;
        """.trimIndent()

        val formBody = LinkedMultiValueMap<String, String>().apply {
            add("data", query)
        }

        try {
            val response = restClient.post()
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(formBody)
                .retrieve()
                .body<OverpassResponse>()
                ?: throw OsmLookupException("Overpass lookup returned an empty body")
            return response.elements
        } catch (ex: RestClientResponseException) {
            throw OsmLookupException("Overpass lookup failed with HTTP ${ex.statusCode}", ex)
        } catch (ex: RestClientException) {
            throw OsmLookupException("Overpass lookup failed", ex)
        }
    }
```

with:

```kotlin
    /** Ways with a `highway` tag within [OsmProperties.searchRadiusMeters] of [lat]/[lon]. */
    fun findNearbyWays(lat: Double, lon: Double): List<OverpassElement> = withOsmRetry(osmProperties) {
        val query = """
            [out:json];
            way(around:${osmProperties.searchRadiusMeters},$lat,$lon)["highway"];
            out geom;
        """.trimIndent()

        val formBody = LinkedMultiValueMap<String, String>().apply {
            add("data", query)
        }

        try {
            val response = restClient.post()
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(formBody)
                .retrieve()
                .body<OverpassResponse>()
                ?: throw OsmLookupException("Overpass lookup returned an empty body")
            response.elements
        } catch (ex: RestClientResponseException) {
            throw OsmLookupException(
                "Overpass lookup failed with HTTP ${ex.statusCode}",
                ex,
                isRetryable = ex.statusCode.is5xxServerError(),
            )
        } catch (ex: RestClientException) {
            throw OsmLookupException("Overpass lookup failed", ex)
        }
    }
```

- [ ] **Step 7: Run the test to verify it passes**

Run (from `server/`): `./gradlew.bat test --tests "com.trafficwatch.server.geo.OverpassClientTest"`
Expected: PASS (3 tests).

- [ ] **Step 8: Write the failing test for `NominatimClient`, then wire it up the same way**

Create `server/src/test/kotlin/com/trafficwatch/server/geo/NominatimClientTest.kt`:

```kotlin
package com.trafficwatch.server.geo

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.github.tomakehurst.wiremock.stubbing.Scenario
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClient

class NominatimClientTest {

    private val wireMockServer = WireMockServer(WireMockConfiguration.options().dynamicPort())

    @BeforeEach
    fun startWireMock() {
        wireMockServer.start()
    }

    @AfterEach
    fun stopWireMock() {
        wireMockServer.stop()
    }

    private fun client(retryAttempts: Int = 3, retryDelayMs: Long = 1L): NominatimClient {
        val restClient = RestClient.builder().baseUrl("http://localhost:${wireMockServer.port()}").build()
        val properties = OsmProperties(lookupRetryAttempts = retryAttempts, lookupRetryDelayMs = retryDelayMs)
        return NominatimClient(restClient, properties)
    }

    @Test
    fun `reverseGeocode retries once on a connection-level failure then returns the successful result`() {
        wireMockServer.stubFor(
            get(urlPathEqualTo("/reverse"))
                .inScenario("retry")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(502))
                .willSetStateTo("second attempt"),
        )
        wireMockServer.stubFor(
            get(urlPathEqualTo("/reverse"))
                .inScenario("retry")
                .whenScenarioStateIs("second attempt")
                .willReturn(okJson("""{"address": {"road": "Test Road"}}""")),
        )

        val result = client().reverseGeocode(31.5, 74.3)

        assertThat(result.address?.road).isEqualTo("Test Road")
        assertThat(wireMockServer.allServeEvents).hasSize(2)
    }

    @Test
    fun `reverseGeocode does not retry a 404 and fails immediately`() {
        wireMockServer.stubFor(get(urlPathEqualTo("/reverse")).willReturn(aResponse().withStatus(404)))

        assertThatThrownBy { client().reverseGeocode(31.5, 74.3) }
            .isInstanceOf(OsmLookupException::class.java)

        assertThat(wireMockServer.allServeEvents).hasSize(1)
    }
}
```

Note: `NominatimClient`'s constructor currently takes only `restClient`. This step also updates
the constructor to accept `osmProperties: OsmProperties`, matching `OverpassClient`'s shape -
Spring autowires the existing `OsmProperties` `@Component` bean automatically, no other wiring
changes needed.

In `server/src/main/kotlin/com/trafficwatch/server/geo/NominatimClient.kt`, replace the entire file with:

```kotlin
package com.trafficwatch.server.geo

import com.trafficwatch.server.geo.dto.NominatimReverseResponse
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.client.body
import org.springframework.web.client.RestClient

/** Thin wrapper around Nominatim's reverse-geocoding endpoint. */
@Component
class NominatimClient(
    @Qualifier("nominatimRestClient") private val restClient: RestClient,
    private val osmProperties: OsmProperties,
) {

    /** Reverse-geocodes [lat]/[lon] to an address (used only for its `road` field). */
    fun reverseGeocode(lat: Double, lon: Double): NominatimReverseResponse = withOsmRetry(osmProperties) {
        try {
            restClient.get()
                .uri { builder ->
                    builder.path("/reverse")
                        .queryParam("format", "jsonv2")
                        .queryParam("lat", lat)
                        .queryParam("lon", lon)
                        .build()
                }
                .retrieve()
                .body<NominatimReverseResponse>()
                ?: throw OsmLookupException("Nominatim reverse geocode returned an empty body")
        } catch (ex: RestClientResponseException) {
            throw OsmLookupException(
                "Nominatim reverse geocode failed with HTTP ${ex.statusCode}",
                ex,
                isRetryable = ex.statusCode.is5xxServerError(),
            )
        } catch (ex: RestClientException) {
            throw OsmLookupException("Nominatim reverse geocode failed", ex)
        }
    }
}
```

- [ ] **Step 9: Run both tests to verify they pass**

Run (from `server/`): `./gradlew.bat test --tests "com.trafficwatch.server.geo.OverpassClientTest" --tests "com.trafficwatch.server.geo.NominatimClientTest"`
Expected: PASS (5 tests total: 3 + 2).

- [ ] **Step 10: Run the full server test suite to confirm no regressions**

Run (from `server/`): `./gradlew.bat test`
Expected: all tests pass, aside from the pre-existing, already-known network-dependent flake in
`EndToEndFlowTest`'s `register, login, submit, poll...` test (makes a real, unstubbed call to
public Nominatim/Overpass - unrelated to this change). If anything else fails, investigate
before continuing.

- [ ] **Step 11: Add the new config to `application.yml`**

In `server/src/main/resources/application.yml`, replace:

```yaml
  osm:
    nominatim-base-url: "https://nominatim.openstreetmap.org"
    overpass-base-url: "https://overpass-api.de/api/interpreter"
    # OSM's usage policy requires an identifying User-Agent - set a real contact before
    # sending any production traffic.
    user-agent: "TrafficWatch-Server/1.0 (set a real contact in your environment)"
    connect-timeout-ms: 5000
    read-timeout-ms: 8000
    search-radius-meters: 50
```

with:

```yaml
  osm:
    nominatim-base-url: "https://nominatim.openstreetmap.org"
    overpass-base-url: "https://overpass-api.de/api/interpreter"
    # OSM's usage policy requires an identifying User-Agent - set a real contact before
    # sending any production traffic.
    user-agent: "TrafficWatch-Server/1.0 (set a real contact in your environment)"
    connect-timeout-ms: 5000
    read-timeout-ms: 8000
    search-radius-meters: 50
    lookup-retry-attempts: 3
    lookup-retry-delay-ms: 500
```

- [ ] **Step 12: Commit**

```bash
git add server/src/main/kotlin/com/trafficwatch/server/geo/OsmLookupException.kt server/src/main/kotlin/com/trafficwatch/server/geo/OsmRetry.kt server/src/main/kotlin/com/trafficwatch/server/geo/OsmProperties.kt server/src/main/kotlin/com/trafficwatch/server/geo/OverpassClient.kt server/src/main/kotlin/com/trafficwatch/server/geo/NominatimClient.kt server/src/main/resources/application.yml server/src/test/kotlin/com/trafficwatch/server/geo/OverpassClientTest.kt server/src/test/kotlin/com/trafficwatch/server/geo/NominatimClientTest.kt
git commit -m "fix(server): retry Overpass/Nominatim lookups on transient failure"
```

---

### Task 2: Let peer-supported OSM evidence reach a contested corridor's candidates

**Files:**
- Modify: `server/src/main/kotlin/com/trafficwatch/server/geo/ClipFlowAnalyzer.kt`
- Modify: `server/src/main/kotlin/com/trafficwatch/server/reports/ReportAnalysisJob.kt`
- Test: `server/src/test/kotlin/com/trafficwatch/server/geo/ClipFlowAnalyzerTest.kt`
- Test: `server/src/test/kotlin/com/trafficwatch/server/reports/ReportAnalysisJobTest.kt`

**Interfaces:**
- Produces: `ClipFlowAnalyzer.hasPeerSupport(flowVehicles: List<FlowVehicle>, candidate: FlowVehicle): Boolean`.
- Consumes (from `ReportAnalysisJob`): the existing `FlowVehicle`, `CorridorConsensus?`, and `AnalysisProperties.agreementToleranceDegrees` (no new config needed - reuses the existing 45-degree default already used by `movesWith()`).

- [ ] **Step 1: Write the failing tests for `hasPeerSupport`**

In `server/src/test/kotlin/com/trafficwatch/server/geo/ClipFlowAnalyzerTest.kt`, add these three tests at the end of the class, right before the closing brace:

```kotlin
    @Test
    fun `hasPeerSupport is true when a corridor peer is within agreement tolerance`() {
        // Mirrors the real report this fix was diagnosed from: candidate at 257.7, one peer
        // at 262.9 (5.2 degrees away, well within the default 45-degree tolerance).
        val flow = analyzer.qualifyVehicles(
            listOf(vehicle(1, 257.7), vehicle(2, 262.9)), 0.0, 1920, 1080
        )
        val candidate = flow.first { it.vehicle.trackId == 1L }
        assertTrue(analyzer.hasPeerSupport(flow, candidate))
    }

    @Test
    fun `hasPeerSupport is false when every corridor peer is beyond agreement tolerance`() {
        // Mirrors the existing "contested corridor, never falsely confirmed" scenario: three
        // vehicles pairwise 120 degrees apart, no pair within the 45-degree tolerance.
        val flow = analyzer.qualifyVehicles(
            listOf(vehicle(1, 270.0), vehicle(2, 30.0), vehicle(3, 150.0)), 0.0, 1920, 1080
        )
        val candidate = flow.first { it.vehicle.trackId == 1L }
        assertFalse(analyzer.hasPeerSupport(flow, candidate))
    }

    @Test
    fun `hasPeerSupport is false for a candidate alone in its corridor`() {
        val flow = analyzer.qualifyVehicles(listOf(vehicle(1, 90.0)), 0.0, 1920, 1080)
        val candidate = flow.first { it.vehicle.trackId == 1L }
        assertFalse(analyzer.hasPeerSupport(flow, candidate))
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run (from `server/`): `./gradlew.bat test --tests "com.trafficwatch.server.geo.ClipFlowAnalyzerTest"`
Expected: FAIL to compile - `hasPeerSupport` doesn't exist on `ClipFlowAnalyzer` yet.

- [ ] **Step 3: Add `hasPeerSupport` to `ClipFlowAnalyzer`**

In `server/src/main/kotlin/com/trafficwatch/server/geo/ClipFlowAnalyzer.kt`, add this function right after `movesWith(...)`, before the class's closing brace:

```kotlin
    /**
     * True when at least one OTHER member of [candidate]'s corridor has a bearing within
     * agreement tolerance of [candidate]'s own - i.e. the candidate's specific direction is
     * corroborated by a real peer, not a lone coincidental bearing in an otherwise scattered
     * corridor. Used when the corridor's overall consensus is unavailable (bimodal/dispersed)
     * to decide whether independent evidence (OSM tag, learned history) is still safe to
     * trust for this specific candidate.
     */
    fun hasPeerSupport(flowVehicles: List<FlowVehicle>, candidate: FlowVehicle): Boolean =
        flowVehicles.any {
            it.corridorId == candidate.corridorId &&
                it !== candidate &&
                BearingMath.angularDifferenceDegrees(it.absoluteBearingDegrees, candidate.absoluteBearingDegrees) <=
                    properties.agreementToleranceDegrees
        }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run (from `server/`): `./gradlew.bat test --tests "com.trafficwatch.server.geo.ClipFlowAnalyzerTest"`
Expected: PASS (12 tests: the 9 existing plus the 3 new ones).

- [ ] **Step 5: Commit the `ClipFlowAnalyzer` change**

```bash
git add server/src/main/kotlin/com/trafficwatch/server/geo/ClipFlowAnalyzer.kt server/src/test/kotlin/com/trafficwatch/server/geo/ClipFlowAnalyzerTest.kt
git commit -m "feat(server): add ClipFlowAnalyzer.hasPeerSupport"
```

- [ ] **Step 6: Write the failing tests for `ReportAnalysisJob`'s corrected gate**

In `server/src/test/kotlin/com/trafficwatch/server/reports/ReportAnalysisJobTest.kt`, add these
two tests right after the existing `candidate in a contested (bimodal) corridor is skipped,
never falsely confirmed` test (do not modify that existing test - it must keep passing
unchanged as the regression guard for the "no peer support" case):

```kotlin
    @Test
    fun `candidate in a contested corridor WITH peer support is evaluated against OSM and can be confirmed`() {
        val report = sampleReport(compassHeadingDegrees = BigDecimal.ZERO)
        every {
            streetDirectionResolver.resolve(report.latitude, report.longitude)
        } returns DirectionResolution.OneWay("Khayaban-e-Jinnah", 90.0)
        // Mirrors the real report this fix was diagnosed from: two vehicles flowing with the
        // legal bearing (88, 92), one candidate moving dead against it (270, exactly the
        // illegal bearing) with one real peer nearby (250, 20 degrees away, within the
        // 45-degree tolerance) - all in the same corridor, so the corridor's OVERALL
        // consensus (excluding the candidate: 88/92/250) is bimodal/dispersed
        // (R ~= 0.37, below the 0.6 gate), but the candidate's OWN specific direction is
        // corroborated by track 4, not a lone coincidence.
        // Score check: candidate (track 3) angularDistance from illegal = 0 ->
        // bearingMatchScore 1.0 -> finalScore = 1.0(fusion) * 1.0(quality) * 0.9(detection)
        // * 1.0 = 0.9. The peer (track 4) is also evaluated independently (angularDistance
        // 20 -> bearingMatchScore 0.667 -> finalScore 0.6) but scores lower and has no
        // plate, so the winner is unambiguous.
        every {
            videoAnalysisClient.analyze(fakeVideoPath, any())
        } returns analysisResponse(
            listOf(
                vehicle(trackId = 1, bearingDegrees = 88.0, detectionConfidence = 0.9),
                vehicle(trackId = 2, bearingDegrees = 92.0, detectionConfidence = 0.9),
                vehicle(
                    trackId = 3,
                    bearingDegrees = 270.0,
                    detectionConfidence = 0.9,
                    plateText = "LEA-1234",
                    plateConfidence = 0.8,
                ),
                vehicle(trackId = 4, bearingDegrees = 250.0, detectionConfidence = 0.9, plateText = null, plateConfidence = null),
            ),
        )
        every { reportRepository.save(any()) } answers { firstArg() }

        job.applyOutcome(report)

        assertThat(report.status).isEqualTo(ReportStatus.CONFIRMED)
        assertThat(report.licensePlate).isEqualTo("LEA-1234")
    }

    @Test
    fun `candidate in a contested corridor WITH peer support but no OSM or history evidence still lands on insufficient`() {
        val report = sampleReport(compassHeadingDegrees = BigDecimal.ZERO)
        every {
            streetDirectionResolver.resolve(report.latitude, report.longitude)
        } returns DirectionResolution.Unknown(null)
        // Same peer-support shape as the test above, but no OSM tag (Unknown, not OneWay) and
        // no learned history (stubVideoResolution already defaults historyEvidence to null) -
        // peer support alone must never BE evidence, only a gate on whether to attempt fusion.
        every {
            videoAnalysisClient.analyze(fakeVideoPath, any())
        } returns analysisResponse(
            listOf(
                vehicle(trackId = 1, bearingDegrees = 88.0, detectionConfidence = 0.9),
                vehicle(trackId = 2, bearingDegrees = 92.0, detectionConfidence = 0.9),
                vehicle(trackId = 3, bearingDegrees = 270.0, detectionConfidence = 0.9),
                vehicle(trackId = 4, bearingDegrees = 250.0, detectionConfidence = 0.9),
            ),
        )
        every { reportRepository.save(any()) } answers { firstArg() }

        job.applyOutcome(report)

        assertThat(report.status).isEqualTo(ReportStatus.REJECTED)
        assertThat(report.status).isNotEqualTo(ReportStatus.CONFIRMED)
    }
```

- [ ] **Step 7: Run the tests to verify they fail**

Run (from `server/`): `./gradlew.bat test --tests "com.trafficwatch.server.reports.ReportAnalysisJobTest"`
Expected: the two new tests FAIL (the first asserts CONFIRMED but today's code still skips
the candidate and produces REJECTED; the existing tests, including the "never falsely
confirmed" one, still PASS unchanged).

- [ ] **Step 8: Replace the blanket skip with the peer-support gate**

In `server/src/main/kotlin/com/trafficwatch/server/reports/ReportAnalysisJob.kt`, replace:

```kotlin
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

            // "Alone" must mean literally alone. A null consensus can mean either the
            // corridor genuinely has no other qualified member (the quiet-street case,
            // where OSM/history evidence alone may still score the candidate) or the
            // corridor HAS other members but their bearings are bimodal/dispersed, so the
            // R-gate refused to elect one (a divided road merged into one corridor). Gate 1
            // can't protect a legally-flowing far-side vehicle in the second case, so a
            // contested corridor must never elect a violator either - skip it outright.
            val hasOtherCorridorMembers = flowVehicles.any { it.corridorId == candidate.corridorId && it !== candidate }
            if (consensus == null && hasOtherCorridorMembers) {
                continue
            }

            if (consensus != null && clipFlowAnalyzer.movesWith(candidate, consensus)) {
                continue // gate 1: flows with its own corridor - never a violator
            }
```

with:

```kotlin
    /**
     * Evaluates every qualified vehicle as a potential violator. Per spec:
     * a candidate moving WITH its own corridor's consensus is never a violator
     * (legal opposing stream on a divided road); a violator moves against its
     * corridor's consensus, or against the fused legal bearing when alone or
     * when its corridor's overall consensus is unavailable but its own direction has real
     * peer support. Fusion is per-candidate because the clip-consensus source is the
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

            // A null consensus can mean either the corridor genuinely has no other
            // qualified member (the quiet-street case, where OSM/history evidence alone may
            // still score the candidate), or the corridor HAS other members but their
            // bearings are bimodal/dispersed, so the R-gate refused to elect one (a divided
            // road merged into one corridor, or a one-way street with both normal traffic
            // and a violator in frame). In the second case, only skip the candidate if its
            // OWN specific direction has no real peer support - a lone bearing that happens
            // to coincide with the illegal direction by coincidence must never be trusted on
            // OSM/history evidence alone, but a direction corroborated by another observed
            // vehicle is not a coincidence and independent evidence may still apply.
            if (consensus == null && !clipFlowAnalyzer.hasPeerSupport(flowVehicles, candidate)) {
                continue
            }

            if (consensus != null && clipFlowAnalyzer.movesWith(candidate, consensus)) {
                continue // gate 1: flows with its own corridor - never a violator
            }
```

- [ ] **Step 9: Run the tests to verify they pass**

Run (from `server/`): `./gradlew.bat test --tests "com.trafficwatch.server.reports.ReportAnalysisJobTest"`
Expected: PASS (all tests, including both new ones and the unmodified existing
"never falsely confirmed" test).

- [ ] **Step 10: Run the full server test suite to confirm no regressions**

Run (from `server/`): `./gradlew.bat test`
Expected: all tests pass, aside from the same pre-existing network-dependent
`EndToEndFlowTest` flake noted in Task 1 - unrelated to this change.

- [ ] **Step 11: Commit**

```bash
git add server/src/main/kotlin/com/trafficwatch/server/reports/ReportAnalysisJob.kt server/src/test/kotlin/com/trafficwatch/server/reports/ReportAnalysisJobTest.kt
git commit -m "fix(server): let peer-supported OSM evidence reach contested-corridor candidates"
```

- [ ] **Step 12: Deploy to production and manually verify**

```bash
scp -i ~/.ssh/trafficwatch_ovh server/src/main/kotlin/com/trafficwatch/server/geo/OsmLookupException.kt server/src/main/kotlin/com/trafficwatch/server/geo/OsmRetry.kt server/src/main/kotlin/com/trafficwatch/server/geo/OsmProperties.kt server/src/main/kotlin/com/trafficwatch/server/geo/OverpassClient.kt server/src/main/kotlin/com/trafficwatch/server/geo/NominatimClient.kt server/src/main/kotlin/com/trafficwatch/server/geo/ClipFlowAnalyzer.kt ubuntu@137.74.173.97:~/trafficwatch/server/src/main/kotlin/com/trafficwatch/server/geo/
scp -i ~/.ssh/trafficwatch_ovh server/src/main/kotlin/com/trafficwatch/server/reports/ReportAnalysisJob.kt ubuntu@137.74.173.97:~/trafficwatch/server/src/main/kotlin/com/trafficwatch/server/reports/
scp -i ~/.ssh/trafficwatch_ovh server/src/main/resources/application.yml ubuntu@137.74.173.97:~/trafficwatch/server/src/main/resources/
ssh -i ~/.ssh/trafficwatch_ovh ubuntu@137.74.173.97 "cd ~/trafficwatch && docker compose -f docker-compose.prod.yml up -d --build server"
```

Resubmit report `9fd4fea9`'s clip (or an equivalent one-way-street clip with mixed
correct/wrong-way traffic in one corridor) through the full production pipeline and confirm
it now reaches a real verdict - `CONFIRMED` or a specific rejection based on the video content
- instead of "Legal traffic direction could not be established for this street". Also confirm
via `docker logs trafficwatch-server` that no unexpected retry-loop errors appear during
normal operation.
