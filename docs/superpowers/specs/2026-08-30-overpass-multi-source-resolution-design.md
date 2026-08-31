# Overpass Multi-Source Direction Resolution Design

> **SUPERSEDED (2026-08-31)** by
> `docs/superpowers/specs/2026-08-31-divided-carriageway-resolution-and-approach-on-unknown-design.md`,
> which carries this design forward as its "Part A" and adds the
> `Unknown`-reason tagging + stationary-approach gate changes ("Part B")
> without which this spec alone is a regression for reports 759cd / 24908 /
> a5275 (it makes them resolve reliably to `Unknown`, which the shipped
> `is OneWay` confirmation gate blocks). Kept for history.

## Context

`StreetDirectionResolver` resolves the street and legal traffic direction
nearest a report's coordinates from a single Overpass API call
(`OverpassClient.findNearbyWays`, one POST to one configured
`overpass-base-url`, wrapped in `withOsmRetry` which only retries on
outright failure). On a divided road it relies on
`hasAntiParallelOneWayNeighbor`: if a second `oneway` way with an
anti-parallel legal bearing sits within 30 m of the chosen way's segment
midpoint, the result is downgraded to `Unknown` instead of a confident
`OneWay`, so a legally-driving motorist on the far carriageway is never
judged wrong-way and a report is never rejected against the wrong
carriageway's legal direction.

Three user-confirmed wrong-way reports on خیبان جناح / Khayaban-e-Jinnah,
Lahore (`101aef9e...759cd`, `9e44e167...24908`, `7d578a63...a5275`, all at
cache bucket 31.4706 / 74.4059) resolved to a confident `OneWay` with legal
bearing 11.23 deg. That is the **northbound** carriageway; the traffic in
the clips is on the **southbound** carriageway (legal ~193 deg). The
mismatch made the observed flow "conflict" with the resolved legal
direction, and all three reports were REJECTED with "Conflicting direction
evidence for this street" before any vehicle was scored.

### Root cause (established by a 2026-08-30 spike)

The resolver code is **not** buggy. A live Overpass capture for the exact
`759cd` point at the 50 m radius its accuracy (3.79 m) searches clearly
contains both carriageways (northbound ways ~11-27 deg, southbound ways
~191-196 deg). Fed that data, the real resolver correctly returns
`Unknown`: `hasAntiParallelOneWayNeighbor` finds way 23815251 (legal
bearing 193 deg, 178 deg from the chosen way, nearest segment 11.9 m from
its midpoint - inside the 30 m cap) and trips.

The stored bearing `11.233486109523824` is full `double` precision, so it
came from a fresh `resolveFresh()` call, not a rounded cache hit (the cache
stores 2 dp). Therefore the fresh Overpass call **at analysis time returned
incomplete data** - the southbound carriageway ways were absent, so the
guard had nothing anti-parallel to trip on. This is the leading hypothesis
from the 2026-08-17 investigation of report `649b9a` (same road, same
symptom, cause never provable because `OverpassClient` logs nothing):
**overpass-api.de's public service is multi-replica with eventual
consistency, and different replicas serve different subsets of
recently-edited ways.** It has now been observed on this road four times
(`649b9a` plus these three, which share a cache bucket, so really one more
bad fetch).

The three reports share a cache bucket, so only the first analysed did a
fresh lookup; the other two were served its cached wrong result.

## Scope decision (confirmed with the user during brainstorming - do not re-litigate)

- **Stop trusting a single Overpass fetch.** Query multiple independent
  Overpass endpoints and union the ways, so one stale replica cannot
  determine the result. Approach "A" of three considered (the others:
  a confirmation re-fetch only when about to return `OneWay`; retry one
  endpoint until two responses agree - both rejected as more complex for
  less coverage).
- **Single un-cross-checked source must not assert a confident direction.**
  When fewer than two endpoints answer, a `OneWay` result is downgraded to
  `Unknown` - that is exactly the condition that produced this bug.
- **Cache: TTL + truncate on deploy.** A TTL makes poisoned rows
  self-heal and bounds any future bad entry; truncating on deploy clears
  the known-bad rows immediately.
