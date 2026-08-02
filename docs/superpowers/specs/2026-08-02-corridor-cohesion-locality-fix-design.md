# Corridor Cohesion Locality Fix Design

## Context

The video-analysis service's corridor-consensus pipeline (`app/corridors.py`)
clusters tracked vehicles into "corridors" via direction-agnostic
single-linkage clustering, then scores each vehicle's `corridor_cohesion` -
how tightly its path matches its corridor's other members - which feeds into
the Kotlin server's `ClipFlowAnalyzer`/`DirectionEvidenceResolver` to decide
whether a wrong-way violation can be confirmed.

Diagnosing a real report where a genuinely wrong-way motorcycle went
unconfirmed (after a separate, already-fixed video-orientation bug was
ruled out - see the `fix/video-orientation-detection` branch) surfaced this:
`corridor_cohesion()` currently averages a track's path-distance to **every**
member of its corridor, including ones reached only transitively through a
chain of intermediate vehicles. Single-linkage clustering legitimately (and
by design - see below) merges many vehicles into one corridor on a busy
street: near lane, far lane, different depths, opposing directions.
Averaging cohesion across that entire chain-inflated membership drags the
score toward zero for nearly everyone in it, even vehicles that are
genuinely tightly clustered with their immediate neighbors.

Confirmed on real production data: a single clip tracked 23 vehicles, ~22 of
which landed in one corridor via chaining, and ~20 of them had
`corridor_cohesion` of exactly `0.0` - including several cars with tightly
agreeing bearings (265-290 degrees) that were clearly part of the same real
traffic stream. This collapsed `meanCohesion` in the Kotlin fusion formula
(`clipConfidence = sizeFactor * R * meanCohesion`) to near zero regardless of
how strong the underlying directional agreement actually was, causing the
report to fall through to "insufficient evidence" instead of confirming.

This is not an implementation bug relative to the original spec - the
cohesion formula and single-linkage clustering are exactly as originally
designed and tested (see `docs/superpowers/specs/2026-07-31-video-inferred-direction-design.md`).
The existing synthetic tests (2-3 tracks) never exercised this failure mode
at the scale of a real, busy clip. This is a design limitation the original
approach didn't anticipate, revealed by production data.

## Scope & explicit decisions (confirmed with the user - do not re-litigate)

- **Camera-motion (ego-motion) compensation is explicitly out of scope.**
  The diagnosed clip was shot from inside a moving car, not a stationary
  bystander (the scenario this whole corridor system was designed around),
  which likely contributed to the scale of chaining observed. Compensating
  for camera motion (frame-to-frame homography/optical-flow estimation) is
  a much larger, separate effort and its own future plan. This fix targets
  cohesion/clustering robustness to busy traffic in general, regardless of
  what caused the over-merging.
- **`cluster_tracks()` (single-linkage, direction-agnostic) is unchanged.**
  It is what guarantees a wrong-way vehicle lands in the same corridor as
  the traffic it opposes - a tested, load-bearing invariant
  (`test_reversed_path_joins_same_corridor`) that the confirmation logic
  depends on. Changing the clustering algorithm risks breaking this
  guarantee. The fix is isolated entirely to `corridor_cohesion()`.
- **Fix approach: direct-neighbors-only averaging.** `corridor_cohesion()`
  now averages a track's path-distance only against corridor members that
  are themselves directly within `threshold_px` (the same distance concept
  already used by `cluster_tracks()` for clustering) - not the full
  chain-connected membership. This reuses existing geometry with no new
  tunable constant, and directly targets the confirmed mechanism (averaging
  across far, chain-reached members).
- **No wire-format or Kotlin changes.** `VehicleResult`'s `corridor_id`/
  `corridor_cohesion` fields, `ClipFlowAnalyzer.kt`, and
  `DirectionEvidenceResolver.kt` are all unchanged - this is a pure,
  self-contained behavior change inside one Python function.

## The fix

Every member of a corridor with 2+ tracks is guaranteed at least one direct
neighbor - a connected component of size 2+ cannot contain a vertex with no
within-threshold edge to another member of that component - PROVIDED
`corridor_cohesion()` is called with the same `threshold_px` that produced
the `assignments` dict (true for this module's only caller, `pipeline.py`,
which computes `threshold_px` once and passes the identical value to both
`cluster_tracks()` and `corridor_cohesion()`). The shipped function still
includes a defensive `if not direct_distances: return 0.0` fallback for a
caller that violates this precondition, rather than dividing by zero.

