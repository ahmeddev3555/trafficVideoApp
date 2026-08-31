# Divided-Carriageway Resolution + Stationary-Approach on `Unknown` — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make a divided one-way carriageway resolve deterministically to `Unknown` with a recorded reason, and let the shipped stationary-approach detector confirm a wrong-way approach on such a road — without opening the common "untagged street" `Unknown` to false positives.

**Architecture:** Part A queries multiple Overpass mirrors sequentially and unions the ways, so one stale replica can't produce a wrong `OneWay`; a single un-cross-checked source can no longer assert `OneWay`. Part B tags every `DirectionResolution.Unknown` with a reason, persists it, and widens `ReportAnalysisJob`'s approach-path gate from `is OneWay` to also accept `Unknown(DIVIDED_CARRIAGEWAY)` when the clip's own qualified traffic forms one coherent directional stream.

**Tech Stack:** Kotlin, Spring Boot, Spring `RestClient`, JPA/Hibernate, Flyway, JUnit 5, MockK, WireMock, AssertJ.

**Spec:** `docs/superpowers/specs/2026-08-31-divided-carriageway-resolution-and-approach-on-unknown-design.md`

## Global Constraints

- **False-positive-averse.** A missed detection beats a false one. Every ambiguous OSM state resolves toward `Unknown`, never toward a confident direction.
- **No retroactive re-analysis.** All changes apply to future analysis and TTL-triggered re-resolution only.
- **Sequential Overpass queries** — analysis is async; do not parallelise.
- **`DirectionResolution.Unknown`'s new `reason` parameter MUST have a default value** (`UnknownReason.NO_ONEWAY_TAG`) so existing construction sites and tests compile unchanged.
- **Config keys are kebab-case** under `app.osm.*` / `app.analysis.*` in `application.yml`, camelCase in the `@ConfigurationProperties` data classes.
- **Exact new identifiers** (use verbatim): `OverpassResult`, `sourceCount`, `overpassBaseUrls`, `overpassPerEndpointAttempts`, `cacheTtlDays`, `UnknownReason` with values `NO_ONEWAY_TAG` / `AMBIGUOUS_NEAREST_STREET` / `DIVIDED_CARRIAGEWAY` / `NOT_CROSS_CHECKED`, `approachCorroborationMinMembers`, evidence fields `resolutionState` / `corroborationConsensusMembers`, migration `V11__add_unknown_reason_and_reset_osm_cache.sql`.
- **`./gradlew test` green** at the end of every task (run from `server/`).
- **The VPS `application-local.yml` / `.env` are not in git.** Any deployment override of `app.osm.overpass-base-url` must be migrated to the list key — call this out in the deploy notes (Task 6), do not attempt to edit those files here.

---

## File Structure

| File | Responsibility | Task |
|---|---|---|
| `server/src/main/kotlin/com/trafficwatch/server/geo/OsmProperties.kt` | add `overpassBaseUrls: List<String>` (replaces `overpassBaseUrl`), `overpassPerEndpointAttempts: Int`, `cacheTtlDays: Long` | 1, 2 |
| `server/src/main/kotlin/com/trafficwatch/server/geo/dto/OverpassDtos.kt` | add `OverpassResult(ways, sourceCount)` | 1 |
| `server/src/main/kotlin/com/trafficwatch/server/geo/OsmClientConfig.kt` | overpass `RestClient` bean loses its base URL (called with absolute URIs) | 1 |
| `server/src/main/kotlin/com/trafficwatch/server/geo/OverpassClient.kt` | iterate mirrors, union ways deduped by id, `sourceCount`, per-endpoint logging | 1 |
| `server/src/main/kotlin/com/trafficwatch/server/geo/StreetDirectionResolver.kt` | consume `OverpassResult`; single-source `OneWay`→`Unknown`; `Clock` + cache TTL; set `UnknownReason` at every `Unknown` return | 1, 2, 3 |
| `server/src/main/kotlin/com/trafficwatch/server/geo/DirectionResolution.kt` | `enum UnknownReason`; `Unknown` gains `reason` (defaulted) | 3 |
| `server/src/main/kotlin/com/trafficwatch/server/config/TimeConfig.kt` | **new** — `@Bean fun clock(): Clock` | 2 |
| `server/src/main/kotlin/com/trafficwatch/server/geo/OsmLookupCache.kt` | `unknownReason` column + enum mapping | 4 |
| `server/src/main/resources/db/migration/V11__add_unknown_reason_and_reset_osm_cache.sql` | **new** — add column + CHECK, `TRUNCATE osm_lookup_cache` | 4 |
| `server/src/main/kotlin/com/trafficwatch/server/reports/AnalysisProperties.kt` | add `approachCorroborationMinMembers: Int = 2` | 5 |
| `server/src/main/kotlin/com/trafficwatch/server/reports/ReportAnalysisJob.kt` | widened approach gate; `strongestFlowConsensus` helper; `ApproachEvidenceBreakdown` + `tryStationaryApproachDetection` take `resolution` / consensus member count | 5 |
| `server/src/main/resources/application.yml` | `app.osm.overpass-base-urls`, `overpass-per-endpoint-attempts`, `cache-ttl-days`; `app.analysis.approach-corroboration-min-members` | 1, 2, 5 |
| `server/src/test/kotlin/com/trafficwatch/server/geo/OverpassClientTest.kt` | rewrite for multi-endpoint (2–3 WireMock servers) | 1 |
| `server/src/test/kotlin/com/trafficwatch/server/geo/StreetDirectionResolverTest.kt` | two overpass WireMocks + `stubOverpass` / `overpassResolveRounds` helpers; new downgrade / TTL / reason tests; adjustable `Clock` | 1, 2, 3 |
| `server/src/test/resources/fixtures/overpass-khayaban-e-jinnah-one-carriageway.json` | **new** — the 649b9a fixture minus the second carriageway | 1 |
| `server/src/test/kotlin/com/trafficwatch/server/reports/ReportAnalysisJobTest.kt` | new approach-on-`Unknown` tests; update the existing OneWay approach + `Unknown` tests | 5 |
| `server/src/test/kotlin/com/trafficwatch/server/reports/ReportAnalysisIntegrationTest.kt` | rescale any hardcoded values that move | 5 |
| `docs/improvements-backlog.md` | mark the divided-carriageway + `[HIGH]` approach items addressed | 6 |

---

## Task 1: Multi-source `OverpassClient`