- **Add Overpass response logging** - non-controversial, and the 2026-08-17
  note explicitly says it is needed before the next occurrence can be
  proven.
- **Sequential endpoint queries.** Analysis is async (10 s+ delay already,
  180 s read timeout); latency is a non-issue. Parallelising is a later
  micro-optimisation, out of scope.
- Fix A (near-camera approach bearing,
  `docs/superpowers/specs/2026-08-30-near-camera-approach-bearing-fix-design.md`)
  is still needed for end-to-end confirmation of these reports; this design
  only removes the false "conflicting evidence" reject.

## Design

### `OverpassClient`

`findNearbyWays(lat, lon, radiusMeters)` returns a small result type
instead of a bare `List<OverpassElement>`:

```kotlin
data class OverpassResult(
    val ways: List<OverpassElement>,
    /** How many configured endpoints returned a usable response. */
    val sourceCount: Int,
)
```

Behaviour:
- Iterate `osmProperties.overpassBaseUrls` **in sequence**. For each, issue
  the same query as today (`[out:json]; way(around:R,lat,lon)["highway"];
  out geom;`), each call wrapped in `withOsmRetry` with a **reduced
  per-endpoint attempt count** (1, or a small config value) - cross-endpoint
  redundancy replaces most same-endpoint retrying and bounds worst-case
  latency.
- A per-endpoint failure (after its own retries) is caught, logged at WARN,
  and skipped - it does not fail the whole call.
- **Union** the ways from every endpoint that answered, deduped by
  `way.id`. On the (rare) same-id-different-geometry case, keep the element
  with more `geometry` points.
- `sourceCount` = number of endpoints that returned a response (empty or
  not; an HTTP 200 with `elements: []` still counts as "answered" - it is a
  real statement that there is nothing there).
- **All endpoints fail** -> throw `OsmLookupException` (retryable), exactly
  as a single-endpoint total failure does today -> `LookupFailed` upstream.

**Logging** (new): for each endpoint, INFO one line - endpoint host, HTTP
status, way count, the list of way ids. At DEBUG, the raw response body.
Resolutions are cached, so this is low-frequency.

RestClient wiring: `OsmClientConfig` currently builds one
`overpassRestClient` bean from `overpassBaseUrl`. It will instead expose
what `OverpassClient` needs to address each URL - either one `RestClient`
per configured URL, or a base-URL-less `RestClient` that `OverpassClient`
calls with absolute URIs. Either is fine; the plan picks one.

### `OsmProperties`

- `overpassBaseUrl: String` -> `overpassBaseUrls: List<String>`, default
  `["https://overpass-api.de/api/interpreter",
  "https://overpass.private.coffee/api/interpreter",
  "https://overpass.kumi.systems/api/interpreter"]`.
- New `cacheTtlDays: Long`, default `30`.
- (Optional) `overpassPerEndpointAttempts: Int`, default `1`.

`application.yml` / `application-local.yml.example` updated to match. Any
deployment override of the old single-URL key must be migrated - call this
out in the plan and the deploy notes.

### `StreetDirectionResolver`

- `resolveFresh` takes the `OverpassResult`. After it computes `resolution`
  from the unioned ways (candidate selection and both existing guards
  unchanged):

  ```kotlin
  if (resolution is DirectionResolution.OneWay && overpass.sourceCount < 2) {
      logger.warn("Overpass direction OneWay from a single un-cross-checked source at {},{} - downgrading to Unknown", lat, lon)
      return DirectionResolution.Unknown(streetName)
  }
  return resolution
  ```

  `Unknown` here just means the pipeline leans on video/history evidence
  instead of an OSM tag; a missed detection beats a false one, and this
  repo is false-positive-averse.

- **Cache TTL.** Inject a `java.time.Clock` (replacing the bare
  `OffsetDateTime.now()` in `persist`). In `resolve()`, a cache row whose
  `updatedAt` is older than `osmProperties.cacheTtlDays` is treated as a
  miss - re-resolve and overwrite. The existing radius/accuracy cache-hit
  conditions are unchanged; the TTL is an additional gate.

### Deploy step

