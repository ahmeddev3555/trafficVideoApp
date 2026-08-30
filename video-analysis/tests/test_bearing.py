import pytest

from app.tracking_bearing import (
    bbox_diagonal,
    compute_bearing_degrees,
    compute_displacement_pixels,
    compute_track_midpoint_ms,
    resolve_bearing,
    scale_trend,
)


def _linear_track(start, end, steps=8):
    sx, sy = start
    ex, ey = end
    return [
        (sx + (ex - sx) * i / (steps - 1), sy + (ey - sy) * i / (steps - 1))
        for i in range(steps)
    ]


def test_moving_up_the_frame_is_bearing_zero():
    # Pixel y decreases as the vehicle moves toward the top of the frame ("up").
    track = _linear_track((100.0, 200.0), (100.0, 50.0), steps=24)
    assert compute_bearing_degrees(track) == pytest.approx(0.0, abs=1e-6)


def test_moving_right_across_the_frame_is_bearing_90():
    track = _linear_track((50.0, 100.0), (300.0, 100.0), steps=24)
    assert compute_bearing_degrees(track) == pytest.approx(90.0, abs=1e-6)


def test_moving_down_the_frame_is_bearing_180():
    track = _linear_track((100.0, 50.0), (100.0, 300.0), steps=24)
    assert compute_bearing_degrees(track) == pytest.approx(180.0, abs=1e-6)


def test_moving_left_across_the_frame_is_bearing_270():
    track = _linear_track((300.0, 100.0), (50.0, 100.0), steps=24)
    assert compute_bearing_degrees(track) == pytest.approx(270.0, abs=1e-6)


def test_too_few_observations_returns_none():
    assert compute_bearing_degrees([(0.0, 0.0), (1.0, 1.0), (2.0, 2.0)]) is None


def test_negligible_displacement_returns_none():
    # A track that jitters by a couple pixels but never really moves.
    track = [(100.0, 100.0), (101.0, 100.0), (100.0, 101.0), (101.0, 101.0), (100.0, 100.0)]
    assert compute_bearing_degrees(track) is None


def test_result_is_always_in_range():
    track = _linear_track((10.0, 10.0), (400.0, 250.0), steps=24)
    bearing = compute_bearing_degrees(track)
    assert bearing is not None
    assert 0.0 <= bearing < 360.0


def test_track_midpoint_ms_at_30fps():
    # Frames 0..29 at 30fps span exactly 1 second (0..1000ms); midpoint frame 14.5 -> ~483ms.
    assert compute_track_midpoint_ms(0, 29, 30.0) == pytest.approx(483, abs=1)


def test_track_midpoint_ms_single_frame_track():
    assert compute_track_midpoint_ms(10, 10, 30.0) == pytest.approx(333, abs=1)


def test_track_midpoint_ms_returns_none_when_fps_is_none():
    assert compute_track_midpoint_ms(0, 29, None) is None


def test_track_midpoint_ms_returns_none_when_fps_is_zero_or_negative():
    assert compute_track_midpoint_ms(0, 29, 0.0) is None
    assert compute_track_midpoint_ms(0, 29, -5.0) is None


def test_near_centered_approaching_vehicle_returns_180_degrees():
    # Centroids barely move (lateral displacement well under the 8.0px floor), but the
    # bounding box grows dramatically - a vehicle driving straight at the camera.
    centroids = [(50.0, 50.0)] * 24
    early_bbox = (40.0, 40.0, 60.0, 60.0)   # diagonal ~28.28
    late_bbox = (0.0, 0.0, 100.0, 100.0)    # diagonal ~141.42
    bboxes = [early_bbox] * 12 + [late_bbox] * 12

    assert compute_bearing_degrees(centroids, bboxes) == 180.0


def test_near_centered_receding_vehicle_returns_0_degrees():
    centroids = [(50.0, 50.0)] * 24
    early_bbox = (0.0, 0.0, 100.0, 100.0)   # diagonal ~141.42
    late_bbox = (40.0, 40.0, 60.0, 60.0)    # diagonal ~28.28
    bboxes = [early_bbox] * 12 + [late_bbox] * 12

    assert compute_bearing_degrees(centroids, bboxes) == 0.0