**Files:**
- Modify: `server/src/main/kotlin/com/trafficwatch/server/geo/OsmProperties.kt`
- Modify: `server/src/main/kotlin/com/trafficwatch/server/geo/dto/OverpassDtos.kt`
- Modify: `server/src/main/kotlin/com/trafficwatch/server/geo/OsmClientConfig.kt`
- Modify: `server/src/main/kotlin/com/trafficwatch/server/geo/OverpassClient.kt`
- Modify: `server/src/main/kotlin/com/trafficwatch/server/geo/StreetDirectionResolver.kt` (mechanical: `.ways` only)
- Modify: `server/src/main/resources/application.yml`
- Test: `server/src/test/kotlin/com/trafficwatch/server/geo/OverpassClientTest.kt` (rewrite)
- Test: `server/src/test/kotlin/com/trafficwatch/server/geo/StreetDirectionResolverTest.kt` (wiring + helpers only, no new behavior)
- Create: `server/src/test/resources/fixtures/overpass-khayaban-e-jinnah-one-carriageway.json`

**Interfaces:**
- Produces: `OverpassClient.findNearbyWays(lat: Double, lon: Double, radiusMeters: Double): OverpassResult`
- Produces: `data class OverpassResult(val ways: List<OverpassElement>, val sourceCount: Int)`
- Produces: `OsmProperties.overpassBaseUrls: List<String>`, `OsmProperties.overpassPerEndpointAttempts: Int` (default `1`)
- Consumes: existing `withOsmRetry(properties, call)`, `OverpassResponse`, `OverpassElement`, `OsmLookupException`

- [ ] **Step 1: `OverpassResult` DTO**

In `OverpassDtos.kt`, append:

```kotlin
/**
 * Result of an [com.trafficwatch.server.geo.OverpassClient] query. [ways] is the union of
 * every mirror that answered, deduped by way id; [sourceCount] is how many configured
 * endpoints returned a usable HTTP response (an empty `elements` body still counts).
 */
data class OverpassResult(
    val ways: List<OverpassElement>,
    val sourceCount: Int,
)
```

- [ ] **Step 2: `OsmProperties` — mirror list + per-endpoint attempts**

Replace `var overpassBaseUrl: String = "https://overpass-api.de/api/interpreter",` with:

```kotlin
    var overpassBaseUrls: List<String> = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
    ),
    // Per-endpoint attempts inside OverpassClient's mirror loop. Cross-endpoint redundancy
    // replaces most same-endpoint retrying, so this is 1 by default (lookupRetryAttempts
    // still governs NominatimClient).
    var overpassPerEndpointAttempts: Int = 1,
```

- [ ] **Step 3: `OsmClientConfig` — base-URL-less overpass client**

Change `overpassRestClient()` so the returned `RestClient` has **no** `baseUrl`. Extract the shared builder config (Jackson converter, `SimpleClientHttpRequestFactory` timeouts, `User-Agent` header) into a helper that both beans use; `nominatimRestClient()` keeps `.baseUrl(osmProperties.nominatimBaseUrl)`, `overpassRestClient()` omits it:

```kotlin
    @Bean("overpassRestClient")
    fun overpassRestClient(): RestClient = baseClientBuilder().build()

    @Bean("nominatimRestClient")
    fun nominatimRestClient(): RestClient = baseClientBuilder().baseUrl(osmProperties.nominatimBaseUrl).build()

    private fun baseClientBuilder(): RestClient.Builder {
        val objectMapper = ObjectMapper()
            .registerKotlinModule()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        val converter = MappingJackson2HttpMessageConverter(objectMapper)
        val requestFactory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(osmProperties.connectTimeoutMs)
            setReadTimeout(osmProperties.readTimeoutMs)
        }
        return RestClient.builder()
            .defaultHeader(HttpHeaders.USER_AGENT, osmProperties.userAgent)
            .requestFactory(requestFactory)
            .messageConverters { converters ->
                converters.removeIf { it is MappingJackson2HttpMessageConverter }
                converters.add(0, converter)
            }
    }
```

- [ ] **Step 4: rewrite `OverpassClient`**

```kotlin
package com.trafficwatch.server.geo

import com.trafficwatch.server.geo.dto.OverpassElement
import com.trafficwatch.server.geo.dto.OverpassResponse
import com.trafficwatch.server.geo.dto.OverpassResult
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.client.body
import java.net.URI

/**
 * Queries every configured Overpass mirror in sequence and unions the ways, so one stale
 * replica of the public API cannot determine a resolution. Ways with a `highway` tag within
 * a radius; deduped by way id (keeping the richer geometry on a rare id collision).
 */
@Component
class OverpassClient(
    @Qualifier("overpassRestClient") private val restClient: RestClient,
    private val osmProperties: OsmProperties,
) {
    private val logger = LoggerFactory.getLogger(OverpassClient::class.java)

    fun findNearbyWays(lat: Double, lon: Double, radiusMeters: Double): OverpassResult {
        val query = """
            [out:json];
            way(around:$radiusMeters,$lat,$lon)["highway"];
            out geom;
        """.trimIndent()
        val formBody = LinkedMultiValueMap<String, String>().apply { add("data", query) }

        val byId = LinkedHashMap<Long, OverpassElement>()
        var sourceCount = 0

        for (url in osmProperties.overpassBaseUrls) {
            val host = runCatching { URI(url).host }.getOrNull() ?: url
            val elements = try {
                queryEndpoint(url, formBody)
            } catch (ex: OsmLookupException) {
                logger.warn("Overpass endpoint {} failed, skipping: {}", host, ex.message)
                continue
            }
            sourceCount++
            logger.info(
                "Overpass endpoint {} returned {} ways: {}",
                host, elements.size, elements.mapNotNull { it.id },
            )
            for (el in elements) {
                val id = el.id ?: continue
                val existing = byId[id]
                if (existing == null || (el.geometry?.size ?: 0) > (existing.geometry?.size ?: 0)) {
                    byId[id] = el
                }
            }
        }

        if (sourceCount == 0) {
            throw OsmLookupException("Every Overpass endpoint failed", isRetryable = true)
        }
        return OverpassResult(ways = byId.values.toList(), sourceCount = sourceCount)
    }

    private fun queryEndpoint(url: String, formBody: LinkedMultiValueMap<String, String>): List<OverpassElement> {
        val perEndpointProps = osmProperties.copy(
            lookupRetryAttempts = osmProperties.overpassPerEndpointAttempts,
        )
        return withOsmRetry(perEndpointProps) {
            try {
                val response = restClient.post()
                    .uri(url)
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
    }
}
```

