from __future__ import annotations

import math
from typing import Sequence, Tuple

# Below this many observed frames, a track is too brief to trust a direction estimate.
# Scaled by 3x after frame_stride changed from 3 to 1: old value was 4 (representing ~0.4
# real seconds at 30fps with frame_stride=3 cadence). At the new frame_stride=1 cadence,
# the same ~0.4s of real time now produces ~12 observations.
MIN_OBSERVATIONS = 12

# Below this many pixels of net LATERAL displacement, motion is treated as noise (a
# stationary or barely-moving vehicle), not a fabricated direction. Applies only to lateral
# (centroid) displacement - scale (bbox-diagonal) displacement is judged separately, as a
# fraction of the vehicle's own apparent size (see MIN_SCALE_CHANGE_FRACTION) rather than a
# flat pixel count, since a flat pixel floor is far more permissive for large/near vehicles
# than for small/distant ones.
MIN_DISPLACEMENT_PIXELS = 8.0

# Below this fractional change in bbox diagonal (relative to the early-window diagonal),
# apparent size change is treated as noise/jitter, not real approach/recession motion.
# 0.15 matches the Kotlin server's ClipFlowAnalyzer.minDisplacementFraction convention for
# judging displacement relative to a vehicle's own size, rather than reusing
# MIN_DISPLACEMENT_PIXELS (an absolute pixel count calibrated for centroid motion, not bbox
# scale) for a fundamentally different, size-relative quantity.
MIN_SCALE_CHANGE_FRACTION = 0.15

# How many frames at the start/end of a track to average when estimating displacement.
# Scaled by 3x after frame_stride changed from 3 to 1: old value was 4. At the new
# frame_stride=1 cadence, reaching the same real-time window requires 12 samples.
DEFAULT_SAMPLE_SIZE = 12


def bbox_diagonal(bbox: Tuple[float, float, float, float]) -> float:
    """Euclidean length of a bounding box's diagonal, in pixels. `bbox` is (x1, y1, x2, y2)."""
    x1, y1, x2, y2 = bbox
    return math.hypot(x2 - x1, y2 - y1)


def scale_trend(
    bboxes: Sequence[Tuple[float, float, float, float]],
) -> Tuple[str, float]:
    """Classifies a track's apparent-size change over time.

    Splits the track's bounding-box diagonals into three equal time-ordered
    segments (means s1, s2, s3) and returns:
    - ("growing", (s3 - s1) / s1) when s1 < s2 < s3 and the total growth
      clears MIN_SCALE_CHANGE_FRACTION - the vehicle is approaching the camera.
    - ("shrinking", 0.0) when s1 > s2 > s3 and the total shrink clears
      MIN_SCALE_CHANGE_FRACTION - the vehicle is receding.
    - ("flat", 0.0) otherwise: stable, jittering, changing by less than the
      threshold, non-monotonic (a one-frame size spike), or grows-then-shrinks
      (a vehicle that passes the camera). Also "flat" for a track with fewer
      than MIN_OBSERVATIONS frames - too brief to trust a trend.

    The monotonic-across-thirds test (rather than just first-vs-last) is what
    rejects a single blown-up detection box and a pass-the-camera track.
    """
    if len(bboxes) < MIN_OBSERVATIONS:
        return "flat", 0.0
    diagonals = [bbox_diagonal(b) for b in bboxes]
    k = len(diagonals) // 3
    if k == 0:
        return "flat", 0.0
    s1 = sum(diagonals[:k]) / k
    s2 = sum(diagonals[k : 2 * k]) / k
    s3 = sum(diagonals[2 * k :]) / (len(diagonals) - 2 * k)
    if s1 <= 0:
        return "flat", 0.0
    if s1 < s2 < s3 and (s3 - s1) / s1 >= MIN_SCALE_CHANGE_FRACTION:
        return "growing", (s3 - s1) / s1
    if s1 > s2 > s3 and (s1 - s3) / s1 >= MIN_SCALE_CHANGE_FRACTION:
        return "shrinking", 0.0
    return "flat", 0.0


