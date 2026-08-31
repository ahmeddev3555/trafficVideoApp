# Divided-Carriageway Resolution + Stationary-Approach Detection on `Unknown` — Design

**Status:** draft for review
**Supersedes:** `docs/superpowers/specs/2026-08-30-overpass-multi-source-resolution-design.md`
(that spec's Part A is carried forward here largely unchanged; this document
adds the Part B changes without which Part A is a regression — see
"Why the two halves ship together").

## Context

Three user-confirmed wrong-way reports on خیبان جناح / Khayaban-e-Jinnah,
Lahore — `101aef9e…759cd`, `9e44e167…24908`, `7d578a63…a5275`, all in cache
bucket `31.4706 / 74.4059` — cannot be reliably confirmed by the current
pipeline. The road is a divided one-way carriageway pair: two independently
`oneway=yes`-tagged ways ~9–12 m apart, legal bearings ~11° (northbound) and
~193° (southbound). The riders film the southbound carriageway from a
stationary phone (GPS speed 0) with all legal traffic receding and one rider
approaching against it.

### Two coupled failures

**1. Non-deterministic OSM resolution.** `StreetDirectionResolver` resolves
direction from a single Overpass API call. `overpass-api.de`'s public
service is multi-replica with eventual consistency; different replicas
serve different subsets of recently-edited ways. When the fetch happens to
omit the southbound carriageway, `hasAntiParallelOneWayNeighbor` has
nothing to trip on and the resolver returns a confident
`OneWay(legalBearing = 11.23°)` — the *wrong* carriageway. The observed
southbound flow then "conflicts" with that bearing and the report is
REJECTED with "Conflicting direction evidence for this street" before any
vehicle is scored. When the fetch is complete, the guard trips and the
resolver returns `Unknown`. Established as data-completeness, not resolver
logic, by a 2026-08-30 spike (fed the full real capture, the real resolver
correctly returns `Unknown`). Observed on this road four times
(`649b9a` plus these three, which share a bucket so really one more bad
fetch).

**2. The confirmation path is gated on `OneWay`.** The shipped
stationary-approach fallback (`ReportAnalysisJob.tryStationaryApproachDetection`,
2026-08-30 plan) is the only mechanism that can currently confirm these
three reports — it needs no world bearing, only a verified-stationary
camera, a vehicle whose bbox grew sustainedly, and ≥3 others receding. Its
production replay confirmed all three pass every internal gate (growth
1.52 / 2.24 / 0.93, frames 72 / 80 / 34, detection 0.89 / 0.89 / 0.78,
≥3 shrinking each). But `ReportAnalysisJob.kt:185` gates it on
`resolution is DirectionResolution.OneWay`. The stationary-approach *spec*
gated on `!is TwoWay` (which includes `Unknown`); the final whole-branch
review narrowed the shipped code to `is OneWay` on the stated basis that
"the 3 target reports resolve to `OneWay` in current *stale* production."

### Why the two halves ship together

That basis evaporates the moment failure 1 is fixed. Today the reports are
a coin flip: incomplete fetch → `OneWay(11.23°)` → approach path fires →
CONFIRMED; complete fetch → `Unknown` → approach path blocked → REJECTED.
**Fixing the Overpass fetch alone converts the coin flip into a reliable
REJECTED** — the union will reliably restore the southbound carriageway,
the anti-parallel guard will reliably trip, and the street will reliably
resolve to `Unknown`, which the `is OneWay` gate reliably blocks.

So Part A (make resolution reliable → `Unknown` on this road) and Part B
(let the approach path fire on the *right kind* of `Unknown`, with
corroboration) are one change.

## Goal

A divided one-way carriageway resolves deterministically to `Unknown` with
a recorded reason, and a stationary-camera wrong-way approach on such a road
is CONFIRMED from bbox-scale evidence and coherent receding-flow
corroboration — without opening the far more common "untagged street"
`Unknown` to false positives.

## Scope decisions (confirmed with the user — do not re-litigate)

- **Part A — stop trusting a single Overpass fetch.** Query multiple
  independent Overpass endpoints sequentially and union the ways, so one
  stale replica cannot determine the result. (Two alternatives —
  confirmation re-fetch only before returning `OneWay`; retry one endpoint
  until two responses agree — were rejected in the prior brainstorming as
  more complex for less coverage.)