def test_neither_lateral_nor_scale_change_is_significant_returns_none():
    centroids = [(50.0, 50.0)] * 12 + [(50.5, 50.0)] * 12  # lateral displacement 0.5px
    early_bbox = (40.0, 40.0, 60.0, 60.0)   # diagonal ~28.28
    late_bbox = (42.0, 42.0, 62.0, 62.0)    # same size, diagonal ~28.28 - no scale change
    bboxes = [early_bbox] * 12 + [late_bbox] * 12

    assert compute_bearing_degrees(centroids, bboxes) is None


def test_real_lateral_motion_is_unaffected_by_the_new_bboxes_parameter():
    track = _linear_track((50.0, 100.0), (300.0, 100.0), steps=24)
    dummy_bboxes = [(0.0, 0.0, 10.0, 10.0)] * len(track)

    # Identical result whether bboxes is omitted (existing callers) or passed (new caller) -
    # lateral displacement alone already clears the floor, so the fallback never activates.
    assert compute_bearing_degrees(track) == pytest.approx(90.0, abs=1e-6)
    assert compute_bearing_degrees(track, dummy_bboxes) == pytest.approx(90.0, abs=1e-6)


def test_bbox_diagonal_computes_the_euclidean_diagonal():
    from app.tracking_bearing import bbox_diagonal

    assert bbox_diagonal((0.0, 0.0, 3.0, 4.0)) == pytest.approx(5.0, abs=1e-9)


def test_resolve_bearing_reports_centroid_source_for_real_lateral_motion():
    track = _linear_track((50.0, 100.0), (300.0, 100.0), steps=24)
    result = resolve_bearing(track)
    assert result is not None
    bearing, source = result
    assert bearing == pytest.approx(90.0, abs=1e-6)
    assert source == "centroid"


def test_resolve_bearing_reports_scale_source_for_near_centered_approach():
    centroids = [(50.0, 50.0)] * 24
    early_bbox = (40.0, 40.0, 60.0, 60.0)
    late_bbox = (0.0, 0.0, 100.0, 100.0)
    bboxes = [early_bbox] * 12 + [late_bbox] * 12

    result = resolve_bearing(centroids, bboxes)

    assert result == (180.0, "scale")


def test_resolve_bearing_returns_none_when_scale_change_is_under_the_relative_floor():
    # early diagonal 28.28, late diagonal 30.0 - a real but small (~6%) size change, well
    # under the 15% MIN_SCALE_CHANGE_FRACTION floor - must not be trusted as approach motion.
    centroids = [(50.0, 50.0)] * 24
    early_bbox = (40.0, 40.0, 60.0, 60.0)   # diagonal ~28.28
    late_bbox = (39.0, 39.0, 60.2, 60.2)     # diagonal ~30.0 (~6% growth)
    bboxes = [early_bbox] * 12 + [late_bbox] * 12

    assert resolve_bearing(centroids, bboxes) is None


def test_compute_displacement_pixels_is_lateral_only_and_unaffected_by_bboxes_once_lateral_clears_the_floor():
    # A track with clear lateral motion (50px) AND a meaningfully changing bbox size - the
    # bbox size change must be completely ignored once lateral alone clears the floor,
    # exactly matching this function's pre-bbox-awareness behavior.
    centroids = [(0.0, 0.0), (50.0, 0.0)]
    bboxes = [(0.0, 0.0, 10.0, 10.0), (0.0, 0.0, 200.0, 200.0)]  # huge diagonal change too

    assert compute_displacement_pixels(centroids, bboxes) == pytest.approx(50.0, abs=1e-9)
    # Also unaffected when bboxes is omitted entirely, matching pipeline.py callers that
    # always pass bboxes, and any hypothetical caller that doesn't.
    assert compute_displacement_pixels(centroids, None) == pytest.approx(50.0, abs=1e-9)


def test_compute_displacement_pixels_combines_scale_when_lateral_is_under_the_floor():
    centroids = [(50.0, 50.0)] * 24
    early_bbox = (40.0, 40.0, 60.0, 60.0)   # diagonal ~28.28
    late_bbox = (0.0, 0.0, 100.0, 100.0)    # diagonal ~141.42
    bboxes = [early_bbox] * 12 + [late_bbox] * 12

    # lateral = 0, scale ~= 113.14, combined = hypot(0, 113.14) ~= 113.14
    assert compute_displacement_pixels(centroids, bboxes) == pytest.approx(113.137, abs=0.01)


