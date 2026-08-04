# OSM Lookup Retry and Contested-Corridor OSM Override Design

## Context

A real report (`9fd4fea9-3a9e-4cee-a91b-bb33e924f47d`) on a confirmed, genuine
one-way street (`خیبان جناح` / Khayaban-e-Jinnah, OSM way `726823668`,
`highway=secondary`, `oneway=yes`, 4.3 meters from the report's GPS point) was
rejected with `"Legal traffic direction could not be established for this
street"` - despite the clip containing a visually unambiguous wrong-way
motorcyclist (confirmed by extracting and inspecting the actual video frames:
the rider is shown facing directly into the camera while every other nearby
vehicle recedes in the normal flow direction, on the same carriageway, no
median).

Root-caused via direct investigation (repo-code reading plus live Nominatim/
Overpass queries against the report's exact coordinates) to two independent,
compounding bugs:

1. **No retry on OSM lookup failures.** `OverpassClient.findNearbyWays()` and
   `NominatimClient.reverseGeocode()` each make a single HTTP attempt; any
   transient failure (the public Overpass instance returned an HTTP 504 on
   the very first live query made during this investigation, succeeding only
   on retry) propagates as `OsmLookupException` ->
   `DirectionResolution.LookupFailed`. `LookupFailed` is deliberately never
   cached (a documented, correct choice - a transient outage must not poison
   the cache with a wrong answer) - but today there is also no in-request
   retry, so a single flaky call permanently loses that report's only chance
   at OSM evidence. Confirmed via `osm_lookup_cache`: zero rows exist for
   this report's lat/lon bucket, which only happens on the `LookupFailed`
   path (`NotFound`/`Unknown`/`TwoWay`/`OneWay` are all cached).
2. **A contested corridor discards OSM evidence entirely, even when strong.**
   `ReportAnalysisJob.evaluateCandidates()` skips any candidate outright
   (`continue`, before `directionEvidenceResolver.fuse()` is ever called)
   whenever its corridor has other members but no elected consensus - which
   is exactly what a one-way street's corridor looks like whenever both
   normal traffic and a wrong-way vehicle appear in the same clip (corridors
   are deliberately direction-agnostic spatial clusters, per the
   2026-08-02 corridor-cohesion fix). This gate was added to protect a
   legally-flowing vehicle on a divided two-way road that got merged into one
   corridor - but that scenario is already fully covered by (a) the earlier
   `DirectionResolution.TwoWay` early-return when OSM confidently says
   two-way, and (b) `DirectionEvidenceResolver.fuse()`'s own "zero survivors
   -> insufficient" behavior when no evidence source is available. The gate's
   only remaining effect is to discard a *confident* OSM one-way tag whenever
   the clip also contains normal traffic in the same corridor as the
   violator - i.e. the common case, not an edge case.

   **Important nuance found while preparing the implementation plan:**
   `ReportAnalysisJobTest.kt` already has a deliberately-written test -
   `candidate in a contested (bimodal) corridor is skipped, never falsely
   confirmed` - covering a *different*, genuinely-ambiguous scenario: three
   vehicles in one corridor, bearings spread evenly ~120 degrees apart
   (270, 30, 150), no real majority anywhere. One of them happens to exactly
   match the OSM-derived illegal bearing by pure coincidence - not because
   its direction is corroborated by any other observed vehicle. A blanket
   "always let OSM through when consensus is null" fix would flip this
   specific vehicle to CONFIRMED, reintroducing the exact false positive
   this test was written to prevent. The real report's case is different in
   a way that matters: track 319 (the confirmed wrong-way motorcycle) had
   four other corridor members within 1.7-33.9 degrees of its own bearing -
   a real, populated opposing direction, not a lone coincidence. The fix
   (below) distinguishes these two cases directly instead of erasing the
   distinction.

Both bugs are independently real and compounding: bug 2 alone would still
have blocked this report even if bug 1's retry had recovered the OSM
evidence in time for a *different*, quieter corridor; bug 1 alone explains
why *no* OSM evidence reached the pipeline at all for this specific report.
Fixing both closes the gap for this report and for the general case.

## Scope & explicit decisions (confirmed with the user - do not re-litigate)

- **Fix both bugs together in one plan** (user's explicit choice - "fix
  both" - after the two were presented as separable).
- **Retry: a small manual retry loop, not a library.** No retry
  infrastructure (Spring Retry, resilience4j) exists anywhere in this
  codebase today; introducing one for a two-call-site problem is more than
  this needs. A shared helper used by both `OverpassClient` and
  `NominatimClient` keeps the two call sites from duplicating the loop.
- **Retry only transient failures.** A `RestClientException` with no HTTP
  response (connection refused, timeout) or a 5xx `RestClientResponseException`
  is retried; a 4xx is not (a malformed request is a real bug, not a
  transient condition - retrying would only mask it).
- **3 attempts total, 500ms fixed delay**, both configurable via new
  `OsmProperties` fields (`lookupRetryAttempts`, `lookupRetryDelayMs`),
  matching every other tuning knob in that class already having a sane
  default. This runs inside the existing `@Async` analysis job, never on the
  report-submission request/response path, so the added worst-case latency
  (~1-2 extra seconds across retries) has no user-facing effect.
- **Contested-corridor fix: gate the bypass on peer support, not a blanket
  removal.** The block at `ReportAnalysisJob.kt:189-192`
  (`if (consensus == null && hasOtherCorridorMembers) continue`) is replaced
  with a peer-support check: a contested-corridor candidate only proceeds to
  fusion when at least one OTHER corridor member's bearing is within
  `agreementToleranceDegrees` (the existing 45-degree tuning knob already
  used by `movesWith()` - no new config needed) of the candidate's OWN
  bearing. This distinguishes "a real, populated opposing direction" (our
  report's case: 4 peers within 1.7-33.9 degrees) from "a lone coincidental
  match with no supporting pattern" (the existing test's case: pairwise
  120-degree separation between all three vehicles, no peer within
  tolerance for any of them) - verified against both scenarios' actual
  numbers before finalizing this approach. `movesWith(candidate, consensus)`
  immediately below is unaffected (still guarded by `consensus != null &&`).
- **No changes to corridor clustering itself** (`corridors.py`,
  `corridor_cohesion()`) - direction-agnostic clustering is correct and
  intentional (see the 2026-08-02 fix); this plan only changes how the
  *absence* of a clip-consensus signal is handled downstream.
- **No changes to `DirectionEvidenceResolver.fuse()`** - it already handles
  "OSM alone survives" and "zero survivors" correctly; the bug was entirely
  in `evaluateCandidates()` preventing candidates from ever reaching it.

## The fixes

### Fix 1: retry helper

A small shared function, used identically by both clients:

```kotlin
private fun <T> withOsmRetry(properties: OsmProperties, call: () -> T): T {
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

`OsmLookupException` gains an `isRetryable: Boolean` constructor parameter
(default `true`, matching the existing "no HTTP response at all" case), set
`false` specifically when the wrapped cause is a `RestClientResponseException`
with a non-5xx (i.e. 4xx) status. `OverpassClient.findNearbyWays()` and
`NominatimClient.reverseGeocode()` each wrap their existing try/catch body in
`withOsmRetry(osmProperties) { ... }` - the catch blocks themselves are
unchanged (they already produce the correctly-classified `OsmLookupException`
for `withOsmRetry` to inspect).

`OsmProperties` gains:

```kotlin
var lookupRetryAttempts: Int = 3,
var lookupRetryDelayMs: Long = 500,
```

### Fix 2: contested-corridor peer-support override

`ClipFlowAnalyzer.kt` gains a new function, alongside `corridorConsensus()`/
`movesWith()`:

```kotlin
/**
 * True when at least one OTHER member of [candidate]'s corridor has a bearing within
 * agreement tolerance of [candidate]'s own - i.e. the candidate's specific direction is
 * corroborated by a real peer, not a lone coincidental bearing in an otherwise scattered
 * corridor. Used when the corridor's overall consensus is unavailable (bimodal/dispersed)
 * to decide whether independent evidence (OSM tag, learned history) is still safe to trust
 * for this specific candidate.
 */
fun hasPeerSupport(flowVehicles: List<FlowVehicle>, candidate: FlowVehicle): Boolean =
    flowVehicles.any {
        it.corridorId == candidate.corridorId &&
            it !== candidate &&
            BearingMath.angularDifferenceDegrees(it.absoluteBearingDegrees, candidate.absoluteBearingDegrees) <=
                properties.agreementToleranceDegrees
    }
```

`ReportAnalysisJob.kt`'s `evaluateCandidates()` - replace:

```kotlin
val hasOtherCorridorMembers = flowVehicles.any { it.corridorId == candidate.corridorId && it !== candidate }
if (consensus == null && hasOtherCorridorMembers) {
    continue
}
```

with:

```kotlin
if (consensus == null && !clipFlowAnalyzer.hasPeerSupport(flowVehicles, candidate)) {
    continue
}
```

The surrounding docstring (the `evaluateCandidates()` KDoc, currently
describing "a candidate moving WITH its own corridor's consensus is never a
violator") is rewritten to describe the corrected behavior: a contested
corridor contributes no *overall* clip-consensus evidence, but a candidate
whose own direction is corroborated by at least one real peer still reaches
fusion using independent evidence (OSM tag, learned history) - a candidate
is only ever confirmed when some evidence source clears fusion and the
candidate's own bearing matches the resulting illegal direction within
tolerance. A true lone outlier (no peer within tolerance, matching the
existing test's synthetic scenario) is still skipped exactly as today.

## Edge cases

- **Contested corridor, no OSM tag, no history evidence** - `fuse()` receives
  zero evidence sources, returns `Insufficient(conflict = false)`, same
  "Legal traffic direction could not be established" outcome as today. No
  regression.
- **Contested corridor, candidate is a true lone outlier (no peer within
  `agreementToleranceDegrees`)** - `hasPeerSupport` returns `false`, the
  candidate is skipped exactly as today. This is what keeps the existing
  "never falsely confirmed" test passing unchanged.
- **Contested corridor, candidate has real peer support, but no OSM tag and
  no history evidence either** - the candidate reaches fusion, gets zero
  evidence sources, `Insufficient(conflict = false)` - correctly still
  produces no verdict, since peer support alone (without OSM or history) was
  never itself usable as evidence in this design (it only decides whether to
  attempt fusion, not what fusion concludes).
- **Contested corridor, OSM says two-way** - never reaches
  `evaluateCandidates()` at all; the existing `DirectionResolution.TwoWay`
  early-return in `determineOutcome()` fires first, unchanged.
- **Retry exhausts all attempts** - `OsmLookupException` propagates exactly
  as it does today (single-attempt failure); `StreetDirectionResolver`'s
  existing `catch (ex: OsmLookupException) -> LookupFailed` path and its
  "never cache `LookupFailed`" behavior are both unchanged.
- **4xx response** - never retried, fails on the first attempt, same
  latency as today for this case.
- **A future report on a road OSM has no data for at all** - unaffected by
  either fix; `NotFound`/`Unknown` still result in no OSM evidence, exactly
  as designed (already noted in `docs/improvements-backlog.md` as a
  separate, known limitation).

## Testing

- New test(s) for the retry helper (either a dedicated
  `OsmRetryTest`/inline in `OverpassClientTest`/`NominatimClientTest` -
  implementer's call given the existing test file layout): a stubbed
  `RestClient` failing with a 503 once then succeeding on the second call
  asserts the final result is the successful one; a 400 on the first call
  asserts an immediate throw with the mock invoked exactly once (no retry);
  failing on every attempt asserts `OsmLookupException` after exactly
  `lookupRetryAttempts` calls.
- `ClipFlowAnalyzerTest`: new cases for `hasPeerSupport()` - a candidate with
  a corridor peer within tolerance returns `true`; a candidate whose only
  corridor peers are all beyond tolerance (mirroring the existing
  `ReportAnalysisJobTest` 120-degrees-apart scenario's numbers) returns
  `false`; a candidate alone in its corridor (no other members at all)
  returns `false`.
- `ReportAnalysisJobTest`: a new case - a corridor with several vehicles
  moving with a confident OSM legal bearing plus one moving against it
  *with at least one corridor peer supporting its own bearing* (mirroring
  the real report's numbers: peers within 1.7-33.9 degrees), corridor
  consensus unavailable (bimodal), OSM evidence present and confident -
  asserts the against-bearing vehicle is now evaluated and can reach
  `CONFIRMED` (today this whole corridor is skipped before fusion ever
  runs). A second case: same contested-corridor setup but no OSM evidence
  and no history evidence - asserts the outcome is still "insufficient,"
  confirming no regression. The existing
  `candidate in a contested (bimodal) corridor is skipped, never falsely
  confirmed` test is left unchanged and must continue to pass exactly as
  written - it is the regression guard for the "no peer support" case.
- Manual verification: resubmit the real clip from report `9fd4fea9` (or an
  equivalent one-way-street clip with mixed correct/wrong-way traffic in one
  corridor) against the fixed, redeployed server and confirm it now reaches
  a real verdict instead of "Legal traffic direction could not be
  established for this street."
