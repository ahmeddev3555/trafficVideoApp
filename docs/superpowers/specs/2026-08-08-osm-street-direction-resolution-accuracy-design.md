# OSM Street/Direction Resolution Accuracy - Design

## Context

Two open items from `docs/improvements-backlog.md`'s "Location / GPS accuracy"
section, combined into one design because they touch the same resolution
pipeline (`server/src/main/kotlin/com/trafficwatch/server/geo/StreetDirectionResolver.kt`
and its Overpass query):

1. **The server searches a flat 50m Overpass radius regardless of a report's
   GPS accuracy.** A submitted report with 37.7m accuracy once resolved to
   the wrong OSM street ("Street 4", a small residential road) instead of
   the actual road in the video ("Khayaban-e-Jinnah", a major arterial) -
   the true position was ~56m from the reported point, outside the fixed
   50m search radius. (The client-side confirm-location step shipped
   2026-08-07 substantially reduces how often this recurs going forward,
   since a user now corrects weak-accuracy points before submission, but
   the server-side gap itself remains unaddressed.)
2. **A divided-carriageway one-way road can be misjudged as a wrong-way
   violation on the far carriageway.** A physically divided road (opposite
   legal directions on separate carriageways) is mapped in OSM as two
   separate ways, each tagged `oneway=yes` - not one `oneway=no` way -
   so `StreetDirectionResolver` picks whichever carriageway is nearest and
   confidently returns `OneWay`, never signaling the ambiguity. Combined
   with the 2026-08-04 contested-corridor peer-support fix, this creates a
   real risk of confirming a legally-driving motorist on the far
   carriageway as a wrong-way violator, with their plate captured.

## Key finding that shapes this design

Widening the search radius alone does not fix item 1's diagnosed case: the
resolver always picks whichever candidate way is *nearest* to the reported
point. Street 4 was apparently closer to the point than Khayaban-e-Jinnah,
so even a wider search that included Khayaban-e-Jinnah as a candidate would
still have picked Street 4 as "nearest." Radius widening only helps a
different failure mode: reports that currently get `NotFound` because
nothing at all is within 50m. Fixing the diagnosed case's actual mechanism
requires the resolver to recognize when it *can't confidently tell* which
of two nearby candidates is the true nearest one, given the report's own
GPS uncertainty - not just search wider.

## Architecture overview

Both fixes live entirely inside `StreetDirectionResolver` and its supporting
classes - no new services, no new external API calls (the divided-carriageway
check reuses the same Overpass response already fetched for the primary
lookup). Three changes:

1. `resolve()` takes the report's `accuracy` and scales the Overpass search
   radius from it.
2. Way selection stops being a naive "pick the single nearest" - it becomes
   ambiguity-aware (GPS uncertainty between two different streets) and
   carriageway-aware (divided-road uncertainty on the same street).
3. The cache gains a stored search radius, so a result cached from a
   narrow, high-accuracy search is never wrongly served to a later
   low-accuracy report that would need a wider search to find the real
   street.

## Radius scaling + ambiguity-aware selection

`resolve(latitude, longitude, accuracyMeters)` computes:

```
searchRadius = clamp(accuracyMeters * radiusAccuracyMultiplier, searchRadiusMeters, maxSearchRadiusMeters)
```

with `radiusAccuracyMultiplier = 2.0`, `searchRadiusMeters = 50.0` (today's
existing floor, unchanged), `maxSearchRadiusMeters = 200.0` (a cap, to bound
Overpass query cost/latency for very poor GPS fixes) - all three
configurable via `OsmProperties`, consistent with the existing config-driven
style.

`OverpassClient.findNearbyWays(lat, lon, radiusMeters)` takes the computed
radius as a parameter instead of reading `OsmProperties.searchRadiusMeters`
internally.

Way selection changes from "pick the single nearest way" to:

- Compute `(way, nodes, segmentIndex, distance)` for every candidate way
  (skipping any with fewer than 2 geometry nodes, as today), sort by
  distance ascending.
