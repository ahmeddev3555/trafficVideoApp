from __future__ import annotations

from app.corridors import cluster_tracks, corridor_cohesion, path_distance


def _line(x: float, y_start: float, y_end: float, n: int = 10) -> list[tuple[float, float]]:
    """Vertical path at fixed x from y_start to y_end with n points."""
    step = (y_end - y_start) / (n - 1)
    return [(x, y_start + i * step) for i in range(n)]


def test_path_distance_is_zero_for_identical_paths():
    a = _line(50.0, 0.0, 100.0)
    assert path_distance(a, a) == 0.0


def test_path_distance_is_symmetric():
    a = _line(50.0, 0.0, 100.0)
    b = _line(60.0, 0.0, 100.0)
    assert abs(path_distance(a, b) - path_distance(b, a)) < 1e-9


def test_same_lane_paths_cluster_together():
    paths = {1: _line(50.0, 0.0, 100.0), 2: _line(52.0, 0.0, 100.0)}
    assignments = cluster_tracks(paths, threshold_px=10.0)
    assert assignments[1] == assignments[2]


def test_reversed_path_joins_same_corridor():
    # A wrong-way vehicle drives the same corridor in reverse - point-set
    # comparison must ignore travel direction entirely.
    paths = {1: _line(50.0, 0.0, 100.0), 2: _line(51.0, 100.0, 0.0)}
    assignments = cluster_tracks(paths, threshold_px=10.0)
    assert assignments[1] == assignments[2]


def test_separated_streams_form_distinct_corridors():
    paths = {1: _line(50.0, 0.0, 100.0), 2: _line(300.0, 0.0, 100.0)}
    assignments = cluster_tracks(paths, threshold_px=10.0)
    assert assignments[1] != assignments[2]


def test_corridor_ids_are_dense_and_ordered_by_smallest_track_id():
    paths = {
        7: _line(300.0, 0.0, 100.0),
        3: _line(50.0, 0.0, 100.0),
        9: _line(52.0, 0.0, 100.0),
    }
    assignments = cluster_tracks(paths, threshold_px=10.0)
    # Track 3's cluster contains the smallest track id overall -> corridor 0.
    assert assignments[3] == 0
    assert assignments[9] == 0
    assert assignments[7] == 1


def test_single_member_corridor_has_cohesion_one():
    paths = {1: _line(50.0, 0.0, 100.0)}
    assignments = cluster_tracks(paths, threshold_px=10.0)
    assert corridor_cohesion(1, paths, assignments, threshold_px=10.0) == 1.0


def test_cohesion_decreases_with_distance_from_corridor_mates():
    paths = {
        1: _line(50.0, 0.0, 100.0),
        2: _line(51.0, 0.0, 100.0),
        3: _line(58.0, 0.0, 100.0),  # same cluster via single-linkage chain, but farther out
    }
    assignments = cluster_tracks(paths, threshold_px=10.0)
    assert assignments[1] == assignments[3]
    assert corridor_cohesion(2, paths, assignments, 10.0) > corridor_cohesion(3, paths, assignments, 10.0)


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