- **Part A — a single un-cross-checked source must not assert a confident
  `OneWay`.** Fewer than two endpoints answering downgrades `OneWay` to
  `Unknown`.
- **Part A — do NOT try to pick the correct carriageway.** The union +
  anti-parallel guard → `Unknown` is the deliberate outcome. A "choose the
  carriageway the report sits on" heuristic is out of scope: it is fragile
  on 9–12 m geometry, and it would not help anyway — the riders' bearings
  are perspective-understated (759cd measured 210° vs a true ~13°), so the
  bearing-based path cannot confirm these reports regardless of which legal
  bearing OSM supplies. The scale-trend path is what confirms them.
- **Part B — gate widening is "Both":** tag the `Unknown` downgrade reason
  AND require clip-flow corroboration on the divided-carriageway branch.
- **Cache — TTL + truncate on deploy** (Part A) and a persisted
  `unknown_reason` (Part B), one Flyway migration.
- **Add Overpass per-endpoint response logging** — the 2026-08-17 `649b9a`
  note explicitly calls for it before the next occurrence can be proven.
- **Sequential endpoint queries.** Analysis is async; latency is a
  non-issue. Parallelising is a later micro-optimisation, out of scope.
- **No retroactive re-analysis** of already-submitted reports — applies to
  future analysis and TTL-triggered re-resolution only, as with every
  change in this project's history.

---

## Part A — Multi-source Overpass resolution

### `OverpassClient`

`findNearbyWays(lat, lon, radiusMeters)` returns a result type instead of a
bare `List<OverpassElement>`:

```kotlin
data class OverpassResult(
    /** Ways unioned across every endpoint that answered, deduped by way id. */
    val ways: List<OverpassElement>,
    /** How many configured endpoints returned a usable HTTP response. */
    val sourceCount: Int,
)
```

Behaviour:

- Iterate `osmProperties.overpassBaseUrls` **in sequence**. Issue today's
  query to each (`[out:json]; way(around:R,lat,lon)["highway"]; out geom;`),
  each call wrapped in `withOsmRetry` with a **reduced per-endpoint attempt
  count** (`osmProperties.overpassPerEndpointAttempts`, default `1`) —
  cross-endpoint redundancy replaces most same-endpoint retrying and bounds
  worst-case latency.
- A per-endpoint failure (after its own retries) is caught, logged at WARN
  with the endpoint host and reason, and skipped — it does not fail the
  whole call.
- **Union** the ways from every endpoint that answered, deduped by
  `way.id`. On the rare same-id / different-geometry case, keep the element
  with more `geometry` points.
- `sourceCount` = number of endpoints that returned an HTTP 200 response,
  including an `elements: []` body (a real statement that there is nothing
  there).
- **All endpoints fail** → throw `OsmLookupException` (retryable), exactly
  as a single-endpoint total failure does today → `LookupFailed` upstream.

**Logging (new).** For each endpoint: one INFO line — endpoint host, HTTP
status, way count, the list of way ids. At DEBUG, the raw response body.
Resolutions are cached and bucketed, so this is a trickle (one burst per
uncached bucket).

**RestClient wiring.** `OsmClientConfig` builds one `overpassRestClient`
bean from a single base URL today. Replace it with a base-URL-less
`RestClient` (same Jackson converter, timeouts, User-Agent header) that
`OverpassClient` calls with absolute URIs from the configured list. One
client, N URLs — simpler than N beans.

### `OsmProperties` / config

- `overpassBaseUrl: String` → `overpassBaseUrls: List<String>`, default:
  ```
  https://overpass-api.de/api/interpreter
  https://overpass.kumi.systems/api/interpreter
  https://overpass.private.coffee/api/interpreter
  ```
- New `overpassPerEndpointAttempts: Int = 1`.
- New `cacheTtlDays: Long = 30`.
- `application.yml` and `application-local.yml.example` updated to match.
  **Any deployment override of the old `app.osm.overpass-base-url` key must
  be migrated to the list form** — called out in the plan and the deploy
  notes. The VPS `.env` / compose files are not in git; the deploy runbook
  must check for an override there.

### `StreetDirectionResolver.resolveFresh`

