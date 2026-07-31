from __future__ import annotations

import math
from typing import Sequence, Tuple

Point = Tuple[float, float]


def path_distance(a: Sequence[Point], b: Sequence[Point]) -> float:
    """Symmetric mean nearest-point distance between two paths (Chamfer-style).

    Paths are compared as point SETS - travel direction is deliberately ignored,
    so a wrong-way vehicle lands in the same corridor as the oncoming traffic it
    opposes. O(len(a) * len(b)); track lengths are small (tens of points).
    """

    def mean_nearest(src: Sequence[Point], dst: Sequence[Point]) -> float:
        total = 0.0
        for (sx, sy) in src:
            total += min(math.hypot(sx - dx, sy - dy) for (dx, dy) in dst)
        return total / len(src)

    return (mean_nearest(a, b) + mean_nearest(b, a)) / 2.0


def cluster_tracks(paths: dict[int, Sequence[Point]], threshold_px: float) -> dict[int, int]:
    """Single-linkage agglomerative clustering: tracks whose paths run within
    `threshold_px` of each other (directly or through a chain) share a corridor.

    Returns track_id -> corridor_id, with corridor ids renumbered 0..k-1 in
    order of each cluster's smallest track_id (deterministic across runs).
    """
    track_ids = sorted(paths.keys())
    parent: dict[int, int] = {tid: tid for tid in track_ids}

    def find(tid: int) -> int:
        while parent[tid] != tid:
            parent[tid] = parent[parent[tid]]
            tid = parent[tid]
        return tid

    def union(a: int, b: int) -> None:
        ra, rb = find(a), find(b)
        if ra != rb:
            # Root at the smaller id so cluster roots are stable/deterministic.
            parent[max(ra, rb)] = min(ra, rb)

    for i, tid_a in enumerate(track_ids):
        for tid_b in track_ids[i + 1:]:
            if path_distance(paths[tid_a], paths[tid_b]) <= threshold_px:
                union(tid_a, tid_b)

    roots_in_order = sorted({find(tid) for tid in track_ids})
    corridor_of_root = {root: i for i, root in enumerate(roots_in_order)}
    return {tid: corridor_of_root[find(tid)] for tid in track_ids}


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