def test_resolve_bearing_honors_a_custom_min_displacement_pixels_floor():
    # resolve_bearing averages over a sample_size window (unlike compute_displacement_pixels,
    # which uses raw first-vs-last-frame), so a 24px endpoint-to-endpoint track produces
    # ~13.7px of AVERAGED displacement (24 * 4/7, given DEFAULT_SAMPLE_SIZE=12 and a
    # 24-point track - n = min(12, 24//2) = 12) - clears the default 8.0px floor
    # (would resolve via "centroid"), but must be rejected as noise once the caller raises the
    # floor to 20.0 (e.g. to represent the same 8.0px real-world sensitivity at 2x-ish zoom).
    track = _linear_track((50.0, 100.0), (74.0, 100.0), steps=24)
    assert resolve_bearing(track) is not None
    assert resolve_bearing(track, min_displacement_pixels=20.0) is None


def test_compute_displacement_pixels_honors_a_custom_min_displacement_pixels_floor():
    # Same 12px lateral motion, no bboxes: with the default floor this already clears
    # MIN_DISPLACEMENT_PIXELS so bboxes wouldn't even be consulted; with a raised floor and
    # a bbox-diagonal scale change supplied, the scale contribution must kick in instead -
    # proving the floor value actually gates which code path runs, not just the label.
    centroids = [(50.0, 100.0), (62.0, 100.0)]
    bboxes = [(0.0, 0.0, 10.0, 10.0), (0.0, 0.0, 10.0, 34.0)]  # diagonal ~14.14 -> ~35.44

    assert compute_displacement_pixels(centroids, bboxes) == pytest.approx(12.0, abs=1e-6)
    combined = compute_displacement_pixels(centroids, bboxes, min_displacement_pixels=20.0)
    assert combined > 12.0  # scale contribution added, since 12px alone no longer clears 20.0


def _growing_bboxes(n=24, start=30.0, end=120.0):
    # square bboxes whose side grows linearly start -> end
    return [
        (0.0, 0.0, s, s)
        for s in (start + (end - start) * i / (n - 1) for i in range(n))
    ]


def test_scale_trend_growing_monotonic_over_threshold():
    trend, frac = scale_trend(_growing_bboxes(24, 30.0, 120.0))
    assert trend == "growing"
    # three-segment means for _growing_bboxes(24, 30, 120), k=8: s1≈61.78, s3≈150.26
    assert frac == pytest.approx(1.4328358208955227, rel=1e-4)


def test_scale_trend_shrinking_monotonic_over_threshold():
    trend, frac = scale_trend(_growing_bboxes(24, 120.0, 30.0))
    assert trend == "shrinking"
    assert frac == 0.0


def test_scale_trend_flat_when_growth_below_threshold():
    # grows only ~10% end-to-end, under MIN_SCALE_CHANGE_FRACTION (0.15)
    trend, frac = scale_trend(_growing_bboxes(24, 100.0, 110.0))
    assert trend == "flat"
    assert frac == 0.0


def test_scale_trend_flat_when_not_monotonic_across_thirds():
    # stable, stable, then one-frame spike -> middle third mean not > first third mean
    stable = [(0.0, 0.0, 40.0, 40.0)] * 22
    spike = [(0.0, 0.0, 400.0, 400.0)] * 2
    trend, frac = scale_trend(stable + spike)
    assert trend == "flat"


def test_scale_trend_flat_when_vehicle_passes_the_camera():
    # grows for the first half, then recedes -> grows-then-shrinks -> flat
    grow = _growing_bboxes(12, 30.0, 120.0)
    shrink = _growing_bboxes(12, 120.0, 30.0)
    trend, frac = scale_trend(grow + shrink)
    assert trend == "flat"


def test_scale_trend_flat_for_short_track():
    trend, frac = scale_trend(_growing_bboxes(8, 30.0, 200.0))
    assert trend == "flat"


def test_scale_trend_frac_uses_segment_means_not_endpoints():
    # flat for the first third, then a steady ramp: the first-third MEAN sits
    # well above the very first frame's diagonal, so the segment-mean growth
    # fraction is materially smaller than the raw first-vs-last endpoint ratio.
    flat = [(0.0, 0.0, 40.0, 40.0)] * 8
    ramp = [(0.0, 0.0, s, s) for s in (48.0 + (160.0 - 48.0) * i / 15 for i in range(16))]
    trend, frac = scale_trend(flat + ramp)
    assert trend == "growing"
    first_diag = bbox_diagonal(flat[0])
    last_diag = bbox_diagonal(ramp[-1])
    endpoint_ratio = (last_diag - first_diag) / first_diag
    # segment means should be less extreme than raw endpoints
    assert frac < endpoint_ratio - 0.3
