# Video-Inferred Legal Direction: Corridor Flow, Learned Observations, Evidence Fusion

## Context

Today `ReportAnalysisJob` can only confirm a wrong-way violation when OSM
carries an explicit `oneway` tag on the road segment nearest the report. OSM
coverage in Pakistan makes that the common failure: real-world testing found
locations where the nearest segment has no tag even though a tagged one-way
road sits meters away, so `StreetDirectionResolver` correctly returns
`Unknown` and the report dead-ends in `REJECTED` - permanently, no matter how
clear the violation in the clip.

This spec adds two new evidence sources for the legal direction and a fusion
layer that combines them:

1. **Clip flow consensus** - infer direction from the traffic visible in the
   video itself: vehicles traveling the same physical corridor of the frame
   establish, by majority flow, which way traffic goes there.
2. **Learned observations** - accumulate those per-clip consensus flows into
   a per-location database, so the system learns street directions from
   repeated reports over time; mature history can support a confirmation
   even for a clip showing only the violator.
3. **Evidence fusion** - OSM tag, clip consensus, and learned history each
   emit `(bearing, confidence)`; a resolver fuses agreeing sources
   (noisy-OR) and refuses to guess when strong sources disagree. The design
   is source-agnostic: user direction-attestation (a map-arrow UI asking
   users to mark one-way directions, with per-user reliability weighting) is
   a **declared future source, out of scope here** - it gets its own spec.

The final confidence formula extends the existing one with a
direction-certainty factor:

```
wrongWayConfidence = directionConfidence x candidateQuality
                   x detectionConfidence x bearingMatchScore
```

## Decisions (confirmed with the user - do not re-litigate)

- **Fallback + cross-check.** Video/history evidence fills in when OSM has
  no usable tag, AND cross-checks OSM when it does: strong observed flow
  contradicting the tag forces "insufficient evidence," never a coin-flip.
- **Explicit `oneway=no` stays terminal.** OSM saying two-way means
  opposing traffic is legal; video inference never runs. This is also the
  guard against the quiet-two-way-street false positive.
- **Mature history alone can confirm.** A lone-vehicle clip at a location
  with mature learned history (thresholds below) can reach CONFIRMED -
  that is the payoff of the learned DB.
- **DB trust: consensus-only ingestion + reporter diversity.** Only
  corridor consensus flows (>=2 agreeing vehicles) are ever ingested; the
  evaluated candidate never is. Maturity requires >=3 distinct reporters,
  so one user can never mature a location alone.
- **User attestation is a separate follow-up spec.** The fusion interface
  accommodates it as another source; nothing else here depends on it.
- **Architecture split (Approach A):** Python owns frame-space geometry
  (corridor clustering - it already owns bearing computation and holds the
  trajectories, which are currently discarded); Kotlin owns all statistics,
  fusion, persistence, and the decision. Python stays stateless and
  compass-blind.
- **Evidence breakdown always computed and stored server-side, returned in
  the status response; only the Android debug build renders it.** No server
  gating in v1 (a config flag can strip it from the wire later if wanted).
- No new `ReportStatus` values. No Android release-UI changes.

## Flow changes in `ReportAnalysisJob.determineOutcome`

1. Compass check - unchanged.
2. OSM resolution - unchanged mechanics; branch handling changes:
   - `TwoWay` -> terminal rejection, as today.
   - `OneWay` -> proceeds as an evidence source (confidence 1.0), now
     subject to cross-check.
   - `Unknown` / `NotFound` -> **no longer terminal**; falls through with
     the OSM source absent.
   - `LookupFailed` -> also falls through (OSM source absent). A transient
     Overpass outage no longer kills a report that carries its own
     evidence.
3. Video analysis runs (already runs for every non-rejected report; no
   extra Python calls), now returning corridor-annotated vehicles.
4. `DirectionEvidenceResolver` fuses available sources into
   `(legalBearing, directionConfidence)` or "insufficient evidence."
5. Wrong-way matching runs against the fused bearing (existing tolerance
   logic), with two corridor gates (below). Final score per the formula
   above; CONFIRMED requires `>= confirmationThreshold` (config, default
   0.5), otherwise REJECTED with a specific message.
6. After the decision - every report, every outcome - qualifying corridor
   consensus flows are ingested into `flow_observations`.

## Python: corridor clustering (`app/corridors.py`)

New pure module, unit-testable with synthetic centroid paths (same style as
`tracking_bearing.py` / `test_bearing.py`). No new dependencies.