```python
def corridor_cohesion(
    track_id: int,
    paths: dict[int, Sequence[Point]],
    assignments: dict[int, int],
    threshold_px: float,
) -> float:
    """1 - (mean path distance to the corridor's DIRECT neighbors / threshold),
    clamped to [0, 1]. "Direct" means within threshold_px of track_id itself -
    members reached only transitively through a chain of intermediate tracks
    are excluded from the average, so a genuinely tight local cluster scores
    well even when single-linkage has merged it into a much larger corridor
    elsewhere in the frame. Single-member corridors get 1.0 by definition
    (harmless: their consensus size is 1 downstream). Every member of a
    corridor with 2+ tracks is guaranteed at least one direct neighbor - a
    connected component of size 2+ cannot contain a vertex with no
    within-threshold edge to another member of that component - so the
    direct-neighbor set below is never empty (given a consistent threshold_px;
    see the fallback below for the inconsistent case). The clamp guards only
    against floating-point rounding (a mean of values each <= threshold_px
    cannot exceed it).
    """
    corridor_id = assignments[track_id]
    others = [tid for tid, cid in assignments.items() if cid == corridor_id and tid != track_id]
    if not others:
        return 1.0

    direct_distances = [
        d for o in others
        if (d := path_distance(paths[track_id], paths[o])) <= threshold_px
    ]
    if not direct_distances:
        # Should not happen when assignments/threshold_px are consistent (see
        # docstring precondition) - defensive fallback rather than a crash.
        return 0.0
    mean_distance = sum(direct_distances) / len(direct_distances)
    return max(0.0, min(1.0, 1.0 - mean_distance / threshold_px))
```

No other files change. `cluster_tracks()`, `path_distance()`,
`VehicleResult`/`AnalyzeResponse` DTOs, `ClipFlowAnalyzer.kt`, and
`DirectionEvidenceResolver.kt` are all untouched.

## Testing

Verified by hand against every existing test in `tests/test_corridors.py`
and `tests/test_pipeline.py`:

- `test_path_distance_is_zero_for_identical_paths`,
  `test_path_distance_is_symmetric`, `test_same_lane_paths_cluster_together`,
  `test_reversed_path_joins_same_corridor`,
  `test_separated_streams_form_distinct_corridors`,
  `test_corridor_ids_are_dense_and_ordered_by_smallest_track_id` - all
  exercise `path_distance`/`cluster_tracks` only, untouched by this change.
- `test_single_member_corridor_has_cohesion_one` - unchanged special case,
  still passes.
- `test_cohesion_decreases_with_distance_from_corridor_mates` - all three
  tracks in this example are pairwise within threshold (fully-connected, no
  chain-only exclusion triggers), so the relative ordering it asserts
  (`cohesion(2) > cohesion(3)`) still holds under the new formula.
- `test_same_lane_tracks_share_a_corridor_and_opposite_lane_does_not`
  (pipeline test) - only range-checks `0.0 <= cohesion <= 1.0`, holds
  regardless.
- **`test_cohesion_is_clamped_to_zero_floor` is replaced.** Its premise - a
  chain-linked member's mean distance to *everyone* in the corridor can
  exceed the threshold, requiring a clamp to zero - is no longer reachable:
  since only distances `<= threshold_px` are ever included in the average,
  that average can never itself exceed `threshold_px`, so the result can
  never go negative when there is at least one direct neighbor (which is
  always the case for a multi-member corridor). The replacement test,
  `test_cohesion_only_counts_direct_neighbors_not_chain_reached_ones`, uses
  the same three-track chain data (1 at x=50, 2 at x=59, 3 at x=68,
  threshold=10 - so 1-2 and 2-3 are directly linked at distance 9, but 1-3
  are only chain-reached at distance 18, over threshold) and asserts the new,
  correct behavior: track 1's cohesion comes only from its direct neighbor
  (2, distance 9) as `1 - 9/10 = 0.1`, not diluted by the far, chain-only
  track 3 - contrasted in the test's comment against the old formula, which
  would have driven this to a clamped `0.0`.
- **Manual verification:** resubmit the same real clip that surfaced this
  bug through the full pipeline (video-analysis directly, then through the
  Kotlin server) and confirm the corridor-consensus evidence source is no
  longer dropped as `DROPPED_WEAK` for the tightly-clustered traffic stream,
  and that the wrong-way motorcycle's evaluation now has real, non-collapsed
  evidence to compare against.