Truncate `osm_lookup_cache` as part of the release (a one-line SQL step in
the deploy runbook, or a Flyway `R__`/versioned migration if the team
prefers it tracked). It is a pure cache; every bucket re-resolves through
the new two-endpoint path on next use. This removes the known-bad
`OneWay(11.23)` rows for the خیبان جناح bucket immediately rather than
waiting out the TTL.

## Testing

### `OverpassClientTest`

- All configured endpoints are queried; ways are unioned and deduped by id
  (A returns {1,2}, B returns {2,3} -> {1,2,3}).
- Same id from two endpoints with differing geometry -> the element with
  more nodes is kept.
- Partial failure: endpoint A returns HTTP 500 (after its retry), endpoint
  B returns ways -> result is B's ways, `sourceCount == 1`.
- All endpoints fail -> `OsmLookupException`.
- `sourceCount` reflects the number that answered, including an endpoint
  that returns `elements: []`.
- Infra: 2-3 WireMock servers, one per overpass endpoint, plus the shared
  Nominatim stub. `@DynamicPropertySource` wires
  `app.osm.overpass-base-urls` to the list of WireMock ports.

### `StreetDirectionResolverTest`

- **Stale-replica reproduction (the test that would have caught
  production):** endpoint A serves the full real `759cd` Overpass capture
  (`fixtures/overpass-khayaban-e-jinnah-report-759cd.json`, both
  carriageways); endpoint B serves that same fixture trimmed to drop the
  southbound ways (`...-759cd-stale.json`). Resolve the `759cd`
  coordinates -> assert `Unknown` (the union restores the southbound
  carriageway and the anti-parallel guard trips).
- **Single-source downgrade (c1):** only endpoint A responds, returning a
  clean single-carriageway `oneway` way with no anti-parallel neighbour ->
  assert `Unknown`, not `OneWay` (`sourceCount == 1`).
- **Cross-check success:** two endpoints return that same clean
  single-carriageway data -> assert `OneWay` (confident, cross-checked) -
  proving the downgrade is about cross-checking, not the data.
- **Cache TTL:** resolve for a bucket (row written); advance the injected
  `Clock` past `cacheTtlDays`; resolve again -> a second Overpass round
  occurs (`wireMockServer.verify(2, ...)`) and `updatedAt` refreshes. A
  resolve within the TTL still hits the cache (the existing "caches the
  resolution" test, updated to inject a fixed `Clock`, stays green).

### Regression

- Every existing `StreetDirectionResolverTest` / `OverpassClientTest` case
  passes under the multi-URL config (a single configured URL, or all URLs
  returning identical data, behaves exactly as the single-endpoint code
  did).
- The `649b9a` divided-carriageway test still passes - its fixture served
  to every endpoint unions to the same data and still yields `Unknown`.
- `./gradlew test` green.

### Production verification

1. After deploy, replay (or re-submit) a report at the `759cd`
   coordinates. `direction_evidence` should show `Unknown` for the OSM
   source, not `OneWay(11.23)`.
2. Confirm `OverpassClient` INFO logs now carry per-endpoint way
   counts/ids, so the next anomaly is diagnosable from logs alone.
3. Sanity-check log volume - one INFO line per uncached resolution, and
   resolutions are cached and bucketed, so this should be a trickle.

## Non-goals (explicitly out of scope)

- **Fix A** (near-camera approach bearing) - separate spec; still required
  for these reports to reach CONFIRMED. This design only removes the false
  "conflicting evidence" reject.
- **`corridor_cohesion` under-confirmation** (`50bcc6` / `71f78`) - its own
  backlog item.
- **Self-hosting Overpass** with a regional extract - the mirror-union
  approach is the deliberate choice; self-hosting stays a fallback if the
  public mirrors prove collectively unreliable.
- **Parallel endpoint queries** - sequential is fine for async analysis.
- **Conflict-driven cache invalidation** (clearing a bucket when
  `ReportAnalysisJob` sees a direction conflict) - TTL + truncate covers
  the need without coupling the job to the cache.
- **Retroactive re-analysis of already-submitted reports** - applies to
  future analysis and TTL-triggered re-resolution only, like every other
  change in this project's history.
- **Tuning the anti-parallel guard's 30 m / 45 deg constants** - the spike
  showed they are correct for this geometry; the problem was missing input
  data, not the thresholds.