- **Path distance, direction-agnostic:** a track is its ordered centroid
  sequence; distance between two tracks is the symmetric mean
  nearest-point distance between their paths (Chamfer-style). Paths are
  compared as point sets, ignoring travel direction - a wrong-way vehicle
  must land in the SAME corridor as the oncoming traffic it opposes.
- **Clustering:** single-linkage agglomerative, threshold = 5% of the
  frame diagonal (configurable via pydantic Settings). Vehicle counts are
  single-digit per clip; O(n^2) is trivial.
- **Cohesion:** per vehicle,
  `1 - (mean path distance to corridor's other members / threshold)`,
  clamped to [0, 1]; single-member corridors get 1.0 (harmless - their
  consensus size is 1 downstream).
- Tracks with null `bearing_degrees` still get corridor assignments but
  contribute nothing to consensus downstream.

**Wire format additions** - `VehicleResult` gains raw frame-space facts
only: `corridor_id: int`, `corridor_cohesion: float`,
`track_frame_count: int`, `displacement_pixels: float`.
`AnalyzeResponse` gains `frame_width: int`, `frame_height: int` so the
server can normalize displacement by frame diagonal.

## Kotlin: clip flow consensus (`geo/ClipFlowAnalyzer`)

Pure component (no I/O), mirroring `BearingMath`'s testability.

- Converts each non-null frame bearing to absolute exactly as today:
  `(compassHeading + frameBearing) mod 360`.