- If there is a second candidate that is **not clearly the same street** as
  the best match - defined as: the two do not share the same non-null
  `name` tag (different names, or either one missing a name, both count as
  "not clearly the same") - and the distance gap between the two is
  **smaller than the report's own accuracy**, the two are indistinguishable
  given GPS noise - return `Unknown(bestMatch.streetName)` instead of
  confidently picking one. Two candidates sharing the same non-null street
  name (OSM often splits one physical street into multiple way objects)
  never trigger this - picking either segment yields the same direction
  answer.
- Otherwise, proceed exactly as today with the unambiguous nearest match.

## Divided-carriageway detection

After the selection above lands on a confident `OneWay` result, one more
pass runs over the *already-fetched* candidate list (no extra Overpass
call): if any other candidate way is also `oneway`-tagged (recognizing the
same `yes`/`true`/`1`/`-1`/`reverse` values as today), its own bearing (at
its own nearest segment to the point) is anti-parallel to the chosen way's
legal bearing (within ±45° of exactly 180°), and its own distance-to-point
is within 30m of the chosen way's distance-to-point (tight, so this only
fires for genuinely adjacent infrastructure - not any coincidental one-way
street picked up by a widened up-to-200m search radius) - the result is
downgraded to `Unknown(streetName)` rather than `OneWay`.

`Unknown` (not `TwoWay`) is deliberate: it matches the existing "OSM tag
exists but isn't trustworthy enough to assert a legal direction on its own"
semantics used elsewhere in this resolver, letting clip-consensus/history
evidence still confirm or reject normally. `TwoWay` asserts "no wrong-way
violation is possible here" at all, which is false for a divided road - a
vehicle can still genuinely drive the wrong way on either carriageway.

## Cache correctness fix

Since the search radius now varies per report, a result cached from a
narrow (high-accuracy) search could otherwise be served as if permanent to
a later low-accuracy report that would need a wider search to find the real
street. Fix: `OsmLookupCache` gains a `searchRadiusMeters` column, recording
the radius actually used to produce that cached row. On a lookup,
`StreetDirectionResolver` only reuses a cached row if
`cachedRow.searchRadiusMeters >= radiusNeededForThisReport`; otherwise it's
treated as a miss, re-resolved with the wider radius, and the cache entry
is overwritten (a strictly more-informed answer always wins). A cache row
computed with a wide radius is always safe to reuse for a later report that
would only need a narrower one - widening the radius only adds farther-away
candidates, it never removes closer ones already considered.

## Files touched

- **Modify:** `OverpassClient.kt` (`findNearbyWays` takes `radiusMeters`
  parameter), `OsmProperties.kt` (add `maxSearchRadiusMeters`,
  `radiusAccuracyMultiplier`), `StreetDirectionResolver.kt` (`resolve()`
  gains `accuracyMeters` parameter; `resolveFresh` rewritten around a
  sorted candidate list with ambiguity + divided-carriageway checks),
  `OsmLookupCache.kt` (new `searchRadiusMeters` column),
  `ReportAnalysisJob.kt` (pass `report.accuracy.toDouble()` through to
  `resolve()`).
- **New migration:** `V5__add_search_radius_to_osm_lookup_cache.sql` - adds
  `search_radius_meters` column, backfilled to `50.0` for existing rows.
- **Tests:** `OverpassClientTest.kt` (radius appears in the request body),
  `StreetDirectionResolverTest.kt` (radius scales with accuracy; ambiguous
  same-distance different-street pair returns `Unknown`; same-street
  multi-segment pair does NOT trigger ambiguity; divided-carriageway
  anti-parallel pair returns `Unknown` instead of `OneWay`; a stale
  narrow-radius cache entry is re-resolved, not served, for a later
  wide-radius report; a wide-radius cache entry IS reused for a later
  narrow-radius report).

## Verification

1. Unit/integration tests above, run via the existing WireMock-based
   `StreetDirectionResolverTest` pattern (no real network calls).
2. One manual smoke test against the real public Nominatim/Overpass
   endpoints for a known Lahore coordinate with a real divided road,
   matching this project's established verification pattern for OSM-facing
   code.