After candidate selection and both existing guards produce `resolution`
from the unioned ways (selection logic and guard constants unchanged):

```kotlin
if (resolution is DirectionResolution.OneWay && overpass.sourceCount < 2) {
    logger.warn(
        "Overpass OneWay from a single un-cross-checked source at {},{} - downgrading to Unknown",
        lat, lon,
    )
    return DirectionResolution.Unknown(streetName, UnknownReason.NOT_CROSS_CHECKED)
}
```

`Unknown` here means "lean on video/history evidence instead of an OSM tag";
a missed detection beats a false one, and this repo is false-positive-averse.

### Cache TTL

Inject a `java.time.Clock` (replacing the bare `OffsetDateTime.now()` in
`persist`, and used in `resolve`). In `resolve()`, a cache row whose
`updatedAt` is older than `osmProperties.cacheTtlDays` is treated as a miss
— re-resolve and overwrite. The existing radius/accuracy cache-hit
conditions are unchanged; the TTL is an additional gate. This makes any
future poisoned row self-heal.

---

## Part B — `Unknown` reason + approach detection on divided carriageways

### `DirectionResolution.Unknown` carries a reason

```kotlin
enum class UnknownReason {
    /** A way was found but carries no (recognized) `oneway` tag. */
    NO_ONEWAY_TAG,
    /** Two different named streets are within `accuracy` of each other — the
        nearest street itself is ambiguous. */
    AMBIGUOUS_NEAREST_STREET,
    /** The chosen one-way way has an anti-parallel one-way neighbour within
        30 m — a divided one-way carriageway pair; the tag is sound, only the
        carriageway is undetermined. */
    DIVIDED_CARRIAGEWAY,
    /** A OneWay result came from a single un-cross-checked Overpass source
        (Part A). */
    NOT_CROSS_CHECKED,
}

data class Unknown(
    val streetName: String?,
    val reason: UnknownReason = UnknownReason.NO_ONEWAY_TAG,
) : DirectionResolution()
```

The default keeps every existing `Unknown(streetName)` construction site and
test compiling. `resolveFresh` sets the reason explicitly at each of its
three `Unknown` return points:

| `resolveFresh` site | reason |
|---|---|
| two equidistant different-named streets (`…distanceMeters < accuracyMeters`) | `AMBIGUOUS_NEAREST_STREET` |
| `oneway` tag absent / unrecognized (`else` branch) | `NO_ONEWAY_TAG` |
| `hasAntiParallelOneWayNeighbor(best, candidates)` true | `DIVIDED_CARRIAGEWAY` |
| single-source `OneWay` downgrade (Part A) | `NOT_CROSS_CHECKED` |

### Cache persists the reason

`osm_lookup_cache` gains a nullable `unknown_reason VARCHAR(32)` column
(CHECK-constrained to the enum names, mirroring `direction_state`).
`persist` writes `(resolution as? Unknown)?.reason?.name`; `toDirectionResolution`
reads it back, defaulting a NULL (pre-migration rows, or non-`UNKNOWN`
states) to `NO_ONEWAY_TAG`. This matters because the three target reports
share a bucket — only the first re-resolves fresh post-deploy; reports 2
and 3 must get `DIVIDED_CARRIAGEWAY` from the cache too.

### Flyway migration `V11__add_unknown_reason_and_reset_osm_cache.sql`

```sql
ALTER TABLE osm_lookup_cache ADD COLUMN unknown_reason VARCHAR(32);
ALTER TABLE osm_lookup_cache ADD CONSTRAINT osm_lookup_cache_unknown_reason_check
    CHECK (unknown_reason IN ('NO_ONEWAY_TAG','AMBIGUOUS_NEAREST_STREET','DIVIDED_CARRIAGEWAY','NOT_CROSS_CHECKED'));
-- Every row re-resolves through the new multi-endpoint path on next use;
-- this clears the known-bad OneWay(11.23) rows for the خیبان جناح bucket
-- immediately rather than waiting out the 30-day TTL.
TRUNCATE TABLE osm_lookup_cache;
```

### `ReportAnalysisJob` — widened gate with corroboration

Replace the `determineOutcome` tail (`ReportAnalysisJob.kt:185`):

