from __future__ import annotations

import math
from typing import Sequence, Tuple

# Below this many observed frames, a track is too brief to trust a direction estimate.
MIN_OBSERVATIONS = 4

# Below this many pixels of net displacement, motion is treated as noise (a stationary or
# barely-moving vehicle), not a fabricated direction.
MIN_DISPLACEMENT_PIXELS = 8.0

# How many frames at the start/end of a track to average when estimating displacement.
DEFAULT_SAMPLE_SIZE = 4


def compute_bearing_degrees(
    centroids: Sequence[Tuple[float, float]],
    sample_size: int = DEFAULT_SAMPLE_SIZE,
) -> float | None:
    """Frame-relative bearing in degrees [0, 360), clockwise from "up" in the frame.

    `centroids` is a track's (x, y) pixel centroids in temporal order (pixel y increases
    downward, as in OpenCV/most image coordinate systems). Averages the first and last
    `sample_size` observations (rather than just the first/last single frame) to smooth out
    per-frame detection jitter. Returns None - never a fabricated direction - when there
    are too few observations or the net displacement is too small to trust.
    """
    if len(centroids) < MIN_OBSERVATIONS:
        return None

    n = min(sample_size, len(centroids) // 2)
    if n == 0:
        return None

    early = centroids[:n]
    late = centroids[-n:]

    early_x = sum(p[0] for p in early) / len(early)
    early_y = sum(p[1] for p in early) / len(early)
    late_x = sum(p[0] for p in late) / len(late)
    late_y = sum(p[1] for p in late) / len(late)

    dx = late_x - early_x
    dy = late_y - early_y

    if math.hypot(dx, dy) < MIN_DISPLACEMENT_PIXELS:
        return None

    # atan2(dx, -dy): "up" in the frame (negative dy, since pixel y grows downward) maps to
    # 0 degrees, "right" (positive dx) maps to 90 degrees - the same clockwise-from-up
    # convention as a compass bearing, just frame-relative instead of true-north-relative.
    return math.degrees(math.atan2(dx, -dy)) % 360.0


def compute_track_midpoint_ms(
    first_frame_index: int,
    last_frame_index: int,
    fps: float | None,
) -> int | None:
    """Elapsed milliseconds from the analyzed clip's start to the midpoint between a
    track's first and last observed frame. None when fps is unavailable or non-positive -
    never a fabricated timestamp. Used by the Kotlin server to look up this vehicle's
    camera orientation at roughly the moment it was observed, instead of applying one
    static compass reading to every vehicle in the clip regardless of when it appeared."""
    if fps is None or fps <= 0:
        return None
    midpoint_frame = (first_frame_index + last_frame_index) / 2
    return round(midpoint_frame / fps * 1000)
