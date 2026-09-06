from __future__ import annotations

import math

import pytest

from app.tracking_bearing import windowed_velocity


def _line(n, x0, y0, dx, dy, w0=20.0, dw=0.0):
    """n frames moving (dx,dy)/frame from (x0,y0), bbox side w0 growing by dw/frame."""
    cents = [(x0 + dx * i, y0 + dy * i) for i in range(n)]
    bxs = [(0.0, 0.0, w0 + dw * i, w0 + dw * i) for i in range(n)]
    return cents, bxs


def test_windowed_velocity_is_unit_and_points_along_motion():
    cents, bxs = _line(20, 100.0, 100.0, 5.0, 0.0)  # moving +x
    v, disp = windowed_velocity(cents, bxs)
    assert math.isclose(math.hypot(*v), 1.0, abs_tol=1e-6)
    assert v[0] > 0.99 and abs(v[1]) < 0.01
    assert disp > 8.0


def test_windowed_velocity_uses_the_small_bbox_window_not_the_late_swerve():
    # Frames 0-11: approaching near-straight down the frame (small lateral drift), small box.
    # Frames 12-19: large box, big rightward swerve.
    # The vertical approach here is 3 px/frame (above MIN_DISPLACEMENT_PIXELS over the
    # window) so windowed_velocity has a real direction to report; the point of the test
    # is WHICH frames it measures - the small-box approach, not the near-camera swerve.
    cents = [(100.0 + 0.2 * i, 100.0 + 3.0 * i) for i in range(12)] + [(100.0 + 40.0 * (i - 11), 200.0) for i in range(12, 20)]
    bxs = [(0.0, 0.0, 20.0, 20.0)] * 12 + [(0.0, 0.0, 200.0, 200.0)] * 8
    v = windowed_velocity(cents, bxs)
    # Small-bbox window is frames 0-11: near-vertical motion, not the horizontal swerve.
    assert v is not None
    assert abs(v[0][0]) < 0.3          # not dominated by the swerve's +x
    assert v[0][1] > 0.9              # points down the frame, along the approach


def test_windowed_velocity_none_when_windowed_displacement_below_floor():
    cents, bxs = _line(20, 100.0, 100.0, 0.0, 0.0)  # stationary
    assert windowed_velocity(cents, bxs) is None


def test_windowed_velocity_none_for_a_too_short_track():
    cents, bxs = _line(3, 100.0, 100.0, 5.0, 0.0)
    assert windowed_velocity(cents, bxs) is None