def resolve_bearing(
    centroids: Sequence[Tuple[float, float]],
    bboxes: Sequence[Tuple[float, float, float, float]] | None = None,
    sample_size: int = DEFAULT_SAMPLE_SIZE,
    min_displacement_pixels: float = MIN_DISPLACEMENT_PIXELS,
) -> Tuple[float, str] | None:
    """Frame-relative bearing in degrees [0, 360) plus its source, or None - never a
    fabricated direction - when there are too few observations or no motion is significant
    enough to trust.

    `centroids` is a track's (x, y) pixel centroids in temporal order (pixel y increases
    downward, as in OpenCV/most image coordinate systems). Averages the first and last
    `sample_size` observations (rather than just the first/last single frame) to smooth out
    per-frame detection jitter.

    Returns (bearing_degrees, source):
    - source "centroid": real lateral pixel motion (>= MIN_DISPLACEMENT_PIXELS) - the
      original, well-tested computation. atan2(dx, -dy): "up" in the frame (negative dy,
      since pixel y grows downward) maps to 0 degrees, "right" (positive dx) maps to 90
      degrees - the same clockwise-from-up convention as a compass bearing, just
      frame-relative instead of true-north-relative.
    - source "scale": lateral motion is negligible, but the bounding box's diagonal changed
      by at least MIN_SCALE_CHANGE_FRACTION of its own size - a vehicle moving nearly
      head-on toward (grew - 180 degrees, the reverse of "away", matching the "0 = up = away
      from camera" convention above) or away from (shrank - 0 degrees) the camera. IMPORTANT:
      a "scale" bearing measures camera-to-vehicle distance changing, NOT necessarily the
      other vehicle's own motion - if the CAMERA itself is moving (this app records from a
      dashcam) and closes on a stationary or slower same-direction vehicle, that vehicle's
      box also grows. A "scale" bearing must be corroborated by the recording vehicle's own
      low GPS speed before being trusted as a genuine approach - see
      OrientationTimeline.recordingSpeedMetersPerSecondAt and its use in
      ClipFlowAnalyzer.kt (Kotlin server, not this function) for that corroboration gate.
      This function only reports what the frame-relative motion LOOKS like; it cannot itself
      distinguish "the vehicle came at me" from "I caught up to it" - see the
      2026-08-06-approach-recession-bearing-fix design spec's Critical-finding fix note.
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
    lateral_displacement = math.hypot(dx, dy)

    if lateral_displacement >= min_displacement_pixels:
        if scale_trend(bboxes if bboxes is not None else [])[0] == "growing":
            # A vehicle that both sweeps laterally AND grows steadily is passing
            # close to the camera on its way toward it - an approach, not motion
            # along the flow. See the 2026-08-30 stationary-approach-detection spec.
            return (180.0, "scale")
        return (math.degrees(math.atan2(dx, -dy)) % 360.0, "centroid")

    if bboxes is None or len(bboxes) < MIN_OBSERVATIONS:
        return None

    early_bboxes = bboxes[:n]
    late_bboxes = bboxes[-n:]
    early_diagonal = sum(bbox_diagonal(b) for b in early_bboxes) / len(early_bboxes)
    late_diagonal = sum(bbox_diagonal(b) for b in late_bboxes) / len(late_bboxes)

    if early_diagonal <= 0.0:
        return None

    scale_change_fraction = abs(late_diagonal - early_diagonal) / early_diagonal
    if scale_change_fraction < MIN_SCALE_CHANGE_FRACTION:
        return None

    return (180.0, "scale") if late_diagonal > early_diagonal else (0.0, "scale")


def compute_bearing_degrees(
    centroids: Sequence[Tuple[float, float]],
    bboxes: Sequence[Tuple[float, float, float, float]] | None = None,
    sample_size: int = DEFAULT_SAMPLE_SIZE,
) -> float | None:
    """Bearing only, discarding the source - see resolve_bearing() for the full result
    including whether this came from real lateral motion or a bbox-scale fallback. Kept as
    a thin wrapper so existing callers/tests that only need the bearing value are
    unaffected by resolve_bearing's richer return type."""
    resolved = resolve_bearing(centroids, bboxes, sample_size)
    return resolved[0] if resolved else None


def compute_displacement_pixels(
    centroids: Sequence[Tuple[float, float]],
    bboxes: Sequence[Tuple[float, float, float, float]] | None = None,
    sample_size: int = DEFAULT_SAMPLE_SIZE,
    min_displacement_pixels: float = MIN_DISPLACEMENT_PIXELS,
) -> float:
    """Net displacement in pixels, sent to the Kotlin server as VehicleResult.displacement_pixels.

    Lateral (centroid, first vs LAST SINGLE frame - not averaged) exactly as computed before
    bbox-awareness existed: unconditional, unchanged - this is the pre-existing, well-tested
    behavior for every ordinary track and must stay byte-for-byte identical.

    Bbox-scale displacement (averaged over sample_size early/late frames, matching
    resolve_bearing's own window, to reduce single-frame truncation/occlusion sensitivity) is
    added via quadrature (math.hypot) ONLY when lateral alone is under
    MIN_DISPLACEMENT_PIXELS - so a track whose lateral motion alone already clears the floor
    has a displacement_pixels value completely unaffected by this function's bbox awareness,
    the same invariant resolve_bearing's own gate already honors. Never returns less than the
    lateral-only value.
    """
    lateral = math.hypot(centroids[-1][0] - centroids[0][0], centroids[-1][1] - centroids[0][1])

    if lateral >= min_displacement_pixels or bboxes is None:
        return lateral

    n = min(sample_size, len(bboxes) // 2)
    if n == 0:
        return lateral

    early_diagonal = sum(bbox_diagonal(b) for b in bboxes[:n]) / n
    late_diagonal = sum(bbox_diagonal(b) for b in bboxes[-n:]) / n
    scale = abs(late_diagonal - early_diagonal)

    return math.hypot(lateral, scale)


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