```kotlin
if (outcome.status == ReportStatus.REJECTED) {
    val approachEligible = when (resolution) {
        is DirectionResolution.OneWay -> true
        is DirectionResolution.Unknown ->
            resolution.reason == UnknownReason.DIVIDED_CARRIAGEWAY &&
                hasCoherentRecedingFlow(flowVehicles)
        else -> false
    }
    if (approachEligible) {
        tryStationaryApproachDetection(report, analysis, orientationTimeline, streetName)
            ?.let { return it }
    }
}
return outcome
```

`flowVehicles` is already computed above in `determineOutcome` (line ~148)
and is in scope here.

**`hasCoherentRecedingFlow`** — the corroboration gate, divided-carriageway
branch only:

```kotlin
/**
 * True when the qualified vehicles form one coherent directional stream:
 * the strongest corridor consensus (no candidate excluded) has at least
 * [AnalysisProperties.approachCorroborationMinMembers] members and a
 * resultant length at or above the existing consensus R-gate.
 *
 * This is a "the scene is a real one-way road with directional traffic"
 * check, NOT a bearing-opposition check on the grower: the grower's
 * frame-relative bearing is perspective-understated by construction (that
 * is why the scale-trend path exists), so requiring it to oppose the
 * consensus by >90 deg would reject exactly the true positives this path
 * is for (759cd: rider 210 deg vs flow ~285 deg = 75 deg apart).
 */
private fun hasCoherentRecedingFlow(flowVehicles: List<FlowVehicle>): Boolean {
    val consensus = flowVehicles.map { it.corridorId }.distinct()
        .mapNotNull { clipFlowAnalyzer.corridorConsensus(flowVehicles, it, excluding = null) }
        .maxByOrNull { it.clipConfidence }
        ?: return false
    return consensus.memberCount >= analysisProperties.approachCorroborationMinMembers &&
        consensus.resultantLength >= analysisProperties.consensusMinResultantLength
}
```

`corridorConsensus` already enforces `resultantLength >=
consensusMinResultantLength` internally (returns null otherwise), so the
explicit R check is belt-and-suspenders / self-documenting.

### `AnalysisProperties` / config

- New `approachCorroborationMinMembers: Int = 2` under `app.analysis`.
  Rationale: the scale-based `shrinking >= 3` gate inside
  `tryStationaryApproachDetection` is a separate, already-strict receding
  check; this adds "and ≥2 of the qualified, bearing-resolved vehicles
  agree on a single flow direction (R ≥ 0.6)", which rules out an
  intersection, a parking maneuver, or a camera that catches *both*
  carriageways (bimodal bearings → the R-gate returns no consensus).
- `application.yml` updated.

### Evidence JSON

`ApproachEvidenceBreakdown` gains two fields so a divided-carriageway
confirmation is self-explaining in the stored `direction_evidence`:

```kotlin
internal data class ApproachEvidenceBreakdown(
    val method: String = "stationary_approach",
    val resolutionState: String,          // "ONE_WAY" | "UNKNOWN_DIVIDED_CARRIAGEWAY"
    val recedingCount: Int,
    val strongGrowerCount: Int,
    val corroborationConsensusMembers: Int?,   // null on the OneWay branch
    val growthFraction: Double,
    val trackFrames: Int,
    val detectionConfidence: Double,
    val confirmationThreshold: Double,
)
```

`tryStationaryApproachDetection` takes the extra context it needs
(`resolution`, and the corroboration consensus member count) as parameters.

---

## Will this confirm 759cd / 24908 / a5275?

**Yes — with one tuning risk to verify on the production replay.**

Trace, post-change, for `759cd` (the other two are stronger — 24908 has 10
shrinking, a5275 is confirmed today from the 34-frame fragment):

1. Overpass union restores the southbound carriageway →
   `hasAntiParallelOneWayNeighbor` trips →
   `Unknown(streetName, DIVIDED_CARRIAGEWAY)`. Deterministic now.
2. `sourceCount >= 2` (three endpoints configured), so no `NOT_CROSS_CHECKED`
   confusion.
3. Video analysis runs; `wasStationaryThroughout()` is true (all
   `location_samples` speed 0).
4. `qualifyVehicles` produces `flowVehicles` for the receding traffic; a
   `CorridorConsensus` at ~285° exists (the backlog records this consensus
   — it is what currently "conflicts" with OSM 11°).
