# Corridor Cohesion Locality Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix `corridor_cohesion()` in the video-analysis service so it scores a track's cohesion against only its directly-close corridor members, instead of averaging against the entire chain-connected corridor - which collapses the score toward zero for nearly everyone in a busy real-world clip, even genuinely tightly-clustered traffic.

**Architecture:** A single, self-contained change inside one pure Python function (`app/corridors.py`). Clustering (`cluster_tracks`) is unchanged. No wire-format or Kotlin changes.

**Tech Stack:** Python 3.11, pytest.

## Global Constraints

- `cluster_tracks()` (single-linkage, direction-agnostic clustering) is NOT modified - it must keep guaranteeing a wrong-way vehicle lands in the same corridor as the traffic it opposes.
- No new tunable constant - reuse the existing `threshold_px` parameter already passed into `corridor_cohesion()`.
- No wire-format changes (`VehicleResult`, `AnalyzeResponse`) and no Kotlin changes (`ClipFlowAnalyzer.kt`, `DirectionEvidenceResolver.kt`) - this fix is isolated entirely to `app/corridors.py` and its test file.
- Camera-motion (ego-motion) compensation is explicitly out of scope - not part of this plan.

---

### Task 1: Rewrite `corridor_cohesion()` to only count direct neighbors

**Files:**
- Modify: `video-analysis/app/corridors.py:58-74`
- Test: `video-analysis/tests/test_corridors.py`

**Interfaces:**
- Consumes: `path_distance(a, b) -> float` (unchanged, existing function in the same file).
- Produces: `corridor_cohesion(track_id: int, paths: dict[int, Sequence[Point]], assignments: dict[int, int], threshold_px: float) -> float` - same signature as before; only the internal computation changes. No other file calls this function today except `app/pipeline.py`'s `AnalysisPipeline._summarize_track` (unchanged call site, unaffected by this signature-preserving change).

- [x] **Step 1: Replace the failing test**

In `video-analysis/tests/test_corridors.py`, delete this existing test (its premise is no longer reachable under the fix - see Step 2's docstring for why):

```python
def test_cohesion_is_clamped_to_zero_floor():
    # Chained single-linkage can include a member whose mean distance to the
    # others exceeds the threshold; cohesion must clamp at 0, never negative.
    paths = {
        1: _line(50.0, 0.0, 100.0),
        2: _line(59.0, 0.0, 100.0),
        3: _line(68.0, 0.0, 100.0),
    }
    assignments = cluster_tracks(paths, threshold_px=10.0)
    assert assignments[1] == assignments[3]
    assert corridor_cohesion(3, paths, assignments, 10.0) >= 0.0
```

Replace it with (same location in the file, i.e. at the end):

```python
def test_cohesion_only_counts_direct_neighbors_not_chain_reached_ones():
    # 1-2 directly linked (9px apart), 2-3 directly linked (9px apart), but
    # 1 and 3 are NOT directly close (18px apart, over the 10px threshold) -
    # only chain-reached through 2. Single-linkage still merges all three
    # into one corridor, but track 1's cohesion must come only from its
    # direct neighbor (2), not diluted by the far, chain-only track 3.
    paths = {
        1: _line(50.0, 0.0, 100.0),
        2: _line(59.0, 0.0, 100.0),
        3: _line(68.0, 0.0, 100.0),
    }
    assignments = cluster_tracks(paths, threshold_px=10.0)
    assert assignments[1] == assignments[3]  # merged via chain through 2

    # Only direct neighbor is 2, at distance 9: cohesion = 1 - 9/10 = 0.1.
    # The old whole-corridor average (including chain-only track 3 at
    # distance 18) would have driven this to a clamped 0.0.
    assert abs(corridor_cohesion(1, paths, assignments, 10.0) - 0.1) < 1e-9
```

- [x] **Step 2: Run the test to verify it fails**

Run (from `video-analysis/`): `.venv/Scripts/python.exe -m pytest tests/test_corridors.py::test_cohesion_only_counts_direct_neighbors_not_chain_reached_ones -v`
Expected: FAIL. Under the current (unfixed) implementation, `corridor_cohesion(1, ...)` averages distances to *both* other members (`[9, 18]`, mean `13.5`), giving `1 - 13.5/10 = -0.35`, clamped to `0.0` - not the `0.1` the new test expects.

- [x] **Step 3: Replace `corridor_cohesion()`**

In `video-analysis/app/corridors.py`, replace the entire function (lines 58-74):

```python
def corridor_cohesion(
    track_id: int,
    paths: dict[int, Sequence[Point]],
    assignments: dict[int, int],
    threshold_px: float,
) -> float:
    """1 - (mean path distance to the corridor's other members / threshold),
    clamped to [0, 1]. Single-member corridors get 1.0 by definition (harmless:
    their consensus size is 1 downstream, so this can never inflate a score).
    """
    corridor_id = assignments[track_id]
    others = [tid for tid, cid in assignments.items() if cid == corridor_id and tid != track_id]
    if not others:
        return 1.0

    mean_distance = sum(path_distance(paths[track_id], paths[o]) for o in others) / len(others)
    return max(0.0, min(1.0, 1.0 - mean_distance / threshold_px))
```

with:

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

- [x] **Step 4: Run the test to verify it passes**

Run: `.venv/Scripts/python.exe -m pytest tests/test_corridors.py::test_cohesion_only_counts_direct_neighbors_not_chain_reached_ones -v`
Expected: PASS.

- [x] **Step 5: Run the full existing test suite to confirm no regressions**

Run: `.venv/Scripts/python.exe -m pytest -v`
Expected: all tests PASS (24 existing minus the 1 deleted plus the 1 new = same total count as before this task; every other test in `test_corridors.py` and `test_pipeline.py` was hand-verified against the new formula during design and requires no changes).

- [x] **Step 6: Commit**

```bash
git add video-analysis/app/corridors.py video-analysis/tests/test_corridors.py
git commit -m "fix(video-analysis): score corridor cohesion against direct neighbors only

Single-linkage clustering legitimately merges busy-scene vehicles into
oversized corridors via chaining; averaging cohesion across that whole
membership collapsed the score toward zero even for tightly-clustered
real traffic. Restrict the average to directly-close members only -
every multi-member corridor guarantees at least one."
```

- [x] **Step 7: Deploy to production and manually verify**

Copy the updated file to the server and rebuild the `video-analysis` container:

```bash
scp -i ~/.ssh/trafficwatch_ovh video-analysis/app/corridors.py ubuntu@137.74.173.97:~/trafficwatch/video-analysis/app/
ssh -i ~/.ssh/trafficwatch_ovh ubuntu@137.74.173.97 "cd ~/trafficwatch && docker compose -f docker-compose.prod.yml up -d --build video-analysis"
```

Resubmit the same real clip that originally surfaced this bug (the one already pulled to `/tmp/latest.mp4` on the production server during diagnosis) through the full pipeline - either directly to `POST /v1/analyze` on the video-analysis service, or through the Kotlin server's `POST /v1/reports` - and confirm the corridor-consensus evidence source for the tightly-clustered traffic stream is no longer dropped as `DROPPED_WEAK` with a near-zero confidence. Compare the new `direction_evidence`/`evidence_breakdown` output against the earlier diagnostic run's `{"kind":"CLIP_CONSENSUS","confidence":0.008568068018905285,...}` result.
Expected: the CLIP_CONSENSUS confidence for the dominant traffic stream is now meaningfully higher (reflecting the real, tight bearing agreement among the majority-flow vehicles), no longer floored by chain-inflated cohesion.