(Confirm `OsmProperties` is a `data class` so `.copy(...)` exists — it is. If `withOsmRetry`'s attempt clamp `coerceAtLeast(1)` means `overpassPerEndpointAttempts = 1` yields exactly one attempt — correct.)

- [ ] **Step 5: mechanical `StreetDirectionResolver` compile fix**

In `resolveFresh`, change `val ways = overpassClient.findNearbyWays(lat, lon, searchRadius)` to:

```kotlin
        val overpass = overpassClient.findNearbyWays(lat, lon, searchRadius)
        val ways = overpass.ways
```

Leave everything else in this file for Tasks 2–3. `overpass.sourceCount` is unused for now — add `@Suppress("unused")` only if the compiler warns as error; otherwise leave it (Task 2 uses it).

- [ ] **Step 6: `application.yml`**

Under `app.osm:`, replace `overpass-base-url: "..."` with:

```yaml
    overpass-base-urls:
      - "https://overpass-api.de/api/interpreter"
      - "https://overpass.kumi.systems/api/interpreter"
      - "https://overpass.private.coffee/api/interpreter"
    overpass-per-endpoint-attempts: 1
```

- [ ] **Step 7: one-carriageway fixture**

Copy `server/src/test/resources/fixtures/overpass-khayaban-e-jinnah-report-649b9a.json` to `server/src/test/resources/fixtures/overpass-khayaban-e-jinnah-one-carriageway.json` and delete the element with `"id": 726823670` (keep only way `24996609`). This is the "stale replica served only one carriageway" input.

- [ ] **Step 8: rewrite `OverpassClientTest`**

Use 3 `WireMockServer` instances (`endpointA/B/C`, dynamic ports), started/stopped per test. Build the client with an absolute-URI `RestClient` (no base URL) and `OsmProperties(overpassBaseUrls = listOf(urlA, urlB, urlC), overpassPerEndpointAttempts = 1)`. Helper to stub one endpoint's `post(urlPathEqualTo("/"))`.

Tests:

```kotlin
@Test fun `unions and dedupes ways across endpoints by id`() {
    stub(endpointA, waysJson(1L, 2L)); stub(endpointB, waysJson(2L, 3L)); stub(endpointC, waysJson(3L))
    val result = client().findNearbyWays(31.5, 74.3, 50.0)
    assertThat(result.ways.mapNotNull { it.id }).containsExactlyInAnyOrder(1L, 2L, 3L)
    assertThat(result.sourceCount).isEqualTo(3)
}

@Test fun `on an id collision keeps the element with more geometry nodes`() {
    stub(endpointA, wayJson(id = 5L, nodeCount = 2))
    stub(endpointB, wayJson(id = 5L, nodeCount = 6))
    stubEmpty(endpointC)
    val result = client().findNearbyWays(31.5, 74.3, 50.0)
    assertThat(result.ways.single { it.id == 5L }.geometry).hasSize(6)
}

@Test fun `a failing endpoint is skipped and the rest still answer`() {
    stubStatus(endpointA, 500); stub(endpointB, waysJson(9L)); stubStatus(endpointC, 500)
    val result = client().findNearbyWays(31.5, 74.3, 50.0)
    assertThat(result.ways.mapNotNull { it.id }).containsExactly(9L)
    assertThat(result.sourceCount).isEqualTo(1)
}

@Test fun `throws OsmLookupException when every endpoint fails`() {
    stubStatus(endpointA, 500); stubStatus(endpointB, 500); stubStatus(endpointC, 500)
    assertThatThrownBy { client().findNearbyWays(31.5, 74.3, 50.0) }
        .isInstanceOf(OsmLookupException::class.java)
}

@Test fun `an empty elements body still counts as a source`() {
    stubEmpty(endpointA); stubEmpty(endpointB); stubEmpty(endpointC)
    val result = client().findNearbyWays(31.5, 74.3, 50.0)
    assertThat(result.ways).isEmpty()
    assertThat(result.sourceCount).isEqualTo(3)
}

@Test fun `includes the given radius in each endpoint query`() {
    stubEmpty(endpointA); stubEmpty(endpointB); stubEmpty(endpointC)
    client().findNearbyWays(31.5, 74.3, 120.0)
    endpointA.verify(postRequestedFor(urlPathEqualTo("/"))
        .withRequestBody(containing("around%3A120.0%2C31.5%2C74.3")))
}
```

Write `waysJson`/`wayJson`/`wayJson(nodeCount)` helpers producing valid `out geom` bodies (each node `{"lat":.., "lon":..}`).

- [ ] **Step 9: `StreetDirectionResolverTest` — two-endpoint wiring, no behavior change**

- Companion starts **two** overpass WireMocks (`overpassA`, `overpassB`) plus keep the existing one for Nominatim (or reuse `overpassA` for Nominatim — it already serves `urlMatching(".*")`).
- `@DynamicPropertySource`: `registry.add("app.osm.overpass-base-urls") { "http://localhost:${overpassA.port()},http://localhost:${overpassB.port()}" }` (Spring binds a comma-separated string to `List<String>`), keep `nominatim-base-url` pointed at whichever server also stubs Nominatim.
- Add helpers:

```kotlin
private fun stubOverpass(json: String) {
    listOf(overpassA, overpassB).forEach {
        it.stubFor(post(urlMatching(".*")).willReturn(okJson(json)))
    }
}
/** Number of full resolve rounds = total Overpass POSTs across mirrors / mirror count. */
private fun overpassResolveRounds(): Int =
    (overpassA.findAll(postRequestedFor(urlMatching(".*"))).size +
        overpassB.findAll(postRequestedFor(urlMatching(".*"))).size) / 2
```

- Mechanically replace all 20 `wireMockServer.stubFor(post(urlMatching(".*"))...)` with `stubOverpass(<the json>)`, and the 5 `wireMockServer.verify(N, postRequestedFor(...))` assertions with `assertThat(overpassResolveRounds()).isEqualTo(N)`. `resetStubs()` resets both.
- The retry test (`retries once on a 500 then succeeds`, line ~144): with `overpassPerEndpointAttempts = 1` a single mirror no longer retries — but the OTHER mirror answers, so the resolution still succeeds. Rework it to: `overpassA` → 500, `overpassB` → ways ⇒ assert `OneWay` and `overpassResolveRounds() == 1`. (Cross-mirror redundancy replacing same-mirror retry is the whole point.)

- [ ] **Step 10: run the suite**

Run: `cd server && ./gradlew test --tests "com.trafficwatch.server.geo.*"`
Expected: PASS. Then full `./gradlew test` — PASS (no behavior change outside `geo`).

- [ ] **Step 11: Commit**

```bash
git add -A && git commit -m "feat: query multiple Overpass mirrors and union the ways"
```

---

## Task 2: Single-source downgrade + cache TTL

**Files:**
- Modify: `server/src/main/kotlin/com/trafficwatch/server/geo/OsmProperties.kt`
- Create: `server/src/main/kotlin/com/trafficwatch/server/config/TimeConfig.kt`
- Modify: `server/src/main/kotlin/com/trafficwatch/server/geo/StreetDirectionResolver.kt`
- Modify: `server/src/main/resources/application.yml`
- Test: `server/src/test/kotlin/com/trafficwatch/server/geo/StreetDirectionResolverTest.kt`

**Interfaces:**
- Consumes: `OverpassResult.sourceCount` (Task 1), `DirectionResolution.Unknown` (still single-arg here — `reason` arrives in Task 3; **for now pass only `streetName`**)
- Produces: `OsmProperties.cacheTtlDays: Long` (default `30`); a `Clock` bean; TTL-gated cache reads

- [ ] **Step 1: `cacheTtlDays` + `Clock` bean**

`OsmProperties`: add `var cacheTtlDays: Long = 30,`.

New `TimeConfig.kt`:

```kotlin
package com.trafficwatch.server.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class TimeConfig {
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
```

`application.yml` under `app.osm:`: add `cache-ttl-days: 30`.

- [ ] **Step 2: failing test — a single un-cross-checked `OneWay` is downgraded**

In `StreetDirectionResolverTest`:

```kotlin
@Test
fun `downgrades OneWay to Unknown when only one Overpass source answered`() {
    // Only mirror A responds; B is down. A clean single-carriageway oneway way with no
    // anti-parallel neighbour would be OneWay from a cross-checked fetch - but one source
    // is not enough to assert a direction.
    overpassA.stubFor(post(urlMatching(".*"))
        .willReturn(okJson(overpassResponseJson(oneway = "yes", name = "Single Source St"))))
    overpassB.stubFor(post(urlMatching(".*")).willReturn(aResponse().withStatus(500)))

    val result = streetDirectionResolver.resolve(
        BigDecimal("31.6001"), BigDecimal("74.6001"), accuracyMeters = 5.0,
    )
    assertThat(result).isInstanceOf(DirectionResolution.Unknown::class.java)
}

@Test
fun `keeps OneWay when two Overpass sources agree`() {
    stubOverpass(overpassResponseJson(oneway = "yes", name = "Cross Checked Ave"))
    val result = streetDirectionResolver.resolve(
        BigDecimal("31.6002"), BigDecimal("74.6002"), accuracyMeters = 5.0,
    )
    assertThat(result).isInstanceOf(DirectionResolution.OneWay::class.java)
}
```

Run: `./gradlew test --tests "*StreetDirectionResolverTest"` → the first test FAILS (currently returns `OneWay`).

- [ ] **Step 3: implement the downgrade**

In `resolveFresh`, immediately before the final `return resolution`:

```kotlin
        if (resolution is DirectionResolution.OneWay && overpass.sourceCount < 2) {
            logger.warn(
                "Overpass OneWay from a single un-cross-checked source at {},{} - downgrading to Unknown",
                lat, lon,
            )
            return DirectionResolution.Unknown(streetName)
        }
        return resolution
```

Add `private val logger = LoggerFactory.getLogger(StreetDirectionResolver::class.java)` (import `org.slf4j.LoggerFactory`) if not present.

Run the two new tests → PASS. Run all `*StreetDirectionResolverTest` → PASS (existing tests stub both mirrors via `stubOverpass`, so `sourceCount == 2`).

- [ ] **Step 4: inject `Clock`, add the TTL gate**

Constructor: add `private val clock: Clock` (import `java.time.Clock`). In `persist`, replace `entity.updatedAt = OffsetDateTime.now()` with `entity.updatedAt = OffsetDateTime.now(clock)` (and the `OsmLookupCache(...)` default-arg path is unaffected — it's overwritten right after).

In `resolve()`, change the cache-hit condition:

```kotlin
        val cached = cacheRepository.findByLatBucketAndLonBucket(latBucket, lonBucket)
        val cacheFresh = cached != null &&
            cached.updatedAt.isAfter(OffsetDateTime.now(clock).minusDays(osmProperties.cacheTtlDays))
        if (cacheFresh && cached!!.searchRadiusMeters.toDouble() >= searchRadius &&
            cached.accuracyMeters.toDouble() >= clampedAccuracy
        ) {
            return cached.toDirectionResolution()
        }
```

- [ ] **Step 5: TTL tests**

Add an adjustable clock to the test context. Nested in `StreetDirectionResolverTest`:

```kotlin
@TestConfiguration
class FixedClockConfig {
    @Bean @Primary
    fun testClock(): Clock = MutableClock(Instant.parse("2026-08-31T00:00:00Z"))
}

class MutableClock(private var instant: Instant) : Clock() {
    fun advanceDays(days: Long) { instant = instant.plus(Duration.ofDays(days)) }
    override fun instant() = instant
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?) = this
}
```

Annotate the class `@Import(StreetDirectionResolverTest.FixedClockConfig::class)` and `@Autowired` the `Clock`, casting to `MutableClock` in the TTL test. (If `@Primary` on a `@TestConfiguration` bean is awkward with the existing context, instead make `FixedClockConfig` the only `Clock` provider on the `test` profile — `TimeConfig`'s bean can be `@ConditionalOnMissingBean`.)

```kotlin
@Test
fun `re-resolves after the cache TTL expires`() {
    stubOverpass(overpassResponseJson(oneway = "yes", name = "Ttl Street"))
    val lat = BigDecimal("31.6100"); val lon = BigDecimal("74.6100")
    streetDirectionResolver.resolve(lat, lon, 5.0)
    assertThat(overpassResolveRounds()).isEqualTo(1)

    (clock as MutableClock).advanceDays(31)
    streetDirectionResolver.resolve(lat, lon, 5.0)
    assertThat(overpassResolveRounds()).isEqualTo(2) // cache treated as a miss
}

@Test
fun `still hits the cache within the TTL`() {
    stubOverpass(overpassResponseJson(oneway = "yes", name = "Fresh Cache Street"))
    val lat = BigDecimal("31.6101"); val lon = BigDecimal("74.6101")
    streetDirectionResolver.resolve(lat, lon, 5.0)
    (clock as MutableClock).advanceDays(5)
    streetDirectionResolver.resolve(lat, lon, 5.0)
    assertThat(overpassResolveRounds()).isEqualTo(1)
}
```

The existing "caches the resolution" test (line ~131) still passes (default 5-day-equivalent gap is 0 here; it does two resolves back-to-back → 1 round).

Run: `./gradlew test --tests "*StreetDirectionResolverTest"` → PASS. Full `./gradlew test` → PASS.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat: downgrade single-source OneWay to Unknown, add OSM cache TTL"
```

---

## Task 3: `UnknownReason` on `DirectionResolution`

**Files:**
- Modify: `server/src/main/kotlin/com/trafficwatch/server/geo/DirectionResolution.kt`
- Modify: `server/src/main/kotlin/com/trafficwatch/server/geo/StreetDirectionResolver.kt`
- Test: `server/src/test/kotlin/com/trafficwatch/server/geo/StreetDirectionResolverTest.kt`

**Interfaces:**
- Produces: `enum class UnknownReason { NO_ONEWAY_TAG, AMBIGUOUS_NEAREST_STREET, DIVIDED_CARRIAGEWAY, NOT_CROSS_CHECKED }`
- Produces: `DirectionResolution.Unknown(val streetName: String?, val reason: UnknownReason = UnknownReason.NO_ONEWAY_TAG)`
- Consumes: the four `Unknown` return sites in `resolveFresh` + the Task 2 downgrade site

- [ ] **Step 1: the enum + field**

`DirectionResolution.kt`:

```kotlin
/**
 * Why [DirectionResolution.Unknown] was returned. Only [DIVIDED_CARRIAGEWAY] means "the
 * street IS one-way, we just can't tell which carriageway" - the one case where
 * ReportAnalysisJob's stationary-approach path may still fire (see the 2026-08-31 design).
 */
enum class UnknownReason {
    NO_ONEWAY_TAG,
    AMBIGUOUS_NEAREST_STREET,
    DIVIDED_CARRIAGEWAY,
    NOT_CROSS_CHECKED,
}
```

Change `Unknown`:

```kotlin
    data class Unknown(
        val streetName: String?,
        val reason: UnknownReason = UnknownReason.NO_ONEWAY_TAG,
    ) : DirectionResolution()
```

Build (`./gradlew compileKotlin compileTestKotlin`) — every existing `Unknown(streetName)` still compiles via the default.

- [ ] **Step 2: failing tests — reasons at each site**

```kotlin
@Test fun `Unknown from an untagged way carries NO_ONEWAY_TAG`() {
    stubOverpass(overpassResponseJson(oneway = null, name = "Untagged St"))
    val r = streetDirectionResolver.resolve(BigDecimal("31.6200"), BigDecimal("74.6200"), 5.0)
    assertThat(r).isEqualTo(DirectionResolution.Unknown("Untagged St", UnknownReason.NO_ONEWAY_TAG))
}

@Test fun `Unknown from a divided carriageway carries DIVIDED_CARRIAGEWAY`() {
    val fixture = readFixture("overpass-khayaban-e-jinnah-report-649b9a.json")
    stubOverpass(fixture)
    val r = streetDirectionResolver.resolve(
        BigDecimal("31.486191240932015"), BigDecimal("74.38313319364715"), 5.0,
    )
    assertThat(r).isInstanceOf(DirectionResolution.Unknown::class.java)
    assertThat((r as DirectionResolution.Unknown).reason).isEqualTo(UnknownReason.DIVIDED_CARRIAGEWAY)
}

@Test fun `Unknown from a single un-cross-checked source carries NOT_CROSS_CHECKED`() {
    overpassA.stubFor(post(urlMatching(".*"))
        .willReturn(okJson(overpassResponseJson(oneway = "yes", name = "Solo St"))))
    overpassB.stubFor(post(urlMatching(".*")).willReturn(aResponse().withStatus(500)))
    val r = streetDirectionResolver.resolve(BigDecimal("31.6201"), BigDecimal("74.6201"), 5.0)
    assertThat((r as DirectionResolution.Unknown).reason).isEqualTo(UnknownReason.NOT_CROSS_CHECKED)
}
```

For `AMBIGUOUS_NEAREST_STREET`: reuse whatever the existing "two equidistant different streets → Unknown" test setup is (search the file for the test hitting the `nearestDifferentStreet` branch) and add `.reason == AMBIGUOUS_NEAREST_STREET`.

Run → the new assertions FAIL (reason is the default `NO_ONEWAY_TAG` everywhere except where it happens to match).

- [ ] **Step 3: set the reason at every site in `resolveFresh`**

- `nearestDifferentStreet` branch (line ~82): `return DirectionResolution.Unknown(streetName, UnknownReason.AMBIGUOUS_NEAREST_STREET)`
- `oneway` `else` branch (line ~98): `else -> DirectionResolution.Unknown(streetName, UnknownReason.NO_ONEWAY_TAG)`
- anti-parallel guard (line ~102): `return DirectionResolution.Unknown(streetName, UnknownReason.DIVIDED_CARRIAGEWAY)`
- Task 2 downgrade site: `return DirectionResolution.Unknown(streetName, UnknownReason.NOT_CROSS_CHECKED)`

Run the new tests → PASS. Update the existing `downgrades to Unknown for the real Khayaban-e-Jinnah divided carriageway behind report 649b9a` test to also assert `.reason == DIVIDED_CARRIAGEWAY`. Full `./gradlew test` → PASS.

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "feat: tag DirectionResolution.Unknown with a reason"
```

---

## Task 4: Persist the `Unknown` reason (cache + migration)

**Files:**
- Create: `server/src/main/resources/db/migration/V11__add_unknown_reason_and_reset_osm_cache.sql`
- Modify: `server/src/main/kotlin/com/trafficwatch/server/geo/OsmLookupCache.kt`
- Modify: `server/src/main/kotlin/com/trafficwatch/server/geo/StreetDirectionResolver.kt`
- Test: `server/src/test/kotlin/com/trafficwatch/server/geo/StreetDirectionResolverTest.kt`

**Interfaces:**
- Consumes: `UnknownReason` (Task 3), `OsmLookupCache`
- Produces: `osm_lookup_cache.unknown_reason` column; `OsmLookupCache.unknownReason: UnknownReason?`; round-trip in `persist` / `toDirectionResolution`

- [ ] **Step 1: migration**

`V11__add_unknown_reason_and_reset_osm_cache.sql`:

```sql
ALTER TABLE osm_lookup_cache ADD COLUMN unknown_reason VARCHAR(32);
ALTER TABLE osm_lookup_cache ADD CONSTRAINT chk_osm_lookup_cache_unknown_reason
    CHECK (unknown_reason IN (
        'NO_ONEWAY_TAG', 'AMBIGUOUS_NEAREST_STREET', 'DIVIDED_CARRIAGEWAY', 'NOT_CROSS_CHECKED'
    ));

-- Every bucket re-resolves through the new multi-mirror path on next use; this clears the
-- known-bad OneWay(11.23) rows for the خیبان جناح bucket (reports 759cd / 24908 / a5275)
-- immediately rather than waiting out the 30-day TTL.
TRUNCATE TABLE osm_lookup_cache;
```

- [ ] **Step 2: entity column**

`OsmLookupCache.kt`, after `directionState`:

```kotlin
    @Enumerated(EnumType.STRING)
    @Column(name = "unknown_reason", length = 32)
    var unknownReason: UnknownReason? = null,
```

- [ ] **Step 3: failing test — reason survives a cache hit**

```kotlin
@Test
fun `a cached DIVIDED_CARRIAGEWAY resolution keeps its reason on the next lookup`() {
    val fixture = readFixture("overpass-khayaban-e-jinnah-report-649b9a.json")
    stubOverpass(fixture)
    val lat = BigDecimal("31.4869"); val lon = BigDecimal("74.3830")
    val first = streetDirectionResolver.resolve(lat, lon, 5.0)
    assertThat((first as DirectionResolution.Unknown).reason).isEqualTo(UnknownReason.DIVIDED_CARRIAGEWAY)

    overpassA.resetAll(); overpassB.resetAll() // any further Overpass call would now 404
    val second = streetDirectionResolver.resolve(lat, lon, 5.0)
    assertThat(second).isEqualTo(DirectionResolution.Unknown(first.streetName, UnknownReason.DIVIDED_CARRIAGEWAY))
    assertThat(overpassResolveRounds()).isEqualTo(1)
}
```

Run → FAILS (`second` comes back `Unknown(streetName, NO_ONEWAY_TAG)`).

- [ ] **Step 4: round-trip in the resolver**

In `persist`, after `entity.directionState = ...`:

```kotlin
        entity.unknownReason = (resolution as? DirectionResolution.Unknown)?.reason
```

In `toDirectionResolution`:

```kotlin
        DirectionState.UNKNOWN -> DirectionResolution.Unknown(
            streetName,
            unknownReason ?: UnknownReason.NO_ONEWAY_TAG,
        )
```

Run the new test → PASS. Full `./gradlew test` → PASS (Flyway runs V11 against the test DB; the `TRUNCATE` is harmless there).

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: persist the Unknown reason in osm_lookup_cache (V11)"
```

---

## Task 5: Widened approach gate with flow corroboration

**Files:**
- Modify: `server/src/main/kotlin/com/trafficwatch/server/reports/AnalysisProperties.kt`
- Modify: `server/src/main/kotlin/com/trafficwatch/server/reports/ReportAnalysisJob.kt`
- Modify: `server/src/main/resources/application.yml`
- Test: `server/src/test/kotlin/com/trafficwatch/server/reports/ReportAnalysisJobTest.kt`
- Test: `server/src/test/kotlin/com/trafficwatch/server/reports/ReportAnalysisIntegrationTest.kt` (only if a hardcoded value moves)

**Interfaces:**
- Consumes: `DirectionResolution.Unknown.reason`, `UnknownReason.DIVIDED_CARRIAGEWAY`, `ClipFlowAnalyzer.corridorConsensus(flowVehicles, corridorId, excluding)`, `CorridorConsensus.memberCount`
- Produces: `AnalysisProperties.approachCorroborationMinMembers: Int = 2`; `ApproachEvidenceBreakdown` with `resolutionState: String` + `corroborationConsensusMembers: Int?`

- [ ] **Step 1: property**

`AnalysisProperties`: add

```kotlin
    // Stationary-approach on a DIVIDED_CARRIAGEWAY Unknown street additionally requires the
    // clip's qualified traffic to form one coherent stream: the strongest corridor consensus
    // must have at least this many members. NOT a bearing-opposition check on the grower -
    // the grower's frame bearing is perspective-understated by construction.
    var approachCorroborationMinMembers: Int = 2,
```

`application.yml` under `app.analysis:`: `approach-corroboration-min-members: 2`.

- [ ] **Step 2: failing tests in `ReportAnalysisJobTest`**

```kotlin
@Test
fun `stationary approach on a DIVIDED_CARRIAGEWAY Unknown street with a coherent receding stream is CONFIRMED`() {
    val report = sampleReport(locationSamples = stationaryLocationSamplesJson())
    every { streetDirectionResolver.resolve(any(), any(), any()) } returns
        DirectionResolution.Unknown("Khayaban-e-Jinnah", UnknownReason.DIVIDED_CARRIAGEWAY)
    every { videoAnalysisClient.analyze(fakeVideoPath, any(), any()) } returns analysisResponse(
        listOf(
            vehicle(trackId = 1, bearingDegrees = 190.0, scaleTrend = "shrinking", trackFrameCount = 40),
            vehicle(trackId = 2, bearingDegrees = 190.0, scaleTrend = "shrinking", trackFrameCount = 40),
            vehicle(trackId = 3, bearingDegrees = 190.0, scaleTrend = "shrinking", trackFrameCount = 40),
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
    assertThat(report.directionEvidence).contains("stationary_approach")
    assertThat(report.directionEvidence).contains("UNKNOWN_DIVIDED_CARRIAGEWAY")
}

@Test
fun `stationary approach on a DIVIDED_CARRIAGEWAY street is REJECTED when the traffic has no coherent consensus`() {
    val report = sampleReport(locationSamples = stationaryLocationSamplesJson())
    every { streetDirectionResolver.resolve(any(), any(), any()) } returns
        DirectionResolution.Unknown("Khayaban-e-Jinnah", UnknownReason.DIVIDED_CARRIAGEWAY)
    // 3 shrinking by bbox scale, but bearings are scattered -> corridorConsensus elects nothing.
    every { videoAnalysisClient.analyze(fakeVideoPath, any(), any()) } returns analysisResponse(
        listOf(
            vehicle(trackId = 1, bearingDegrees = 10.0, scaleTrend = "shrinking", trackFrameCount = 40),
            vehicle(trackId = 2, bearingDegrees = 130.0, scaleTrend = "shrinking", trackFrameCount = 40),
            vehicle(trackId = 3, bearingDegrees = 250.0, scaleTrend = "shrinking", trackFrameCount = 40),
            vehicle(trackId = 5, bearingDegrees = 185.0, detectionConfidence = 0.9,
                scaleTrend = "growing", scaleGrowthFraction = 1.4, trackFrameCount = 60),
        ),
    )
    every { reportRepository.save(any()) } answers { firstArg() }

    job.applyOutcome(report)

    assertThat(report.status).isEqualTo(ReportStatus.REJECTED)
}

@Test
fun `stationary approach does NOT run on a NO_ONEWAY_TAG Unknown street`() {
    val report = sampleReport(locationSamples = stationaryLocationSamplesJson())
    every { streetDirectionResolver.resolve(any(), any(), any()) } returns
        DirectionResolution.Unknown("Side Street", UnknownReason.NO_ONEWAY_TAG)
    every { videoAnalysisClient.analyze(fakeVideoPath, any(), any()) } returns analysisResponse(
        listOf(
            vehicle(trackId = 1, bearingDegrees = 190.0, scaleTrend = "shrinking", trackFrameCount = 40),
            vehicle(trackId = 2, bearingDegrees = 190.0, scaleTrend = "shrinking", trackFrameCount = 40),
            vehicle(trackId = 3, bearingDegrees = 190.0, scaleTrend = "shrinking", trackFrameCount = 40),
            vehicle(trackId = 5, bearingDegrees = 185.0, scaleTrend = "growing",
                scaleGrowthFraction = 1.4, trackFrameCount = 60),
        ),
    )
    every { reportRepository.save(any()) } answers { firstArg() }

    job.applyOutcome(report)

    assertThat(report.status).isEqualTo(ReportStatus.REJECTED)
}
```

Run → the CONFIRMED test FAILS (gate is still `is OneWay`).

- [ ] **Step 3: widen the gate**

In `determineOutcome`, replace the block at `ReportAnalysisJob.kt:185-188`:

```kotlin
        if (outcome.status == ReportStatus.REJECTED) {
            val flowConsensus = strongestFlowConsensus(flowVehicles)
            val approachEligible = when (resolution) {
                is DirectionResolution.OneWay -> true
                is DirectionResolution.Unknown ->
                    resolution.reason == UnknownReason.DIVIDED_CARRIAGEWAY &&
                        (flowConsensus?.memberCount ?: 0) >= analysisProperties.approachCorroborationMinMembers
                else -> false
            }
            if (approachEligible) {
                tryStationaryApproachDetection(
                    report, analysis, orientationTimeline, streetName,
                    resolution, flowConsensus?.memberCount,
                )?.let { return it }
            }
        }
        return outcome
```

Add the helper (near `evaluateCandidates`):

```kotlin
    /**
     * The clip's strongest corridor consensus over all qualified vehicles, no candidate
     * excluded - a "the scene is one coherent directional stream" signal. Reuses
     * ClipFlowAnalyzer's R-gate (returns null below consensus-min-resultant-length).
     */
    private fun strongestFlowConsensus(flowVehicles: List<FlowVehicle>): CorridorConsensus? =
        flowVehicles.map { it.corridorId }.distinct()
            .mapNotNull { clipFlowAnalyzer.corridorConsensus(flowVehicles, it, excluding = null) }
            .maxByOrNull { it.clipConfidence }
```

Import `com.trafficwatch.server.geo.UnknownReason`. (`CorridorConsensus` is already imported.)

- [ ] **Step 4: thread `resolution` + member count into the evidence**

`tryStationaryApproachDetection` signature:

```kotlin
    private fun tryStationaryApproachDetection(
        report: Report,
        analysis: VideoAnalysisResponse,
        orientationTimeline: OrientationTimeline,
        streetName: String?,
        resolution: DirectionResolution,
        corroborationMembers: Int?,
    ): AnalysisOutcome? {
```

At the `directionEvidenceJson =` call, pass the new context. Update `approachBreakdownJson`:

```kotlin
    private fun approachBreakdownJson(
        best: VehicleAnalysisResult,
        recedingCount: Int,
        strongGrowerCount: Int,
        resolution: DirectionResolution,
        corroborationMembers: Int?,
    ): String? = try {
        objectMapper.writeValueAsString(
            ApproachEvidenceBreakdown(
                resolutionState = when (resolution) {
                    is DirectionResolution.Unknown -> "UNKNOWN_${resolution.reason.name}"
                    is DirectionResolution.OneWay -> "ONE_WAY"
                    else -> "OTHER"
                },
                recedingCount = recedingCount,
                strongGrowerCount = strongGrowerCount,
                corroborationConsensusMembers = corroborationMembers,
                growthFraction = best.scaleGrowthFraction,
                trackFrames = best.trackFrameCount ?: 0,
                detectionConfidence = best.detectionConfidence,
                confirmationThreshold = analysisProperties.confirmationThreshold,
            ),
        )
    } catch (ex: Exception) { logger.warn("...", ex); null }
```

`ApproachEvidenceBreakdown` (line ~510):

```kotlin
internal data class ApproachEvidenceBreakdown(
    val method: String = "stationary_approach",
    val resolutionState: String,
    val recedingCount: Int,
    val strongGrowerCount: Int,
    val corroborationConsensusMembers: Int?,
    val growthFraction: Double,
    val trackFrames: Int,
    val detectionConfidence: Double,
    val confirmationThreshold: Double,
)
```

- [ ] **Step 5: fix the two existing approach tests**

- `stationary clip with a receding majority and a strong approaching vehicle is CONFIRMED via the approach path` (line ~876, `OneWay`): add `assertThat(report.directionEvidence).contains("ONE_WAY")`. Its 4 shrinking + 1 grower already form a consensus, so no gate change affects it.
- `approach path does not run when the street is not resolved to a one-way` (line ~1059): change the stub to `DirectionResolution.Unknown("Side Street", UnknownReason.NO_ONEWAY_TAG)` and keep the REJECTED assertion; update the comment to note `DIVIDED_CARRIAGEWAY` is now the one exception.

Run: `./gradlew test --tests "*ReportAnalysisJobTest"` → PASS. Then full `./gradlew test`. If `ReportAnalysisIntegrationTest` fails on a hardcoded evidence-JSON substring, update it to the new shape.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat: run stationary-approach detection on divided-carriageway Unknown streets"
```

---

## Task 6: Backlog + deploy notes

**Files:**
- Modify: `docs/improvements-backlog.md`

- [ ] **Step 1: update the backlog**

- In the `## Location / GPS accuracy` section, under the report-649b9a recurrence entry, add a dated note: the non-deterministic single-fetch cause is addressed by multi-mirror union + `NOT_CROSS_CHECKED` downgrade + cache TTL (this plan / spec `2026-08-31-...`), and Overpass per-endpoint logging is now in place so the next anomaly is diagnosable.
- In `## Vehicle detection / tracking`, under `[HIGH - found 2026-08-30] A motorcycle riding straight at a stationary camera`, extend the "Partly addressed by" note: the approach path now also fires on `Unknown(DIVIDED_CARRIAGEWAY)` with flow corroboration, so `759cd` / `24908` / `a5275` confirm on current production once deployed (was previously blocked by the `is OneWay` gate after the divided-carriageway downgrade).
- Leave `50bcc6` / `71f78` listed as still-open (deferred to the clip-flow-relative bearing design).

- [ ] **Step 2: append deploy notes to this plan**

Add a `## Deploy` section (below) with the runbook steps. No code.

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "docs: backlog + deploy notes for divided-carriageway resolution"
```

---

## Deploy

1. **Config migration.** The VPS runs from an scp'd snapshot; check `~/trafficwatch` (compose env / `application-local.yml` if any) for an `APP_OSM_OVERPASS_BASE_URL` / `app.osm.overpass-base-url` override. If present, replace with the list form (`APP_OSM_OVERPASS_BASE_URLS_0`, `_1`, `_2` env-var style, or the YAML list). If absent, the new 3-mirror default applies automatically.
2. **Full `main` deploy** via `git archive` (as done 2026-08-30) → `docker compose -f docker-compose.prod.yml up -d --build`.
3. **Flyway** runs `V11` on server start: adds `unknown_reason`, then `TRUNCATE osm_lookup_cache`. Confirm in the server log (`Migrating schema "public" to version "11"`).
4. **Verify** by re-submitting a report at the `759cd` coordinates (throwaway user, as before). Expect: `direction_evidence` OSM source `Unknown / DIVIDED_CARRIAGEWAY`; outcome **CONFIRMED** via `stationary_approach` with `resolutionState = "UNKNOWN_DIVIDED_CARRIAGEWAY"`; `OverpassClient` INFO logs showing per-endpoint way ids.
5. **Tuning check.** In the same replay, capture the server log line for `strongestFlowConsensus` (add a temporary `logger.info` in the branch if needed) — confirm `memberCount >= 2` for all three target reports. If `759cd` comes in at 1, lower `app.analysis.approach-corroboration-min-members` to `1` (a real R ≥ 0.6 consensus must still exist) and note it in the backlog.
6. Clean up the throwaway report/user/video as in the 2026-08-30 runbook.

---

## Self-Review

**Spec coverage:**
- Part A multi-endpoint union + `sourceCount` → Task 1. ✅
- Single-source `OneWay` downgrade → Task 2. ✅
- Cache TTL + `Clock` → Task 2. ✅
- Per-endpoint logging → Task 1 (Step 4). ✅
- `UnknownReason` enum + 4 assignment sites → Task 3. ✅
- Cache persists reason + `V11` + `TRUNCATE` → Task 4. ✅
- Widened gate `OneWay || Unknown(DIVIDED_CARRIAGEWAY) && hasCoherentRecedingFlow` → Task 5. ✅
- Corroboration = strongest consensus `memberCount >= approachCorroborationMinMembers`, NOT a grower-bearing check → Task 5 (`strongestFlowConsensus`, comment). ✅
- Evidence `resolutionState` + `corroborationConsensusMembers` → Task 5. ✅
- Stale-replica reproduction test → the explicit "mirror A full, mirror B trimmed to one carriageway → union restores it → `Unknown(DIVIDED_CARRIAGEWAY)`" case is added to Task 3 Step 2 (see the extra test at the end of this Self-Review). The `one-carriageway.json` fixture is created in Task 1 Step 7. ✅
- Production verification → Deploy section. ✅
- Non-goals (carriageway choice, cohesion, clip-flow-relative bearing) → not in any task. ✅

**Placeholder scan:** no TBDs; every code step has the literal code; test steps have assertions. ✅

**Type consistency:** `OverpassResult.ways` / `.sourceCount`; `findNearbyWays` returns `OverpassResult` (Task 1) consumed as `.ways` (Task 1 Step 5) and `.sourceCount` (Task 2 Step 3); `UnknownReason` values identical across enum (Task 3), migration CHECK (Task 4), `resolutionState` string prefix (Task 5); `strongestFlowConsensus` returns `CorridorConsensus?`, `.memberCount` is `Int`; `approachCorroborationMinMembers` used in Task 5 gate and Deploy step 5. ✅

**Added test for the spec's stale-replica case:** append to Task 3 Step 2:

```kotlin
@Test fun `union across a full mirror and a trimmed mirror still downgrades a divided carriageway`() {
    overpassA.stubFor(post(urlMatching(".*"))
        .willReturn(okJson(readFixture("overpass-khayaban-e-jinnah-report-649b9a.json"))))
    overpassB.stubFor(post(urlMatching(".*"))
        .willReturn(okJson(readFixture("overpass-khayaban-e-jinnah-one-carriageway.json"))))
    val r = streetDirectionResolver.resolve(
        BigDecimal("31.486191240932015"), BigDecimal("74.38313319364715"), 5.0,
    )
    assertThat((r as DirectionResolution.Unknown).reason).isEqualTo(UnknownReason.DIVIDED_CARRIAGEWAY)
}
```