5. `buildOutcome` → REJECTED (no bearing-based candidate; the rider's 210°
   is only ~74° off the ~285° flow, inside `wrongWayToleranceDegrees` of
   the *legal* direction, not the illegal one).
6. Widened gate: `resolution` is `Unknown(DIVIDED_CARRIAGEWAY)` **and**
   `hasCoherentRecedingFlow` → needs the strongest consensus to have
   `memberCount >= 2`. **Tuning risk:** with only 3 shrinking vehicles and
   the consensus excluding none, member count should be ~3 — but "shrinking
   by bbox scale" and "qualified as a `FlowVehicle`" are not the same set
   (a shrinking vehicle may fail `MIN_TRACK_FRAMES` or corridor assignment;
   a `FlowVehicle` may be scale-`flat`). The plan's production-replay step
   MUST print the actual `flowVehicles` count and strongest-consensus
   `memberCount` for all three reports and confirm `>= 2`. If 759cd comes
   in at 1, drop `approachCorroborationMinMembers` to `1` (still requires a
   real R ≥ 0.6 consensus to exist) or widen the corroboration to count
   scale-`shrinking` vehicles directly.
7. `tryStationaryApproachDetection` runs with `resolution` known: grower
   growth 1.52 ≥ 0.8, 72 frames ≥ 30, detection 0.89 ≥ 0.5, shrinking 3 ≥ 3,
   3 ≥ 3×1 → `best.detectionConfidence 0.89 ≥ 0.5` → **CONFIRMED**,
   `wrongWayConfidence 0.89`, `resolutionState =
   "UNKNOWN_DIVIDED_CARRIAGEWAY"`.

`50bcc6` (violator drives *past* the camera → grows-then-shrinks → scale
`flat`) and `71f78` (moving camera, GPS 1.19 m/s →
`wasStationaryThroughout()` false) remain out of reach — deferred to the
future clip-flow-relative bearing design, as before.

**False-positive exposure added by Part B:** an `Unknown` street can now
reach the approach path, but only when (a) OSM found two anti-parallel
`oneway` carriageways within 30 m — a strong structural signal that this
*is* a one-way road — and (b) the clip's own qualified traffic forms one
coherent directional stream, and (c) every pre-existing
`tryStationaryApproachDetection` gate still passes (verified-stationary
camera, sustained bbox growth ≥ 0.8 over ≥ 30 frames, ≥3 receding,
receding ≥ 3× growers, detection ≥ threshold). An untagged residential
street resolves to `Unknown(NO_ONEWAY_TAG)` and is still excluded outright.

---

## Testing

### `OverpassClientTest`

- All configured endpoints queried; ways unioned and deduped by id
  (A → {1,2}, B → {2,3} ⇒ {1,2,3}).
- Same id from two endpoints, differing geometry → element with more nodes
  kept.
- Partial failure: endpoint A → HTTP 500 (after its retry), B → ways ⇒
  result is B's ways, `sourceCount == 1`.
- All endpoints fail → `OsmLookupException`.
- `sourceCount` counts an endpoint that returns `elements: []`.
- Infra: 2–3 WireMock servers, one per endpoint, plus the shared Nominatim
  stub; `@DynamicPropertySource` wires `app.osm.overpass-base-urls` to the
  WireMock ports.

### `StreetDirectionResolverTest`

- **Stale-replica reproduction (the test that would have caught prod):**
  endpoint A serves the full real `759cd` capture (new fixture
  `fixtures/overpass-khayaban-e-jinnah-report-759cd.json`, both
  carriageways); endpoint B serves it trimmed to drop the southbound ways
  (`…-759cd-stale.json`). Resolve the `759cd` coordinates ⇒
  `Unknown(reason = DIVIDED_CARRIAGEWAY)` (union restores the carriageway,
  guard trips).
- **Single-source downgrade:** only endpoint A responds, clean
  single-carriageway `oneway` way, no anti-parallel neighbour ⇒
  `Unknown(reason = NOT_CROSS_CHECKED)`, not `OneWay`.
- **Cross-check success:** two endpoints return that same clean data ⇒
  `OneWay` (confident) — proves the downgrade is about cross-checking, not
  the data.