- **Quality floor:** members with `track_frame_count < 3` or
  `displacement_pixels < 5% of frame diagonal` are excluded from consensus
  entirely (a jittery track doesn't vote).
- **Per-corridor consensus** over remaining members, excluding the
  candidate under evaluation: circular mean bearing + mean resultant
  length **R**. `R < 0.6` -> no consensus (the bimodality gate - a split
  flow never elects a winner).
- **Clip evidence:** the candidate corridor's consensus produces
  `(consensusBearing, clipConfidence)` with
  `clipConfidence = sizeFactor x R x meanCohesion`,
  `sizeFactor = n / (n + 2)` over consensus members.

**Candidate gates** (the divided-road / filmed-from-across-the-road
protection):

1. A candidate moving WITH its own corridor's consensus is never a
   violator, even if it opposes the street's fused legal bearing - that is
   a legal opposing stream (far carriageway), not wrong-way driving.
2. A candidate is a violator only when it moves AGAINST its own corridor's
   consensus, or against the fused legal bearing when it is alone in its
   corridor (the quiet-street case mature history serves).

**Candidate quality:**
`candidateQuality = trackQuality x corridorCohesion` where
`trackQuality = min(frames/5, 1) x min(displacement / (0.05 x diagonal), 1)`.

## Learned observations DB

New table `flow_observations` (Flyway migration), bucketed like
`osm_lookup_cache` (lat/lon rounded to 4 decimals, ~11 m; keyed off the
reporter's standing position):

```sql
id                UUID PRIMARY KEY
lat_bucket        NUMERIC(8,4) NOT NULL
lon_bucket        NUMERIC(8,4) NOT NULL
bearing_degrees   NUMERIC(6,2) NOT NULL   -- corridor-consensus absolute bearing
vehicle_count     INT NOT NULL            -- consensus size behind it
resultant_length  NUMERIC(4,3) NOT NULL   -- R of that consensus
reporter_id       UUID NOT NULL           -- diversity requirement
report_id         UUID NOT NULL           -- provenance; one row per corridor per report
created_at        TIMESTAMP WITH TIME ZONE NOT NULL  -- decay possible later, none in v1
```

- **Ingestion** (post-decision, every report): each corridor whose
  post-floor consensus has >=2 members and R >= 0.6 writes one row. The
  candidate is excluded by construction and never taught. A divided road
  yields two opposing rows at one bucket - honest: that vantage point is
  genuinely ambiguous and history there will decline to testify.
- **Evidence derivation:** circular mean + `R_hist` over the bucket's rows
  (one vote each). History qualifies only when ALL hold: >=5 observations,
  >=3 distinct reporters, `R_hist >= 0.8`. Then
  `historyConfidence = (obsCount / (obsCount + 5)) x R_hist` - naturally
  capped below ~0.9; learned history never matches an explicit tag's
  authority. A bimodal bucket yields nothing - never a "two-way" verdict
  (indistinguishable from a divided-road vantage).

## Evidence fusion (`geo/DirectionEvidenceResolver`)

Each source emits `(bearingDegrees, confidence, kind)`:

| Source            | Bearing                        | Confidence         |
|-------------------|--------------------------------|--------------------|
| OSM `oneway` tag  | tag-implied segment bearing    | 1.0                |
| Clip consensus    | candidate corridor's consensus | `clipConfidence`   |
| Learned history   | bucket's circular mean         | `historyConfidence`|
| (future) attestation | slots in, no interface change | -                |

Rules, applied to whatever subset is present:

1. Drop sources below `weakEvidenceFloor` (default 0.2).
2. Two sources agree when bearings differ <= `agreementToleranceDegrees`
   (default 45).
3. All survivors agree -> fused bearing = confidence-weighted circular
   mean; `directionConfidence = 1 - PRODUCT(1 - c_i)` (noisy-OR). OSM
   alone yields 1.0 - today's behavior preserved exactly.
4. Any two survivors disagree -> insufficient evidence (including against
   OSM - the cross-check).
5. No survivors -> insufficient evidence.

**Rejection messages** (all `REJECTED`, each distinguishable):

- `"Legal traffic direction could not be established for this street"` -
  no qualifying evidence (replaces Unknown/NotFound terminal messages on
  this path).
- `"Conflicting direction evidence for this street"` - disagreement veto.
- `"Possible wrong-way vehicle detected, but confidence was too low to
  confirm"` - passed gates, product below `confirmationThreshold`.
- Compass-missing, two-way, and video-service-down messages unchanged.

## Evidence breakdown visibility

- New `direction_evidence` JSONB column on `reports`: each source's entry
  (`kind`, `bearing`, `confidence`, fate: `accepted` / `dropped_weak` /
  `conflict`), the fused bearing + `directionConfidence`, plus
  `candidateQuality`, `detectionConfidence`, `bearingMatchScore`, and the
  final product.
- `ReportStatusResponse` gains `evidence_breakdown` carrying it (always -
  it is the user's own report).
- Android: **debug builds only** (`BuildConfig.DEBUG`) render a "Score
  Breakdown" card on the report detail screen; release builds render
  nothing new. No server gating in v1.

## Config additions (`app.analysis.*`, all with defaults)

```yaml
app:
  analysis:
    confirmation-threshold: 0.5
    agreement-tolerance-degrees: 45
    weak-evidence-floor: 0.2
    consensus-min-resultant-length: 0.6
    history-min-observations: 5
    history-min-distinct-reporters: 3
    history-min-resultant-length: 0.8
```

(Python: corridor clustering threshold as a Settings field,
`corridor_cluster_threshold_fraction`, default 0.05.)

Dev environments can lower `history-min-distinct-reporters` while the app
has few real users.

## Error handling / graceful degradation

- Fusion failure modes all resolve to specific REJECTED messages (above);
  nothing throws out of the evidence path.
- Corridor fields absent from a Python response (older service version) ->
  clip-consensus source simply absent; OSM/history proceed.
- `flow_observations` write failures are logged and never affect the
  report's outcome (mirrors the frame-annotation error contract).
- Old reports predating the feature have null `direction_evidence`; the
  debug card simply doesn't render.

## Testing

- **Python `tests/test_corridors.py`** (synthetic paths, no inference):
  same-lane paths cluster; a reversed path joins its corridor
  (direction-agnosticism); separated streams form distinct corridors;
  outlier cohesion drops; single-track clip -> one corridor, cohesion 1.0.
- **`ClipFlowAnalyzerTest`** (table-driven): unimodal consensus with
  expected R; bimodal -> none; candidate WITH its corridor never a
  violator (divided-road gate); candidate AGAINST its corridor is;
  quality-floor exclusion; size factor and clipConfidence against
  hand-computed values.
- **`DirectionEvidenceResolverTest`**: OSM alone (=1.0, today's behavior);
  OSM + agreeing clip (noisy-OR boost); OSM + strong conflicting clip
  (veto); weak source dropped without veto; history alone at maturity;
  empty set; circular mean across 0/360.
- **`FlowObservationServiceTest`**: ingestion rules (>=2 + R gate,
  candidate never ingested); each maturity threshold individually unmet ->
  no evidence; confidence curve values.
- **`ReportAnalysisJobTest`** (extended): Unknown + strong clip consensus
  -> CONFIRMED with breakdown; LookupFailed + history -> proceeds;
  conflict -> its message; below-threshold -> its message; TwoWay still
  terminal.
- **Integration** (WireMock): OSM without `oneway` tag + Python stub with
  corridor consensus -> CONFIRMED, `flow_observations` row written,
  status response carries `evidence_breakdown`.
- **Manual (device, debug build):** record at the same location as report
  `f0798d95...b5e3b` (today a guaranteed `Unknown` dead-end) with normal
  traffic visible; verify the breakdown card shows clip-consensus evidence
  and a sensible score; submit a second report there and watch history
  accumulate in `flow_observations`.
