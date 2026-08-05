import pytest

from app.tracking_bearing import compute_bearing_degrees, compute_track_midpoint_ms


def _linear_track(start, end, steps=8):
    sx, sy = start
    ex, ey = end
    return [
        (sx + (ex - sx) * i / (steps - 1), sy + (ey - sy) * i / (steps - 1))
        for i in range(steps)
    ]


def test_moving_up_the_frame_is_bearing_zero():
    # Pixel y decreases as the vehicle moves toward the top of the frame ("up").
    track = _linear_track((100.0, 200.0), (100.0, 50.0))
    assert compute_bearing_degrees(track) == pytest.approx(0.0, abs=1e-6)


def test_moving_right_across_the_frame_is_bearing_90():
    track = _linear_track((50.0, 100.0), (300.0, 100.0))
    assert compute_bearing_degrees(track) == pytest.approx(90.0, abs=1e-6)


def test_moving_down_the_frame_is_bearing_180():
    track = _linear_track((100.0, 50.0), (100.0, 300.0))
    assert compute_bearing_degrees(track) == pytest.approx(180.0, abs=1e-6)


def test_moving_left_across_the_frame_is_bearing_270():
    track = _linear_track((300.0, 100.0), (50.0, 100.0))
    assert compute_bearing_degrees(track) == pytest.approx(270.0, abs=1e-6)


def test_too_few_observations_returns_none():
    assert compute_bearing_degrees([(0.0, 0.0), (1.0, 1.0), (2.0, 2.0)]) is None


def test_negligible_displacement_returns_none():
    # A track that jitters by a couple pixels but never really moves.
    track = [(100.0, 100.0), (101.0, 100.0), (100.0, 101.0), (101.0, 101.0), (100.0, 100.0)]
    assert compute_bearing_degrees(track) is None


def test_result_is_always_in_range():
    track = _linear_track((10.0, 10.0), (400.0, 250.0))
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