- **Reason mapping:** unit-level — no `oneway` tag ⇒ `NO_ONEWAY_TAG`; two
  equidistant different streets ⇒ `AMBIGUOUS_NEAREST_STREET`.
- **Cache round-trips the reason:** resolve a `DIVIDED_CARRIAGEWAY` bucket
  (row written with `unknown_reason = 'DIVIDED_CARRIAGEWAY'`); second
  resolve within TTL hits cache and still returns
  `Unknown(DIVIDED_CARRIAGEWAY)` with no Overpass round.
- **Cache TTL:** resolve (row written); advance the injected `Clock` past
  `cacheTtlDays`; resolve again ⇒ a second Overpass round occurs
  (`verify(2, …)`), `updatedAt` refreshes. Within TTL still hits cache
  (existing "caches the resolution" test, updated to inject a fixed
  `Clock`, stays green).
- The existing `649b9a` divided-carriageway test still passes — its fixture
  served to every endpoint unions to the same data and yields
  `Unknown(DIVIDED_CARRIAGEWAY)`.

### `ReportAnalysisJobTest` (or `ReportAnalysisIntegrationTest`)

- `resolution = Unknown(DIVIDED_CARRIAGEWAY)`, stationary camera, one strong
  grower, 3 shrinking, a coherent ~285° consensus with ≥2 members ⇒
  CONFIRMED, `directionEvidence.method == "stationary_approach"`,
  `resolutionState == "UNKNOWN_DIVIDED_CARRIAGEWAY"`.
- Same, but `resolution = Unknown(NO_ONEWAY_TAG)` ⇒ REJECTED (gate excludes
  it) — the untagged-street guard.
- Same divided-carriageway setup, but the qualified vehicles' bearings are
  dispersed / no consensus (`hasCoherentRecedingFlow` false) ⇒ REJECTED.
- Same, but `resolution = TwoWay` ⇒ REJECTED (unchanged).
- Existing `OneWay` → approach → CONFIRMED test still passes with the
  `resolutionState == "ONE_WAY"` / `corroborationConsensusMembers == null`
  fields.

### Regression

- Every existing `StreetDirectionResolverTest` / `OverpassClientTest` case
  passes under multi-URL config (one configured URL, or all URLs returning
  identical data, behaves exactly as the single-endpoint code did).
- `ReportAnalysisIntegrationTest` hardcoded values rescaled as needed.
- `./gradlew test` green.

### Production verification (post-deploy)

1. Replay (or re-submit) a report at the `759cd` coordinates.
   `direction_evidence` should show the OSM source as
   `Unknown / DIVIDED_CARRIAGEWAY`, and the outcome CONFIRMED via
   `stationary_approach` with `resolutionState =
   "UNKNOWN_DIVIDED_CARRIAGEWAY"`.
2. Confirm `OverpassClient` INFO logs carry per-endpoint way counts/ids.
3. Confirm the printed `flowVehicles` count and strongest-consensus
   `memberCount` for 759cd / 24908 / a5275 — the tuning check for
   `approachCorroborationMinMembers` (see "Will this confirm…" step 6).
4. Sanity-check log volume — one INFO burst per uncached resolution;
   resolutions are cached and bucketed, so a trickle.

---

## Non-goals

- **Choosing the correct carriageway** on a divided road — see scope
  decisions; the union → `Unknown` outcome is deliberate and the
  bearing-based path cannot confirm these reports anyway.
- **`corridor_cohesion` under-confirmation** (`50bcc6` / `71f78`) — its own
  backlog item and spec.
- **Clip-flow-relative bearing ("B")** — the larger change that would cover
  the moving-camera and violator-passes-camera cases; deferred.
- **Self-hosting Overpass** with a regional extract — the mirror-union
  approach is the deliberate choice; self-hosting stays a fallback if the
  public mirrors prove collectively unreliable.
- **Parallel endpoint queries** — sequential is fine for async analysis.
- **Conflict-driven cache invalidation** — TTL + truncate covers the need
  without coupling `ReportAnalysisJob` to the cache.
- **Retroactive re-analysis** of already-submitted reports.
- **Tuning the anti-parallel guard's 30 m / 45° constants** — the spike
  showed they are correct for this geometry; the problem was missing input
  data.
